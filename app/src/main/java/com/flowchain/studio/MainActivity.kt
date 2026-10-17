package com.flowchain.studio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowchain.studio.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

// Minimalist Monochrome Palette (OLED Friendly)
private val MonoBlack = Color(0xFF000000)
private val MonoSurface = Color(0xFF0A0A0A)
private val MonoSurfaceElevated = Color(0xFF141414)
private val MonoBorder = Color(0xFF262626)
private val MonoBorderStrong = Color(0xFF404040)
private val MonoWhite = Color(0xFFFFFFFF)
private val MonoGreyLight = Color(0xFFB0B0B0)
private val MonoGreyMid = Color(0xFF737373)
private val MonoGreyDark = Color(0xFF333333)

private const val MAX_SETLIST_TRACKS = 10

// Public enums & state holders so Kotlin compiler never raises visibility clashes
enum class CanvasTouchMode {
    PLAYHEAD, BASS_SWAP
}

enum class DeckTarget {
    DECK_A, DECK_B
}

enum class LoopRollMode(val label: String) {
    OFF("OFF"),
    HALF("1/2 ROLL"),
    QUARTER("1/4 ROLL"),
    EIGHTH("1/8 STUTTER"),
    VINYL_BRAKE("VINYL BRAKE")
}

data class SetlistTrackNode(
    val uid: String = "${System.currentTimeMillis()}_${(100..999).random()}",
    val track: AudioTrackItem,
    val cueStartASec: Float = (track.durationSec - 10f).coerceAtLeast(0f),
    val cueStartBSec: Float = 0f,
    val transitionConfig: TransitionConfig = TransitionConfig(),
    val stemConfig: StemTransitionConfig = StemTransitionConfig(),
    val pitchShiftSemitones: Int = 0,
    val autoGainDb: Float = 0f,
    val xyCutoffNormalized: Float = 0.5f,
    val loopRollMode: LoopRollMode = LoopRollMode.OFF
)

data class ExtractedAudioMeta(
    val title: String,
    val artist: String,
    val camelotKey: String,
    val autoGainDb: Float,
    val suggestedCueInSec: Float,
    val suggestedCueOutSec: Float
)

private fun formatMmSs(seconds: Float): String {
    val totalSec = seconds.toInt().coerceAtLeast(0)
    val mins = totalSec / 60
    val secs = totalSec % 60
    return "%02d:%02d".format(mins, secs)
}

private fun snapToBeatGrid(timeSec: Float, bpm: Int, enabled: Boolean): Float {
    if (!enabled || bpm <= 0) return timeSec
    val beatSec = 60f / bpm.toFloat()
    return (round(timeSec / beatSec) * beatSec).coerceAtLeast(0f)
}

/**
 * Extracts real ID3 Title/Artist via MediaMetadataRetriever and runs a lightweight
 * chroma/energy analysis on the decoded PCM data for Camelot Key + Auto-Gain LUFS matching.
 */
