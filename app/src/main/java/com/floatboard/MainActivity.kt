package com.floatboard

import android.Manifest
import android.view.HapticFeedbackConstants
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
                        Modifier.fillMaxSize().background(Pal.Bg).graphicsLayer {
                            val p = reveal.value
                            alpha = p
                            scaleX = 0.94f + 0.06f * p
                            scaleY = 0.94f + 0.06f * p
                        }
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                            Crossfade(targetState = tab, animationSpec = tween(180), label = "tab") { t ->
                                when (t) {
                                    Tab.Home -> HomeScreen(goPro = { tab = Tab.Pro })
                                    Tab.Sounds -> SoundsScreen(goPro = { tab = Tab.Pro })
                                    Tab.Pro -> ProScreen()
                                    Tab.Settings -> SettingsScreen()
                                }
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
        val barShape = RoundedCornerShape(30.dp)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(barShape).background(Pal.Surface.copy(alpha = 0.94f))
                .border(0.5.dp, Pal.Outline, barShape).padding(vertical = 6.dp)
        ) {
            Tab.values().forEach { t ->
                val sel = t == selected
                val color by animateColorAsState(if (sel) Pal.Accent else Pal.TextLo, tween(160), label = "navColor")
                val grow by animateFloatAsState(
                    if (sel) 1.2f else 1f, spring(dampingRatio = 0.45f, stiffness = 380f), label = "navGrow"
                )
                val fade by animateFloatAsState(if (sel) 1f else 0.55f, tween(180), label = "navFade")
                val spin by animateFloatAsState(
                    if (sel && t == Tab.Settings) 180f else 0f, tween(650), label = "navSpin"
                )
                Column(
                    Modifier.weight(1f).clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSelect(t)
                    }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AppIcon(
                        t.icon, 28.dp, if (t.tinted) color else Color.Unspecified, t.label,
                        Modifier
                            .pulse(t == Tab.Sounds && SoundPlayer.isPlaying, 0.14f, 380)
                            .graphicsLayer { scaleX = grow; scaleY = grow; alpha = fade; rotationZ = spin }
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    // ───────────────────────── Home ─────────────────────────

    @Composable
    private fun HomeScreen(goPro: () -> Unit) {
        val sounds = SoundStore.sounds
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_logo, 44.dp, modifier = Modifier.pulse(SoundPlayer.isPlaying, 0.06f, 800))
                Column(Modifier.padding(start = 12.dp)) {
                    Text("FloatBoard", color = Pal.TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                    Text("Your soundboard, anywhere", color = Pal.TextLo, fontSize = 13.sp)
                }
            }

            val running = OverlayService.running
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Pal.Accent.copy(alpha = 0.26f), Pal.Accent2.copy(alpha = 0.26f))))
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (running) Pal.Accent else Pal.Outline))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        when {
                            !overlayGranted -> "Permission needed"
                            running -> "Board is live"
                            else -> "Floating board"
                        },
                        color = Pal.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            !overlayGranted -> "Allow display over other apps"
                            running -> "Floating over your apps"
                            else -> "Ready to launch"
                        },
                        color = Pal.TextLo, fontSize = 12.sp
                    )
                }
                Button(onClick = { if (running) stopBoard() else startBoard() }) {
                    Text(
                        when {
                            !overlayGranted -> "Allow"
                            running -> "Close"
                            else -> "Launch"
                        }
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(
                    R.drawable.ic_add, "Add sounds",
                    { pickAudio.launch(arrayOf("audio/*")) }, Modifier.weight(1f)
                )
                ActionTile(
                    if (VoiceRecorder.isRecording) R.drawable.ic_stop_flat else R.drawable.ic_mic,
                    if (VoiceRecorder.isRecording) "Stop" else "Record",
                    { toggleRecord() }, Modifier.weight(1f), highlight = VoiceRecorder.isRecording
                )
            }

            if (!Entitlement.isPro) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Surface).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Free plan", color = Pal.TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${Entitlement.usedSlots()} / ${Entitlement.FREE_SOUND_LIMIT} sounds",
                            color = Pal.TextLo, fontSize = 12.sp
                        )
                    }
                    OutlinedButton(onClick = goPro) { Text("Go Pro") }
                }
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(R.drawable.ic_expand, 22.dp, Pal.TextLo)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Resize the floating window", color = Pal.TextHi, fontSize = 15.sp)
                    Text(
                        "Hold any edge of the window, then drag. Hold a corner to resize both ways.",
                        color = Pal.TextLo, fontSize = 12.sp
                    )
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
        val visible = sounds.filter { it.name.contains(query, ignoreCase = true) }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_wave, 30.dp, modifier = Modifier.pulse(SoundPlayer.isPlaying, 0.14f, 380))
                        Spacer(Modifier.width(10.dp))
                        Text("Sounds", color = Pal.TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
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
                }
            }

            if (sounds.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppIcon(
                            R.drawable.ic_speaker, 72.dp,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).pulse(true, 0.05f, 1400)
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
                    Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Surface)
                        .clickable { SoundPlayer.play(s) }.padding(10.dp),
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
                    Spacer(Modifier.width(6.dp))
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
            Text("FloatBoard Pro", color = Pal.TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pal.Surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(R.drawable.ic_star, 26.dp, if (pro) Pal.Accent else Pal.TextLo)
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pal.Surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("What Pro gives you", color = Pal.TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                ProRow("Unlimited sounds (free: ${Entitlement.FREE_SOUND_LIMIT})")
                ProRow("Your own image on every sound")
                ProRow("Everything we add to Pro later")
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
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pal.Surface).padding(16.dp),
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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "4.0.0"
        } catch (e: Exception) {
            "4.0.0"
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_tune, 32.dp)
                Spacer(Modifier.width(10.dp))
                Text("Settings", color = Pal.TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
            }

            SectionLabel("Floating window")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Window opacity", color = Pal.TextHi, fontSize = 15.sp)
                        Text("Background only, buttons stay solid", color = Pal.TextLo, fontSize = 12.sp)
                    }
                    Text("${(AppSettings.opacity * 100).roundToInt()}%", color = Pal.TextLo, fontSize = 13.sp)
                }
                Slider(
                    value = AppSettings.opacity,
                    onValueChange = { AppSettings.updateOpacity(it) },
                    valueRange = 0.4f..1f
                )

                Text("Tile size", color = Pal.TextHi, fontSize = 15.sp)
                SegmentedRow(listOf("Small", "Medium", "Large"), AppSettings.tile) { AppSettings.updateTile(it) }

                Spacer(Modifier.height(6.dp))
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

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Window size", color = Pal.TextHi, fontSize = 15.sp)
                        Text(
                            "${AppSettings.floatWDp.roundToInt()} \u00D7 ${AppSettings.floatHDp.roundToInt()}. Hold an edge of the window to change it.",
                            color = Pal.TextLo, fontSize = 12.sp
                        )
                    }
                    OutlinedButton(onClick = { AppSettings.resetFloatSize() }) { Text("Reset") }
                }
            }

            SectionLabel("Permissions")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Display over other apps", color = Pal.TextHi, fontSize = 15.sp)
                    Text(if (overlayGranted) "Allowed" else "Not allowed yet", color = Pal.TextLo, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { openOverlaySettings() }) { Text("Open") }
            }

            SectionLabel("About")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Surface).padding(16.dp)) {
                Text("FloatBoard", color = Pal.TextHi, fontSize = 15.sp)
                Text("Version $version", color = Pal.TextLo, fontSize = 12.sp)
            }

            var crash by remember { mutableStateOf(CrashLog.read()) }
            crash?.let { report ->
                SectionLabel("Last crash")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        report, color = Pal.TextLo, fontSize = 10.sp, maxLines = 14,
                        overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val cm = getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("FloatBoard crash", report))
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
            text, color = Pal.TextLo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp)
        )
    }

    @Composable
    private fun SegmentedRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, label ->
                val sel = i == selected
                val bg by animateColorAsState(if (sel) Pal.Accent else Pal.SurfaceHi, tween(160), label = "segBg")
                Box(
                    Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp)).background(bg)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, color = if (sel) Pal.Bg else Pal.TextHi,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** Launch animation: logo springs in, a ring ripples out, the name rises, then the app scales up from behind. */
@Composable
private fun SplashOverlay(reveal: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, onDone: () -> Unit) {
    val logoScale = remember { Animatable(0.55f) }
    val logoAlpha = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(320)) }
        launch { logoScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 200f)) }
        launch { delay(180); ring.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
        launch { delay(420); title.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        delay(1350)
        reveal.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        onDone()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).graphicsLayer { alpha = 1f - reveal.value },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(200.dp)
                .graphicsLayer {
                    val sc = 0.5f + ring.value * 1.3f
                    scaleX = sc
                    scaleY = sc
                    alpha = (1f - ring.value) * 0.55f
                }
                .border(2.dp, Pal.Accent, CircleShape)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(
                R.drawable.ic_logo, 120.dp,
                modifier = Modifier.graphicsLayer {
                    val sc = logoScale.value * (1f + reveal.value * 0.4f)
                    scaleX = sc
                    scaleY = sc
                    alpha = logoAlpha.value
                }
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "FloatBoard", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 28f }
            )
            Text(
                "Your soundboard, anywhere", color = Pal.TextLo, fontSize = 14.sp,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 28f }
            )
        }
    }
}
