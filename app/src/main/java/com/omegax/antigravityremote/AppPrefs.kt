package com.omegax.antigravityremote

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("antigravity_remote_prefs", Context.MODE_PRIVATE)

    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()

    var showBottomDock: Boolean
        get() = prefs.getBoolean(KEY_BOTTOM_DOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BOTTOM_DOCK, value).apply()

    var comfortCssEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMFORT_CSS, true)
        set(value) = prefs.edit().putBoolean(KEY_COMFORT_CSS, value).apply()

    var fontScalePercent: Int
        get() = prefs.getInt(KEY_FONT_SCALE, 100).coerceIn(80, 140)
        set(value) = prefs.edit().putInt(KEY_FONT_SCALE, value.coerceIn(80, 140)).apply()

    var openExternalLinksInBrowser: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL_LINKS_BROWSER, true)
        set(value) = prefs.edit().putBoolean(KEY_EXTERNAL_LINKS_BROWSER, value).apply()

    var pillOffsetYFraction: Float
        get() = prefs.getFloat(KEY_PILL_Y, 0.72f).coerceIn(PILL_Y_MIN, PILL_Y_MAX)
        set(value) = prefs.edit().putFloat(KEY_PILL_Y, value.coerceIn(PILL_Y_MIN, PILL_Y_MAX)).apply()

    var pillOnRightSide: Boolean
        get() = prefs.getBoolean(KEY_PILL_RIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_PILL_RIGHT, value).apply()

    var homeUrl: String
        get() {
            val saved = prefs.getString(KEY_HOME_URL, DEFAULT_HOME_URL)
            return SecurityPolicy.validateIncomingDeepLink(saved) ?: DEFAULT_HOME_URL
        }
        set(value) {
            val safe = SecurityPolicy.validateIncomingDeepLink(value) ?: DEFAULT_HOME_URL
            prefs.edit().putString(KEY_HOME_URL, safe).apply()
        }

    companion object {
        const val DEFAULT_HOME_URL = "https://antigravity.google.com/"
        const val PILL_Y_MIN = 0.10f
        const val PILL_Y_MAX = 0.88f
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_BOTTOM_DOCK = "bottom_dock"
        private const val KEY_COMFORT_CSS = "comfort_css"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_EXTERNAL_LINKS_BROWSER = "external_links_browser"
        private const val KEY_PILL_Y = "pill_y"
        private const val KEY_PILL_RIGHT = "pill_right"
        private const val KEY_HOME_URL = "home_url"
    }
}
