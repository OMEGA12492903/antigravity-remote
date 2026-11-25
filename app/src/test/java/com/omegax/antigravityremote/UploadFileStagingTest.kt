package com.omegax.antigravityremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadFileStagingTest {

    @Test
    fun resolveSafeUploadFileName_preservesOrAppendsExtension() {
        assertEquals(
            "screenshot.png",
            UploadFileStaging.resolveSafeUploadFileName("screenshot.png", "png")
        )
        assertEquals(
            "photo.jpg",
            UploadFileStaging.resolveSafeUploadFileName("photo", "jpg")
        )
        val traversal = UploadFileStaging.resolveSafeUploadFileName("../../secret.txt", "txt")
        assertFalse(traversal.contains(".."))
        assertFalse(traversal.contains("/"))
        assertTrue(traversal.endsWith("secret.txt"))

        val unnamed = UploadFileStaging.resolveSafeUploadFileName(null, "webp")
        assertTrue(unnamed.startsWith("upload_"))
        assertTrue(unnamed.endsWith(".webp"))
    }

    @Test
    fun normalizePickerMimeTypes_broadensImageAndFallbackCorrectly() {
        assertArrayEquals(
            arrayOf("image/*"),
            UploadFileStaging.normalizePickerMimeTypes(arrayOf("image/png", "image/jpeg", ".webp"))
        )
        assertArrayEquals(
            arrayOf("*/*"),
            UploadFileStaging.normalizePickerMimeTypes(arrayOf("image/png", ".pdf", ".kt"))
        )
        assertArrayEquals(
            arrayOf("*/*"),
            UploadFileStaging.normalizePickerMimeTypes(null)
        )
        assertArrayEquals(
            arrayOf("*/*"),
            UploadFileStaging.normalizePickerMimeTypes(emptyArray())
        )
    }
}
