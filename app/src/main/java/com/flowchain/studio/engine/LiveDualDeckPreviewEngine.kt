package com.flowchain.studio.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom Media3 AudioProcessor that runs real-time Biquad High-Pass / Low-Pass / Bass-Swap
 * and Mid-Side Vocal/Stem isolation on live ExoPlayer audio streams.
 */
@OptIn(UnstableApi::class)
class RealTimeDeckDspProcessor : AudioProcessor {

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Real-time thread-safe DSP parameters updated at 60fps during transition preview
    @Volatile var deckGain: Float = 1.0f
    @Volatile var cutBassAmount: Float = 0.0f      // 0.0 = full bass, 1.0 = full bass cut (Bass-Swap)
    @Volatile var highPassSweep: Float = 0.0f      // 0.0 -> 1.0 filter sweep
    @Volatile var lowPassWash: Float = 0.0f        // 0.0 -> 1.0 underwater wash
    @Volatile var vocalStemGain: Float = 1.0f      // Independent Vocal Stem multiplier
    @Volatile var instrumentalStemGain: Float = 1.0f // Independent Drums/Bass/Inst Stem multiplier

    // Biquad filter state variables per channel
    private var lpStateL = 0f
    private var lpStateR = 0f
    private var subStateL = 0f
    private var subStateR = 0f

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = inputAudioFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        val channels = inputAudioFormat.channelCount
        val bassAlpha = 0.038f // ~250Hz crossover at 44.1kHz
        val dynamicSweepAlpha = (0.04f + highPassSweep * 0.45f).coerceIn(0.02f, 0.65f)

        while (inputBuffer.remaining() >= channels * 2) {
            val rawL = inputBuffer.short / 32768f
            val rawR = if (channels > 1) inputBuffer.short / 32768f else rawL

            // 1. Mid-Side Vocal vs Instrumental Real-Time Stem Separation
            val midVocal = (rawL + rawR) * 0.5f * vocalStemGain
            val sideInst = (rawL - rawR) * 0.5f * instrumentalStemGain
            var sampleL = (midVocal + sideInst) * instrumentalStemGain.coerceAtLeast(0.35f)
            var sampleR = (midVocal - sideInst) * instrumentalStemGain.coerceAtLeast(0.35f)

            // 2. Sub-Bass State Tracking for Auto Bass-Swap
            subStateL += bassAlpha * (sampleL - subStateL)
            subStateR += bassAlpha * (sampleR - subStateR)

            if (cutBassAmount > 0.01f) {
                sampleL -= subStateL * cutBassAmount
                sampleR -= subStateR * cutBassAmount
            }

            // 3. High-Pass / Low-Pass Filter Sweep FX
            lpStateL += dynamicSweepAlpha * (sampleL - lpStateL)
            lpStateR += dynamicSweepAlpha * (sampleR - lpStateR)

            if (highPassSweep > 0.01f) {
                sampleL -= lpStateL * highPassSweep
                sampleR -= lpStateR * highPassSweep
            }
            if (lowPassWash > 0.01f) {
                sampleL = sampleL * (1f - lowPassWash) + subStateL * lowPassWash
                sampleR = sampleR * (1f - lowPassWash) + subStateR * lowPassWash
            }

            // 4. Master Crossfade Envelope Gain
            val outL = (sampleL * deckGain * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            val outR = (sampleR * deckGain * 32767f).toInt().coerceIn(-32768, 32767).toShort()

            outputBuffer.putShort(outL)
            if (channels > 1) {
                outputBuffer.putShort(outR)
                for (c in 2 until channels) {
                    inputBuffer.short // consume extra surround channels if present
                    outputBuffer.putShort(outL)
                }
            }
        }

        outputBuffer.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        lpStateL = 0f
        lpStateR = 0f
        subStateL = 0f
        subStateR = 0f
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
    }
}

/**
 * Dual-Deck ExoPlayer controller that synchronizes Deck A and Deck B during the transition window.
 */
@OptIn(UnstableApi::class)
class LiveDualDeckPreviewEngine(private val context: Context) {

    val dspA = RealTimeDeckDspProcessor()
    val dspB = RealTimeDeckDspProcessor()

    private val playerA: ExoPlayer = createDeckPlayer(dspA)
    private val playerB: ExoPlayer = createDeckPlayer(dspB)

