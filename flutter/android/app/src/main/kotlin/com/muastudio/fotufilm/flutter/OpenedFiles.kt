package com.muastudio.fotufilm.flutter

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

/**
 * Photographs, scans and movies chosen in the system's picker, as files the engine opens by path
 * (`chooseFiles` in lib/model/file_types.dart, over `fotufilm/files`). The picker answers with
 * content URIs; each is copied into the app's cache a buffer at a time, so a camera's RAW or a scan
 * of any size crosses without being held in memory, which file_selector does and runs out of.
 */
class OpenedFiles(private val activity: Activity, messenger: BinaryMessenger) {
    private val channel = MethodChannel(messenger, "fotufilm/files")
    private val copying = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var waiting: MethodChannel.Result? = null

    init {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "open" -> {
                    waiting?.success(emptyList<String>())
                    waiting = result
                    val types = call.argument<List<String>>("types") ?: listOf("image/*", "video/*")
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    activity.startActivityForResult(intent, REQUEST)
                }
                // Where the engine writes an export before it is published (lib/model/export.dart).
                // Without this the export went looking for file_selector's getSavePath(), which
                // Android has no implementation of: "UnimplementedError" and no file.
                "tempDir" -> result.success(exportsDir().path)
                "publish" -> {
                    val path = call.argument<String>("path")
                    val name = call.argument<String>("name")
                    val mime = call.argument<String>("mime") ?: "image/jpeg"
                    if (path == null || name == null) {
                        result.error("bad-arguments", "publish needs a path and a name", null)
                    } else {
                        copying.execute {
                            val where = runCatching { publish(File(path), name, mime) }
                                .onSuccess { runCatching { File(path).delete() } }
                            main.post {
                                where.fold({ result.success(it) }, { e ->
                                    result.error("publish-failed", e.message ?: "$e", null)
                                })
                            }
                        }
                    }
                }
                "publishDir" -> {
                    val path = call.argument<String>("path")
                    val mime = call.argument<String>("mime") ?: "image/jpeg"
                    if (path == null) {
                        result.error("bad-arguments", "publishDir needs a path", null)
                    } else {
                        copying.execute {
                            val where = runCatching {
                                File(path).listFiles()?.filter { it.isFile }?.forEach { file ->
                                    runCatching { publish(file, file.name, mime) }
                                        .onSuccess { runCatching { file.delete() } }
                                }
                                publishedFolder(mime)
                            }
                            main.post {
                                where.fold({ result.success(it) }, { e ->
                                    result.error("publish-failed", e.message ?: "$e", null)
                                })
                            }
                        }
                    }
                }
                // What the app is using, so growth can be seen rather than guessed at.
                "storage" -> result.success(
                    mapOf(
                        "imports" to sizeOf(importsDir()),
                        "exports" to sizeOf(exportsDir()),
                        "resources" to sizeOf(File(activity.filesDir, "fotufilm-resources")),
                        "cache" to sizeOf(activity.cacheDir),
                        "importsAt" to importsDir().path,
                    )
                )
                // Deletes an imported copy once the export is published. Guarded to the imports
                // folder: a path outside it is never removed, whatever Dart asks for.
                "deleteImport" -> {
                    val path = call.argument<String>("path")
                    val file = path?.let { File(it) }
                    val inside = file != null &&
                        file.canonicalPath.startsWith(importsDir().canonicalPath + File.separator)
                    result.success(inside && file!!.delete())
                }
                // Scratch space only: exports already published elsewhere, and the engine's
                // temporary decodes. Called at start-up and when the app goes to the background.
                "clearExports" -> result.success(clearScratch())
                else -> result.notImplemented()
            }
        }
    }

    /** The picker's answer; false when the result is another request's. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST) return false
        val result = waiting ?: return true
        waiting = null
        val uris = buildList {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null) for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
                else data.data?.let(::add)
            }
        }
        copying.execute {
            val paths = uris.mapNotNull { uri -> runCatching { copy(uri) }.getOrNull() }
            main.post { result.success(paths) }
        }
        return true
    }

    /** `uri` in a folder of its own under the cache, under its own name (its extension says what
     *  it is to the engine). */
    private fun copy(uri: Uri): String {
        val name = displayName(uri)?.replace('/', '_') ?: "photo"
        // filesDir, not cacheDir: the library remembers this path (photo_library.dart keeps it in
        // settings), and Android deletes cacheDir whenever storage runs short -- which would leave
        // library entries pointing at files that no longer exist.
        val folder = File(importsDir(), System.nanoTime().toString()).apply { mkdirs() }
        val target = File(folder, name)
        activity.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it, 1 shl 20) }
        }
        return target.path
    }

    private fun displayName(uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** The engine's exports land here first; the cache needs no permission on any API level. */
    private fun exportsDir(): File = File(activity.cacheDir, "exports").apply { mkdirs() }

    /** Imported photographs. Their own folder each, the library keeps the file's path. */
    private fun importsDir(): File = File(activity.filesDir, "imports").apply { mkdirs() }

    private fun clearScratch(): Boolean {
        exportsDir().listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        File(activity.cacheDir, "fotufilm-import").deleteRecursively()
        // Left behind by the earlier build, which unpacked imports into the cache.
        File(activity.cacheDir, "opened").deleteRecursively()
        return true
    }

    private fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /// What the gallery collection accepts. TIFF, DPX and EXR are images to the engine but not
    /// pictures to MediaStore's Images collection: inserting one there produced a row that
    /// openOutputStream could not open ("No item at content://.../images/media/<id>"). Anything
    /// that is not in this set goes to Downloads, which takes any file.
    private fun isGalleryPicture(mime: String) = mime.lowercase() in setOf(
        "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif"
    )

    private fun publishedFolder(mime: String) =
        if (isGalleryPicture(mime)) "Pictures/$ALBUM" else "Download/$ALBUM"

    /**
     * Hands a file to the system, so it shows in the phone's file manager and survives the cache
     * being cleared. MediaStore on Android 10 and later needs no permission; before that the file
     * is written beside the camera's own pictures.
     */
    private fun publish(file: File, name: String, mime: String): String {
        val folder = publishedFolder(mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isGalleryPicture(mime)) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val resolver = activity.contentResolver
            val pending = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, pending)
            if (uri != null) {
                val written = runCatching {
                    val out = resolver.openOutputStream(uri)
                        ?: throw java.io.IOException("no stream for $uri")
                    out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                }
                if (written.isSuccess) {
                    runCatching {
                        resolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null)
                    }
                    return "$folder/$name"
                }
                // Leave nothing half-written behind, then take the road that always works.
                runCatching { resolver.delete(uri, null, null) }
            }
            return publishBesideTheApp(file, name, folder, "MediaStore refused it")
        }
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(
                if (isGalleryPicture(mime)) Environment.DIRECTORY_PICTURES
                else Environment.DIRECTORY_DOWNLOADS
            ), ALBUM
        ).apply { mkdirs() }
        val target = File(directory, name)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.path
    }

    /**
     * The last resort: the app's own external folder. Always writable without a permission and
     * visible over USB and to most file managers, so an export can still be collected by hand.
     */
    private fun publishBesideTheApp(file: File, name: String, folder: String, why: String): String {
        val root = activity.getExternalFilesDir(null) ?: activity.filesDir
        val directory = File(root, folder.replace('/', '_')).apply { mkdirs() }
        val target = File(directory, name)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        Log.w(TAG, "publish to the gallery failed ($why); wrote $target")
        return target.path
    }

    private companion object {
        const val TAG = "FotufilmFiles"
        const val REQUEST = 0x0f07
        const val ALBUM = "Fotufilm"
    }
}
