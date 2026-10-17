package com.flowchain.studio.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

enum class CrossfadeCurve(val label: String) {
    CONSTANT_POWER("Constant Power (Pro)"),
    S_CURVE("Smooth S-Curve"),
    LINEAR("Linear Blend"),
    HARD_SLAM("Drop Slam Cut")
}

enum class TransitionFx(val label: String) {
    NONE("Clean Blend"),
    HIGH_PASS_SWEEP("High-Pass Filter Riser"),
    LOW_PASS_WASH("Underwater Low-Pass"),
    ECHO_FREEZE("Echo Tail Out"),
    LOOP_ROLL_STUTTER("1/4 -> 1/16 Beat Stutter Roll"),
    VINYL_BRAKE("Vinyl Spinback")
}

data class TransitionConfig(
    val durationSeconds: Float = 8f,
    val curve: CrossfadeCurve = CrossfadeCurve.CONSTANT_POWER,
    val bassSwapRatio: Float = 0.5f,
    val enableBassSwap: Boolean = true,
    val enableVocalDucking: Boolean = true,
    val enableBpmSync: Boolean = true,
    val transitionFx: TransitionFx = TransitionFx.HIGH_PASS_SWEEP,
    val fxIntensity: Float = 0.75f,
    val outgoingTrimEndSec: Float = 0f,
    val incomingTrimStartSec: Float = 0f,
    val cueStartASec: Float = -1f,
    val cueStartBSec: Float = 0f,
    val xyCutoffNormalized: Float = 0.5f,
    val loopRollCode: Int = 0, // 0 = OFF, 1 = 1/2, 2 = 1/4, 3 = 1/8, 4 = VINYL_BRAKE
    val pitchShiftSemitonesB: Int = 0,
    val autoGainDbA: Float = 0f,
    val autoGainDbB: Float = 0f
)

data class AudioTrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val bpm: Int,
    val musicalKey: String, // Camelot format e.g. "8A", "9A", "8B"
    val durationSec: Float,
    val uri: Uri? = null,
    val localFile: File? = null,
    val decodedPcmCache: FloatArray? = null,
    val separatedStems: SeparatedStemsPcm? = null,
    val waveformPeaks: List<Float> = List(80) { (0.25f + 0.75f * abs(sin(it * 0.23f))) },
    val isChainedCombo: Boolean = false,
    val chainCount: Int = 1
)

object CamelotKeyMatcher {
    /**
     * Evaluates harmonic compatibility between two Camelot keys (e.g. "8A" and "9A").
     */
    fun getCompatibilityBadge(keyA: String, keyB: String): Pair<String, Boolean> {
        val numA = keyA.takeWhile { it.isDigit() }.toIntOrNull() ?: 8
        val letA = keyA.firstOrNull { it.isLetter() } ?: 'A'
        val numB = keyB.takeWhile { it.isDigit() }.toIntOrNull() ?: 8
        val letB = keyB.firstOrNull { it.isLetter() } ?: 'A'

        val diff = abs(numA - numB)
        return when {
            numA == numB && letA == letB -> "Perfect Harmonic Match ($keyA = $keyB)" to true
            numA == numB && letA != letB -> "Relative Major/Minor Modulation ($keyA -> $keyB)" to true
            (diff == 1 || diff == 11) && letA == letB -> "Harmonic Fifth Step ($keyA -> $keyB)" to true
            else -> "Key Clash Warning ($keyA vs $keyB) • Enable Stem Vocal Isolation" to false
        }
    }
}

