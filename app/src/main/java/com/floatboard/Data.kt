package com.floatboard

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Sound(val id: String, val name: String, val file: String)

/** Process-wide sound library. Shared by MainActivity and OverlayService (same process). */
object SoundStore {
    private const val TAG = "SoundStore"

    val sounds = mutableStateListOf<Sound>()

    private lateinit var dir: File
    private lateinit var index: File
    private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        dir = File(context.filesDir, "sounds").apply { mkdirs() }
        index = File(context.filesDir, "sounds.json")
        load()
        ready = true
    }

    fun fileOf(sound: Sound): File = File(dir, sound.file)

    fun newRecordingFile(): File = File(dir, "${UUID.randomUUID()}.m4a")

    fun importFrom(context: Context, uri: Uri): Sound? = try {
        val resolver = context.contentResolver
        var display = "sound"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) display = c.getString(0) ?: display
        }
        val id = UUID.randomUUID().toString()
        val ext = display.substringAfterLast('.', "audio").take(5)
        val fileName = "$id.$ext"
        val input = resolver.openInputStream(uri) ?: error("cannot open $uri")
        input.use { src -> File(dir, fileName).outputStream().use { dst -> src.copyTo(dst) } }
        val sound = Sound(id, display.substringBeforeLast('.').take(40), fileName)
        sounds.add(sound)
        save()
        sound
    } catch (e: Exception) {
        Log.e(TAG, "import failed for $uri", e)
        null
    }

    fun addRecording(file: File) {
        val sound = Sound(file.nameWithoutExtension, "Recording ${sounds.size + 1}", file.name)
        sounds.add(sound)
        save()
    }

    fun rename(id: String, newName: String) {
        val i = sounds.indexOfFirst { it.id == id }
        if (i >= 0) {
            sounds[i] = sounds[i].copy(name = newName)
            save()
        }
    }

    fun remove(id: String) {
        val sound = sounds.firstOrNull { it.id == id } ?: return
        sounds.remove(sound)
        fileOf(sound).delete()
        save()
    }

    private fun load() {
        if (!index.exists()) return
        try {
            val arr = JSONArray(index.readText())
            val loaded = (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { Sound(it.getString("id"), it.getString("name"), it.getString("file")) }
                .filter { File(dir, it.file).exists() }
            sounds.clear()
            sounds.addAll(loaded)
        } catch (e: Exception) {
            Log.e(TAG, "load failed, starting empty", e)
        }
    }

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            sounds.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("file", it.file)) }
            val tmp = File(index.parentFile, "sounds.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(index)) { index.writeText(arr.toString()); tmp.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }
}
