package com.flowchain.studio.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DecodedAudioAnalysis(
    val pcmStereo44k: FloatArray,
    val durationSec: Float,
    val detectedBpm: Int,
    val waveformPeaks: List<Float>
)

class MediaCodecAudioDecoder(private val context: Context) {

    companion object {
        const val TARGET_SAMPLE_RATE = 44100
        const val TARGET_CHANNELS = 2
        private const val TIMEOUT_US = 10_000L
    }

    /**
     * Decodes any local MP3, AAC (.m4a), WAV, or FLAC file into stereo 44.1kHz FloatArray PCM [-1.0f, 1.0f].
     */
    suspend fun decodeAudioUri(
        uri: Uri,
        maxSecondsToLoad: Int = 180
    ): DecodedAudioAnalysis = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open audio URI: $uri")

        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()

        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        var mimeType = ""

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = format
                mimeType = mime
                break
            }
        }

        require(audioTrackIndex >= 0 && inputFormat != null) { "No audio track found in selected file." }
        extractor.selectTrack(audioTrackIndex)

        val codec = MediaCodec.createDecoderByType(mimeType)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        var sourceSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else TARGET_SAMPLE_RATE

        var sourceChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else TARGET_CHANNELS

        val rawPcmBytes = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var isInputEOS = false
        var isOutputEOS = false
        val maxBytes = maxSecondsToLoad * sourceSampleRate * sourceChannels * 2

        while (!isOutputEOS && rawPcmBytes.size() < maxBytes) {
            if (!isInputEOS) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isInputEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outIndex)!!
                    if (bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        outputBuffer.clear()
                        rawPcmBytes.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sourceSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        sourceChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val decodedBytes = rawPcmBytes.toByteArray()
        val shortCount = decodedBytes.size / 2
        val shortBuffer = ByteBuffer.wrap(decodedBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val rawFloats = FloatArray(shortCount)
        for (i in 0 until shortCount) {
            rawFloats[i] = shortBuffer.get(i) / 32768f
        }

        // Convert to Stereo if Mono or multi-channel
        val stereoSource = if (sourceChannels == 2) {
            rawFloats
        } else if (sourceChannels == 1) {
            FloatArray(rawFloats.size * 2).apply {
                for (i in rawFloats.indices) {
                    this[i * 2] = rawFloats[i]
                    this[i * 2 + 1] = rawFloats[i]
                }
            }
        } else {
            val frames = rawFloats.size / sourceChannels
            FloatArray(frames * 2).apply {
                for (f in 0 until frames) {
                    this[f * 2] = rawFloats[f * sourceChannels]
                    this[f * 2 + 1] = rawFloats[f * sourceChannels + 1]
                }
            }
        }

        // Resample to 44.1kHz if needed
        val finalPcm = if (sourceSampleRate == TARGET_SAMPLE_RATE) {
            stereoSource
        } else {
            resampleStereoLinear(stereoSource, sourceSampleRate, TARGET_SAMPLE_RATE)
        }

        val durationSec = (finalPcm.size / 2).toFloat() / TARGET_SAMPLE_RATE
        val peaks = extractWaveformPeaks(finalPcm, 80)
        val bpm = detectBpmFromPcm(finalPcm, TARGET_SAMPLE_RATE)

        DecodedAudioAnalysis(
            pcmStereo44k = finalPcm,
            durationSec = durationSec,
            detectedBpm = bpm,
            waveformPeaks = peaks
        )
    }

    private fun resampleStereoLinear(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        val inFrames = input.size / 2
        val ratio = inRate.toDouble() / outRate.toDouble()
        val outFrames = (inFrames / ratio).toInt()
        val output = FloatArray(outFrames * 2)

        for (i in 0 until outFrames) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, max(0, inFrames - 1))
            val idx1 = (idx0 + 1).coerceIn(0, max(0, inFrames - 1))
            val frac = (srcPos - idx0).toFloat()

            output[i * 2] = input[idx0 * 2] * (1f - frac) + input[idx1 * 2] * frac
            output[i * 2 + 1] = input[idx0 * 2 + 1] * (1f - frac) + input[idx1 * 2 + 1] * frac
        }
        return output
    }

    private fun extractWaveformPeaks(stereoPcm: FloatArray, bars: Int): List<Float> {
        if (stereoPcm.isEmpty()) return List(bars) { 0.3f }
        val chunkSize = (stereoPcm.size / bars).coerceAtLeast(2)
        val rawPeaks = FloatArray(bars)
        var maxPeak = 0.01f

        for (b in 0 until bars) {
            val start = b * chunkSize
            val end = (start + chunkSize).coerceAtMost(stereoPcm.size)
            var sumSq = 0f
            var count = 0
            for (i in start until end step 16) {
                val v = stereoPcm[i]
                sumSq += v * v
                count++
            }
            val rms = if (count > 0) sqrt(sumSq / count) else 0.1f
            rawPeaks[b] = rms
            if (rms > maxPeak) maxPeak = rms
        }
        return rawPeaks.map { (it / maxPeak).coerceIn(0.12f, 1.0f) }
    }

    /**
     * Energy envelope onset autocorrelation BPM detector (searches 85 BPM to 165 BPM).
     */
    private fun detectBpmFromPcm(stereoPcm: FloatArray, sampleRate: Int): Int {
        val hopFrames = 512
        val totalFrames = stereoPcm.size / 2
        val envSize = totalFrames / hopFrames
        if (envSize < 100) return 124

        val energyEnv = FloatArray(envSize)
        for (e in 0 until envSize) {
            var sum = 0f
            val baseIdx = e * hopFrames * 2
            for (k in 0 until hopFrames step 4) {
                val sample = abs(stereoPcm[baseIdx + k * 2])
                sum += sample * sample
            }
            energyEnv[e] = sum
        }

        // First-order positive onset difference
        val onset = FloatArray(envSize)
        for (e in 1 until envSize) {
            onset[e] = max(0f, energyEnv[e] - energyEnv[e - 1])
        }

        val envRate = sampleRate.toFloat() / hopFrames
        var bestBpm = 124
        var bestScore = -1f

        for (candidateBpm in 90..160) {
            val lag = ((60f / candidateBpm) * envRate).roundToInt()
            if (lag <= 0 || lag >= envSize / 2) continue
            var score = 0f
            val limit = envSize.coerceAtMost(1500)
            for (i in 0 until limit - lag) {
                score += onset[i] * onset[i + lag]
            }
            if (score > bestScore) {
                bestScore = score
                bestBpm = candidateBpm
            }
        }
        return bestBpm
    }
}
