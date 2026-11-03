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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class Sound(
    val id: String,
    val name: String,
    val file: String,
    val cover: String? = null,
    val durationMs: Long = 0L,
    val builtin: Boolean = false,
    val fav: Boolean = false
)

/** Outcome of importing a sound pack zip. */
data class PackResult(val added: Int, val failed: Int, val limitHit: Boolean, val tooLarge: Boolean = false)

/** Process-wide sound library. Shared by MainActivity and OverlayService (same process). */
object SoundStore {
    private const val TAG = "SoundStore"
    private const val COVER_SIZE = 256
    private const val MAX_PACK_FILES = 600
    private const val MAX_PACK_BYTES = 400L * 1024 * 1024
    private const val MAX_PACK_FILE_BYTES = 25L * 1024 * 1024
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus")

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

    fun toggleFav(id: String) {
        val i = sounds.indexOfFirst { it.id == id }
        if (i >= 0) {
            sounds[i] = sounds[i].copy(fav = !sounds[i].fav)
            save()
        }
    }

    /**
     * Adds every audio file in a zip to the library. The zip is only read, never kept: the files are copied
     * into the app, so a pack can live on the phone instead of inside the app or the repo.
     * An optional index.json in the zip ([{file, name, fav}]) supplies names and favorites.
     */
    fun importPack(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): PackResult {
        val tmp = File(context.cacheDir, "pack-${UUID.randomUUID()}.zip")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return PackResult(0, 0, false)
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            if (tmp.length() > MAX_PACK_BYTES) return PackResult(0, 0, false, tooLarge = true)

            var added = 0
            var limitHit = false
            var total = 0
            ZipFile(tmp).use { zip ->
                val meta = HashMap<String, JSONObject>()
                zip.getEntry("index.json")?.let { e ->
                    try {
                        val arr = JSONArray(zip.getInputStream(e).bufferedReader().use { it.readText() })
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            meta[o.getString("file")] = o
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "bad pack index, using file names", ex)
                    }
                }
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { !it.name.startsWith("__MACOSX") && !it.name.substringAfterLast('/').startsWith(".") }
                    .filter { it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }
                    .take(MAX_PACK_FILES)
                    .toList()
                total = entries.size

                for (entry in entries) {
                    if (!Entitlement.canAddSound()) { limitHit = true; break }
                    val base = entry.name.substringAfterLast('/')
                    val id = UUID.randomUUID().toString()
                    val target = File(dir, "$id.${base.substringAfterLast('.').lowercase()}")
                    var copied = 0L
                    var tooBig = false
                    zip.getInputStream(entry).use { src ->
                        target.outputStream().use { dst ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                copied += n
                                if (copied > MAX_PACK_FILE_BYTES) { tooBig = true; break }
                                dst.write(buf, 0, n)
                            }
                        }
                    }
                    val duration = if (tooBig || copied == 0L) 0L else durationOf(target)
                    if (duration == 0L) { target.delete(); continue }   // not real audio, or too big
                    val m = meta[entry.name] ?: meta[base]
                    val name = (m?.optString("name")?.takeIf { it.isNotBlank() } ?: base.substringBeforeLast('.')).take(40)
                    sounds.add(Sound(id, name, target.name, null, duration, false, m?.optBoolean("fav", false) ?: false))
                    added++
                    onProgress(added)
                }
            }
            if (added > 0) save()
            return PackResult(added, if (limitHit) 0 else total - added, limitHit)
        } finally {
            tmp.delete()
        }
    }

    /** Writes the user's own sounds (not the built-in starter pack) into a zip with an index.json. Returns how many. */
    fun exportPack(context: Context, uri: Uri): Int {
        val mine = sounds.filter { !it.builtin }
        var count = 0
        val out = context.contentResolver.openOutputStream(uri) ?: return 0
        ZipOutputStream(out.buffered()).use { zip ->
            val used = HashSet<String>()
            val index = JSONArray()
            for (s in mine) {
                val src = fileOf(s)
                if (!src.exists()) continue
                val ext = s.file.substringAfterLast('.', "mp3")
                val stem = s.name.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifEmpty { "sound" }.take(60)
                var entryName = "$stem.$ext"
                var n = 2
                while (!used.add(entryName)) { entryName = "$stem-$n.$ext"; n++ }
                zip.putNextEntry(ZipEntry(entryName))
                src.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                index.put(JSONObject().put("file", entryName).put("name", s.name).put("fav", s.fav))
                count++
            }
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write(index.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return count
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
                        builtin = it.optBoolean("builtin", false),
                        fav = it.optBoolean("fav", false)
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
                        .put("cover", it.cover ?: "").put("duration", it.durationMs).put("builtin", it.builtin).put("fav", it.fav)
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
