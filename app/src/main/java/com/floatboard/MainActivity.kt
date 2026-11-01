package com.floatboard

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            lifecycleScope.launch(Dispatchers.IO) {
                uris.forEach { SoundStore.importFrom(applicationContext, it) }
            }
        }

    // Result is ignored on purpose: the board still launches without mic/notification permission.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { launchService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SoundStore.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { HomeScreen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
    }

    private fun startBoard() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
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

    @Composable
    private fun HomeScreen() {
        val sounds = SoundStore.sounds
        var renaming by remember { mutableStateOf<Sound?>(null) }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            Text("FloatBoard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("A floating soundboard that sits on top of any app.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            if (!overlayGranted) {
                Text(
                    "Needs the \"Display over other apps\" permission before the board can float.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (OverlayService.running) stopBoard() else startBoard() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            !overlayGranted -> "Grant overlay permission"
                            OverlayService.running -> "Close floating board"
                            else -> "Launch floating board"
                        }
                    )
                }
                OutlinedButton(
                    onClick = { pickAudio.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add sounds from storage") }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            if (sounds.isEmpty()) {
                Text(
                    "No sounds yet. Add some audio files or record from the floating mic.",
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(sounds, key = { it.id }) { sound ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            sound.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { SoundPlayer.play(sound) }) { Text("Play") }
                        TextButton(onClick = { renaming = sound }) { Text("Rename") }
                        TextButton(onClick = { SoundStore.remove(sound.id) }) { Text("Delete") }
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
    }
}
