package com.floatboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Screen { Home, Settings }

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var pendingCoverFor: String? = null

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            lifecycleScope.launch(Dispatchers.IO) {
                uris.forEach { SoundStore.importFrom(applicationContext, it) }
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
        enableEdgeToEdge()
        SoundStore.init(applicationContext)
        AppSettings.init(applicationContext)
        setContent {
            FloatTheme {
                Surface(Modifier.fillMaxSize(), color = Pal.Bg) {
                    var screen by remember { mutableStateOf(Screen.Home) }
                    BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Home }
                    when (screen) {
                        Screen.Home -> HomeScreen(onSettings = { screen = Screen.Settings })
                        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                    }
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

    // ───────────────────────── Home ─────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen(onSettings: () -> Unit) {
        val sounds = SoundStore.sounds
        var query by remember { mutableStateOf("") }
        var sheetId by remember { mutableStateOf<String?>(null) }
        var renaming by remember { mutableStateOf<Sound?>(null) }
        var deleting by remember { mutableStateOf<Sound?>(null) }
        val visible = sounds.filter { it.name.contains(query, ignoreCase = true) }

        LazyColumn(
            Modifier.fillMaxSize().systemBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_logo, 44.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("FloatBoard", color = Pal.TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Your soundboard, anywhere", color = Pal.TextLo, fontSize = 13.sp)
                    }
                    IconBtn(R.drawable.ic_settings, "Settings", onSettings)
                }
            }

            item {
                val running = OverlayService.running
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp))
                        .background(Pal.Surface).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(if (running) Pal.Accent else Pal.Outline)
                    )
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
            }

            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(
                        R.drawable.ic_add, "Add sounds",
                        { pickAudio.launch(arrayOf("audio/*")) }, Modifier.weight(1f)
                    )
                    ActionTile(
                        if (VoiceRecorder.isRecording) R.drawable.ic_stop else R.drawable.ic_mic,
                        if (VoiceRecorder.isRecording) "Stop" else "Record",
                        { toggleRecord() }, Modifier.weight(1f), highlight = VoiceRecorder.isRecording
                    )
                }
            }

            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("Your sounds", color = Pal.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
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
                    Text(
                        "No sounds yet. Add audio files, or record one with the mic.",
                        color = Pal.TextLo, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }

            items(visible, key = { it.id }) { s ->
                val active = SoundPlayer.nowPlaying?.id == s.id && SoundPlayer.isPlaying
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Surface)
                        .clickable { SoundPlayer.play(s) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SoundThumb(s, 48.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            s.name, color = if (active) Pal.Accent else Pal.TextHi, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(fmt(s.durationMs), color = Pal.TextLo, fontSize = 12.sp)
                    }
                    IconBtn(
                        res = if (active) R.drawable.ic_pause else R.drawable.ic_play,
                        description = if (active) "Pause" else "Play",
                        onClick = { if (active) SoundPlayer.togglePause() else SoundPlayer.play(s) },
                        size = 40.dp
                    )
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
                    ActionRow(R.drawable.ic_play, "Play") { SoundPlayer.play(sheetSound); sheetId = null }
                    ActionRow(R.drawable.ic_edit, "Rename") { renaming = sheetSound; sheetId = null }
                    ActionRow(R.drawable.ic_image, "Change image") {
                        pendingCoverFor = sheetSound.id
                        pickCover.launch(arrayOf("image/*"))
                        sheetId = null
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

    // ───────────────────────── Settings ─────────────────────────

    @Composable
    private fun SettingsScreen(onBack: () -> Unit) {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "2.0.0"
        } catch (e: Exception) {
            "2.0.0"
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(R.drawable.ic_back, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Text("Settings", color = Pal.TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            SectionLabel("Floating window")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Surface).padding(16.dp)) {
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
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Columns", color = Pal.TextHi, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    SegButton("3", AppSettings.columns == 3) { AppSettings.updateColumns(3) }
                    Spacer(Modifier.width(6.dp))
                    SegButton("4", AppSettings.columns == 4) { AppSettings.updateColumns(4) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Overlap sounds", color = Pal.TextHi, fontSize = 15.sp)
                        Text("Off: a new tap replaces the current sound", color = Pal.TextLo, fontSize = 12.sp)
                    }
                    Switch(checked = AppSettings.overlap, onCheckedChange = { AppSettings.updateOverlap(it) })
                }
            }

            SectionLabel("Permissions")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Display over other apps", color = Pal.TextHi, fontSize = 15.sp)
                    Text(if (overlayGranted) "Allowed" else "Not allowed yet", color = Pal.TextLo, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { openOverlaySettings() }) { Text("Open") }
            }

            SectionLabel("About")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Surface).padding(16.dp)) {
                Text("FloatBoard", color = Pal.TextHi, fontSize = 15.sp)
                Text("Version $version", color = Pal.TextLo, fontSize = 12.sp)
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
    private fun SegButton(label: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.size(40.dp, 34.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) Pal.Accent else Pal.SurfaceHi).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(label, color = if (selected) Pal.Bg else Pal.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
    }
}
