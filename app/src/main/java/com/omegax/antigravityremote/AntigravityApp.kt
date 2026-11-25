package com.omegax.antigravityremote

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AntigravityApp : Application() {

    val prefs: AppPrefs by lazy { AppPrefs(this) }
    val updater: AppUpdater by lazy { AppUpdater(this) }
    val ioExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    private var _geckoRuntime: GeckoRuntime? = null

    var mainSession: GeckoSession? = null
        private set

    @Volatile
    var hasLoadedInitialPage: Boolean = false

    @Volatile
    var lastKnownUrl: String = AppPrefs.DEFAULT_HOME_URL

    @Volatile
    var lastKnownCanGoBack: Boolean = false

    @Volatile
    var hasCheckedUpdateOnce: Boolean = false

    @Volatile
    var cachedUpdateInfo: AppUpdateInfo? = null

    @Volatile
    var enhancerExtension: WebExtension? = null
        private set

    private val extensionReadyListeners = mutableListOf<(WebExtension) -> Unit>()

    // Bounded LRU map of active web notifications to prevent unbounded memory growth
    private val activeWebNotifications = object : LinkedHashMap<String, Pair<Int, WebNotification>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, WebNotification>>?): Boolean {
            return size > MAX_TRACKED_NOTIFICATIONS
        }
    }

    override fun onCreate() {
        super.onCreate()
        // CRITICAL: GeckoView spawns child processes (:tab0..39, :gpu, :socket, :media, etc.)
        // and Android instantiates this Application class in EVERY child process.
        // Never initialize GeckoRuntime, SharedPreferences, or NotificationChannels outside main process!
        if (!isMainProcess()) {
            return
        }

        installCrashHandler()
        lastKnownUrl = prefs.homeUrl
        NotificationHelper.ensureChannels(this)
        ioExecutor.execute { UploadFileStaging.cleanupOldUploads(applicationContext) }
    }

    private fun isMainProcess(): Boolean {
        val currentProcess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            try {
                File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n', '\r')
            } catch (_: Exception) {
                packageName
            }
        }
        return currentProcess == packageName
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                sw.append("App: Antigravity Remote v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                sw.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                sw.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.joinToString()})\n")
                sw.append("Thread: ${thread.name}\n\n")
                throwable.printStackTrace(PrintWriter(sw))
                val report = sw.toString().take(8000)

                val crashIntent = Intent(applicationContext, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_TRACE, report)
                }
                applicationContext.startActivity(crashIntent)
                Thread.sleep(300)
                Process.killProcess(Process.myPid())
            } catch (_: Exception) {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    @Synchronized
    fun getOrCreateRuntime(): GeckoRuntime {
        val existing = _geckoRuntime
        if (existing != null) return existing

        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
            .javaScriptEnabled(true)
            .webFontsEnabled(true)
            .webManifest(true)
            .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
            .inputAutoZoomEnabled(false)
            .doubleTapZoomingEnabled(true)
            .automaticFontSizeAdjustment(false)
            .fontSizeFactor(prefs.fontScalePercent / 100f)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .consoleOutput(BuildConfig.DEBUG)
            .build()

        val runtime = GeckoRuntime.create(this, runtimeSettings)
        _geckoRuntime = runtime

        runtime.webNotificationDelegate = object : WebNotificationDelegate {
            override fun onShowNotification(notification: WebNotification) {
                val origin = notification.origin ?: notification.source
                if (!origin.isNullOrBlank() && !SecurityPolicy.isStrictAntigravityOrigin(origin)) {
                    Log.w(TAG, "Blocked web notification from untrusted origin")
                    notification.dismiss()
                    return
                }

                val tag = notification.tag?.takeIf { it.isNotBlank() }
                    ?: "ag_${System.currentTimeMillis()}"
                val title = notification.title?.takeIf { it.isNotBlank() } ?: "Antigravity Remote"
                val text = notification.text?.takeIf { it.isNotBlank() }
                    ?: "Tu agente tiene una novedad."

                val notifId = NotificationHelper.showAgentAlert(
                    context = applicationContext,
                    title = title,
                    body = text,
                    tag = tag
                )
                synchronized(activeWebNotifications) {
                    activeWebNotifications[tag] = notifId to notification
                }
            }

            override fun onCloseNotification(notification: WebNotification) {
                val tag = notification.tag
                if (!tag.isNullOrBlank()) {
                    val removed = synchronized(activeWebNotifications) {
                        activeWebNotifications.remove(tag)
                    }
                    removed?.let { (notifId, _) ->
                        NotificationHelper.cancelNotification(applicationContext, notifId)
                    }
                }
            }
        }

        // Install the bundled non-destructive mobile enhancer WebExtension
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { ext ->
                    if (ext != null) {
                        enhancerExtension = ext
                        val pending = synchronized(extensionReadyListeners) {
                            val copy = extensionReadyListeners.toList()
                            extensionReadyListeners.clear()
                            copy
                        }
                        pending.forEach { it(ext) }
                        Log.i(TAG, "Built-in WebExtension ready: ${ext.id}")
                    }
                },
                { err ->
                    Log.e(TAG, "Failed to install built-in WebExtension", err)
                }
            )

        return runtime
    }

    fun getRuntimeIfInitialized(): GeckoRuntime? = _geckoRuntime

    fun onEnhancerReady(callback: (WebExtension) -> Unit) {
        val current = enhancerExtension
        if (current != null) {
            callback(current)
        } else {
            synchronized(extensionReadyListeners) {
                val again = enhancerExtension
                if (again != null) {
                    callback(again)
                } else {
                    extensionReadyListeners.add(callback)
                }
            }
        }
    }

    fun getOrCreateMainSession(): GeckoSession {
        val existing = mainSession
        if (existing != null && existing.isOpen) {
            return existing
        }
        return recreateMainSession()
    }

    fun recreateMainSession(): GeckoSession {
        try {
            mainSession?.close()
        } catch (_: Exception) {
        }
        val runtime = getOrCreateRuntime()
        val sessionSettings = buildSessionSettings(prefs.desktopMode)
        val session = GeckoSession(sessionSettings)
        session.open(runtime)
        mainSession = session
        hasLoadedInitialPage = false
        lastKnownCanGoBack = false
        return session
    }

    fun buildSessionSettings(desktopMode: Boolean): GeckoSessionSettings {
        return GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .useTrackingProtection(false)
            .suspendMediaWhenInactive(false)
            .allowJavascript(true)
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_STANDALONE)
            .userAgentMode(
                if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .viewportMode(
                if (desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
            .build()
    }

    fun handleNotificationClick(tag: String?) {
        if (tag.isNullOrBlank()) return
        val entry = synchronized(activeWebNotifications) {
            activeWebNotifications.remove(tag)
        }
        entry?.let { (_, webNotif) ->
            try {
                webNotif.click()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "AntigravityApp"
        private const val MAX_TRACKED_NOTIFICATIONS = 25
        const val EXTENSION_LOCATION = "resource://android/assets/antigravity_enhancer/"
        const val EXTENSION_ID = "antigravity-enhancer@omegax.local"
        const val BRIDGE_NAME = "antigravity_bridge"
    }
}
