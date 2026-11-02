package com.floatboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Sound(
    val id: String,
    val name: String,
    val file: String,
    val cover: String? = null,
    val durationMs: Long = 0L,
    val builtin: Boolean = false
)

/** Process-wide sound library. Shared by MainActivity and OverlayService (same process). */
object SoundStore {
    private const val TAG = "SoundStore"
    private const val COVER_SIZE = 256

    val sounds = mutableStateListOf<Sound>()

    private lateinit var dir: File
    private lateinit var coversDir: File
    private lateinit var index: File
    private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        dir = File(context.filesDir, "sounds").apply { mkdirs() }
        coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        index = File(context.filesDir, "sounds.json")
        load()
        ready = true
    }

    fun fileOf(sound: Sound): File = File(dir, sound.file)
    fun coverFile(name: String): File = File(coversDir, name)
    fun newRecordingFile(): File = File(dir, "${UUID.randomUUID()}.m4a")

    private fun durationOf(file: File): Long = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        0L
    }

    fun importFrom(context: Context, uri: Uri): Sound? {
        if (!Entitlement.canAddSound()) return null
        return importUnchecked(context, uri)
    }

    private fun importUnchecked(context: Context, uri: Uri): Sound? = try {
        val resolver = context.contentResolver
        var display = "sound"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) display = c.getString(0) ?: display
        }
        val id = UUID.randomUUID().toString()
        val ext = display.substringAfterLast('.', "audio").take(5)
        val fileName = "$id.$ext"
        val input = resolver.openInputStream(uri) ?: error("cannot open $uri")
        val target = File(dir, fileName)
        input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        val sound = Sound(id, display.substringBeforeLast('.').take(40), fileName, null, durationOf(target))
        sounds.add(sound)
        save()
        sound
    } catch (e: Exception) {
        Log.e(TAG, "import failed for $uri", e)
        null
    }

    fun addRecording(file: File) {
        val sound = Sound(file.nameWithoutExtension, "Recording ${sounds.size + 1}", file.name, null, durationOf(file))
        sounds.add(sound)
        save()
    }

    /** Copies the bundled starter sounds (assets/starter) into the library once. Safe to call on every launch. */
    @Synchronized
    fun installStarterPack(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("floatboard_starter", Context.MODE_PRIVATE)
        val done = (prefs.getStringSet("installed", emptySet()) ?: emptySet()).toMutableSet()
        val index = try {
            JSONArray(context.assets.open("starter/index.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "no starter pack", e)
            return
        }
        var changed = false
        for (i in 0 until index.length()) {
            val o = index.getJSONObject(i)
            val file = o.getString("file")
            if (file in done) continue
            try {
                val id = UUID.randomUUID().toString()
                val target = File(dir, "$id.${file.substringAfterLast('.', "wav")}")
                context.assets.open("starter/$file").use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
                sounds.add(Sound(id, o.getString("name"), target.name, null, durationOf(target), builtin = true))
                done.add(file)
                changed = true
            } catch (e: Exception) {
                Log.e(TAG, "starter sound failed: $file", e)
            }
        }
        if (changed) {
            save()
            prefs.edit().putStringSet("installed", done).apply()
        }
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
        sound.cover?.let { coverFile(it).delete() }
        save()
    }

    /** Center-crops the picked image to a 256px square JPEG and attaches it to the sound. */
    fun setCover(context: Context, id: String, uri: Uri): Boolean {
        val i = sounds.indexOfFirst { it.id == id }
        if (i < 0) return false
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= COVER_SIZE && bounds.outHeight / (sample * 2) >= COVER_SIZE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            val side = minOf(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, COVER_SIZE, COVER_SIZE, true)
            val name = "${UUID.randomUUID()}.jpg"
            coverFile(name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            val j = sounds.indexOfFirst { it.id == id }
            if (j < 0) { coverFile(name).delete(); return false }
            sounds[j].cover?.let { coverFile(it).delete() }
            sounds[j] = sounds[j].copy(cover = name)
            save()
            true
        } catch (e: Exception) {
            Log.e(TAG, "setCover failed", e)
            false
        }
    }

    fun clearCover(id: String) {
        val i = sounds.indexOfFirst { it.id == id }
        if (i < 0) return
        sounds[i].cover?.let { coverFile(it).delete() }
        sounds[i] = sounds[i].copy(cover = null)
        save()
    }

    private fun load() {
        if (!index.exists()) return
        try {
            val arr = JSONArray(index.readText())
            val loaded = (0 until arr.length()).map { arr.getJSONObject(it) }
                .map {
                    Sound(
                        id = it.getString("id"),
                        name = it.getString("name"),
                        file = it.getString("file"),
                        cover = it.optString("cover", "").ifEmpty { null },
                        durationMs = it.optLong("duration", 0L),
                        builtin = it.optBoolean("builtin", false)
                    )
                }
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
            sounds.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("name", it.name).put("file", it.file)
                        .put("cover", it.cover ?: "").put("duration", it.durationMs).put("builtin", it.builtin)
                )
            }
            val tmp = File(index.parentFile, "sounds.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(index)) { index.writeText(arr.toString()); tmp.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }
}

/** Small in-memory cache for decoded cover images (256px each, so 48 entries is ~12 MB worst case). */
object Covers {
    private val cache = LruCache<String, ImageBitmap>(48)

    suspend fun load(sound: Sound): ImageBitmap? {
        val name = sound.cover ?: return null
        cache.get(name)?.let { return it }
        return withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(SoundStore.coverFile(name).absolutePath)
                ?.asImageBitmap()
                ?.also { cache.put(name, it) }
        }
    }
}

@Composable
fun rememberCover(sound: Sound): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, sound.cover) {
        value = Covers.load(sound)
    }
    return state.value
}
