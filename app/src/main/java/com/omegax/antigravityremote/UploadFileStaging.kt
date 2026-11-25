package com.omegax.antigravityremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class EncodedBridgeImage(
    val name: String,
    val mimeType: String,
    val base64: String
)

/**
 * Copies Android Scoped Storage `content://` URIs returned by Photo Picker / OpenDocument
 * into private app cache files (`file://...`) so GeckoView's FilePrompt.confirm() can
 * read any image, screenshot, PDF, or code file reliably on Android 11–17.
 * Also supports creating camera capture files and compact image encoding for 1-tap/Share attach.
 */
object UploadFileStaging {

    private const val TAG = "UploadFileStaging"
    private const val UPLOADS_DIR_NAME = "uploads"
    private const val MAX_FILE_BYTES = 100L * 1024L * 1024L // 100 MiB per file
    private const val MAX_BRIDGE_RAW_BYTES = 2 * 1024 * 1024 // Keep small PNG/WebP lossless under 2 MiB
    private const val MAX_BRIDGE_DIMENSION = 2048
    private const val MAX_AGE_MS = 6L * 60L * 60L * 1000L // 6 hours

    /**
     * Creates a private cache file and FileProvider content URI for ActivityResultContracts.TakePicture.
     */
    fun createCameraCaptureTarget(context: Context): Pair<File, Uri> {
        val uploadsRoot = File(context.cacheDir, UPLOADS_DIR_NAME).apply { mkdirs() }
        val batchDir = File(uploadsRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(batchDir, "foto_$stamp.jpg")
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updater.fileprovider",
            photoFile
        )
        return photoFile to contentUri
    }

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

    /**
     * Reads an image URI (content:// only for external safety) and encodes it compactly as base64
     * for instant injection into Antigravity's chat composer via our WebExtension bridge.
     */
    fun encodeImageForBridge(context: Context, uri: Uri): EncodedBridgeImage? {
        if (!uri.scheme.equals("content", ignoreCase = true)) return null
        val resolver = context.contentResolver
        val (rawName, reportedSize) = queryMetadata(context, uri)
        if (reportedSize != null && reportedSize > MAX_FILE_BYTES) return null

        val mime = try {
            resolver.getType(uri)?.lowercase()
        } catch (_: Exception) {
            null
        } ?: "image/jpeg"
        if (!mime.startsWith("image/")) return null

        val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        val safeName = resolveSafeUploadFileName(rawName, extFromMime)

        return try {
            val rawBytes = resolver.openInputStream(uri)?.use { readBoundedBytes(it, 20L * 1024L * 1024L) }
                ?: return null
            if (rawBytes.isEmpty()) return null

            // Small images (e.g., crisp screenshots under 2 MiB): send untouched original bytes
            if (rawBytes.size <= MAX_BRIDGE_RAW_BYTES && (mime == "image/png" || mime == "image/jpeg" || mime == "image/webp")) {
                return EncodedBridgeImage(
                    name = safeName,
                    mimeType = mime,
                    base64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                )
            }

            // Larger camera photos / HEIC: decode with subsampling & compress cleanly to JPEG
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            var sampleSize = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sampleSize > MAX_BRIDGE_DIMENSION * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return null

            val scaled = if (maxOf(decoded.width, decoded.height) > MAX_BRIDGE_DIMENSION) {
                val scale = MAX_BRIDGE_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
                val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            scaled.recycle()
            val jpegBytes = out.toByteArray()
            val jpegName = if (safeName.contains('.')) {
                safeName.substringBeforeLast('.') + ".jpg"
            } else {
                "$safeName.jpg"
            }
            EncodedBridgeImage(
                name = jpegName,
                mimeType = "image/jpeg",
                base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to encode image for bridge", e)
            null
        }
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            total += read
            if (total > maxBytes) throw IllegalStateException("Image too large")
            out.write(buf, 0, read)
        }
        return out.toByteArray()
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
