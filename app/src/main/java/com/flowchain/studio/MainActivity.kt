package com.flowchain.studio

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowchain.studio.engine.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

private enum class CanvasTouchMode {
    PLAYHEAD, BASS_SWAP
}

private enum class DeckTarget {
    DECK_A, DECK_B
}

private fun formatMmSs(seconds: Float): String {
    val totalSec = seconds.toInt().coerceAtLeast(0)
    val mins = totalSec / 60
    val secs = totalSec % 60
    return "%02d:%02d".format(mins, secs)
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

    var trackA by remember { mutableStateOf(libraryCatalog[0]) }
    var trackB by remember { mutableStateOf(libraryCatalog[1]) }
    var config by remember { mutableStateOf(TransitionConfig()) }
    var stemConfig by remember { mutableStateOf(StemTransitionConfig()) }

    // Custom MM:SS Cue Points (in seconds) for where the transition begins on each track
    var cueStartASec by remember {
        mutableFloatStateOf((trackA.durationSec - config.durationSeconds).coerceAtLeast(0f))
    }
    var cueStartBSec by remember { mutableFloatStateOf(0f) }
    var editingCueDeck by remember { mutableStateOf<DeckTarget?>(null) }

    val chainedHistory = remember {
        mutableStateListOf(
            "00:00 Midnight Starlight",
            "${formatMmSs((libraryCatalog[0].durationSec - TransitionConfig().durationSeconds).coerceAtLeast(0f))} Neon Horizon"
        )
    }

    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewPlayhead by remember { mutableFloatStateOf(0f) }
    var isDecodingFile by remember { mutableStateOf(false) }
    var isRendering by remember { mutableStateOf(false) }
    var renderProgress by remember { mutableFloatStateOf(0f) }
    var lastRenderedComboFile by remember { mutableStateOf<File?>(null) }
    var showAddSongSheet by remember { mutableStateOf(false) }
    var importTargetDeckA by remember { mutableStateOf(false) }

    // Keep cue points within valid track bounds when overlap duration changes
    LaunchedEffect(config.durationSeconds, trackA.durationSec, trackB.durationSec) {
        val maxA = (trackA.durationSec - config.durationSeconds).coerceAtLeast(0f)
        if (cueStartASec > maxA) cueStartASec = maxA
        val maxB = (trackB.durationSec - config.durationSeconds).coerceAtLeast(0f)
        if (cueStartBSec > maxB) cueStartBSec = maxB
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                isDecodingFile = true
                try {
                    val analysis = codecDecoder.decodeAudioUri(selectedUri)
                    val importedTrack = AudioTrackItem(
                        id = System.currentTimeMillis().toString(),
                        title = "Track_${(100..999).random()}",
                        artist = "Local Audio",
                        bpm = analysis.detectedBpm,
                        musicalKey = "8A",
                        durationSec = analysis.durationSec,
                        uri = selectedUri,
                        decodedPcmCache = analysis.pcmStereo44k,
                        waveformPeaks = analysis.waveformPeaks
                    )
                    libraryCatalog.add(0, importedTrack)
                    if (importTargetDeckA) {
                        trackA = importedTrack
                        cueStartASec = (importedTrack.durationSec - config.durationSeconds).coerceAtLeast(0f)
                        chainedHistory[0] = "00:00 ${importedTrack.title}"
                    } else {
                        trackB = importedTrack
                        cueStartBSec = 0f
                        chainedHistory[chainedHistory.lastIndex] =
                            "${formatMmSs(cueStartASec)} ${importedTrack.title}"
                    }
                    liveDualPreview.loadTracks(trackA.uri ?: trackA.localFile, trackB.uri ?: trackB.localFile)
                    Toast.makeText(
                        context,
                        "Decoded ${importedTrack.bpm} BPM (${formatMmSs(analysis.durationSec)})",
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

    LaunchedEffect(isPreviewPlaying, config, stemConfig, cueStartASec, cueStartBSec) {
        if (isPreviewPlaying) {
            // Passing (cueStartASec + config.durationSeconds) aligns Deck A's transition start to cueStartASec
            liveDualPreview.startSynchronizedPreview(
                cueStartASec + config.durationSeconds,
                config.durationSeconds
            )
        } else {
            liveDualPreview.pausePreview()
        }
        while (isPreviewPlaying) {
            delay(40)
            val step = 0.04f / config.durationSeconds
            previewPlayhead += step
            liveDualPreview.updateLiveTransitionState(
                progress = previewPlayhead.coerceIn(0f, 1f),
                config = config,
                stemConfig = stemConfig,
                bpmA = trackA.bpm,
                bpmB = trackB.bpm
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
        TopStudioHeaderPro(
            chainedHistory = chainedHistory,
            isDecoding = isDecodingFile
        )

        val (harmonyText, isHarmonious) = CamelotKeyMatcher.getCompatibilityBadge(trackA.musicalKey, trackB.musicalKey)
        HarmonyStatusBar(harmonyText = harmonyText, isHarmonious = isHarmonious)

        DualDeckCards(
            trackA = trackA,
            trackB = trackB,
            enableBpmSync = config.enableBpmSync,
            onImportDeckA = {
                importTargetDeckA = true
                filePickerLauncher.launch("audio/*")
            },
            onImportDeckB = {
                importTargetDeckA = false
                filePickerLauncher.launch("audio/*")
            },
            onSwapNextTrack = { showAddSongSheet = true }
        )

        CuePointTimingCard(
            trackA = trackA,
            trackB = trackB,
            overlapDurationSec = config.durationSeconds,
            cueStartASec = cueStartASec,
            cueStartBSec = cueStartBSec,
            onCueAChange = { newCueA ->
                val maxA = (trackA.durationSec - config.durationSeconds).coerceAtLeast(0f)
                cueStartASec = newCueA.coerceIn(0f, maxA)
                chainedHistory[chainedHistory.lastIndex] = "${formatMmSs(cueStartASec)} ${trackB.title}"
            },
            onCueBChange = { newCueB ->
                val maxB = (trackB.durationSec - config.durationSeconds).coerceAtLeast(0f)
                cueStartBSec = newCueB.coerceIn(0f, maxB)
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
            onTogglePlay = { isPreviewPlaying = !isPreviewPlaying },
            onScrub = { newPlayhead ->
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
                config = config.copy(bassSwapRatio = newRatio.coerceIn(0.1f, 0.9f))
            }
        )

        AiStemSeparationCard(
            stemConfig = stemConfig,
            onStemConfigChange = { stemConfig = it }
        )

        TransitionCustomizerPanel(
            config = config,
            onConfigChange = { config = it }
        )

        MergeAndChainActionCard(
            trackA = trackA,
            trackB = trackB,
            cueStartASec = cueStartASec,
            cueStartBSec = cueStartBSec,
            isRendering = isRendering,
            renderProgress = renderProgress,
            lastRenderedFile = lastRenderedComboFile,
            onCombineAndMerge = {
                scope.launch {
                    isRendering = true
                    val comboTitle = "Combo_${chainedHistory.size}Tracks_${System.currentTimeMillis() % 1000}"
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
                    Toast.makeText(context, "Master Render Complete", Toast.LENGTH_SHORT).show()
                }
            },
            onDownloadMergedFile = {
                lastRenderedComboFile?.let { file ->
                    scope.launch {
                        val savedPath = renderer.exportToPublicDownloads(
                            sourceFile = file,
                            displayTitle = "FlowChain_Megamix_${chainedHistory.size}Songs",
                            tracklistText = "FLOWCHAIN STUDIO TRACKLIST:\n" + chainedHistory.joinToString("\n")
                        )
                        Toast.makeText(context, "Saved to $savedPath", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onChainNextSong = { showAddSongSheet = true }
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
                    cueStartASec = confirmedSec
                    chainedHistory[chainedHistory.lastIndex] = "${formatMmSs(cueStartASec)} ${trackB.title}"
                } else {
                    cueStartBSec = confirmedSec
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
                    text = if (lastRenderedComboFile != null) "CHAIN NEXT TRACK" else "SELECT DECK B TRACK",
                    color = MonoWhite,
                    fontSize = 14.sp,
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
                                    lastRenderedComboFile?.let { mergedWav ->
                                        val newChainCount = trackA.chainCount + 1
                                        val newDuration = (cueStartASec + config.durationSeconds) +
                                            (trackB.durationSec - cueStartBSec - config.durationSeconds).coerceAtLeast(0f)
                                        trackA = AudioTrackItem(
                                            id = "combo_${System.currentTimeMillis()}",
                                            title = "Master Combo ($newChainCount)",
                                            artist = "Chained Mix",
                                            bpm = trackB.bpm,
                                            musicalKey = trackB.musicalKey,
                                            durationSec = newDuration,
                                            localFile = mergedWav,
                                            isChainedCombo = true,
                                            chainCount = newChainCount
                                        )
                                        cueStartASec = (newDuration - config.durationSeconds).coerceAtLeast(0f)
                                        lastRenderedComboFile = null
                                        chainedHistory.add("${formatMmSs(cueStartASec)} ${candidate.title}")
                                    } ?: run {
                                        chainedHistory[chainedHistory.lastIndex] =
                                            "${formatMmSs(cueStartASec)} ${candidate.title}"
                                    }
                                    trackB = candidate
                                    cueStartBSec = 0f
                                    liveDualPreview.loadTracks(trackA.uri ?: trackA.localFile, trackB.uri ?: trackB.localFile)
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
                                    text = "${candidate.artist}  •  ${candidate.bpm} BPM  •  ${formatMmSs(candidate.durationSec)}",
                                    color = MonoGreyMid,
                                    fontSize = 11.sp,
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
fun CuePointTimingCard(
    trackA: AudioTrackItem,
    trackB: AudioTrackItem,
    overlapDurationSec: Float,
    cueStartASec: Float,
    cueStartBSec: Float,
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TRANSITION CUE POINTS (MM:SS)",
                color = MonoWhite,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "TAP TIME TO TYPE",
                color = MonoGreyMid,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // DECK A CUE ROW
        val maxA = (trackA.durationSec - overlapDurationSec).coerceAtLeast(0f)
        CueTrackRow(
            deckLabel = "DECK A // TRANSITION BEGINS AT",
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
            deckLabel = "DECK B // SONG ENTERS AT",
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

            // Tappable MM:SS Readout Pill
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

        // Slider + Fine Nudge Steppers (-5s, -1s, +1s, +5s)
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
    onTogglePlay: () -> Unit,
    onScrub: (Float) -> Unit,
    onBassSwapChange: (Float) -> Unit
) {
    var touchMode by remember { mutableStateOf(CanvasTouchMode.PLAYHEAD) }
    var canvasWidthPx by remember { mutableFloatStateOf(1f) }

    val currentDeckATime = cueStartASec + (playhead * config.durationSeconds)
    val currentDeckBTime = cueStartBSec + (playhead * config.durationSeconds)

    // Updated lambdas so pointerInput always reads latest values
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
                    text = "OVERLAP SCOPE // ${config.durationSeconds.toInt()}S",
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

        // Touch Target Mode Bar for direct Canvas finger dragging
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TOUCH DRAG:",
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
                .height(140.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MonoBlack)
                .border(1.dp, MonoBorder, RoundedCornerShape(6.dp))
                .onSizeChanged { canvasWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    fun handleTouchX(x: Float, forceBassSwap: Boolean = false) {
                        val zoneStart = canvasWidthPx * 0.15f
                        val zoneEnd = canvasWidthPx * 0.85f
                        val zoneWidth = (zoneEnd - zoneStart).coerceAtLeast(1f)
                        val norm = ((x - zoneStart) / zoneWidth).coerceIn(0f, 1f)
                        if (forceBassSwap || currentTouchMode == CanvasTouchMode.BASS_SWAP) {
                            currentOnBassSwap(norm)
                        } else {
                            currentOnScrub(norm)
                        }
                    }

                    detectTapGestures { offset ->
                        val zoneStart = canvasWidthPx * 0.15f
                        val zoneWidth = canvasWidthPx * 0.70f
                        val swapX = zoneStart + currentBassSwapRatio * zoneWidth
                        val nearBassLine = currentBassSwapEnabled && abs(offset.x - swapX) < 28f
                        handleTouchX(offset.x, forceBassSwap = nearBassLine)
                    }
                }
                .pointerInput(Unit) {
                    var draggingBassSwap = false
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            val zoneStart = canvasWidthPx * 0.15f
                            val zoneWidth = canvasWidthPx * 0.70f
                            val swapX = zoneStart + currentBassSwapRatio * zoneWidth
                            draggingBassSwap = (currentTouchMode == CanvasTouchMode.BASS_SWAP) ||
                                (currentBassSwapEnabled && abs(startOffset.x - swapX) < 36f)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val zoneStart = canvasWidthPx * 0.15f
                            val zoneEnd = canvasWidthPx * 0.85f
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

            val zoneStart = w * 0.15f
            val zoneEnd = w * 0.85f
            val zoneWidth = zoneEnd - zoneStart

            // Active transition zone background
            drawRect(
                color = Color(0xFF121212),
                topLeft = Offset(zoneStart, 0f),
                size = Size(zoneWidth, h)
            )

            // Transition zone boundary lines
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

            // Center zero-crossing axis
            drawLine(
                color = MonoBorder,
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f
            )

            // Deck A Waveform (Top Half - White)
            val peaksA = trackA.waveformPeaks
            if (peaksA.isNotEmpty()) {
                val barWidth = (w * 0.85f) / peaksA.size
                peaksA.forEachIndexed { idx, amp ->
                    val x = idx * barWidth
                    val normT = ((x - zoneStart) / zoneWidth).coerceIn(0f, 1f)
                    val fadeA = if (x < zoneStart) 1f else (1f - normT)
                    val barH = amp * (h * 0.42f) * fadeA
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
                val barWidth = (w * 0.85f) / peaksB.size
                peaksB.forEachIndexed { idx, amp ->
                    val x = zoneStart + idx * barWidth
                    if (x <= w) {
                        val normT = ((x - zoneStart) / zoneWidth).coerceIn(0f, 1f)
                        val fadeB = if (x > zoneEnd) 1f else normT
                        val barH = amp * (h * 0.42f) * fadeB
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
                val yA = h - (gA * (h * 0.82f) + h * 0.09f)
                val yB = h - (gB * (h * 0.82f) + h * 0.09f)
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

            // Bass Swap Vertical Marker + Bottom Handle
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

            // Active Playhead + Top Handle
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
fun TopStudioHeaderPro(
    chainedHistory: List<String>,
    isDecoding: Boolean
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
                    text = "STUDIO",
                    color = MonoGreyMid,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp
                )
            }

            Text(
                text = "DSP // 4-STEM",
                color = MonoGreyMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
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
                text = "DECODING AUDIO & ANALYZING BPM...",
                color = MonoGreyLight,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

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
                text = "CHAIN",
                color = MonoGreyMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            chainedHistory.forEachIndexed { index, item ->
                val isLast = index == chainedHistory.lastIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isLast) MonoWhite else MonoSurfaceElevated)
                        .border(1.dp, if (isLast) MonoWhite else MonoBorderStrong, RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = item,
                        color = if (isLast) MonoBlack else MonoGreyLight,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun HarmonyStatusBar(harmonyText: String, isHarmonious: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MonoSurface)
            .border(1.dp, if (isHarmonious) MonoBorderStrong else MonoBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            text = if (isHarmonious) "KEY MATCH" else "KEY SHIFT",
            color = MonoGreyMid,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun DualDeckCards(
    trackA: AudioTrackItem,
    trackB: AudioTrackItem,
    enableBpmSync: Boolean,
    onImportDeckA: () -> Unit,
    onImportDeckB: () -> Unit,
    onSwapNextTrack: () -> Unit
) {
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
                .border(1.dp, MonoWhite.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
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
                        text = if (trackA.isChainedCombo) "DECK A [MASTER]" else "DECK A [OUT]",
                        color = MonoWhite,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    if (trackA.isChainedCombo) {
                        Text(
                            text = "${trackA.chainCount}X",
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
                    text = "${trackA.bpm} BPM  •  ${trackA.musicalKey}  •  ${formatMmSs(trackA.durationSec)}",
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
                        text = "DECK B [IN]",
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
                            .clickable { onSwapNextTrack() }
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
            valueRange = 2f..24f,
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
    trackA: AudioTrackItem,
    trackB: AudioTrackItem,
    cueStartASec: Float,
    cueStartBSec: Float,
    isRendering: Boolean,
    renderProgress: Float,
    lastRenderedFile: File?,
    onCombineAndMerge: () -> Unit,
    onDownloadMergedFile: () -> Unit,
    onChainNextSong: () -> Unit
) {
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
                text = "MASTER RENDER & CHAIN",
                color = MonoWhite,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Text(
                text = "WAV // PCM",
                color = MonoGreyMid,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }

        Text(
            text = "Merge \"${trackA.title}\" (@ ${formatMmSs(cueStartASec)}) → \"${trackB.title}\" (@ ${formatMmSs(cueStartBSec)}).",
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
                text = "RENDERING DSP TRANSITION: ${(renderProgress * 100).toInt()}%",
                color = MonoWhite,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Button(
            onClick = onCombineAndMerge,
            enabled = !isRendering,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MonoWhite,
                contentColor = MonoBlack,
                disabledContainerColor = MonoGreyDark,
                disabledContentColor = MonoGreyMid
            )
        ) {
            Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "RENDER COMBINED MASTER",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        AnimatedVisibility(visible = lastRenderedFile != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDownloadMergedFile,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MonoWhite),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MonoWhite)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "EXPORT",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onChainNextSong,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MonoSurface,
                        contentColor = MonoWhite
                    ),
                    border = BorderStroke(1.dp, MonoBorderStrong)
                ) {
                    Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "+ CHAIN NEXT",
                        fontSize = 11.sp,
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