    private fun createDeckPlayer(processor: RealTimeDeckDspProcessor): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            }
        }
        return ExoPlayer.Builder(context, renderersFactory).build()
    }

    fun loadTracks(fileOrUriA: Any?, fileOrUriB: Any?) {
        fileOrUriA?.let { src ->
            val uri = when (src) {
                is Uri -> src
                is File -> Uri.fromFile(src)
                else -> null
            }
            uri?.let {
                playerA.setMediaItem(MediaItem.fromUri(it))
                playerA.prepare()
            }
        }
        fileOrUriB?.let { src ->
            val uri = when (src) {
                is Uri -> src
                is File -> Uri.fromFile(src)
                else -> null
            }
            uri?.let {
                playerB.setMediaItem(MediaItem.fromUri(it))
                playerB.prepare()
            }
        }
    }

    /**
     * Called continuously during live transition preview (progress = 0.0f to 1.0f)
     * to update real-time Biquad EQ, Crossfade Curve, Stem gains, and BPM Tempo Sync.
     */
    fun updateLiveTransitionState(
        progress: Float,
        config: TransitionConfig,
        stemConfig: StemTransitionConfig,
        bpmA: Int,
        bpmB: Int
    ) {
        val (gainA, gainB) = when (config.curve) {
            CrossfadeCurve.LINEAR -> Pair(1f - progress, progress)
            CrossfadeCurve.CONSTANT_POWER -> Pair(
                cos(progress * (Math.PI.toFloat() / 2f)),
                sin(progress * (Math.PI.toFloat() / 2f))
            )
            CrossfadeCurve.S_CURVE -> {
                val s = progress * progress * (3f - 2f * progress)
                Pair(1f - s, s)
            }
            CrossfadeCurve.HARD_SLAM -> Pair(
                if (progress < 0.85f) 1f else (1f - progress) * 6.6f,
                if (progress < 0.85f) progress * 0.4f else 1f
            )
        }

        dspA.deckGain = gainA.coerceIn(0f, 1f)
        dspB.deckGain = gainB.coerceIn(0f, 1f)

        // Live Auto Bass-Swap
        if (config.enableBassSwap) {
            dspA.cutBassAmount = if (progress >= config.bassSwapRatio) 0.92f else 0.0f
            dspB.cutBassAmount = if (progress < config.bassSwapRatio) 0.92f else 0.0f
        } else {
            dspA.cutBassAmount = 0f
            dspB.cutBassAmount = 0f
        }

        // Live Filter Sweep FX
        dspA.highPassSweep = if (config.transitionFx == TransitionFx.HIGH_PASS_SWEEP) {
            (progress * config.fxIntensity).coerceIn(0f, 0.95f)
        } else 0f

        dspA.lowPassWash = if (config.transitionFx == TransitionFx.LOW_PASS_WASH) {
            (progress * config.fxIntensity).coerceIn(0f, 0.95f)
        } else 0f

        // Live Stem Fader Multipliers
        dspA.vocalStemGain = stemConfig.outgoingVocalLevel
        dspA.instrumentalStemGain = (stemConfig.outgoingDrumsLevel + stemConfig.outgoingBassLevel) * 0.5f
        dspB.vocalStemGain = stemConfig.incomingVocalLevel
        dspB.instrumentalStemGain = (stemConfig.incomingDrumsLevel + stemConfig.incomingBassLevel) * 0.5f

        // Live BPM Tempo Sync Ramp
        if (config.enableBpmSync && bpmA > 0 && bpmB > 0) {
            val targetBpm = bpmA + (bpmB - bpmA) * progress
            val speedA = (targetBpm / bpmA.toFloat()).coerceIn(0.75f, 1.35f)
            val speedB = (targetBpm / bpmB.toFloat()).coerceIn(0.75f, 1.35f)
            playerA.playbackParameters = PlaybackParameters(speedA, 1.0f) // Pitch-locked time stretch!
            playerB.playbackParameters = PlaybackParameters(speedB, 1.0f)
        }
    }

    fun startSynchronizedPreview(trackADurationSec: Float, transitionSec: Float) {
        val startOffsetMs = ((trackADurationSec - transitionSec).coerceAtLeast(0f) * 1000f).toLong()
        playerA.seekTo(startOffsetMs)
        playerB.seekTo(0L)
        playerA.play()
        playerB.play()
    }

    fun pausePreview() {
        playerA.pause()
        playerB.pause()
    }

    fun release() {
        playerA.release()
        playerB.release()
    }
}
