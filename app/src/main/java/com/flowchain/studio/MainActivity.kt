package com.flowchain.studio

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowchain.studio.engine.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00F5D4),
                    secondary = Color(0xFFFF2A85),
                    tertiary = Color(0xFF9B5DE5),
                    background = Color(0xFF0B0C10),
                    surface = Color(0xFF141720)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FlowChainStudioProApp()
                }
            }
        }
    }
}

@Composable
fun FlowChainStudioProApp() {
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
    val chainedHistory = remember { mutableStateListOf("00:00 Midnight Starlight", "00:10 Neon Horizon") }

    var config by remember { mutableStateOf(TransitionConfig()) }
    var stemConfig by remember { mutableStateOf(StemTransitionConfig()) }

    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewPlayhead by remember { mutableFloatStateOf(0f) }
    var isDecodingFile by remember { mutableStateOf(false) }
    var isRendering by remember { mutableStateOf(false) }
    var renderProgress by remember { mutableFloatStateOf(0f) }
    var lastRenderedComboFile by remember { mutableStateOf<File?>(null) }
    var showAddSongSheet by remember { mutableStateOf(false) }
    var importTargetDeckA by remember { mutableStateOf(false) }

    // Hardware MediaCodec Audio Picker (MP3 / M4A / WAV / FLAC)
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
                        title = "PhoneTrack_${(100..999).random()}",
                        artist = "MediaCodec Hardware Decoded",
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
                        chainedHistory[0] = "00:00 ${importedTrack.title}"
                    } else {
                        trackB = importedTrack
                        chainedHistory[chainedHistory.lastIndex] = "00:${(trackA.durationSec - config.durationSeconds).toInt()} ${importedTrack.title}"
                    }
                    liveDualPreview.loadTracks(trackA.uri ?: trackA.localFile, trackB.uri ?: trackB.localFile)
                    Toast.makeText(context, "Decoded ${importedTrack.bpm} BPM (${analysis.durationSec.toInt()}s)", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Decode error: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isDecodingFile = false
                }
            }
        }
    }

    // Real-Time Dual ExoPlayer + DSP Scrubber Loop
    LaunchedEffect(isPreviewPlaying, config, stemConfig) {
        if (isPreviewPlaying) {
            liveDualPreview.startSynchronizedPreview(trackA.durationSec, config.durationSeconds)
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. HEADER & HARDWARE DECODER IMPORT BUTTONS
        TopStudioHeaderPro(
            chainedHistory = chainedHistory,
            isDecoding = isDecodingFile,
            onImportDeckA = {
                importTargetDeckA = true
                filePickerLauncher.launch("audio/*")
            },
            onImportDeckB = {
                importTargetDeckA = false
                filePickerLauncher.launch("audio/*")
            }
        )

        // 2. CAMELOT HARMONIC KEY COMPATIBILITY BANNER
        val (harmonyText, isHarmonious) = CamelotKeyMatcher.getCompatibilityBadge(trackA.musicalKey, trackB.musicalKey)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isHarmonious) Color(0x2200F5D4) else Color(0x33FFBE0B))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                if (isHarmonious) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isHarmonious) Color(0xFF00F5D4) else Color(0xFFFFBE0B),
                modifier = Modifier.size(16.dp)
            )
            Text(harmonyText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        // 3. DUAL DECK CARDS
        DualDeckCards(
            trackA = trackA,
            trackB = trackB,
            enableBpmSync = config.enableBpmSync,
            onSwapNextTrack = { showAddSongSheet = true }
        )

        // 4. INTERACTIVE WAVEFORM & LIVE EXOPLAYER PREVIEW
        WaveformTransitionVisualizer(
            trackA = trackA,
            trackB = trackB,
            config = config,
            playhead = previewPlayhead,
            isPlaying = isPreviewPlaying,
            onTogglePlay = { isPreviewPlaying = !isPreviewPlaying },
            onScrub = {
                previewPlayhead = it
                liveDualPreview.updateLiveTransitionState(it, config, stemConfig, trackA.bpm, trackB.bpm)
            }
        )

        // 5. AI 4-STEM SEPARATION & MASHUP MATRIX
        AiStemSeparationCard(
            stemConfig = stemConfig,
            onStemConfigChange = { stemConfig = it }
        )

        // 6. CROSSFADE, BASS-SWAP & LOOP-ROLL FX PANEL
        TransitionCustomizerPanel(
            config = config,
            onConfigChange = { config = it }
        )

        // 7. MERGE, DOWNLOAD (+ TRACKLIST) & DAISY-CHAIN ACTION BAR
        MergeAndChainActionCard(
            trackA = trackA,
            trackB = trackB,
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
                    Toast.makeText(context, "Combo Rendered with Stems & DSP!", Toast.LENGTH_SHORT).show()
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
    }

    if (showAddSongSheet) {
        AlertDialog(
            onDismissRequest = { showAddSongSheet = false },
            containerColor = Color(0xFF181C28),
            title = {
                Text(
                    if (lastRenderedComboFile != null) "Chain Next Song onto Master Combo"
                    else "Select Song for Deck B",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    libraryCatalog.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF222838))
                                .clickable {
                                    lastRenderedComboFile?.let { mergedWav ->
                                        val newChainCount = trackA.chainCount + 1
                                        val newDuration = trackA.durationSec + trackB.durationSec - config.durationSeconds
                                        trackA = AudioTrackItem(
                                            id = "combo_${System.currentTimeMillis()}",
                                            title = "Master Combo ($newChainCount Songs)",
                                            artist = "Chained Megamix",
                                            bpm = trackB.bpm,
                                            musicalKey = trackB.musicalKey,
                                            durationSec = newDuration,
                                            localFile = mergedWav,
                                            isChainedCombo = true,
                                            chainCount = newChainCount
                                        )
                                        lastRenderedComboFile = null
                                        val mins = (newDuration.toInt() / 60).toString().padStart(2, '0')
                                        val secs = (newDuration.toInt() % 60).toString().padStart(2, '0')
                                        chainedHistory.add("$mins:$secs ${candidate.title}")
                                    } ?: run {
                                        chainedHistory[chainedHistory.lastIndex] = "00:${(trackA.durationSec - config.durationSeconds).toInt()} ${candidate.title}"
                                    }
                                    trackB = candidate
                                    liveDualPreview.loadTracks(trackA.uri ?: trackA.localFile, trackB.uri ?: trackB.localFile)
                                    showAddSongSheet = false
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(candidate.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("${candidate.artist} • ${candidate.bpm} BPM • Key ${candidate.musicalKey}", color = Color(0xFF00F5D4), fontSize = 12.sp)
                            }
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = Color(0xFFFF2A85))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddSongSheet = false }) {
                    Text("Close", color = Color(0xFF00F5D4))
                }
            }
        )
    }
}

