package com.omegax.antigravityremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun allowedGithubUrl_permitsOnlyOfficialRepoAndReleaseCdns() {
        assertTrue(AppUpdater.isAllowedGithubUrl(AppUpdater.RELEASE_TAG_LATEST_API))
        assertTrue(AppUpdater.isAllowedGithubUrl(AppUpdater.RELEASE_LATEST_API))
        assertTrue(AppUpdater.isAllowedGithubUrl(AppUpdater.DIRECT_APK_FALLBACK_URL))
        assertTrue(AppUpdater.isAllowedGithubUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertTrue(AppUpdater.isAllowedGithubUrl("https://release-assets.githubusercontent.com/abc/AntigravityRemote-arm64.apk"))

        // Reject plaintext HTTP, foreign repos, traversal, userinfo spoofing, and arbitrary hosts
        assertFalse(AppUpdater.isAllowedGithubUrl("http://github.com/OMEGA12492903/antigravity-remote/releases/latest"))
        assertFalse(AppUpdater.isAllowedGithubUrl("https://github.com/attacker/evil/releases/download/v1/evil.apk"))
        assertFalse(AppUpdater.isAllowedGithubUrl("https://api.github.com/repos/attacker/evil/releases/latest"))
        assertFalse(AppUpdater.isAllowedGithubUrl("https://github.com/OMEGA12492903/antigravity-remote/../../attacker/evil"))
        assertFalse(AppUpdater.isAllowedGithubUrl("https://evil.com@github.com/OMEGA12492903/antigravity-remote/releases"))
        assertFalse(AppUpdater.isAllowedGithubUrl("https://evil.com\\@github.com/OMEGA12492903/antigravity-remote/releases"))
        assertFalse(AppUpdater.isAllowedGithubUrl("https://raw.githubusercontent.com/attacker/evil/main/a.apk"))
        assertFalse(AppUpdater.isAllowedGithubUrl(null))
        assertFalse(AppUpdater.isAllowedGithubUrl(""))
    }

    @Test
    fun compareSemver_ordersVersionsProperly() {
        assertTrue(AppUpdater.compareSemver("1.1.0", "1.0.1") > 0)
        assertTrue(AppUpdater.compareSemver("1.10.0", "1.9.9") > 0)
        assertTrue(AppUpdater.compareSemver("v2.0.0", "1.9.9") > 0)
        assertEquals(0, AppUpdater.compareSemver("v1.1.0", "1.1.0"))
        assertTrue(AppUpdater.compareSemver("1.0.0", "1.1.0") < 0)
    }
}
