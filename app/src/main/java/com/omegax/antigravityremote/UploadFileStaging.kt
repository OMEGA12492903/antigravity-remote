package com.omegax.antigravityremote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Copies Android Scoped Storage `content://` URIs returned by OpenDocument / Photo Pickers
 * into private app cache files (`file://...`) so GeckoView's FilePrompt.confirm() can
 * read any image, screenshot, PDF, or code file reliably on Android 11–17.
 */
object UploadFileStaging {

    private const val TAG = "UploadFileStaging"
    private const val UPLOADS_DIR_NAME = "uploads"
    private const val MAX_FILE_BYTES = 100L * 1024L * 1024L // 100 MiB per file
    private const val MAX_AGE_MS = 6L * 60L * 60L * 1000L // 6 hours

    /**
     * Stages a list of content/file URIs into readable local `file://` URIs inside cacheDir/uploads.
     * Must be invoked on a background IO thread.
     */
    fun stageUrisToLocalFiles(context: Context, uris: List<Uri>): List<Uri> {
        if (uris.isEmpty()) return emptyList()
        val uploadsRoot = File(context.cacheDir, UPLOADS_DIR_NAME).apply { mkdirs() }
        val staged = mutableListOf<Uri>()

        for (uri in uris) {
            try {
                val localFile = stageSingleUri(context, uploadsRoot, uri)
                if (localFile != null && localFile.exists() && localFile.canRead()) {
                    staged.add(Uri.fromFile(localFile))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stage upload URI", e)
            }
        }
        return staged
    }

    private fun stageSingleUri(context: Context, uploadsRoot: File, uri: Uri): File? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val existing = uri.path?.let { File(it) }
            if (existing != null && existing.exists() && existing.canRead()) {
                return existing
            }
            return null
        }

        val resolver = context.contentResolver
        val (rawName, reportedSize) = queryMetadata(context, uri)
        if (reportedSize != null && reportedSize > MAX_FILE_BYTES) {
            Log.w(TAG, "Skipping upload exceeding size limit: $reportedSize bytes")
            return null
        }

        val mimeType = try {
            resolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        val extensionFromMime = mimeType?.let {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(it.lowercase())
        }

        val safeName = resolveSafeUploadFileName(rawName, extensionFromMime)
        val batchDir = File(uploadsRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val outFile = File(batchDir, safeName)

        val input: InputStream = resolver.openInputStream(uri) ?: return null
        return try {
            input.use { src ->
                FileOutputStream(outFile).use { dst ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = src.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_FILE_BYTES) {
                            throw IllegalStateException("Upload exceeds 100 MiB cap")
                        }
                        dst.write(buffer, 0, read)
                    }
                    dst.flush()
                }
            }
            outFile
        } catch (e: Exception) {
            outFile.delete()
            batchDir.delete()
            Log.w(TAG, "Error copying upload stream", e)
            null
        }
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String?, Long?> {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null to null
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                name to size
            } ?: (null to null)
        } catch (_: Exception) {
            null to null
        }
    }

    /**
     * Produces a safe filename preserving extension (or appending one derived from MIME type).
     */
    fun resolveSafeUploadFileName(rawDisplayName: String?, extensionFromMime: String?): String {
        val trimmed = rawDisplayName?.trim().orEmpty()
        val withExt = when {
            trimmed.isEmpty() -> {
                val ext = extensionFromMime?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".bin"
                "upload_${System.currentTimeMillis()}$ext"
            }
            trimmed.contains('.') && trimmed.substringAfterLast('.').length in 1..10 -> trimmed
            !extensionFromMime.isNullOrBlank() -> "$trimmed.$extensionFromMime"
            else -> trimmed
        }
        return SecurityPolicy.sanitizeDownloadFileName(withExt, fallbackPrefix = "upload")
    }

    /**
     * Broadens `<input accept="...">` MIME lists so Android's OpenDocument picker never grays
     * out valid camera formats (HEIC/WebP/JPG) or code files.
     */
    fun normalizePickerMimeTypes(raw: Array<String>?): Array<String> {
        if (raw.isNullOrEmpty()) return arrayOf("*/*")

        val categories = mutableSetOf<String>()
        for (entry in raw) {
            val trimmed = entry.trim().lowercase()
            if (trimmed.isEmpty()) continue
            when {
                trimmed == "*/*" -> return arrayOf("*/*")
                trimmed.startsWith("image/") -> categories.add("image/*")
                trimmed.startsWith("video/") -> categories.add("video/*")
                trimmed.startsWith("audio/") -> categories.add("audio/*")
                trimmed.startsWith(".") -> {
                    when (trimmed) {
                        ".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".heif", ".bmp", ".svg" ->
                            categories.add("image/*")
                        ".mp4", ".webm", ".mov", ".mkv" -> categories.add("video/*")
                        ".mp3", ".wav", ".ogg", ".m4a", ".flac" -> categories.add("audio/*")
                        else -> return arrayOf("*/*")
                    }
                }
                else -> return arrayOf("*/*")
            }
        }
        return if (categories.isEmpty()) arrayOf("*/*") else categories.toTypedArray()
    }

    /**
     * Removes staged upload temp files older than MAX_AGE_MS to keep cache lean.
     */
    fun cleanupOldUploads(context: Context) {
        try {
            val uploadsRoot = File(context.cacheDir, UPLOADS_DIR_NAME)
            if (!uploadsRoot.exists() || !uploadsRoot.isDirectory) return
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            uploadsRoot.listFiles()?.forEach { child ->
                if (child.lastModified() < cutoff) {
                    child.deleteRecursively()
                }
            }
        } catch (_: Exception) {
        }
    }
}
