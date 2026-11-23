package com.omegax.antigravityremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPolicyTest {

    @Test
    fun strictAntigravityOrigin_allowsOnlyExactHttpsOrigins() {
        assertTrue(SecurityPolicy.isStrictAntigravityOrigin("https://antigravity.google.com/"))
        assertTrue(SecurityPolicy.isStrictAntigravityOrigin("https://antigravity.google.com/session/123"))
        assertTrue(SecurityPolicy.isStrictAntigravityOrigin("https://antigravity.google/remote"))

        // Reject plaintext HTTP, null, blank, special schemes, and spoofed hosts
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin(null))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin(""))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("   "))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("http://antigravity.google.com/"))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("about:blank"))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("blob:https://antigravity.google.com/abc"))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("data:text/html,<script>alert(1)</script>"))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("javascript:alert(1)"))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("https://evil.com@antigravity.google.com/"))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("https://evil.com\\@antigravity.google.com/"))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("https://antigravity.google.com.evil.com/"))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("https://accounts.google.com/"))
        assertFalse(SecurityPolicy.isStrictAntigravityOrigin("https://user.googleusercontent.com/"))
    }

    @Test
    fun trustedGoogleAuthUrl_excludesUntrustedUserContentAndSchemes() {
        assertTrue(SecurityPolicy.isTrustedGoogleAuthUrl("https://accounts.google.com/o/oauth2/auth"))
        assertTrue(SecurityPolicy.isTrustedGoogleAuthUrl("https://antigravity.google.com/"))
        assertTrue(SecurityPolicy.isTrustedGoogleAuthUrl("https://myaccount.google.com/security"))

        assertFalse(SecurityPolicy.isTrustedGoogleAuthUrl(null))
        assertFalse(SecurityPolicy.isTrustedGoogleAuthUrl(""))
        assertFalse(SecurityPolicy.isTrustedGoogleAuthUrl("about:blank"))
        assertFalse(SecurityPolicy.isTrustedGoogleAuthUrl("http://accounts.google.com/"))
        assertFalse(SecurityPolicy.isTrustedGoogleAuthUrl("https://attacker.googleusercontent.com/exploit.html"))
        assertFalse(SecurityPolicy.isTrustedGoogleAuthUrl("https://accounts.google.com@evil.com/"))
        assertFalse(SecurityPolicy.isTrustedGoogleAuthUrl("https://evil.com\\accounts.google.com/"))
    }

    @Test
    fun validateIncomingDeepLink_blocksIntentSpoofing() {
        assertEquals(
            "https://antigravity.google.com/conversations/42",
            SecurityPolicy.validateIncomingDeepLink("https://antigravity.google.com/conversations/42")
        )
        assertNull(SecurityPolicy.validateIncomingDeepLink("https://evil-phishing.example/login"))
        assertNull(SecurityPolicy.validateIncomingDeepLink("http://antigravity.google.com/"))
        assertNull(SecurityPolicy.validateIncomingDeepLink("https://evil.com@antigravity.google.com/"))
        assertNull(SecurityPolicy.validateIncomingDeepLink("javascript:alert(1)"))
        assertNull(SecurityPolicy.validateIncomingDeepLink("file:///etc/hosts"))
    }

    @Test
    fun externalSchemeFilter_blocksDangerousAndroidSchemes() {
        assertTrue(SecurityPolicy.isAllowedExternalScheme("https://github.com/google-antigravity"))
        assertTrue(SecurityPolicy.isAllowedExternalScheme("http://example.com"))
        assertTrue(SecurityPolicy.isAllowedExternalScheme("mailto:support@example.com"))
        assertTrue(SecurityPolicy.isAllowedExternalScheme("tel:+34123456789"))

        assertFalse(SecurityPolicy.isAllowedExternalScheme("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(SecurityPolicy.isAllowedExternalScheme("file:///data/data/com.omegax.antigravityremote/files"))
        assertFalse(SecurityPolicy.isAllowedExternalScheme("content://com.android.contacts/contacts"))
        assertFalse(SecurityPolicy.isAllowedExternalScheme("javascript:alert(1)"))
        assertFalse(SecurityPolicy.isAllowedExternalScheme("data:text/html,hi"))
        assertFalse(SecurityPolicy.isAllowedExternalScheme("android-app://com.android.settings"))
        assertFalse(SecurityPolicy.isAllowedExternalScheme("https://evil.com\\@good.com"))
    }

    @Test
    fun sanitizeDownloadFileName_preventsTraversalAndBidiSpoofing() {
        val traversal = SecurityPolicy.sanitizeDownloadFileName("../../../../etc/passwd")
        assertFalse(traversal.contains(".."))
        assertFalse(traversal.contains("/"))
        assertFalse(traversal.contains("\\"))

        // Right-to-left override (U+202E) spoofing "report_gpj.exe"
        val bidi = SecurityPolicy.sanitizeDownloadFileName("report_\u202Egpj.exe")
        assertFalse(bidi.contains('\u202E'))

        val hidden = SecurityPolicy.sanitizeDownloadFileName("...bashrc")
        assertFalse(hidden.startsWith("."))

        val longName = "a".repeat(200) + ".patch"
        val truncated = SecurityPolicy.sanitizeDownloadFileName(longName)
        assertTrue(truncated.length <= 100)
        assertTrue(truncated.endsWith(".patch"))

        val empty = SecurityPolicy.sanitizeDownloadFileName("   ")
        assertTrue(empty.startsWith("antigravity_"))
    }

    @Test
    fun sanitizePromptText_truncatesHugePayloads() {
        val huge = "X".repeat(5000)
        val safe = SecurityPolicy.sanitizePromptText(huge, maxLength = 2000)
        assertEquals(2001, safe.length)
        assertTrue(safe.endsWith("…"))
    }
}