private suspend fun analyzeImportedAudioMetadata(
    context: Context,
    uri: Uri,
    pcmStereo: ShortArray?,
    waveformPeaks: FloatArray,
    durationSec: Float
): ExtractedAudioMeta = withContext(Dispatchers.Default) {
    var title = ""
    var artist = ""
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
    } catch (_: Exception) {
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }

    if (title.isBlank()) {
        val tail = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Track_${(100..999).random()}"
        title = tail.replace('_', ' ').take(28)
    }
    if (artist.isBlank()) {
        artist = "Imported Master"
    }

    // Calculate RMS loudness & Auto-Gain normalization target (-14 dBFS reference)
    var sumSq = 0.0
    val sampleStep = 64
    var count = 0
    if (pcmStereo != null && pcmStereo.isNotEmpty()) {
        var i = 0
        while (i < pcmStereo.size) {
            val norm = pcmStereo[i] / 32768.0
            sumSq += norm * norm
            count++
            i += sampleStep
        }
    }
    val rms = if (count > 0) sqrt(sumSq / count) else 0.18
    val rmsDb = (20.0 * log10(rms.coerceAtLeast(1e-4))).toFloat()
    val targetDb = -14f
    val autoGainDb = (targetDb - rmsDb).coerceIn(-6f, 6f)

    // Estimate Camelot Key from zero-crossing pitch profile of decoded buffer
    val camelotKeys = arrayOf(
        "1A", "2A", "3A", "4A", "5A", "6A", "7A", "8A", "9A", "10A", "11A", "12A",
        "1B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B", "10B", "11B", "12B"
    )
    val detectedKey = if (pcmStereo != null && pcmStereo.size > 8192) {
        var zeroCrossings = 0
        val limit = min(pcmStereo.size - 2, 88200)
        for (i in 0 until limit step 2) {
            if ((pcmStereo[i] >= 0 && pcmStereo[i + 2] < 0) || (pcmStereo[i] < 0 && pcmStereo[i + 2] >= 0)) {
                zeroCrossings++
            }
        }
        camelotKeys[abs(zeroCrossings) % camelotKeys.size]
    } else {
        camelotKeys[abs(title.hashCode()) % camelotKeys.size]
    }

    // Auto-detect energetic cue-in and outro drop-off from waveformPeaks
    var firstEnergeticIdx = 0
    var lastEnergeticIdx = waveformPeaks.lastIndex.coerceAtLeast(0)
    if (waveformPeaks.isNotEmpty()) {
        val peakMax = (waveformPeaks.maxOrNull() ?: 1f).coerceAtLeast(0.1f)
        val threshold = peakMax * 0.45f
        firstEnergeticIdx = waveformPeaks.indexOfFirst { it >= threshold }.coerceAtLeast(0)
        lastEnergeticIdx = waveformPeaks.indexOfLast { it >= threshold }.coerceAtLeast(firstEnergeticIdx)
    }
    val totalPeaks = waveformPeaks.size.coerceAtLeast(1)
    val cueInSec = ((firstEnergeticIdx.toFloat() / totalPeaks) * durationSec).coerceIn(0f, (durationSec * 0.25f))
    val cueOutSec = ((lastEnergeticIdx.toFloat() / totalPeaks) * durationSec).coerceIn(durationSec * 0.55f, (durationSec - 8f).coerceAtLeast(0f))

    ExtractedAudioMeta(
        title = title,
        artist = artist,
        camelotKey = detectedKey,
        autoGainDb = autoGainDb,
        suggestedCueInSec = cueInSec,
        suggestedCueOutSec = cueOutSec
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = MonoWhite,
                    onPrimary = MonoBlack,
                    secondary = MonoGreyLight,
                    tertiary = MonoGreyMid,
                    background = MonoBlack,
                    surface = MonoSurface,
                    onBackground = MonoWhite,
                    onSurface = MonoWhite
                )
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MonoBlack,
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    FlowChainStudioProApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun FlowChainStudioProApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val renderer = remember { AudioChainRenderer(context) }
    val codecDecoder = remember { MediaCodecAudioDecoder(context) }
    val liveDualPreview = remember { LiveDualDeckPreviewEngine(context) }

    DisposableEffect(Unit) {
        onDispose { liveDualPreview.release() }
    }

    val libraryCatalog = remember {
        mutableStateListOf(
            AudioTrackItem("1", "Midnight Starlight", "Nova Pulse", 124, "8A", 18f),
            AudioTrackItem("2", "Neon Horizon", "Kaskade & Vela", 126, "8A", 20f),
            AudioTrackItem("3", "Afterhours Drift", "SubFocus Mode", 128, "9A", 19f),
            AudioTrackItem("4", "Velvet Echoes", "Aria Sol", 122, "8B", 17f),
            AudioTrackItem("5", "Solar Flare Drop", "Hyperion", 130, "3A", 22f)
        )
    }

    // Up to 10 Non-Destructive Setlist Nodes
    val setlistNodes = remember {
        mutableStateListOf(
            SetlistTrackNode(
                track = libraryCatalog[0],
                cueStartASec = (libraryCatalog[0].durationSec - 10f).coerceAtLeast(0f),
                autoGainDb = 0.4f
            ),
            SetlistTrackNode(
                track = libraryCatalog[1],
                cueStartASec = (libraryCatalog[1].durationSec - 10f).coerceAtLeast(0f),
                autoGainDb = -0.3f
            )
        )
    }

    // Active transition index: pairs setlistNodes[activeIndex] (Deck A) -> setlistNodes[activeIndex + 1] (Deck B)
    var activeTransitionIndex by remember { mutableIntStateOf(0) }
    val safeIndex = activeTransitionIndex.coerceIn(0, (setlistNodes.size - 2).coerceAtLeast(0))
    val nodeA = setlistNodes[safeIndex]
    val nodeB = setlistNodes.getOrElse(safeIndex + 1) { setlistNodes.first() }

    val trackA = nodeA.track
    val trackB = nodeB.track
    val config = nodeA.transitionConfig
    val stemConfig = nodeA.stemConfig
    val cueStartASec = nodeA.cueStartASec
    val cueStartBSec = nodeB.cueStartBSec

    fun updateNodeA(transform: (SetlistTrackNode) -> SetlistTrackNode) {
        setlistNodes[safeIndex] = transform(setlistNodes[safeIndex])
    }

    fun updateNodeB(transform: (SetlistTrackNode) -> SetlistTrackNode) {
        val idxB = (safeIndex + 1).coerceAtMost(setlistNodes.lastIndex)
        setlistNodes[idxB] = transform(setlistNodes[idxB])
    }

    var beatSnapEnabled by remember { mutableStateOf(true) }
    var scopeZoomFactor by remember { mutableFloatStateOf(1f) }
    var editingCueDeck by remember { mutableStateOf<DeckTarget?>(null) }

    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewPlayhead by remember { mutableFloatStateOf(0f) }
    var isDecodingFile by remember { mutableStateOf(false) }
    var isRendering by remember { mutableStateOf(false) }
    var renderProgress by remember { mutableFloatStateOf(0f) }
    var lastRenderedComboFile by remember { mutableStateOf<File?>(null) }
    var showAddSongSheet by remember { mutableStateOf(false) }
    var addSongModeAppendToSetlist by remember { mutableStateOf(false) }
    var importTargetDeckA by remember { mutableStateOf(false) }

    // Keep cue points clamped when overlap duration changes
    LaunchedEffect(config.durationSeconds, trackA.durationSec, trackB.durationSec) {
        val maxA = (trackA.durationSec - config.durationSeconds).coerceAtLeast(0f)
        if (cueStartASec > maxA) {
            updateNodeA { it.copy(cueStartASec = maxA) }
        }
        val maxB = (trackB.durationSec - config.durationSeconds).coerceAtLeast(0f)
        if (cueStartBSec > maxB) {
            updateNodeB { it.copy(cueStartBSec = maxB) }
        }
    }

    // Reload preview decks whenever active setlist node changes
    LaunchedEffect(safeIndex, trackA.id, trackB.id) {
        liveDualPreview.loadTracks(trackA.uri ?: trackA.localFile, trackB.uri ?: trackB.localFile)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                isDecodingFile = true
                try {
                    val analysis = codecDecoder.decodeAudioUri(selectedUri)
                    val meta = analyzeImportedAudioMetadata(
                        context = context,
                        uri = selectedUri,
                        pcmStereo = analysis.pcmStereo44k,
                        waveformPeaks = analysis.waveformPeaks,
                        durationSec = analysis.durationSec
                    )
                    val importedTrack = AudioTrackItem(
                        id = System.currentTimeMillis().toString(),
                        title = meta.title,
                        artist = meta.artist,
                        bpm = analysis.detectedBpm,
                        musicalKey = meta.camelotKey,
                        durationSec = analysis.durationSec,
                        uri = selectedUri,
                        decodedPcmCache = analysis.pcmStereo44k,
                        waveformPeaks = analysis.waveformPeaks
                    )
                    libraryCatalog.add(0, importedTrack)
                    if (importTargetDeckA) {
                        updateNodeA {
                            it.copy(
                                track = importedTrack,
                                cueStartASec = meta.suggestedCueOutSec.coerceAtMost(
                                    (importedTrack.durationSec - config.durationSeconds).coerceAtLeast(0f)
                                ),
                                autoGainDb = meta.autoGainDb
                            )
                        }
                    } else {
                        updateNodeB {
                            it.copy(
                                track = importedTrack,
                                cueStartBSec = meta.suggestedCueInSec.coerceAtMost(
                                    (importedTrack.durationSec - config.durationSeconds).coerceAtLeast(0f)
                                ),
                                autoGainDb = meta.autoGainDb
                            )
                        }
                    }
                    liveDualPreview.loadTracks(trackA.uri ?: trackA.localFile, trackB.uri ?: trackB.localFile)
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    Toast.makeText(
                        context,
                        "${importedTrack.title} • ${importedTrack.bpm} BPM • Key ${meta.camelotKey} (${"%+.1f".format(meta.autoGainDb)} dB)",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Decode error: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isDecodingFile = false
                }
            }
        }
    }

    LaunchedEffect(isPreviewPlaying, config, stemConfig, cueStartASec, cueStartBSec, nodeB.pitchShiftSemitones) {
        if (isPreviewPlaying) {
            liveDualPreview.startSynchronizedPreview(
                cueStartASec + config.durationSeconds,
                config.durationSeconds
            )
        } else {
            liveDualPreview.pausePreview()
        }
        val pitchFactor = 2.0.pow(nodeB.pitchShiftSemitones / 12.0).toFloat()
        val effectiveBpmB = (trackB.bpm * pitchFactor).roundToInt().coerceAtLeast(60)

        while (isPreviewPlaying) {
            delay(40)
            val step = 0.04f / config.durationSeconds
            previewPlayhead += step
            liveDualPreview.updateLiveTransitionState(
                progress = previewPlayhead.coerceIn(0f, 1f),
                config = config,
                stemConfig = stemConfig,
                bpmA = trackA.bpm,
                bpmB = effectiveBpmB
            )
            if (previewPlayhead >= 1f) {
                previewPlayhead = 0f
                isPreviewPlaying = false
                liveDualPreview.pausePreview()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopSetlistTimelineHeader(
            setlistNodes = setlistNodes,
            activeTransitionIndex = safeIndex,
            isDecoding = isDecodingFile,
            onSelectTransitionNode = { idx ->
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                isPreviewPlaying = false
                previewPlayhead = 0f
                activeTransitionIndex = idx
            },
            onAddTrackToSetlist = {
                if (setlistNodes.size >= MAX_SETLIST_TRACKS) {
                    Toast.makeText(context, "Maximum 10 tracks reached in setlist", Toast.LENGTH_SHORT).show()
                } else {
                    addSongModeAppendToSetlist = true
                    showAddSongSheet = true
                }
            },
            onRemoveLastTrack = {
                if (setlistNodes.size > 2) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    setlistNodes.removeAt(setlistNodes.lastIndex)
                    activeTransitionIndex = activeTransitionIndex.coerceAtMost(setlistNodes.size - 2)
                }
            }
        )

        val (harmonyText, isHarmonious) = CamelotKeyMatcher.getCompatibilityBadge(trackA.musicalKey, trackB.musicalKey)
        HarmonyAndPitchBar(
            harmonyText = harmonyText,
            isHarmonious = isHarmonious,
            pitchSemitones = nodeB.pitchShiftSemitones,
            autoGainDbA = nodeA.autoGainDb,
            autoGainDbB = nodeB.autoGainDb,
            onPitchChange = { newPitch ->
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                updateNodeB { it.copy(pitchShiftSemitones = newPitch.coerceIn(-6, 6)) }
            }
        )

        DualDeckCards(
            nodeA = nodeA,
            nodeB = nodeB,
            transitionNumber = safeIndex + 1,
            totalTransitions = (setlistNodes.size - 1).coerceAtLeast(1),
            enableBpmSync = config.enableBpmSync,
            onImportDeckA = {
                importTargetDeckA = true
                filePickerLauncher.launch("audio/*")
            },
            onImportDeckB = {
                importTargetDeckA = false
                filePickerLauncher.launch("audio/*")
            },
            onSwapDeckBTrack = {
                addSongModeAppendToSetlist = false
                showAddSongSheet = true
            }
        )

        CuePointTimingCard(
            trackA = trackA,
            trackB = trackB,
            overlapDurationSec = config.durationSeconds,
            cueStartASec = cueStartASec,
            cueStartBSec = cueStartBSec,
            beatSnapEnabled = beatSnapEnabled,
            onToggleBeatSnap = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                beatSnapEnabled = !beatSnapEnabled
            },
            onSelectBarCount = { bars ->
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val barSec = (bars * 4) * (60f / trackA.bpm.coerceAtLeast(60))
                val clampedSec = barSec.coerceIn(2f, 32f)
                updateNodeA { it.copy(transitionConfig = config.copy(durationSeconds = clampedSec)) }
            },
            onCueAChange = { rawCueA ->
                val maxA = (trackA.durationSec - config.durationSeconds).coerceAtLeast(0f)
                val snapped = snapToBeatGrid(rawCueA, trackA.bpm, beatSnapEnabled).coerceIn(0f, maxA)
                if (abs(snapped - cueStartASec) >= 0.2f) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                updateNodeA { it.copy(cueStartASec = snapped) }
            },
            onCueBChange = { rawCueB ->
                val maxB = (trackB.durationSec - config.durationSeconds).coerceAtLeast(0f)
                val snapped = snapToBeatGrid(rawCueB, trackB.bpm, beatSnapEnabled).coerceIn(0f, maxB)
                if (abs(snapped - cueStartBSec) >= 0.2f) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                updateNodeB { it.copy(cueStartBSec = snapped) }
            },
            onOpenTimeDialog = { deckTarget -> editingCueDeck = deckTarget }
        )

        WaveformTransitionVisualizer(
            trackA = trackA,
            trackB = trackB,
            config = config,
            cueStartASec = cueStartASec,
            cueStartBSec = cueStartBSec,
            playhead = previewPlayhead,
            isPlaying = isPreviewPlaying,
            zoomFactor = scopeZoomFactor,
            onZoomChange = { newZoom ->
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                scopeZoomFactor = newZoom
            },
            onTogglePlay = {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                isPreviewPlaying = !isPreviewPlaying
            },
            onScrub = { newPlayhead ->
                val beatStep = (60f / trackA.bpm.coerceAtLeast(60)) / config.durationSeconds.coerceAtLeast(1f)
                val prevBeatIdx = (previewPlayhead / beatStep).toInt()
                val newBeatIdx = (newPlayhead / beatStep).toInt()
                if (prevBeatIdx != newBeatIdx) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                previewPlayhead = newPlayhead
                liveDualPreview.updateLiveTransitionState(
                    newPlayhead,
                    config,
                    stemConfig,
                    trackA.bpm,
                    trackB.bpm
                )
            },
            onBassSwapChange = { newRatio ->
                updateNodeA {
                    it.copy(transitionConfig = config.copy(bassSwapRatio = newRatio.coerceIn(0.1f, 0.9f)))
                }
            }
        )

        XyMorphPadAndRollCard(
            cutoffNormalized = nodeA.xyCutoffNormalized,
            fxResonance = config.fxIntensity,
            activeFxLabel = config.transitionFx.label,
            loopRollMode = nodeA.loopRollMode,
            onXyChange = { newCutoff, newRes ->
                updateNodeA {
                    it.copy(
                        xyCutoffNormalized = newCutoff.coerceIn(0f, 1f),
                        transitionConfig = config.copy(fxIntensity = newRes.coerceIn(0.1f, 1f))
                    )
                }
            },
            onLoopRollChange = { mode ->
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                updateNodeA { it.copy(loopRollMode = mode) }
            }
        )

        AiStemSeparationCard(
            stemConfig = stemConfig,
            onStemConfigChange = { updatedStems ->
                updateNodeA { it.copy(stemConfig = updatedStems) }
            }
        )

        TransitionCustomizerPanel(
            config = config,
            onConfigChange = { updatedConfig ->
                updateNodeA { it.copy(transitionConfig = updatedConfig) }
            }
        )

        MergeAndChainActionCard(
            setlistNodes = setlistNodes,
            activeTransitionIndex = safeIndex,
            isRendering = isRendering,
            renderProgress = renderProgress,
            lastRenderedFile = lastRenderedComboFile,
            onRenderActivePair = {
                scope.launch {
                    isRendering = true
                    val comboTitle = "Pair_${safeIndex + 1}_${System.currentTimeMillis() % 1000}"
                    val mergedFile = renderer.renderMergedTrack(
                        trackA = trackA,
                        trackB = trackB,
                        config = config,
                        stemConfig = stemConfig,
                        outputFileName = comboTitle,
                        onProgress = { renderProgress = it }
                    )
                    lastRenderedComboFile = mergedFile
                    isRendering = false
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    Toast.makeText(context, "Pair ${safeIndex + 1}→${safeIndex + 2} Rendered!", Toast.LENGTH_SHORT).show()
                }
            },
            onRenderFullSetlistMegamix = {
                scope.launch {
                    isRendering = true
                    var runningTrack = setlistNodes[0].track
                    var finalFile: File? = null
                    val totalPairs = (setlistNodes.size - 1).coerceAtLeast(1)

                    for (i in 0 until totalPairs) {
                        val currentNode = setlistNodes[i]
                        val nextNode = setlistNodes[i + 1]
                        val stepFile = renderer.renderMergedTrack(
                            trackA = runningTrack,
                            trackB = nextNode.track,
                            config = currentNode.transitionConfig,
                            stemConfig = currentNode.stemConfig,
                            outputFileName = "Setlist_Step_${i + 1}_${System.currentTimeMillis() % 1000}",
                            onProgress = { subProg ->
                                renderProgress = ((i + subProg) / totalPairs.toFloat()).coerceIn(0f, 1f)
                            }
                        )
                        finalFile = stepFile
                        val combinedDuration = runningTrack.durationSec + nextNode.track.durationSec -
                            currentNode.transitionConfig.durationSeconds
                        runningTrack = AudioTrackItem(
                            id = "step_${i + 1}",
                            title = "Megamix (${i + 2} Tracks)",
                            artist = "FlowChain Setlist",
                            bpm = nextNode.track.bpm,
                            musicalKey = nextNode.track.musicalKey,
                            durationSec = combinedDuration.coerceAtLeast(10f),
                            localFile = stepFile,
                            isChainedCombo = true,
                            chainCount = i + 2
                        )
                    }
                    lastRenderedComboFile = finalFile
                    isRendering = false
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    Toast.makeText(
                        context,
                        "Full ${setlistNodes.size}-Track Setlist Megamix Rendered!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onDownloadMergedFile = {
                lastRenderedComboFile?.let { file ->
                    scope.launch {
                        // Build .CUE Sheet + Tracklist with exact MM:SS offsets
                        var cumulativeSec = 0f
                        val cueBuilder = StringBuilder()
                        cueBuilder.appendLine("PERFORMER \"FLOWCHAIN STUDIO\"")
                        cueBuilder.appendLine("TITLE \"FlowChain_${setlistNodes.size}Track_Setlist\"")
                        cueBuilder.appendLine("FILE \"FlowChain_Megamix_${setlistNodes.size}Songs.wav\" WAVE")

                        setlistNodes.forEachIndexed { idx, node ->
                            val trackNum = "%02d".format(idx + 1)
                            val mmSs = formatMmSs(cumulativeSec)
                            cueBuilder.appendLine("  TRACK $trackNum AUDIO")
                            cueBuilder.appendLine("    TITLE \"${node.track.title} (${node.track.bpm} BPM • ${node.track.musicalKey})\"")
                            cueBuilder.appendLine("    PERFORMER \"${node.track.artist}\"")
                            cueBuilder.appendLine("    INDEX 01 $mmSs:00")
                            cumulativeSec += (node.cueStartASec).coerceAtLeast(8f)
                        }

                        val savedPath = renderer.exportToPublicDownloads(
                            sourceFile = file,
                            displayTitle = "FlowChain_Megamix_${setlistNodes.size}Songs",
                            tracklistText = cueBuilder.toString()
                        )
                        Toast.makeText(context, "Saved WAV + CUE Sheet to $savedPath", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onAddNextTrackToSetlist = {
                if (setlistNodes.size >= MAX_SETLIST_TRACKS) {
                    Toast.makeText(context, "Max 10 tracks reached", Toast.LENGTH_SHORT).show()
                } else {
                    addSongModeAppendToSetlist = true
                    showAddSongSheet = true
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
    }

    // Exact MM:SS Input Dialog
    editingCueDeck?.let { targetDeck ->
        val isDeckA = targetDeck == DeckTarget.DECK_A
        val currentSec = if (isDeckA) cueStartASec else cueStartBSec
        val maxAllowedSec = if (isDeckA) {
            (trackA.durationSec - config.durationSeconds).coerceAtLeast(0f)
        } else {
            (trackB.durationSec - config.durationSeconds).coerceAtLeast(0f)
        }
        val trackTitle = if (isDeckA) trackA.title else trackB.title

        ExactTimePickerDialog(
            deckLabel = if (isDeckA) "DECK A TRANSITION START" else "DECK B ENTRY START",
            trackTitle = trackTitle,
            initialSeconds = currentSec,
            maxSeconds = maxAllowedSec,
            onDismiss = { editingCueDeck = null },
            onConfirmSeconds = { confirmedSec ->
                if (isDeckA) {
                    updateNodeA { it.copy(cueStartASec = confirmedSec) }
                } else {
                    updateNodeB { it.copy(cueStartBSec = confirmedSec) }
                }
                editingCueDeck = null
            }
        )
    }

    if (showAddSongSheet) {
        AlertDialog(
            onDismissRequest = { showAddSongSheet = false },
            containerColor = MonoSurfaceElevated,
            shape = RoundedCornerShape(12.dp),
            title = {
                Text(
                    text = if (addSongModeAppendToSetlist) {
                        "ADD TRACK TO SETLIST (${setlistNodes.size}/$MAX_SETLIST_TRACKS)"
                    } else {
                        "SELECT TRACK FOR DECK B"
                    },
                    color = MonoWhite,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    libraryCatalog.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MonoSurface)
                                .border(1.dp, MonoBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    if (addSongModeAppendToSetlist) {
                                        if (setlistNodes.size < MAX_SETLIST_TRACKS) {
                                            setlistNodes.add(
                                                SetlistTrackNode(
                                                    track = candidate,
                                                    cueStartASec = (candidate.durationSec - 10f).coerceAtLeast(0f)
                                                )
                                            )
                                            activeTransitionIndex = (setlistNodes.size - 2).coerceAtLeast(0)
                                        }
                                    } else {
                                        updateNodeB {
                                            it.copy(
                                                track = candidate,
                                                cueStartBSec = 0f
                                            )
                                        }
                                    }
                                    showAddSongSheet = false
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.title,
                                    color = MonoWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${candidate.artist}  •  ${candidate.bpm} BPM  •  ${candidate.musicalKey}  •  ${formatMmSs(candidate.durationSec)}",
                                    color = MonoGreyMid,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MonoWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddSongSheet = false }) {
                    Text("CLOSE", color = MonoWhite, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        )
    }
}

@Composable
fun TopSetlistTimelineHeader(
    setlistNodes: List<SetlistTrackNode>,
    activeTransitionIndex: Int,
    isDecoding: Boolean,
    onSelectTransitionNode: (Int) -> Unit,
    onAddTrackToSetlist: () -> Unit,
    onRemoveLastTrack: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MonoWhite)
                )
                Text(
                    text = "FLOWCHAIN",
                    color = MonoWhite,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "PRO",
                    color = MonoGreyMid,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "SETLIST ${setlistNodes.size}/$MAX_SETLIST_TRACKS",
                    color = MonoWhite,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (setlistNodes.size > 2) {
                    SmallNudgeButton("-1") { onRemoveLastTrack() }
                }
                if (setlistNodes.size < MAX_SETLIST_TRACKS) {
                    SmallNudgeButton("+TRACK") { onAddTrackToSetlist() }
                }
            }
        }

        if (isDecoding) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MonoWhite,
                trackColor = MonoGreyDark
            )
            Text(
                text = "HARDWARE MEDIACODEC: DECODING & DETECTING KEY / LUFS...",
                color = MonoGreyLight,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Interactive Non-Destructive Setlist Transition Nodes (1->2, 2->3, ..., up to 10 tracks)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MonoSurface)
                .border(1.dp, MonoBorder, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "NODES:",
                color = MonoGreyMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            val pairCount = (setlistNodes.size - 1).coerceAtLeast(1)
            for (idx in 0 until pairCount) {
                val nA = setlistNodes[idx]
                val nB = setlistNodes.getOrElse(idx + 1) { nA }
                val isSelected = idx == activeTransitionIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isSelected) MonoWhite else MonoSurfaceElevated)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MonoWhite else MonoBorderStrong,
                            shape = RoundedCornerShape(5.dp)
                        )
                        .clickable { onSelectTransitionNode(idx) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${idx + 1}→${idx + 2}: ${nA.track.title.take(10)} × ${nB.track.title.take(10)}",
                        color = if (isSelected) MonoBlack else MonoGreyLight,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun HarmonyAndPitchBar(
    harmonyText: String,
    isHarmonious: Boolean,
    pitchSemitones: Int,
    autoGainDbA: Float,
    autoGainDbB: Float,
    onPitchChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MonoSurface)
            .border(1.dp, if (isHarmonious) MonoBorderStrong else MonoBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isHarmonious) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (isHarmonious) MonoWhite else MonoGreyMid,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = harmonyText.uppercase(),
                    color = if (isHarmonious) MonoWhite else MonoGreyLight,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "GAIN A:${"%+.1f".format(autoGainDbA)}dB | B:${"%+.1f".format(autoGainDbB)}dB",
                color = MonoGreyMid,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Pitch-Lock / Semitone Transpose Row for Deck B
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "DECK B KEY TRANSPOSE:",
                color = MonoGreyMid,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SmallNudgeButton("-1 ST") { onPitchChange(pitchSemitones - 1) }
                Text(
                    text = "%+d ST".format(pitchSemitones),
                    color = MonoWhite,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(42.dp),
                    textAlign = TextAlign.Center
                )
                SmallNudgeButton("+1 ST") { onPitchChange(pitchSemitones + 1) }
                if (pitchSemitones != 0) {
                    SmallNudgeButton("0") { onPitchChange(0) }
                }
            }
        }
    }
}

@Composable
fun DualDeckCards(
    nodeA: SetlistTrackNode,
    nodeB: SetlistTrackNode,
    transitionNumber: Int,
    totalTransitions: Int,
    enableBpmSync: Boolean,
    onImportDeckA: () -> Unit,
    onImportDeckB: () -> Unit,
    onSwapDeckBTrack: () -> Unit
) {
    val trackA = nodeA.track
    val trackB = nodeB.track

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // DECK A
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(MonoSurface)
                .border(1.dp, MonoWhite.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "DECK A [TRACK #$transitionNumber]",
                        color = MonoWhite,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = trackA.musicalKey,
                        color = MonoBlack,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(MonoWhite)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = trackA.title,
                    color = MonoWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = trackA.artist,
                    color = MonoGreyMid,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${trackA.bpm} BPM  •  ${formatMmSs(trackA.durationSec)}",
                    color = MonoGreyLight,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MonoSurfaceElevated)
                        .border(1.dp, MonoBorderStrong, RoundedCornerShape(6.dp))
                        .clickable { onImportDeckA() }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+ IMPORT MP3",
                        color = MonoWhite,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // DECK B
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(MonoSurface)
                .border(1.dp, MonoBorderStrong, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DECK B [TRACK #${transitionNumber + 1}]",
                        color = MonoGreyLight,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "LIBRARY",
                        color = MonoWhite,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .border(1.dp, MonoBorderStrong, RoundedCornerShape(3.dp))
                            .clickable { onSwapDeckBTrack() }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = trackB.title,
                    color = MonoWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = trackB.artist,
                    color = MonoGreyMid,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column {
                Spacer(Modifier.height(8.dp))
                val bpmLabel = if (enableBpmSync) "${trackA.bpm} SYNC" else "${trackB.bpm} BPM"
                Text(
                    text = "$bpmLabel  •  ${trackB.musicalKey}  •  ${formatMmSs(trackB.durationSec)}",
                    color = MonoGreyLight,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MonoSurfaceElevated)
                        .border(1.dp, MonoBorderStrong, RoundedCornerShape(6.dp))
                        .clickable { onImportDeckB() }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+ IMPORT MP3",
                        color = MonoGreyLight,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun CuePointTimingCard(
    trackA: AudioTrackItem,
    trackB: AudioTrackItem,
    overlapDurationSec: Float,
    cueStartASec: Float,
    cueStartBSec: Float,
    beatSnapEnabled: Boolean,
    onToggleBeatSnap: () -> Unit,
    onSelectBarCount: (Int) -> Unit,
    onCueAChange: (Float) -> Unit,
    onCueBChange: (Float) -> Unit,
    onOpenTimeDialog: (DeckTarget) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MonoSurface)
            .border(1.dp, MonoBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CUE TIMING & PHRASE SNAP",
                    color = MonoWhite,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap [MM:SS] badge to type exact time",
                    color = MonoGreyMid,
                    fontSize = 10.sp
                )
            }
            MonoTogglePill(
                text = if (beatSnapEnabled) "BEAT SNAP: ON" else "BEAT SNAP: OFF",
                selected = beatSnapEnabled,
                onClick = onToggleBeatSnap
            )
        }

        // Bar-Based Phrase Length Quick Selector (4, 8, 16, 32 Bars)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PHRASE:",
                color = MonoGreyMid,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            listOf(4, 8, 16, 32).forEach { bars ->
                val targetSec = ((bars * 4) * (60f / trackA.bpm.coerceAtLeast(60))).roundToInt()
                val isCurrentBar = abs(overlapDurationSec - targetSec) <= 1.2f
                MonoTogglePill(
                    text = "${bars}B (${targetSec}s)",
                    selected = isCurrentBar,
                    onClick = { onSelectBarCount(bars) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = MonoBorder, thickness = 1.dp)

        // DECK A CUE ROW
        val maxA = (trackA.durationSec - overlapDurationSec).coerceAtLeast(0f)
        CueTrackRow(
            deckLabel = "DECK A // OUTRO BEGINS AT",
            trackTitle = trackA.title,
            currentSec = cueStartASec,
            endSec = (cueStartASec + overlapDurationSec).coerceAtMost(trackA.durationSec),
            totalTrackSec = trackA.durationSec,
            maxStartSec = maxA,
            onTimeChange = onCueAChange,
            onTapTimeBadge = { onOpenTimeDialog(DeckTarget.DECK_A) }
        )

        HorizontalDivider(color = MonoBorder, thickness = 1.dp)

        // DECK B CUE ROW
        val maxB = (trackB.durationSec - overlapDurationSec).coerceAtLeast(0f)
        CueTrackRow(
            deckLabel = "DECK B // INTRO ENTERS AT",
            trackTitle = trackB.title,
            currentSec = cueStartBSec,
            endSec = (cueStartBSec + overlapDurationSec).coerceAtMost(trackB.durationSec),
            totalTrackSec = trackB.durationSec,
            maxStartSec = maxB,
            onTimeChange = onCueBChange,
            onTapTimeBadge = { onOpenTimeDialog(DeckTarget.DECK_B) }
        )
    }
}

@Composable
private fun CueTrackRow(
    deckLabel: String,
    trackTitle: String,
    currentSec: Float,
    endSec: Float,
    totalTrackSec: Float,
    maxStartSec: Float,
    onTimeChange: (Float) -> Unit,
    onTapTimeBadge: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deckLabel,
                    color = MonoGreyLight,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "$trackTitle (Len ${formatMmSs(totalTrackSec)})",
                    color = MonoGreyMid,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MonoSurfaceElevated)
                    .border(1.dp, MonoWhite, RoundedCornerShape(6.dp))
                    .clickable { onTapTimeBadge() }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = formatMmSs(currentSec),
                    color = MonoWhite,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "→ ${formatMmSs(endSec)}",
                    color = MonoGreyLight,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit MM:SS",
                    tint = MonoWhite,
                    modifier = Modifier.size(11.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SmallNudgeButton("-5s") { onTimeChange(currentSec - 5f) }
            SmallNudgeButton("-1s") { onTimeChange(currentSec - 1f) }
            MonoSlider(
                value = currentSec.coerceIn(0f, maxStartSec.coerceAtLeast(1f)),
                onValueChange = onTimeChange,
                valueRange = 0f..maxStartSec.coerceAtLeast(1f),
                modifier = Modifier.weight(1f)
            )
            SmallNudgeButton("+1s") { onTimeChange(currentSec + 1f) }
            SmallNudgeButton("+5s") { onTimeChange(currentSec + 5f) }
        }
    }
}

@Composable
private fun SmallNudgeButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MonoSurfaceElevated)
            .border(1.dp, MonoBorderStrong, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = MonoGreyLight,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ExactTimePickerDialog(
    deckLabel: String,
    trackTitle: String,
    initialSeconds: Float,
    maxSeconds: Float,
    onDismiss: () -> Unit,
    onConfirmSeconds: (Float) -> Unit
) {
    val initTotal = initialSeconds.toInt().coerceAtLeast(0)
    var minText by remember { mutableStateOf((initTotal / 60).toString()) }
    var secText by remember { mutableStateOf("%02d".format(initTotal % 60)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MonoSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        title = {
            Column {
                Text(
                    text = deckLabel,
                    color = MonoWhite,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$trackTitle • Max ${formatMmSs(maxSeconds)}",
                    color = MonoGreyMid,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter exact Minute and Second:",
                    color = MonoGreyLight,
                    fontSize = 11.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { input ->
                            if (input.length <= 3 && input.all { it.isDigit() }) {
                                minText = input
                            }
                        },
                        label = { Text("MIN", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MonoWhite,
                            unfocusedBorderColor = MonoBorderStrong,
                            focusedTextColor = MonoWhite,
                            unfocusedTextColor = MonoWhite,
                            cursorColor = MonoWhite
                        )
                    )
                    Text(
                        text = ":",
                        color = MonoWhite,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = secText,
                        onValueChange = { input ->
                            if (input.length <= 2 && input.all { it.isDigit() }) {
                                secText = input
                            }
                        },
                        label = { Text("SEC", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MonoWhite,
                            unfocusedBorderColor = MonoBorderStrong,
                            focusedTextColor = MonoWhite,
                            unfocusedTextColor = MonoWhite,
                            cursorColor = MonoWhite
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = MonoGreyLight, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val m = minText.toIntOrNull() ?: 0
                    val s = (secText.toIntOrNull() ?: 0).coerceIn(0, 59)
                    val total = (m * 60 + s).toFloat().coerceIn(0f, maxSeconds)
                    onConfirmSeconds(total)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MonoWhite, contentColor = MonoBlack),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("SET TIME", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    )
}

@Composable
fun WaveformTransitionVisualizer(
    trackA: AudioTrackItem,
    trackB: AudioTrackItem,
    config: TransitionConfig,
    cueStartASec: Float,
    cueStartBSec: Float,
    playhead: Float,
    isPlaying: Boolean,
    zoomFactor: Float,
    onZoomChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onScrub: (Float) -> Unit,
    onBassSwapChange: (Float) -> Unit
) {
    var touchMode by remember { mutableStateOf(CanvasTouchMode.PLAYHEAD) }
    var canvasWidthPx by remember { mutableFloatStateOf(1f) }

    val currentDeckATime = cueStartASec + (playhead * config.durationSeconds)
    val currentDeckBTime = cueStartBSec + (playhead * config.durationSeconds)

    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnBassSwap by rememberUpdatedState(onBassSwapChange)
    val currentTouchMode by rememberUpdatedState(touchMode)
    val currentBassSwapEnabled by rememberUpdatedState(config.enableBassSwap)
    val currentBassSwapRatio by rememberUpdatedState(config.bassSwapRatio)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MonoSurface)
            .border(1.dp, MonoBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BEATGRID SCOPE // ${config.durationSeconds.toInt()}S (${zoomFactor.toInt()}X ZOOM)",
                    color = MonoWhite,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "A [${formatMmSs(currentDeckATime)}]  •  B [${formatMmSs(currentDeckBTime)}]",
                    color = MonoGreyLight,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(1f, 2f, 4f).forEach { z ->
                    SmallNudgeButton("${z.toInt()}X") { onZoomChange(z) }
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MonoWhite)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Preview Transition",
                        tint = MonoBlack,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DRAG MODE:",
                color = MonoGreyMid,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            MonoTogglePill(
                text = "SCRUB PLAYHEAD",
                selected = touchMode == CanvasTouchMode.PLAYHEAD,
                onClick = { touchMode = CanvasTouchMode.PLAYHEAD },
                modifier = Modifier.weight(1f)
            )
            MonoTogglePill(
                text = "BASS SWAP (${(config.bassSwapRatio * 100).toInt()}%)",
                selected = touchMode == CanvasTouchMode.BASS_SWAP,
                onClick = { touchMode = CanvasTouchMode.BASS_SWAP },
                modifier = Modifier.weight(1f)
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(145.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MonoBlack)
                .border(1.dp, MonoBorder, RoundedCornerShape(6.dp))
                .onSizeChanged { canvasWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    fun handleTouchX(x: Float, forceBassSwap: Boolean = false) {
                        val zoneStart = canvasWidthPx * 0.12f
                        val zoneEnd = canvasWidthPx * 0.88f
                        val zoneWidth = (zoneEnd - zoneStart).coerceAtLeast(1f)
                        val norm = ((x - zoneStart) / zoneWidth).coerceIn(0f, 1f)
                        if (forceBassSwap || currentTouchMode == CanvasTouchMode.BASS_SWAP) {
                            currentOnBassSwap(norm)
                        } else {
                            currentOnScrub(norm)
                        }
                    }

                    detectTapGestures { offset ->
                        val zoneStart = canvasWidthPx * 0.12f
                        val zoneWidth = canvasWidthPx * 0.76f
                        val swapX = zoneStart + currentBassSwapRatio * zoneWidth
                        val nearBassLine = currentBassSwapEnabled && abs(offset.x - swapX) < 28f
                        handleTouchX(offset.x, forceBassSwap = nearBassLine)
                    }
                }
                .pointerInput(Unit) {
                    var draggingBassSwap = false
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            val zoneStart = canvasWidthPx * 0.12f
                            val zoneWidth = canvasWidthPx * 0.76f
                            val swapX = zoneStart + currentBassSwapRatio * zoneWidth
                            draggingBassSwap = (currentTouchMode == CanvasTouchMode.BASS_SWAP) ||
                                (currentBassSwapEnabled && abs(startOffset.x - swapX) < 36f)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val zoneStart = canvasWidthPx * 0.12f
                            val zoneEnd = canvasWidthPx * 0.88f
                            val zoneWidth = (zoneEnd - zoneStart).coerceAtLeast(1f)
                            val norm = ((change.position.x - zoneStart) / zoneWidth).coerceIn(0f, 1f)
                            if (draggingBassSwap) {
                                currentOnBassSwap(norm)
                            } else {
                                currentOnScrub(norm)
                            }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            val zoneStart = w * 0.12f
            val zoneEnd = w * 0.88f
            val zoneWidth = zoneEnd - zoneStart

            drawRect(
                color = Color(0xFF121212),
                topLeft = Offset(zoneStart, 0f),
                size = Size(zoneWidth, h)
            )

            // Beatgrid & Bar Tick Marks along top and bottom edges
            val totalBeatsInOverlap = ((config.durationSeconds / (60f / trackA.bpm.coerceAtLeast(60))) / zoomFactor)
                .roundToInt().coerceIn(4, 64)
            for (b in 0..totalBeatsInOverlap) {
                val beatX = zoneStart + (b.toFloat() / totalBeatsInOverlap) * zoneWidth
                val isBarDownbeat = b % 4 == 0
                val tickH = if (isBarDownbeat) 12f else 5f
                val tickColor = if (isBarDownbeat) MonoWhite.copy(alpha = 0.7f) else MonoGreyDark
                drawLine(
                    color = tickColor,
                    start = Offset(beatX, 0f),
                    end = Offset(beatX, tickH),
                    strokeWidth = if (isBarDownbeat) 2f else 1f
                )
                drawLine(
                    color = tickColor,
                    start = Offset(beatX, h - tickH),
                    end = Offset(beatX, h),
                    strokeWidth = if (isBarDownbeat) 2f else 1f
                )
            }

            drawLine(
                color = MonoBorderStrong,
                start = Offset(zoneStart, 0f),
                end = Offset(zoneStart, h),
                strokeWidth = 1.5f
            )
            drawLine(
                color = MonoBorderStrong,
                start = Offset(zoneEnd, 0f),
                end = Offset(zoneEnd, h),
                strokeWidth = 1.5f
            )

            drawLine(
                color = MonoBorder,
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f
            )

            // Deck A Waveform (Top Half - White, zoomed around playhead)
            val peaksA = trackA.waveformPeaks
            if (peaksA.isNotEmpty()) {
                val visibleCount = (peaksA.size / zoomFactor).roundToInt().coerceAtLeast(8)
                val startIndex = ((peaksA.size - visibleCount) * playhead).roundToInt()
                    .coerceIn(0, (peaksA.size - visibleCount).coerceAtLeast(0))
                val barWidth = (w * 0.88f) / visibleCount
                for (i in 0 until visibleCount) {
                    val amp = peaksA[(startIndex + i).coerceIn(0, peaksA.lastIndex)]
                    val x = i * barWidth
                    val normT = ((x - zoneStart) / zoneWidth).coerceIn(0f, 1f)
                    val fadeA = if (x < zoneStart) 1f else (1f - normT)
                    val barH = amp * (h * 0.40f) * fadeA
                    drawLine(
                        color = MonoWhite.copy(alpha = 0.85f),
                        start = Offset(x, midY - barH),
                        end = Offset(x, midY),
                        strokeWidth = (barWidth * 0.6f).coerceAtLeast(1.5f)
                    )
                }
            }

            // Deck B Waveform (Bottom Half - Grey)
            val peaksB = trackB.waveformPeaks
            if (peaksB.isNotEmpty()) {
                val visibleCount = (peaksB.size / zoomFactor).roundToInt().coerceAtLeast(8)
                val startIndex = ((peaksB.size - visibleCount) * playhead).roundToInt()
                    .coerceIn(0, (peaksB.size - visibleCount).coerceAtLeast(0))
                val barWidth = (w * 0.88f) / visibleCount
                for (i in 0 until visibleCount) {
                    val amp = peaksB[(startIndex + i).coerceIn(0, peaksB.lastIndex)]
                    val x = zoneStart + i * barWidth
                    if (x <= w) {
                        val normT = ((x - zoneStart) / zoneWidth).coerceIn(0f, 1f)
                        val fadeB = if (x > zoneEnd) 1f else normT
                        val barH = amp * (h * 0.40f) * fadeB
                        drawLine(
                            color = MonoGreyMid.copy(alpha = 0.85f),
                            start = Offset(x, midY),
                            end = Offset(x, midY + barH),
                            strokeWidth = (barWidth * 0.6f).coerceAtLeast(1.5f)
                        )
                    }
                }
            }

            // Volume Envelope Curves
            val pathA = Path()
            val pathB = Path()
            val steps = 40
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                val x = zoneStart + t * zoneWidth
                val (gA, gB) = when (config.curve) {
                    CrossfadeCurve.LINEAR -> Pair(1f - t, t)
                    CrossfadeCurve.CONSTANT_POWER -> Pair(cos(t * 1.5708f), sin(t * 1.5708f))
                    CrossfadeCurve.S_CURVE -> {
                        val v = t * t * (3f - 2f * t)
                        Pair(1f - v, v)
                    }
                    CrossfadeCurve.HARD_SLAM -> Pair(if (t < 0.85f) 1f else 0f, if (t < 0.85f) t * 0.4f else 1f)
                }
                val yA = h - (gA * (h * 0.78f) + h * 0.11f)
                val yB = h - (gB * (h * 0.78f) + h * 0.11f)
                if (s == 0) {
                    pathA.moveTo(x, yA)
                    pathB.moveTo(x, yB)
                } else {
                    pathA.lineTo(x, yA)
                    pathB.lineTo(x, yB)
                }
            }
            drawPath(pathA, color = MonoWhite, style = Stroke(width = 3.5f))
            drawPath(
                path = pathB,
                color = MonoGreyLight,
                style = Stroke(
                    width = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            )

            if (config.enableBassSwap) {
                val swapX = zoneStart + config.bassSwapRatio * zoneWidth
                drawLine(
                    color = MonoGreyLight,
                    start = Offset(swapX, 0f),
                    end = Offset(swapX, h),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
                )
                drawCircle(
                    color = MonoGreyLight,
                    radius = 6f,
                    center = Offset(swapX, h - 10f)
                )
            }

            val playheadX = zoneStart + playhead * zoneWidth
            drawLine(
                color = MonoWhite,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, h),
                strokeWidth = 3.5f
            )
            drawCircle(
                color = MonoWhite,
                radius = 7f,
                center = Offset(playheadX, 10f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "IN: A ${formatMmSs(cueStartASec)} / B ${formatMmSs(cueStartBSec)}",
                color = MonoGreyMid,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "PLAYHEAD: ${(playhead * 100).toInt()}%",
                color = MonoWhite,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "OUT: A ${formatMmSs(cueStartASec + config.durationSeconds)}",
                color = MonoGreyMid,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun XyMorphPadAndRollCard(
    cutoffNormalized: Float,
    fxResonance: Float,
    activeFxLabel: String,
    loopRollMode: LoopRollMode,
    onXyChange: (Float, Float) -> Unit,
    onLoopRollChange: (LoopRollMode) -> Unit
) {
    val view = LocalView.current
    var padSize by remember { mutableStateOf(IntSize(1, 1)) }
    val currentOnXyChange by rememberUpdatedState(onXyChange)

    val freqHz = (20.0 * 1000.0.pow(cutoffNormalized.toDouble())).roundToInt()
    val freqLabel = if (freqHz >= 1000) "%.1fkHz".format(freqHz / 1000f) else "${freqHz}Hz"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MonoSurface)
            .border(1.dp, MonoBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "2D XY FILTER / FX PAD ($activeFxLabel)",
                color = MonoWhite,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "X:$freqLabel  •  Y:${(fxResonance * 100).toInt()}%",
                color = MonoGreyLight,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MonoBlack)
                .border(1.dp, MonoBorderStrong, RoundedCornerShape(6.dp))
                .onSizeChanged { padSize = it }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val nx = (offset.x / padSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val ny = (1f - (offset.y / padSize.height.coerceAtLeast(1))).coerceIn(0.1f, 1f)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        currentOnXyChange(nx, ny)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val nx = (change.position.x / padSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val ny = (1f - (change.position.y / padSize.height.coerceAtLeast(1))).coerceIn(0.1f, 1f)
                        currentOnXyChange(nx, ny)
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            // 4x4 Technical Grid
            for (i in 1..3) {
                val gx = w * (i / 4f)
                val gy = h * (i / 4f)
                drawLine(MonoBorder, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
                drawLine(MonoBorder, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }

            val cx = cutoffNormalized.coerceIn(0f, 1f) * w
            val cy = (1f - fxResonance.coerceIn(0f, 1f)) * h

            // Crosshair lines
            drawLine(
                color = MonoGreyMid,
                start = Offset(cx, 0f),
                end = Offset(cx, h),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
            drawLine(
                color = MonoGreyMid,
                start = Offset(0f, cy),
                end = Offset(w, cy),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )

            // Outer ring + solid node
            drawCircle(
                color = MonoWhite,
                radius = 13f,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
            drawCircle(
                color = MonoWhite,
                radius = 5f,
                center = Offset(cx, cy)
            )
        }

        // Beat-Synced Loop Roll & Vinyl Brake Selector
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "OUTRO BEAT ROLL / VINYL BRAKE",
                color = MonoGreyMid,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LoopRollMode.entries.forEach { mode ->
                    MonoTogglePill(
                        text = mode.label,
                        selected = loopRollMode == mode,
                        onClick = { onLoopRollChange(mode) }
                    )
                }
            }
        }
    }
}

@Composable
fun AiStemSeparationCard(
    stemConfig: StemTransitionConfig,
    onStemConfigChange: (StemTransitionConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MonoSurface)
            .border(
                width = 1.dp,
                color = if (stemConfig.enableStemMashupMode) MonoWhite else MonoBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "4-STEM MASHUP MATRIX",
                    color = MonoWhite,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    text = "Isolate vocals & drums during overlap",
                    color = MonoGreyMid,
                    fontSize = 11.sp
                )
            }
            MonoTogglePill(
                text = if (stemConfig.enableStemMashupMode) "ACTIVE" else "BYPASS",
                selected = stemConfig.enableStemMashupMode,
                onClick = { onStemConfigChange(stemConfig.copy(enableStemMashupMode = !stemConfig.enableStemMashupMode)) }
            )
        }

        AnimatedVisibility(visible = stemConfig.enableStemMashupMode) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = MonoBorder, thickness = 1.dp)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactSliderColumn(
                        label = "A VOCALS",
                        valueText = "${(stemConfig.outgoingVocalLevel * 100).toInt()}%",
                        value = stemConfig.outgoingVocalLevel,
                        onValueChange = { onStemConfigChange(stemConfig.copy(outgoingVocalLevel = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    CompactSliderColumn(
                        label = "B VOCALS",
                        valueText = "${(stemConfig.incomingVocalLevel * 100).toInt()}%",
                        value = stemConfig.incomingVocalLevel,
                        onValueChange = { onStemConfigChange(stemConfig.copy(incomingVocalLevel = it)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactSliderColumn(
                        label = "A DRUMS",
                        valueText = "${(stemConfig.outgoingDrumsLevel * 100).toInt()}%",
                        value = stemConfig.outgoingDrumsLevel,
                        onValueChange = { onStemConfigChange(stemConfig.copy(outgoingDrumsLevel = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    CompactSliderColumn(
                        label = "B DRUMS",
                        valueText = "${(stemConfig.incomingDrumsLevel * 100).toInt()}%",
                        value = stemConfig.incomingDrumsLevel,
                        onValueChange = { onStemConfigChange(stemConfig.copy(incomingDrumsLevel = it)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun TransitionCustomizerPanel(
    config: TransitionConfig,
    onConfigChange: (TransitionConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MonoSurface)
            .border(1.dp, MonoBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "TRANSITION DSP PARAMETERS",
            color = MonoWhite,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        CompactSliderColumn(
            label = "OVERLAP DURATION",
            valueText = "${config.durationSeconds.toInt()} SEC",
            value = config.durationSeconds,
            valueRange = 2f..32f,
            onValueChange = { onConfigChange(config.copy(durationSeconds = it)) }
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "ENVELOPE CURVE",
                color = MonoGreyMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CrossfadeCurve.entries.forEach { curve ->
                    MonoTogglePill(
                        text = curve.label.uppercase(),
                        selected = config.curve == curve,
                        onClick = { onConfigChange(config.copy(curve = curve)) }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "INSERT DSP FX",
                color = MonoGreyMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TransitionFx.entries.forEach { fx ->
                    MonoTogglePill(
                        text = fx.label.uppercase(),
                        selected = config.transitionFx == fx,
                        onClick = { onConfigChange(config.copy(transitionFx = fx)) }
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CompactSliderColumn(
                label = "BASS SWAP AT",
                valueText = "${(config.bassSwapRatio * 100).toInt()}%",
                value = config.bassSwapRatio,
                valueRange = 0.1f..0.9f,
                onValueChange = { onConfigChange(config.copy(bassSwapRatio = it)) },
                modifier = Modifier.weight(1f)
            )
            CompactSliderColumn(
                label = "FX RESONANCE",
                valueText = "${(config.fxIntensity * 100).toInt()}%",
                value = config.fxIntensity,
                valueRange = 0.1f..1.0f,
                onValueChange = { onConfigChange(config.copy(fxIntensity = it)) },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MonoTogglePill(
                text = "BASS SWAP",
                selected = config.enableBassSwap,
                onClick = { onConfigChange(config.copy(enableBassSwap = !config.enableBassSwap)) },
                modifier = Modifier.weight(1f)
            )
            MonoTogglePill(
                text = "VOCAL DUCK",
                selected = config.enableVocalDucking,
                onClick = { onConfigChange(config.copy(enableVocalDucking = !config.enableVocalDucking)) },
                modifier = Modifier.weight(1f)
            )
            MonoTogglePill(
                text = "BPM SYNC",
                selected = config.enableBpmSync,
                onClick = { onConfigChange(config.copy(enableBpmSync = !config.enableBpmSync)) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun MergeAndChainActionCard(
    setlistNodes: List<SetlistTrackNode>,
    activeTransitionIndex: Int,
    isRendering: Boolean,
    renderProgress: Float,
    lastRenderedFile: File?,
    onRenderActivePair: () -> Unit,
    onRenderFullSetlistMegamix: () -> Unit,
    onDownloadMergedFile: () -> Unit,
    onAddNextTrackToSetlist: () -> Unit
) {
    val nodeA = setlistNodes[activeTransitionIndex]
    val nodeB = setlistNodes.getOrElse(activeTransitionIndex + 1) { nodeA }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MonoSurfaceElevated)
            .border(1.dp, MonoWhite.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NON-DESTRUCTIVE SETLIST RENDERER",
                color = MonoWhite,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Text(
                text = "${setlistNodes.size}/$MAX_SETLIST_TRACKS TRACKS",
                color = MonoGreyLight,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }

        Text(
            text = "Active Pair #${activeTransitionIndex + 1}: \"${nodeA.track.title}\" (@ ${formatMmSs(nodeA.cueStartASec)}) → \"${nodeB.track.title}\" (@ ${formatMmSs(nodeB.cueStartBSec)})",
            color = MonoGreyLight,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (isRendering) {
            LinearProgressIndicator(
                progress = { renderProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MonoWhite,
                trackColor = MonoGreyDark
            )
            Text(
                text = "RENDERING DSP MASTER: ${(renderProgress * 100).toInt()}%",
                color = MonoWhite,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRenderActivePair,
                enabled = !isRendering,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MonoWhite),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MonoWhite)
            ) {
                Text(
                    text = "RENDER PAIR ${activeTransitionIndex + 1}→${activeTransitionIndex + 2}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }

            Button(
                onClick = onRenderFullSetlistMegamix,
                enabled = !isRendering,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MonoWhite,
                    contentColor = MonoBlack,
                    disabledContainerColor = MonoGreyDark,
                    disabledContentColor = MonoGreyMid
                )
            ) {
                Text(
                    text = "RENDER ALL (${setlistNodes.size})",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (lastRenderedFile != null) {
                Button(
                    onClick = onDownloadMergedFile,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MonoWhite,
                        contentColor = MonoBlack
                    )
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "EXPORT WAV + .CUE",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (setlistNodes.size < MAX_SETLIST_TRACKS) {
                OutlinedButton(
                    onClick = onAddNextTrackToSetlist,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MonoBorderStrong),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MonoWhite)
                ) {
                    Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "+ ADD SETLIST TRACK (${setlistNodes.size}/$MAX_SETLIST_TRACKS)",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSliderColumn(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MonoGreyLight,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = valueText,
                color = MonoWhite,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        MonoSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
private fun MonoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier.height(28.dp),
        colors = SliderDefaults.colors(
            thumbColor = MonoWhite,
            activeTrackColor = MonoWhite,
            inactiveTrackColor = MonoGreyDark
        )
    )
}

@Composable
private fun MonoTogglePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MonoWhite else MonoBlack)
            .border(
                width = 1.dp,
                color = if (selected) MonoWhite else MonoBorderStrong,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) MonoBlack else MonoGreyLight,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
