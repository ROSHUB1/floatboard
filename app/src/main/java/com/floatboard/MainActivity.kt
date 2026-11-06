package com.floatboard

import android.Manifest
import android.view.HapticFeedbackConstants
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private enum class Tab(val label: String, val icon: Int, val tinted: Boolean) {
    Home("Home", R.drawable.ic_grid, false),
    Sounds("Sounds", R.drawable.ic_wave, false),
    Pro("Pro", R.drawable.ic_star, true),
    Settings("Settings", R.drawable.ic_settings, false)
}

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var pendingCoverFor: String? = null
    private var packStatus by mutableStateOf<String?>(null)

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    var rejected = 0
                    uris.forEach { if (SoundStore.importFrom(applicationContext, it) == null) rejected++ }
                    if (rejected > 0) {
                        val msg = if (!Entitlement.isPro && Entitlement.usedSlots() >= Entitlement.FREE_SOUND_LIMIT)
                            "Free plan holds ${Entitlement.FREE_SOUND_LIMIT} sounds. Go Pro for unlimited."
                        else "$rejected file(s) couldn't be added"
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

    private val importPackLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) runImportPack(uri)
        }

    private val exportPackLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            if (uri != null) runExportPack(uri)
        }

    private val pickCover =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val id = pendingCoverFor
            pendingCoverFor = null
            if (uri != null && id != null) {
                lifecycleScope.launch(Dispatchers.IO) { SoundStore.setCover(applicationContext, id, uri) }
            }
        }

    // Result is ignored on purpose: the board still launches without mic/notification permission.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { launchService() }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) VoiceRecorder.toggle(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        SoundStore.init(applicationContext)
        AppSettings.init(applicationContext)
        Entitlement.init(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) { SoundStore.installStarterPack(applicationContext) }
        setContent {
            FloatTheme {
                Surface(Modifier.fillMaxSize(), color = Pal.Bg) {
                    var tab by remember { mutableStateOf(Tab.Home) }
                    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }
                    var splashDone by rememberSaveable { mutableStateOf(false) }
                    val reveal = remember { Animatable(if (splashDone) 1f else 0f) }
                    Column(
                        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111115), Pal.Bg))).graphicsLayer {
                            val p = reveal.value
                            alpha = p
                            scaleX = 0.97f + 0.03f * p
                            scaleY = 0.97f + 0.03f * p
                        }
                    ) {
                        // Switched directly (no Crossfade): Crossfade briefly composes BOTH the
                        // outgoing and incoming screen at once, and overlapping Home's motion
                        // background with another full screen was what caused the stutter on tab
                        // switches. A direct switch removes that overlap entirely.
                        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                            when (tab) {
                                Tab.Home -> HomeScreen(
                                    goPro = { tab = Tab.Pro },
                                    goSounds = { tab = Tab.Sounds },
                                    goSettings = { tab = Tab.Settings }
                                )
                                Tab.Sounds -> SoundsScreen(goPro = { tab = Tab.Pro })
                                Tab.Pro -> ProScreen()
                                Tab.Settings -> SettingsScreen()
                            }
                        }
                        NavBar(tab) { tab = it }
                    }
                    if (!splashDone) SplashOverlay(reveal) { splashDone = true }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
    }

    override fun onDestroy() {
        if (isFinishing && VoiceRecorder.isRecording && !OverlayService.running) VoiceRecorder.stop(this)
        super.onDestroy()
    }

    private fun runImportPack(uri: Uri) {
        packStatus = "Importing pack..."
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                SoundStore.importPack(applicationContext, uri) { n -> packStatus = "Importing pack... $n added" }
            } catch (e: Exception) {
                Log.e("MainActivity", "pack import failed", e)
                null
            }
            withContext(Dispatchers.Main) {
                packStatus = null
                val msg = when {
                    result == null -> "Couldn't read that zip"
                    result.tooLarge -> "That zip is too large (max 400 MB)"
                    result.limitHit -> "Added ${result.added}. Free plan is full, go Pro for unlimited."
                    result.added == 0 -> "No playable audio found in that zip"
                    result.failed > 0 -> "Added ${result.added}, skipped ${result.failed}"
                    else -> "Added ${result.added} sounds"
                }
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runExportPack(uri: Uri) {
        packStatus = "Exporting pack..."
        lifecycleScope.launch(Dispatchers.IO) {
            val count = try {
                SoundStore.exportPack(applicationContext, uri)
            } catch (e: Exception) {
                Log.e("MainActivity", "pack export failed", e)
                -1
            }
            withContext(Dispatchers.Main) {
                packStatus = null
                val msg = if (count < 0) "Export failed" else "Exported $count sounds"
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Sends the sound file to any app that accepts audio (Messenger, Discord, WhatsApp, Telegram...). */
    private fun shareSound(sound: Sound) {
        val file = SoundStore.fileOf(sound)
        if (!file.exists()) {
            Toast.makeText(this, "Sound file is missing", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            else -> "audio/*"
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share ${sound.name}"))
        } catch (e: Exception) {
            Log.e("MainActivity", "share failed", e)
            Toast.makeText(this, "Couldn't share this sound", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun startBoard() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun launchService() {
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
    }

    private fun stopBoard() {
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun toggleRecord() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || VoiceRecorder.isRecording) VoiceRecorder.toggle(this)
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ───────────────────────── Navigation bar ─────────────────────────

    @Composable
    private fun NavBar(selected: Tab, onSelect: (Tab) -> Unit) {
        val view = LocalView.current
        Column(Modifier.fillMaxWidth().background(Pal.Surface)) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Pal.Outline))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 6.dp, bottom = 4.dp)) {
                Tab.values().forEach { t ->
                    val sel = t == selected
                    val color by animateColorAsState(if (sel) Pal.Accent else Pal.TextLo, tween(160), label = "navColor")
                    val grow by animateFloatAsState(
                        if (sel) 1.08f else 1f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "navGrow"
                    )
                    val fade by animateFloatAsState(if (sel) 1f else 0.6f, tween(180), label = "navFade")
                    Column(
                        Modifier.weight(1f).clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSelect(t)
                        }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppIcon(
                            t.icon, 26.dp, if (t.tinted) color else Color.Unspecified, t.label,
                            Modifier
                                .pulse(t == Tab.Sounds && SoundPlayer.isPlaying, 0.1f, 420)
                                .graphicsLayer { scaleX = grow; scaleY = grow; alpha = fade }
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(t.label, color = color, style = Ty.Caption.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
    }

    // ───────────────────────── Home ─────────────────────────

    @Composable
    private fun HomeScreen(goPro: () -> Unit, goSounds: () -> Unit, goSettings: () -> Unit) {
        val sounds = SoundStore.sounds
        Box(Modifier.fillMaxSize()) {
        MotionBackground()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Header: tight, no wasted vertical space ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_logo, 34.dp, modifier = Modifier.pulse(SoundPlayer.isPlaying, 0.05f, 800))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("VYBE", color = Pal.TextHi, style = Ty.Title)
                    Text("Your soundboard, anywhere", color = Pal.TextLo, style = Ty.Caption)
                }
            }

            // ── Quick Access: compact shortcuts, not full-size actions ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionLabel("Quick Access") }
                    Text(
                        "See all", color = Pal.Accent, style = Ty.Callout,
                        modifier = Modifier.clickable { goSounds() }.padding(4.dp)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAccessTile(R.drawable.ic_add, "Add", { pickAudio.launch(arrayOf("audio/*")) }, Modifier.weight(1f))
                    QuickAccessTile(R.drawable.ic_folder, "Library", goSounds, Modifier.weight(1f))
                    QuickAccessTile(R.drawable.ic_settings, "Settings", goSettings, Modifier.weight(1f))
                    QuickAccessTile(
                        R.drawable.ic_star, "Pro", goPro, Modifier.weight(1f),
                        tint = Color(0xFFE8B64A), container = Color(0x29E8B64A)
                    )
                }
            }

            // ── Floating board: the primary feature, art-directed rather than text-beside-mascot ──
            val running = OverlayService.running
            val heroShape = RoundedCornerShape(20.dp)
            val heroLabel = when {
                !overlayGranted -> "Allow"
                running -> "Close"
                else -> "Launch"
            }
            val onHero = { if (running) stopBoard() else startBoard() }
            Box(Modifier.fillMaxWidth().elevatedSurface(heroShape)) {
                // The mascot bleeds past the content padding on the bottom-right, clipped by the
                // card's own rounded corner, so it reads as part of the composition instead of a
                // sticker placed beside the text.
                Mascot(
                    152.dp,
                    Modifier.align(Alignment.BottomEnd).offset(x = 28.dp, y = 28.dp)
                        .pulse(SoundPlayer.isPlaying, 0.04f, 600)
                )
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.horizontalGradient(listOf(Pal.Elevated, Pal.Elevated, Pal.Elevated.copy(alpha = 0f)))
                    )
                ) {
                    Column(Modifier.fillMaxWidth(0.72f).padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) Pal.Accent else Pal.Outline))
                            Spacer(Modifier.width(7.dp))
                            Text(
                                when {
                                    !overlayGranted -> "Permission needed"
                                    running -> "Board is live"
                                    else -> "Floating board"
                                },
                                color = Pal.TextHi, style = Ty.Headline
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            when {
                                !overlayGranted -> "Allow display over other apps"
                                running -> "Floating over your apps"
                                else -> "Tap to open your soundboard"
                            },
                            color = Pal.TextLo, style = Ty.Callout
                        )
                        Spacer(Modifier.height(14.dp))
                        if (running) OutlinedButton(onClick = onHero) { Text(heroLabel) }
                        else Button(onClick = onHero) { Text(heroLabel) }
                    }
                }
            }

            // ── Recently used: a proper empty state instead of a stray line of text ──
            val recentSounds = SoundStore.recent.mapNotNull { id -> sounds.firstOrNull { it.id == id } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionLabel("Recently used") }
                    if (recentSounds.isNotEmpty()) {
                        Text(
                            "See all", color = Pal.Accent, style = Ty.Callout,
                            modifier = Modifier.clickable { goSounds() }.padding(4.dp)
                        )
                    }
                }
                if (recentSounds.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().premiumSurface().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MascotStill(36.dp, alpha = 0.55f)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Sounds you play show up here for quick replay.",
                            color = Pal.TextLo, style = Ty.Callout
                        )
                    }
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        items(recentSounds, key = { it.id }) { s ->
                            val active = SoundPlayer.isBusy && SoundPlayer.nowPlaying?.id == s.id
                            Column(
                                Modifier.width(72.dp).bouncyClick { SoundPlayer.play(s) },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                SoundThumb(s, 64.dp, 16.dp, progress = if (active) ({ SoundPlayer.progress }) else null)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    s.name, color = if (active) Pal.Accent else Pal.TextHi, style = Ty.Caption,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // ── Quick actions: the two primary actions, a different shape than the Quick Access grid ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Quick actions")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton(
                        R.drawable.ic_add, "Add sounds",
                        { pickAudio.launch(arrayOf("audio/*")) }, Modifier.weight(1f)
                    )
                    PrimaryActionButton(
                        if (VoiceRecorder.isRecording) R.drawable.ic_stop_flat else R.drawable.ic_mic,
                        if (VoiceRecorder.isRecording) "Stop" else "Record",
                        { toggleRecord() }, Modifier.weight(1f),
                        filled = false, danger = VoiceRecorder.isRecording
                    )
                }
            }

            // ── Library management: compact, organized, clearly secondary ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Library management")
                Column(Modifier.fillMaxWidth().premiumSurface()) {
                    CompactRow(R.drawable.ic_import, "Import pack", {
                        importPackLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                        )
                    })
                    HorizontalDivider(color = Pal.Outline, thickness = 0.5.dp, modifier = Modifier.padding(start = 58.dp))
                    CompactRow(R.drawable.ic_export, "Export pack", {
                        if (sounds.any { !it.builtin }) exportPackLauncher.launch("VYBE-pack.zip")
                        else Toast.makeText(this@MainActivity, "Add some sounds first", Toast.LENGTH_SHORT).show()
                    })
                }
                packStatus?.let { Text(it, color = Pal.Accent, style = Ty.Callout) }
            }

            // ── Subscription status: visible, not dominant, with a progress indicator ──
            if (!Entitlement.isPro) {
                Column(
                    Modifier.fillMaxWidth().premiumSurface().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Free plan", color = Pal.TextHi, style = Ty.Body.copy(fontWeight = FontWeight.SemiBold))
                            Text(
                                "${Entitlement.usedSlots()} / ${Entitlement.FREE_SOUND_LIMIT} sounds",
                                color = Pal.TextLo, style = Ty.Caption
                            )
                        }
                        OutlinedButton(onClick = goPro) { Text("Go Pro") }
                    }
                    ProgressTrack(Entitlement.usedSlots().toFloat() / Entitlement.FREE_SOUND_LIMIT)
                }
            }

            // ── Floating window tip: collapsed by default so it doesn't eat vertical space ──
            var tipOpen by remember { mutableStateOf(false) }
            val chevronRotation by animateFloatAsState(if (tipOpen) 180f else 0f, tween(200), label = "tipChevron")
            Column(Modifier.fillMaxWidth().premiumSurface()) {
                Row(
                    Modifier.fillMaxWidth().clickable { tipOpen = !tipOpen }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconChip(R.drawable.ic_expand, size = 32.dp)
                    Text(
                        "Resize the floating window", color = Pal.TextHi, style = Ty.Body,
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                    )
                    AppIcon(
                        R.drawable.ic_expand, 16.dp, Pal.TextLo,
                        modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
                    )
                }
                AnimatedVisibility(visible = tipOpen) {
                    Text(
                        "Hold any edge of the window, then drag. Hold a corner to resize both ways.",
                        color = Pal.TextLo, style = Ty.Callout,
                        modifier = Modifier.padding(start = 58.dp, end = 14.dp, bottom = 14.dp)
                    )
                }
            }
        }
        }
    }

    // ───────────────────────── Sounds ─────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SoundsScreen(goPro: () -> Unit) {
        val sounds = SoundStore.sounds
        var query by remember { mutableStateOf("") }
        var sheetId by remember { mutableStateOf<String?>(null) }
        var renaming by remember { mutableStateOf<Sound?>(null) }
        var deleting by remember { mutableStateOf<Sound?>(null) }
        var favsOnly by remember { mutableStateOf(false) }
        val visible = sounds.filter { it.name.contains(query, ignoreCase = true) && (!favsOnly || it.fav) }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_wave, 30.dp, modifier = Modifier.pulse(SoundPlayer.isPlaying, 0.14f, 380))
                        Spacer(Modifier.width(10.dp))
                        Text("Sounds", color = Pal.TextHi, style = Ty.LargeTitle)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search your sounds") },
                        leadingIcon = { AppIcon(R.drawable.ic_search, 20.dp, Pal.TextLo) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    SegmentedRow(listOf("All", "Favorites"), if (favsOnly) 1 else 0) { favsOnly = it == 1 }
                    packStatus?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Pal.Accent, fontSize = 13.sp)
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            if (sounds.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppIcon(
                            R.drawable.ic_speaker, 72.dp,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).pulse(true, 0.05f, 1400)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No sounds yet. Add audio files from Home, or record one with the mic.",
                            color = Pal.TextLo, fontSize = 13.sp
                        )
                    }
                }
            }

            items(visible, key = { it.id }) { s ->
                val active = SoundPlayer.isBusy && SoundPlayer.nowPlaying?.id == s.id
                val playingNow = active && SoundPlayer.isPlaying
                Row(
                    Modifier.animateItem().fillMaxWidth()
                        .drawBehind {
                            val y = this.size.height - 0.5.dp.toPx()
                            drawLine(Pal.Outline, Offset(64.dp.toPx(), y), Offset(this.size.width, y), 1.dp.toPx())
                        }
                        .clickable { SoundPlayer.play(s) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SoundThumb(s, 48.dp, progress = if (active) ({ SoundPlayer.progress }) else null)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            s.name, color = if (active) Pal.Accent else Pal.TextHi, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(fmt(s.durationMs), color = Pal.TextLo, fontSize = 12.sp)
                    }
                    PlayPauseBtn(playingNow, { if (active) SoundPlayer.togglePause() else SoundPlayer.play(s) }, size = 46.dp)
                    IconBtn(
                        R.drawable.ic_star, if (s.fav) "Remove favorite" else "Favorite",
                        { SoundStore.toggleFav(s.id) }, size = 40.dp, container = Pal.Surface,
                        tint = if (s.fav) Pal.Accent else Pal.TextLo
                    )
                    Spacer(Modifier.width(2.dp))
                    IconBtn(R.drawable.ic_more, "More", { sheetId = s.id }, size = 40.dp, container = Pal.Surface)
                }
            }
        }

        val sheetSound = sounds.firstOrNull { it.id == sheetId }
        if (sheetSound != null) {
            ModalBottomSheet(onDismissRequest = { sheetId = null }, containerColor = Pal.Surface) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SoundThumb(sheetSound, 96.dp, 18.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(sheetSound.name, color = Pal.TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(fmt(sheetSound.durationMs), color = Pal.TextLo, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    ActionRow(R.drawable.ic_play, "Play", keepColors = true) { SoundPlayer.play(sheetSound); sheetId = null }
                    ActionRow(R.drawable.ic_export, "Share") { shareSound(sheetSound); sheetId = null }
                    ActionRow(R.drawable.ic_edit, "Rename") { renaming = sheetSound; sheetId = null }
                    ActionRow(R.drawable.ic_image, if (Entitlement.isPro) "Change image" else "Change image (Pro)") {
                        sheetId = null
                        if (Entitlement.isPro) {
                            pendingCoverFor = sheetSound.id
                            pickCover.launch(arrayOf("image/*"))
                        } else {
                            Toast.makeText(this@MainActivity, "Custom images are a Pro feature", Toast.LENGTH_SHORT).show()
                            goPro()
                        }
                    }
                    if (sheetSound.cover != null) {
                        ActionRow(R.drawable.ic_close, "Remove image") {
                            SoundStore.clearCover(sheetSound.id)
                            sheetId = null
                        }
                    }
                    ActionRow(R.drawable.ic_delete, "Delete", danger = true) {
                        deleting = sheetSound
                        sheetId = null
                    }
                }
            }
        }

        renaming?.let { target ->
            var text by remember(target.id) { mutableStateOf(target.name) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename sound") },
                text = {
                    OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true)
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (text.isNotBlank()) SoundStore.rename(target.id, text.trim())
                        renaming = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
            )
        }

        deleting?.let { target ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete sound?") },
                text = { Text("\"${target.name}\" will be removed from your board.") },
                confirmButton = {
                    TextButton(onClick = {
                        if (SoundPlayer.nowPlaying?.id == target.id) SoundPlayer.stopAll()
                        SoundStore.remove(target.id)
                        deleting = null
                    }) { Text("Delete", color = Pal.Danger) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
            )
        }
    }

    // ───────────────────────── Pro ─────────────────────────

    @Composable
    private fun ProScreen() {
        var key by remember { mutableStateOf("") }
        var message by remember { mutableStateOf<String?>(null) }
        val pro = Entitlement.isPro

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("VYBE Pro", color = Pal.TextHi, style = Ty.LargeTitle)

            Row(
                Modifier.fillMaxWidth().premiumSurface().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(R.drawable.ic_gift, 44.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        if (pro) "Pro is active" else "You're on the free plan",
                        color = Pal.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            !pro -> "${Entitlement.usedSlots()} / ${Entitlement.FREE_SOUND_LIMIT} sounds used"
                            Entitlement.expiresAtSec == 0L -> "Lifetime license"
                            else -> "Active until " + DateFormat.getDateInstance(DateFormat.MEDIUM)
                                .format(Date(Entitlement.expiresAtSec * 1000))
                        },
                        color = Pal.TextLo, fontSize = 12.sp
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().premiumSurface().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("What Pro gives you", color = Pal.TextHi, style = Ty.Headline)
                        ProRow("Unlimited sounds (free: ${Entitlement.FREE_SOUND_LIMIT})")
                        ProRow("Your own image on every sound")
                        ProRow("Everything we add to Pro later")
                    }
                    Mascot(96.dp)
                }
            }

            if (pro) {
                OutlinedButton(
                    onClick = { Entitlement.deactivate(); message = null },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Remove license from this phone") }
            } else {
                if (Entitlement.PURCHASE_URL.isNotEmpty()) {
                    Button(
                        onClick = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Entitlement.PURCHASE_URL)))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Get a license key") }
                } else {
                    Text(
                        "Get a license key from the developer, then paste it below.",
                        color = Pal.TextLo, fontSize = 13.sp
                    )
                }
                Column(
                    Modifier.fillMaxWidth().premiumSurface().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Activate", color = Pal.TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it; message = null },
                        placeholder = { Text("Paste your license key") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                    Button(
                        onClick = { message = Entitlement.activate(key) ?: "Pro unlocked. Thank you!" },
                        enabled = key.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Activate") }
                    message?.let {
                        Text(
                            it,
                            color = if (Entitlement.isPro) Pal.Accent else Pal.Danger,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ProRow(text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_check, 20.dp, Pal.Accent)
            Spacer(Modifier.width(12.dp))
            Text(text, color = Pal.TextHi, fontSize = 14.sp)
        }
    }

    // ───────────────────────── Settings ─────────────────────────

    @Composable
    private fun SettingsScreen() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "7.2.0"
        } catch (e: Exception) {
            "7.2.0"
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_tune, 32.dp)
                Spacer(Modifier.width(10.dp))
                Text("Settings", color = Pal.TextHi, style = Ty.LargeTitle)
            }

            SectionLabel("Appearance")
            Column(
                Modifier.fillMaxWidth().premiumSurface().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Motion background", color = Pal.TextHi, style = Ty.Body)
                SegmentedRow(listOf("Off", "On"), if (AppSettings.motionBg) 1 else 0) { AppSettings.updateMotionBg(it == 1) }
                Text("The moving background behind Home. Turning it off saves battery.", color = Pal.TextLo, style = Ty.Callout)
            }

            SectionLabel("Floating window")
            Column(
                Modifier.fillMaxWidth().premiumSurface().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Window opacity", color = Pal.TextHi, fontSize = 15.sp)
                        Text("Background only, buttons stay solid", color = Pal.TextLo, fontSize = 12.sp)
                    }
                    Text("${(AppSettings.opacity * 100).roundToInt()}%", color = Pal.Accent, style = Ty.Headline)
                }
                PremiumSlider(AppSettings.opacity, { AppSettings.updateOpacity(it) }, 0.4f..1f)

                Text("Tile size", color = Pal.TextHi, fontSize = 15.sp)
                SegmentedRow(listOf("Small", "Medium", "Large"), AppSettings.tile) { AppSettings.updateTile(it) }

                HorizontalDivider(color = Pal.Outline, modifier = Modifier.padding(vertical = 8.dp))
                Text("When you tap a sound while another plays", color = Pal.TextHi, fontSize = 15.sp)
                SegmentedRow(listOf("Queue", "Replace", "Overlap"), AppSettings.playMode) { AppSettings.updatePlayMode(it) }
                Text(
                    when (AppSettings.playMode) {
                        PlayMode.QUEUE -> "Taps line up next to the mic and play one after another."
                        PlayMode.REPLACE -> "A new tap stops the current sound."
                        else -> "Sounds play on top of each other (up to 8)."
                    },
                    color = Pal.TextLo, fontSize = 12.sp
                )

                HorizontalDivider(color = Pal.Outline, modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Window size", color = Pal.TextHi, fontSize = 15.sp)
                        Text(
                            "${AppSettings.floatWDp.roundToInt()} \u00D7 ${AppSettings.floatHDp.roundToInt()} \u00B7 hold an edge to resize",
                            color = Pal.TextLo, fontSize = 12.sp
                        )
                    }
                    OutlinedButton(onClick = { AppSettings.resetFloatSize() }) { Text("Reset") }
                }
            }

            SectionLabel("Playback")
            Column(
                Modifier.fillMaxWidth().premiumSurface().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Call boost", color = Pal.TextHi, style = Ty.Body)
                SegmentedRow(listOf("Off", "On"), if (AppSettings.callBoost) 1 else 0) { AppSettings.updateCallBoost(it == 1) }
                Text(
                    "Plays sounds louder so a call or game mic picks them up. Works best on speakerphone with media volume up. Very loud clips can distort.",
                    color = Pal.TextLo, style = Ty.Callout
                )
            }

            SectionLabel("Permissions")
            Row(
                Modifier.fillMaxWidth().premiumSurface().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Display over other apps", color = Pal.TextHi, fontSize = 15.sp)
                    Text(if (overlayGranted) "Allowed" else "Not allowed yet", color = Pal.TextLo, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { openOverlaySettings() }) { Text("Open") }
            }

            SectionLabel("About")
            Column(Modifier.fillMaxWidth().premiumSurface().padding(16.dp)) {
                Text("VYBE", color = Pal.TextHi, fontSize = 15.sp)
                Text("Version $version", color = Pal.TextLo, fontSize = 12.sp)
            }

            var crash by remember { mutableStateOf(CrashLog.read()) }
            crash?.let { report ->
                SectionLabel("Last crash")
                Column(
                    Modifier.fillMaxWidth().premiumSurface().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        report, color = Pal.TextLo, fontSize = 10.sp, maxLines = 14,
                        overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val cm = getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("VYBE crash", report))
                            Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy report") }
                        OutlinedButton(onClick = { CrashLog.clear(); crash = null }) { Text("Clear") }
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text.uppercase(), color = Pal.TextLo, style = Ty.Overline,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
        )
    }

    @Composable
    private fun SegmentedRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.SurfaceHi).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEachIndexed { i, label ->
                val sel = i == selected
                val bg by animateColorAsState(if (sel) Pal.Outline else Color.Transparent, tween(160), label = "segBg")
                val fg by animateColorAsState(if (sel) Pal.TextHi else Pal.TextLo, tween(160), label = "segFg")
                Box(
                    Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) { Text(label, color = fg, style = Ty.Callout.copy(fontWeight = FontWeight.Medium)) }
            }
        }
    }
}

/** Launch animation: the logo eases in, the name follows, then the app settles in from behind. */
@Composable
private fun SplashOverlay(reveal: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, onDone: () -> Unit) {
    val logoScale = remember { Animatable(0.82f) }
    val logoAlpha = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(300)) }
        launch { logoScale.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 260f)) }
        launch { delay(260); title.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        delay(1000)
        reveal.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        onDone()
    }

    Box(
        Modifier.fillMaxSize().background(Pal.Bg).graphicsLayer { alpha = 1f - reveal.value },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(
                R.drawable.ic_logo, 104.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                    alpha = logoAlpha.value
                }
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "VYBE", color = Pal.TextHi, style = Ty.LargeTitle,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 16f }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Your soundboard, anywhere", color = Pal.TextLo, style = Ty.Body,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 16f }
            )
        }
    }
}
