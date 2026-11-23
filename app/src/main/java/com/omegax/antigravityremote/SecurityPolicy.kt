package com.omegax.antigravityremote

import java.net.URI

/**
 * Centralized security policy for URL validation, origin trust checks,
 * external scheme filtering, and safe filename/text sanitization.
 *
 * Pure JVM Kotlin (no Android framework dependencies) so every rule
 * can be unit-tested exhaustively against adversarial inputs.
 */
object SecurityPolicy {

    private val STRICT_ANTIGRAVITY_HOSTS = setOf(
        "antigravity.google.com",
        "antigravity.google"
    )

    private val TRUSTED_AUTH_HOSTS = setOf(
        "antigravity.google.com",
        "antigravity.google",
        "accounts.google.com",
        "accounts.youtube.com",
        "myaccount.google.com",
        "gds.google.com",
        "consent.google.com",
        "ogs.google.com",
        "ssl.gstatic.com",
        "www.gstatic.com"
    )

    private val SAFE_EXTERNAL_SCHEMES = setOf(
        "https",
        "http",
        "mailto",
        "tel"
    )

    // Bidirectional override / formatting characters used in filename extension spoofing
    private val DANGEROUS_CHARS_REGEX = Regex("[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|]")

    private fun parseSafeHttpUri(rawUrl: String?): URI? {
        if (rawUrl.isNullOrBlank()) return null
        val trimmed = rawUrl.trim()
        // Reject backslashes and whitespace/control characters that trigger parser differentials
        // between java.net.URI, android.net.Uri, and browser URL parsers
        if (trimmed.contains('\\')) return null
        if (trimmed.any { it.isISOControl() }) return null

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }

        // Reject userinfo (e.g., https://evil.com@antigravity.google.com)
        if (!uri.rawUserInfo.isNullOrEmpty()) return null
        return uri
    }

    /**
     * Returns true ONLY for strict HTTPS origins of Antigravity itself.
     * Used for auto-granting web notifications, persistent storage, and deep links.
     */
    fun isStrictAntigravityOrigin(url: String?): Boolean {
        val uri = parseSafeHttpUri(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in STRICT_ANTIGRAVITY_HOSTS
    }

    /**
     * Returns true ONLY for strict HTTPS Google authentication / Antigravity hosts.
     * Explicitly excludes untrusted user-content hosts (e.g., googleusercontent.com),
     * plaintext HTTP, data:, blob:, about:, and blank strings.
     */
    fun isTrustedGoogleAuthUrl(url: String?): Boolean {
        val uri = parseSafeHttpUri(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in TRUSTED_AUTH_HOSTS
    }

    /**
     * Used exclusively when deciding whether a newly opened popup window (window.open)
     * should be hosted inside our in-app OAuth popup container rather than the external browser.
     * Never use this for permission decisions!
     */
    fun isInAppPopupUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val trimmed = url.trim()
        if (trimmed.equals("about:blank", ignoreCase = true)) return true
        return isTrustedGoogleAuthUrl(trimmed)
    }

    /**
     * Validates an externally supplied deep-link URL from an exported Activity Intent.
     * Rejects everything except strict HTTPS Antigravity URLs.
     */
    fun validateIncomingDeepLink(url: String?): String? {
        if (!isStrictAntigravityOrigin(url)) return null
        return url!!.trim()
    }

    /**
     * Checks whether a URI is safe to hand off to an external Android app via ACTION_VIEW.
     * Blocks dangerous schemes like file:, content:, intent:, javascript:, data:.
     */
    fun isAllowedExternalScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (trimmed.any { it.isISOControl() }) return false
        val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme !in SAFE_EXTERNAL_SCHEMES) return false
        if (scheme == "http" || scheme == "https") {
            val uri = parseSafeHttpUri(trimmed) ?: return false
            return !uri.host.isNullOrBlank()
        }
        return true
    }

    /**
     * True only when the URL has a valid HTTPS scheme and host (used for popup padlock UI).
     */
    fun isHttpsUrl(url: String?): Boolean {
        val uri = parseSafeHttpUri(url) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    /**
     * Safely extracts host for display without leaking credentials or malformed strings.
     */
    fun displayHost(url: String?): String {
        val uri = parseSafeHttpUri(url)
        val host = uri?.host
        return if (!host.isNullOrBlank()) host else "about:blank"
    }

    /**
     * Sanitizes a downloaded filename against path traversal, control/BIDI spoofing chars,
     * hidden dot-files, and excessive length.
     */
    fun sanitizeDownloadFileName(rawName: String?, fallbackPrefix: String = "antigravity"): String {
        val cleaned = (rawName ?: "")
            .replace(DANGEROUS_CHARS_REGEX, "_")
            .replace("..", "_")
            .trim()
            .trimStart('.')

        if (cleaned.isBlank()) {
            return "${fallbackPrefix}_${System.currentTimeMillis()}.bin"
        }

        if (cleaned.length <= 100) return cleaned

        val dotIndex = cleaned.lastIndexOf('.')
        return if (dotIndex in 1 until cleaned.length - 1 && cleaned.length - dotIndex <= 12) {
            val ext = cleaned.substring(dotIndex)
            cleaned.substring(0, 100 - ext.length) + ext
        } else {
            cleaned.substring(0, 100)
        }
    }

    /**
     * Caps untrusted JS prompt text to avoid UI freeze / dialog DoS.
     */
    fun sanitizePromptText(text: String?, maxLength: Int = 2000): String {
        if (text.isNullOrEmpty()) return ""
        return if (text.length > maxLength) text.take(maxLength) + "…" else text
    }
}