@Composable
fun TopStudioHeaderPro(
    chainedHistory: List<String>,
    isDecoding: Boolean,
    onImportDeckA: () -> Unit,
    onImportDeckB: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("FLOWCHAIN STUDIO PRO", color = Color(0xFF00F5D4), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("MediaCodec + Dual ExoPlayer + AI 4-Stem Mixer", color = Color(0xFF8A94A6), fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onImportDeckA, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("+ MP3 A", fontSize = 11.sp, color = Color(0xFF00F5D4))
                }
                OutlinedButton(onClick = onImportDeckB, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("+ MP3 B", fontSize = 11.sp, color = Color(0xFFFF2A85))
                }
            }
        }

        if (isDecoding) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFFF2A85))
            Text("Decoding MP3/AAC via Hardware MediaCodec & analyzing BPM...", color = Color(0xFFFF2A85), fontSize = 11.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF141720))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFF00F5D4), modifier = Modifier.size(18.dp))
            Text("CHAIN TIMELINE:", color = Color(0xFF8A94A6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            chainedHistory.forEachIndexed { index, item ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (index == chainedHistory.lastIndex) Color(0x33FF2A85) else Color(0x3300F5D4))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = item,
                        color = if (index == chainedHistory.lastIndex) Color(0xFFFF2A85) else Color(0xFF00F5D4),
                        fontSize = 11.sp,
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
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141720))
            .border(1.dp, Color(0x559B5DE5), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("AI 4-STEM SEPARATION & MASHUP MATRIX", color = Color(0xFF9B5DE5), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                Text("Fade Vocals, Drums, Bass & Melody independently during overlap", color = Color(0xFF8A94A6), fontSize = 11.sp)
            }
            Switch(
                checked = stemConfig.enableStemMashupMode,
                onCheckedChange = { onStemConfigChange(stemConfig.copy(enableStemMashupMode = it)) }
            )
        }

        AnimatedVisibility(visible = stemConfig.enableStemMashupMode) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Deck A Vocals (${(stemConfig.outgoingVocalLevel * 100).toInt()}%)", color = Color(0xFF00F5D4), fontSize = 11.sp)
                        Slider(
                            value = stemConfig.outgoingVocalLevel,
                            onValueChange = { onStemConfigChange(stemConfig.copy(outgoingVocalLevel = it)) }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Deck B Vocals (${(stemConfig.incomingVocalLevel * 100).toInt()}%)", color = Color(0xFFFF2A85), fontSize = 11.sp)
                        Slider(
                            value = stemConfig.incomingVocalLevel,
                            onValueChange = { onStemConfigChange(stemConfig.copy(incomingVocalLevel = it)) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Deck A Drums (${(stemConfig.outgoingDrumsLevel * 100).toInt()}%)", color = Color(0xFF00F5D4), fontSize = 11.sp)
                        Slider(
                            value = stemConfig.outgoingDrumsLevel,
                            onValueChange = { onStemConfigChange(stemConfig.copy(outgoingDrumsLevel = it)) }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Deck B Drums (${(stemConfig.incomingDrumsLevel * 100).toInt()}%)", color = Color(0xFFFF2A85), fontSize = 11.sp)
                        Slider(
                            value = stemConfig.incomingDrumsLevel,
                            onValueChange = { onStemConfigChange(stemConfig.copy(incomingDrumsLevel = it)) }
                        )
                    }
                }
            }
        }
    }
}

// Re-include DualDeckCards, WaveformTransitionVisualizer, TransitionCustomizerPanel, and MergeAndChainActionCard from aboveMainActivity.kt