class AudioChainRenderer(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
    }

    private val codecDecoder = MediaCodecAudioDecoder(context)
    private val stemSeparator = AiStemSeparatorEngine(context)

    suspend fun renderMergedTrack(
        trackA: AudioTrackItem,
        trackB: AudioTrackItem,
        config: TransitionConfig,
        stemConfig: StemTransitionConfig,
        outputFileName: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {

        onProgress(0.05f)
        val pcmA = loadPcmFromTrack(trackA, isTrackA = true)
        onProgress(0.20f)
        val pcmB = loadPcmFromTrack(trackB, isTrackA = false)
        onProgress(0.35f)

        val framesPerSecond = SAMPLE_RATE * CHANNELS
        val minRequiredSamples = (framesPerSecond * 2) and -2

        val requestedOverlap = ((config.durationSeconds * framesPerSecond).toInt() and -2)
            .coerceAtLeast(framesPerSecond)

        // Stereo-aligned (even index) cue slicing for Track A & Track B
        val transitionStartSample: Int
        val activeASize: Int
        if (config.cueStartASec >= 0f) {
            val rawStartA = ((config.cueStartASec * framesPerSecond).toInt() and -2)
                .coerceIn(0, (pcmA.size - minRequiredSamples).coerceAtLeast(0))
            val rawEndA = (rawStartA + requestedOverlap).coerceAtMost(pcmA.size) and -2
            transitionStartSample = rawStartA
            activeASize = rawEndA.coerceAtLeast(min(pcmA.size and -2, minRequiredSamples))
        } else {
            val trimAFrames = ((config.outgoingTrimEndSec * framesPerSecond).toInt() and -2)
                .coerceAtMost(pcmA.size / 2)
            activeASize = ((pcmA.size - trimAFrames) and -2).coerceAtLeast(min(pcmA.size and -2, minRequiredSamples))
            transitionStartSample = (activeASize - requestedOverlap).coerceAtLeast(0) and -2
        }

        val startSecB = if (config.cueStartBSec > 0f) config.cueStartBSec else config.incomingTrimStartSec
        val activeBStart = ((startSecB * framesPerSecond).toInt() and -2)
            .coerceIn(0, (pcmB.size - minRequiredSamples).coerceAtLeast(0))
        val activeBSize = ((pcmB.size - activeBStart) and -2).coerceAtLeast(minRequiredSamples)

        val overlapSamples = ((activeASize - transitionStartSample) and -2)
            .coerceIn(framesPerSecond, min(activeASize, activeBSize))

        // Separate the overlap window into 4 stems if Stem Mashup Mode is enabled
        val stemsA: SeparatedStemsPcm?
        val stemsB: SeparatedStemsPcm?
        if (stemConfig.enableStemMashupMode) {
            val safeEndA = (transitionStartSample + overlapSamples).coerceAtMost(pcmA.size)
            val safeEndB = (activeBStart + overlapSamples).coerceAtMost(pcmB.size)
            val sliceA = pcmA.copyOfRange(transitionStartSample, safeEndA)
            val sliceB = pcmB.copyOfRange(activeBStart, safeEndB)
            stemsA = stemSeparator.separateIntoFourStems(sliceA) { p -> onProgress(0.35f + p * 0.15f) }
            stemsB = stemSeparator.separateIntoFourStems(sliceB) { p -> onProgress(0.50f + p * 0.15f) }
        } else {
            stemsA = null
            stemsB = null
        }

        // Linear Auto-Gain multipliers from LUFS / RMS dB offsets
        val linearGainA = 10f.pow(config.autoGainDbA / 20f).coerceIn(0.35f, 2.2f)
        val linearGainB = 10f.pow(config.autoGainDbB / 20f).coerceIn(0.35f, 2.2f)

        // Semitone pitch-shift ratio for Deck B
        val pitchRateB = 2.0.pow(config.pitchShiftSemitonesB / 12.0).toFloat().coerceIn(0.7f, 1.45f)

        val totalOutputSamples = (transitionStartSample + activeBSize) and -2
        val outputPcm = ShortArray(totalOutputSamples)

        var lpStateAL = 0f
        var lpStateAR = 0f
        var lpStateBL = 0f
        var lpStateBR = 0f

        var xyLpAL = 0f
        var xyLpAR = 0f

        val delayBufferSize = ((SAMPLE_RATE * 0.375f * CHANNELS).toInt() and -2).coerceAtLeast(4096)
        val echoBuffer = FloatArray(delayBufferSize)
        var echoIndex = 0

        // Beat-repeat loop roll window (1/4 beat buffer at Track A's BPM)
        val oneBeatSamples = (((60f / trackA.bpm.coerceAtLeast(70)) * framesPerSecond).toInt() and -2)
            .coerceAtLeast(4096)
        val quarterBeatSamples = (oneBeatSamples / 4) and -2

        var vinylReadCursorA = 0.0

        for (i in 0 until totalOutputSamples step 2) {
            val sampleRightIdx = i + 1
            if (sampleRightIdx >= totalOutputSamples) break

            val outL: Float
            val outR: Float

            if (i < transitionStartSample) {
                outL = pcmA.getOrElse(i) { 0f } * linearGainA
                outR = pcmA.getOrElse(sampleRightIdx) { 0f } * linearGainA
            } else if (i < transitionStartSample + overlapSamples) {
                val overlapOffset = i - transitionStartSample
                val progress = (overlapOffset.toFloat() / overlapSamples.toFloat()).coerceIn(0f, 1f)

                // Pitch-aware stereo frame index for Deck B
                val pitchedOffsetFrames = ((overlapOffset / 2) * pitchRateB).toInt() * 2
                val bIdxL = activeBStart + pitchedOffsetFrames
                val bIdxR = bIdxL + 1

                var sampleAL: Float
                var sampleAR: Float
                var sampleBL: Float
                var sampleBR: Float

                if (stemsA != null && stemsB != null &&
                    overlapOffset + 1 < stemsA.vocals.size &&
                    overlapOffset + 1 < stemsB.vocals.size
                ) {
                    val vocalFadeA = (1f - progress.pow(1.6f)) * stemConfig.outgoingVocalLevel
                    val drumFadeA = (1f - progress) * stemConfig.outgoingDrumsLevel
                    val bassFadeA = (if (progress < config.bassSwapRatio) 1f else 0.15f) * stemConfig.outgoingBassLevel
                    val melFadeA = (1f - progress) * stemConfig.outgoingMelodyLevel

                    sampleAL = stemsA.vocals[overlapOffset] * vocalFadeA +
                        stemsA.drums[overlapOffset] * drumFadeA +
                        stemsA.bass[overlapOffset] * bassFadeA +
                        stemsA.melody[overlapOffset] * melFadeA
                    sampleAR = stemsA.vocals[overlapOffset + 1] * vocalFadeA +
                        stemsA.drums[overlapOffset + 1] * drumFadeA +
                        stemsA.bass[overlapOffset + 1] * bassFadeA +
                        stemsA.melody[overlapOffset + 1] * melFadeA

                    val vocalFadeB = progress.pow(1.8f) * stemConfig.incomingVocalLevel
                    val drumFadeB = sqrt(progress) * stemConfig.incomingDrumsLevel
                    val bassFadeB = (if (progress >= config.bassSwapRatio) 1f else 0.2f) * stemConfig.incomingBassLevel
                    val melFadeB = progress * stemConfig.incomingMelodyLevel

                    sampleBL = stemsB.vocals[overlapOffset] * vocalFadeB +
                        stemsB.drums[overlapOffset] * drumFadeB +
                        stemsB.bass[overlapOffset] * bassFadeB +
                        stemsB.melody[overlapOffset] * melFadeB
                    sampleBR = stemsB.vocals[overlapOffset + 1] * vocalFadeB +
                        stemsB.drums[overlapOffset + 1] * drumFadeB +
                        stemsB.bass[overlapOffset + 1] * bassFadeB +
                        stemsB.melody[overlapOffset + 1] * melFadeB
                } else {
                    sampleAL = pcmA.getOrElse(i) { 0f }
                    sampleAR = pcmA.getOrElse(sampleRightIdx) { 0f }
                    sampleBL = pcmB.getOrElse(bIdxL) { 0f }
                    sampleBR = pcmB.getOrElse(bIdxR) { 0f }
                }

                // Apply explicit LoopRollMode / Vinyl Brake selected from the 2D XY Pad Card
                if (config.loopRollCode in 1..3 && progress > 0.5f) {
                    val rollWindow = when (config.loopRollCode) {
                        1 -> (oneBeatSamples / 2) and -2
                        2 -> quarterBeatSamples
                        else -> (quarterBeatSamples / 2).coerceAtLeast(1024) and -2
                    }
                    val rollOrigin = (transitionStartSample + (overlapSamples / 2)) and -2
                    val rollIdx = (rollOrigin + (overlapOffset % rollWindow)).coerceIn(0, (pcmA.size - 2).coerceAtLeast(0))
                    sampleAL = pcmA.getOrElse(rollIdx) { sampleAL }
                    sampleAR = pcmA.getOrElse(rollIdx + 1) { sampleAR }
                } else if (config.loopRollCode == 4 && progress > 0.65f) {
                    val brakeProgress = ((progress - 0.65f) / 0.35f).coerceIn(0f, 1f)
                    val speedFactor = (1.0 - brakeProgress.toDouble().pow(1.8)).coerceAtLeast(0.02)
                    vinylReadCursorA += speedFactor * 2.0
                    val brakeOrigin = (transitionStartSample + (overlapSamples * 0.65f).toInt()) and -2
                    val readIdx = (brakeOrigin + (vinylReadCursorA.toInt() and -2)).coerceIn(0, (pcmA.size - 2).coerceAtLeast(0))
                    sampleAL = pcmA.getOrElse(readIdx) { 0f } * (1f - brakeProgress * 0.5f)
                    sampleAR = pcmA.getOrElse(readIdx + 1) { 0f } * (1f - brakeProgress * 0.5f)
                }

                val (gainA, gainB) = computeGains(progress, config.curve)

                val bassCutoffAlpha = 0.035f
                lpStateAL += bassCutoffAlpha * (sampleAL - lpStateAL)
                lpStateAR += bassCutoffAlpha * (sampleAR - lpStateAR)
                lpStateBL += bassCutoffAlpha * (sampleBL - lpStateBL)
                lpStateBR += bassCutoffAlpha * (sampleBR - lpStateBR)

                if (config.enableBassSwap && !stemConfig.enableStemMashupMode) {
                    if (progress < config.bassSwapRatio) {
                        sampleBL -= lpStateBL * 0.9f
                        sampleBR -= lpStateBR * 0.9f
                    } else {
                        sampleAL -= lpStateAL * 0.9f
                        sampleAR -= lpStateAR * 0.9f
                    }
                }

                // 2D XY Touch Pad Filter Sweep (Left of center = Low-Pass, Right of center = High-Pass)
                val xyDeviation = config.xyCutoffNormalized - 0.5f
                if (abs(xyDeviation) > 0.06f) {
                    val xyAlpha = (0.015f + 0.25f * config.xyCutoffNormalized.pow(2f)).coerceIn(0.01f, 0.45f)
                    xyLpAL += xyAlpha * (sampleAL - xyLpAL)
                    xyLpAR += xyAlpha * (sampleAR - xyLpAR)
                    if (xyDeviation < 0f) {
                        val lpMix = ((-xyDeviation * 2f) * config.fxIntensity).coerceIn(0f, 0.95f)
                        sampleAL = sampleAL * (1f - lpMix) + xyLpAL * lpMix
                        sampleAR = sampleAR * (1f - lpMix) + xyLpAR * lpMix
                    } else {
                        val hpMix = ((xyDeviation * 2f) * config.fxIntensity).coerceIn(0f, 0.95f)
                        sampleAL -= xyLpAL * hpMix
                        sampleAR -= xyLpAR * hpMix
                    }
                }

                when (config.transitionFx) {
                    TransitionFx.HIGH_PASS_SWEEP -> {
                        val sweep = (progress * config.fxIntensity).coerceIn(0f, 0.95f)
                        sampleAL -= lpStateAL * sweep
                        sampleAR -= lpStateAR * sweep
                    }
                    TransitionFx.LOW_PASS_WASH -> {
                        val wash = (progress * config.fxIntensity).coerceIn(0f, 0.95f)
                        sampleAL = sampleAL * (1f - wash) + lpStateAL * wash
                        sampleAR = sampleAR * (1f - wash) + lpStateAR * wash
                    }
                    TransitionFx.ECHO_FREEZE -> {
                        val echoFeedback = 0.55f * config.fxIntensity
                        val delayedL = echoBuffer[echoIndex]
                        val delayedR = echoBuffer[(echoIndex + 1) % delayBufferSize]
                        echoBuffer[echoIndex] = sampleAL + delayedL * echoFeedback
                        echoBuffer[(echoIndex + 1) % delayBufferSize] = sampleAR + delayedR * echoFeedback
                        echoIndex = (echoIndex + 2) % delayBufferSize
                        sampleAL += delayedL * progress * 0.7f
                        sampleAR += delayedR * progress * 0.7f
                    }
                    TransitionFx.LOOP_ROLL_STUTTER -> {
                        if (progress > 0.6f) {
                            val subDiv = if (progress > 0.85f) 4 else if (progress > 0.72f) 2 else 1
                            val loopWindow = (quarterBeatSamples / subDiv).coerceAtLeast(512) and -2
                            val stutterIdx = (transitionStartSample + (overlapOffset % loopWindow))
                                .coerceIn(0, (pcmA.size - 2).coerceAtLeast(0))
                            sampleAL = pcmA.getOrElse(stutterIdx) { sampleAL }
                            sampleAR = pcmA.getOrElse(stutterIdx + 1) { sampleAR }
                        }
                    }
                    TransitionFx.VINYL_BRAKE -> {
                        if (progress > 0.75f) {
                            val brakeMod = sin((1f - progress) * 80f)
                            sampleAL *= brakeMod
                            sampleAR *= brakeMod
                        }
                    }
                    TransitionFx.NONE -> Unit
                }

                val midDip = if (config.enableVocalDucking) {
                    1f - (0.15f * sin(progress * Math.PI.toFloat()))
                } else 1f

                val mixedL = (sampleAL * linearGainA * gainA * midDip) + (sampleBL * linearGainB * gainB)
                val mixedR = (sampleAR * linearGainA * gainA * midDip) + (sampleBR * linearGainB * gainB)

                // Soft-saturation studio limiter to prevent digital clipping
                outL = tanh(mixedL * 1.05f).coerceIn(-1f, 1f)
                outR = tanh(mixedR * 1.05f).coerceIn(-1f, 1f)

                if (overlapOffset % 44100 == 0) {
                    onProgress(0.65f + 0.30f * progress)
                }
            } else {
                val postOverlapOffset = i - transitionStartSample
                val pitchedOffsetFrames = ((postOverlapOffset / 2) * pitchRateB).toInt() * 2
                val bIdxL = activeBStart + pitchedOffsetFrames
                val bIdxR = bIdxL + 1
                outL = (pcmB.getOrElse(bIdxL) { 0f } * linearGainB).coerceIn(-1f, 1f)
                outR = (pcmB.getOrElse(bIdxR) { 0f } * linearGainB).coerceIn(-1f, 1f)
            }

            outputPcm[i] = (outL * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            outputPcm[sampleRightIdx] = (outR * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        val outFile = File(context.cacheDir, "$outputFileName.wav")
        writeWavFile(outFile, outputPcm, SAMPLE_RATE, CHANNELS)
        onProgress(1.0f)
        outFile
    }

    suspend fun exportToPublicDownloads(
        sourceFile: File,
        displayTitle: String,
        tracklistText: String
    ): String = withContext(Dispatchers.IO) {
        val fileName = "${displayTitle.replace(" ", "_")}.wav"
        val cueFileName = "${displayTitle.replace(" ", "_")}.cue"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val audioValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FlowChain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, audioValues)
                ?: throw IllegalStateException("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { outStream ->
                sourceFile.inputStream().use { inStream -> inStream.copyTo(outStream) }
            }
            audioValues.clear()
            audioValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, audioValues, null, null)

            val txtValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, cueFileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FlowChain")
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, txtValues)?.let { txtUri ->
                resolver.openOutputStream(txtUri)?.use { it.write(tracklistText.toByteArray()) }
            }
            "Downloads/FlowChain/$fileName (+ $cueFileName)"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FlowChain")
            dir.mkdirs()
            val dest = File(dir, fileName)
            sourceFile.copyTo(dest, overwrite = true)
            File(dir, cueFileName).writeText(tracklistText)
            dest.absolutePath
        }
    }

    private fun computeGains(t: Float, curve: CrossfadeCurve): Pair<Float, Float> {
        return when (curve) {
            CrossfadeCurve.LINEAR -> Pair(1f - t, t)
            CrossfadeCurve.CONSTANT_POWER -> {
                val angle = t * (Math.PI.toFloat() / 2f)
                Pair(cos(angle), sin(angle))
            }
            CrossfadeCurve.S_CURVE -> {
                val smooth = t * t * (3f - 2f * t)
                Pair(1f - smooth, smooth)
            }
            CrossfadeCurve.HARD_SLAM -> {
                val gainB = if (t < 0.85f) t * 0.4f else 1f
                val gainA = if (t < 0.85f) 1f else (1f - t) * 6.6f
                Pair(gainA.coerceIn(0f, 1f), gainB.coerceIn(0f, 1f))
            }
        }
    }

    private suspend fun loadPcmFromTrack(track: AudioTrackItem, isTrackA: Boolean): FloatArray {
        track.decodedPcmCache?.let { return it }

        track.localFile?.let { file ->
            if (file.exists() && file.extension.equals("wav", ignoreCase = true)) {
                return readWavFileToFloatArray(file)
            }
        }

        track.uri?.let { uri ->
            return try {
                codecDecoder.decodeAudioUri(uri).pcmStereo44k
            } catch (e: Exception) {
                synthesizeDemoBeat(track, isTrackA)
            }
        }

        return synthesizeDemoBeat(track, isTrackA)
    }

    private fun synthesizeDemoBeat(track: AudioTrackItem, isTrackA: Boolean): FloatArray {
        val durationSec = track.durationSec.coerceIn(12f, 30f)
        val totalSamples = (durationSec * SAMPLE_RATE * CHANNELS).toInt() and -2
        val buffer = FloatArray(totalSamples)
        val beatPeriodSec = 60f / track.bpm.toFloat()
        val baseFreq = if (isTrackA) 110f else 146.83f

        for (i in 0 until totalSamples step 2) {
            val timeSec = (i / 2).toFloat() / SAMPLE_RATE
            val beatPhase = (timeSec % beatPeriodSec) / beatPeriodSec
            val kickEnv = exp(-beatPhase * 14f)
            val kick = sin(2f * Math.PI.toFloat() * (55f + 90f * kickEnv) * timeSec) * kickEnv * 0.55f
            val lead = sin(2f * Math.PI.toFloat() * (baseFreq * 2f) * timeSec) * 0.16f
            val sidePad = sin(2f * Math.PI.toFloat() * (baseFreq * 1.5f) * timeSec) * 0.14f

            buffer[i] = (kick + lead + sidePad).coerceIn(-0.95f, 0.95f)
            buffer[i + 1] = (kick + lead - sidePad).coerceIn(-0.95f, 0.95f)
        }
        return buffer
    }

    private fun readWavFileToFloatArray(file: File): FloatArray {
        val bytes = file.readBytes()
        val headerOffset = 44
        if (bytes.size <= headerOffset) return FloatArray(0)
        val sampleCount = (bytes.size - headerOffset) / 2
        val floats = FloatArray(sampleCount)
        val byteBuffer = ByteBuffer.wrap(bytes, headerOffset, bytes.size - headerOffset).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            floats[i] = byteBuffer.short / 32768f
        }
        return floats
    }

    private fun writeWavFile(file: File, pcmData: ShortArray, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val dataSize = pcmData.size * 2
        val totalDataLen = dataSize + 36

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalDataLen)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * 2).toShort())
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize)
            fos.write(header.array())

            val pcmBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcmData) {
                pcmBuffer.putShort(s)
            }
            fos.write(pcmBuffer.array())
        }
    }
}
