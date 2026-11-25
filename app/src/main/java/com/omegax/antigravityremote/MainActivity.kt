package com.omegax.antigravityremote

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.URLUtil
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var app: AntigravityApp
    private lateinit var prefs: AppPrefs

    private lateinit var rootContainer: LinearLayout
    private lateinit var geckoView: GeckoView
    private lateinit var popupContainer: LinearLayout
    private lateinit var popupGeckoView: GeckoView
    private lateinit var popupHeaderCompose: ComposeView
    private lateinit var overlayCompose: ComposeView
    private lateinit var bottomDockCompose: ComposeView

    private lateinit var mainSession: GeckoSession
    private var popupSession: GeckoSession? = null
    private var activeBridgePort: WebExtension.Port? = null

    private var uiState by mutableStateOf(RemoteUiState())
    private var lastBackPressedMs = 0L

    // Circuit breaker against infinite Gecko crash/kill recovery loops
    private var crashRecoveryTimestamps = ArrayDeque<Long>()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Separate ActivityResult callbacks so notification prompts never collide with GeckoView prompts
    private var pendingGeckoPermissionCallback: PermissionDelegate.Callback? = null
    private var pendingFileCallback: ((List<Uri>) -> Unit)? = null
    private var pendingAttachAllowMultiple: Boolean = false
    private var pendingAttachMimeTypes: Array<String> = arrayOf("*/*")
    private var pendingCameraFile: File? = null
    private var pendingFolderCallback: ((Uri?) -> Unit)? = null
    private var pendingActivityResult: GeckoResult<Intent>? = null
    private var pendingEnableKeepAliveAfterPermission = false
    private var pendingSharedImageUris: List<Uri> = emptyList()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingEnableKeepAliveAfterPermission) {
            pendingEnableKeepAliveAfterPermission = false
            if (granted || NotificationHelper.hasNotificationPermission(this)) {
                applyKeepAlive(true)
            } else {
                Toast.makeText(
                    this,
                    "Activa el permiso de notificaciones para mantener la sesión en 2º plano",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val geckoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val geckoCb = pendingGeckoPermissionCallback
        pendingGeckoPermissionCallback = null
        if (geckoCb != null) {
            val allGranted = results.values.isNotEmpty() && results.values.all { it }
            if (allGranted) geckoCb.grant() else geckoCb.reject()
        }
    }

    private val singleFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.invoke(if (uri != null) listOf(uri) else emptyList())
    }

    private val multiFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.invoke(uris ?: emptyList())
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val cb = pendingFolderCallback
        pendingFolderCallback = null
        cb?.invoke(uri)
    }

    // Modern Android Photo Picker (fast gallery + screenshots grid)
    private val singlePhotoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.invoke(if (uri != null) listOf(uri) else emptyList())
    }

    private val multiPhotoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.invoke(uris ?: emptyList())
    }

    // Dedicated 1-tap quick photo picker (attaches directly into active chat via bridge)
    private val quickPhotoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            injectImagesIntoChat(uris)
        }
    }

    // Direct camera capture into private cache file
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        val cb = pendingFileCallback
        pendingFileCallback = null
        if (success && file != null && file.exists() && file.length() > 0L) {
            cb?.invoke(listOf(Uri.fromFile(file)))
        } else {
            file?.delete()
            cb?.invoke(emptyList())
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            val cb = pendingFileCallback
            pendingFileCallback = null
            cb?.invoke(emptyList())
            Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
        }
    }

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val pending = pendingActivityResult
        pendingActivityResult = null
        if (pending != null) {
            if (activityResult.resultCode == RESULT_OK) {
                pending.complete(activityResult.data ?: Intent())
            } else {
                pending.completeExceptionally(IllegalStateException("Cancelled by user"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        app = application as AntigravityApp
        prefs = app.prefs

        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        geckoView = findViewById(R.id.geckoView)
        popupContainer = findViewById(R.id.popupContainer)
        popupGeckoView = findViewById(R.id.popupGeckoView)
        popupHeaderCompose = findViewById(R.id.popupHeaderCompose)
        overlayCompose = findViewById(R.id.overlayCompose)
        bottomDockCompose = findViewById(R.id.bottomDockCompose)

        setupWindowInsets()
        initUiStateFromPrefs()
        applyKeepScreenOn(prefs.keepScreenOn)

        // Initialize GeckoRuntime lazily on UI thread in the main process
        val runtime = app.getOrCreateRuntime()

        // Enable native Passkeys / WebAuthn / FIDO2 intents for Google Sign-In
        runtime.activityDelegate = GeckoRuntime.ActivityDelegate { pendingIntent ->
            pendingActivityResult?.completeExceptionally(IllegalStateException("Superseded"))
            val result = GeckoResult<Intent>()
            pendingActivityResult = result
            try {
                val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                intentSenderLauncher.launch(request)
            } catch (e: Exception) {
                pendingActivityResult = null
                result.completeExceptionally(e)
            }
            result
        }

        // Prevent white flash before GeckoView paints its first frame
        geckoView.coverUntilFirstPaint(Color.parseColor("#1F1F1F"))

        attachMainSession(
            session = app.getOrCreateMainSession(),
            loadIfNeeded = true,
            targetUrl = extractIncomingUrl(intent)
        )
        setupComposeLayers()
        setupBackNavigation()
        mainHandler.post { requestNotificationPermissionIfNeeded() }

        if (prefs.keepAliveEnabled && NotificationHelper.hasNotificationPermission(this)) {
            if (!KeepAliveService.start(this)) {
                prefs.keepAliveEnabled = false
                uiState = uiState.copy(keepAliveEnabled = false)
            }
        }

        handleIntentExtras(intent)
        handleIncomingShareIntent(intent)

        // Silent background check once per app process so an update badge appears automatically
        if (!app.hasCheckedUpdateOnce) {
            app.hasCheckedUpdateOnce = true
            checkForUpdates(silent = true)
        }

        // Safety fallback so cold-start splash can never stay indefinitely
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed && uiState.showColdSplash) {
                uiState = uiState.copy(showColdSplash = false)
            }
        }, 6500L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
        handleIncomingShareIntent(intent)
        val deepUrl = extractIncomingUrl(intent)
        if (!deepUrl.isNullOrBlank()) {
            mainSession.loadUri(deepUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        // Only disable toggle if the user explicitly tapped "Desactivar" in the notification
        if (KeepAliveService.consumeStoppedFromNotification()) {
            prefs.keepAliveEnabled = false
            uiState = uiState.copy(keepAliveEnabled = false)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)

        // Complete any pending callbacks cleanly so GeckoView never hangs
        pendingGeckoPermissionCallback?.reject()
        pendingGeckoPermissionCallback = null
        pendingFileCallback?.invoke(emptyList())
        pendingFileCallback = null
        pendingFolderCallback?.invoke(null)
        pendingFolderCallback = null
        pendingActivityResult?.completeExceptionally(IllegalStateException("Activity destroyed"))
        pendingActivityResult = null

        // Detach all delegates referencing this Activity from the persistent Application session
        detachMainSessionDelegates()
        geckoView.releaseSession()
        closePopupSession()
        activeBridgePort = null
        app.getRuntimeIfInitialized()?.activityDelegate = null

        super.onDestroy()
    }

    private fun detachMainSessionDelegates() {
        if (!::mainSession.isInitialized) return
        mainSession.navigationDelegate = null
        mainSession.progressDelegate = null
        mainSession.contentDelegate = null
        mainSession.permissionDelegate = null
        mainSession.promptDelegate = null
        app.enhancerExtension?.let { ext ->
            try {
                mainSession.webExtensionController.setMessageDelegate(ext, null, AntigravityApp.BRIDGE_NAME)
            } catch (_: Exception) {
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeOpen = insets.isVisible(WindowInsetsCompat.Type.ime()) || ime.bottom > sysBars.bottom
            val bottomPadding = maxOf(sysBars.bottom, ime.bottom)
            view.setPadding(sysBars.left, sysBars.top, sysBars.right, bottomPadding)
            if (uiState.isKeyboardVisible != imeOpen) {
                uiState = uiState.copy(isKeyboardVisible = imeOpen)
            }
            insets
        }
    }

    private fun initUiStateFromPrefs() {
        uiState = uiState.copy(
            currentUrl = app.lastKnownUrl,
            canGoBack = app.lastKnownCanGoBack,
            showColdSplash = !app.hasLoadedInitialPage,
            desktopMode = prefs.desktopMode,
            keepScreenOn = prefs.keepScreenOn,
            keepAliveEnabled = prefs.keepAliveEnabled,
            showBottomDock = prefs.showBottomDock,
            comfortCssEnabled = prefs.comfortCssEnabled,
            openExternalInBrowser = prefs.openExternalLinksInBrowser,
            fontScalePercent = prefs.fontScalePercent,
            pillOffsetYFraction = prefs.pillOffsetYFraction,
            pillOnRightSide = prefs.pillOnRightSide,
            updateInfo = app.cachedUpdateInfo
        )
    }

    private fun setupComposeLayers() {
        val actions = RemoteActions(
            onBack = {
                if (popupSession != null) {
                    closePopupSession()
                } else if (uiState.canGoBack) {
                    mainSession.goBack()
                }
            },
            onReload = {
                uiState = uiState.copy(errorMessage = null)
                val cur = uiState.currentUrl
                if (!app.hasLoadedInitialPage || cur.isBlank() || cur.startsWith("about:")) {
                    mainSession.loadUri(prefs.homeUrl)
                } else {
                    mainSession.reload()
                }
            },
            onHome = {
                uiState = uiState.copy(errorMessage = null)
                mainSession.loadUri(prefs.homeUrl)
            },
            onScrollToBottom = {
                sendBridgeAction("scrollToBottom")
            },
            onFocusInput = {
                sendBridgeAction("focusInput")
            },
            onQuickAttachPhoto = {
                if (!SecurityPolicy.isStrictAntigravityOrigin(uiState.currentUrl)) {
                    Toast.makeText(this, "Abre un chat de Antigravity primero", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        quickPhotoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    } catch (_: Exception) {
                        Toast.makeText(this, "No se pudo abrir la galería", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onAttachFromGallery = {
                uiState = uiState.copy(showAttachChooser = false)
                try {
                    val req = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (pendingAttachAllowMultiple) {
                        multiPhotoPickerLauncher.launch(req)
                    } else {
                        singlePhotoPickerLauncher.launch(req)
                    }
                } catch (_: Exception) {
                    val cb = pendingFileCallback
                    pendingFileCallback = null
                    cb?.invoke(emptyList())
                }
            },
            onAttachFromCamera = {
                uiState = uiState.copy(showAttachChooser = false)
                launchCameraWithPermissionCheck()
            },
            onAttachFromFiles = {
                uiState = uiState.copy(showAttachChooser = false)
                try {
                    if (pendingAttachAllowMultiple) {
                        multiFilePickerLauncher.launch(pendingAttachMimeTypes)
                    } else {
                        singleFilePickerLauncher.launch(pendingAttachMimeTypes)
                    }
                } catch (_: Exception) {
                    val cb = pendingFileCallback
                    pendingFileCallback = null
                    cb?.invoke(emptyList())
                }
            },
            onDismissAttachChooser = {
                uiState = uiState.copy(showAttachChooser = false)
                val cb = pendingFileCallback
                pendingFileCallback = null
                cb?.invoke(emptyList())
            },
            onOpenSheet = {
                uiState = uiState.copy(showControlSheet = true)
            },
            onDismissSheet = {
                uiState = uiState.copy(showControlSheet = false)
            },
            onToggleDesktopMode = { enabled ->
                prefs.desktopMode = enabled
                uiState = uiState.copy(desktopMode = enabled)
                mainSession.settings.userAgentMode =
                    if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                mainSession.settings.viewportMode =
                    if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                    else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                mainSession.reload()
            },
            onToggleKeepScreenOn = { enabled ->
                prefs.keepScreenOn = enabled
                uiState = uiState.copy(keepScreenOn = enabled)
                applyKeepScreenOn(enabled)
            },
            onToggleKeepAlive = { enabled ->
                if (enabled && !NotificationHelper.hasNotificationPermission(this)) {
                    pendingEnableKeepAliveAfterPermission = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    applyKeepAlive(enabled)
                }
            },
            onToggleBottomDock = { enabled ->
                prefs.showBottomDock = enabled
                uiState = uiState.copy(showBottomDock = enabled)
            },
            onToggleComfortCss = { enabled ->
                prefs.comfortCssEnabled = enabled
                uiState = uiState.copy(comfortCssEnabled = enabled)
                sendBridgeComfortMode(enabled)
            },
            onToggleExternalLinks = { enabled ->
                prefs.openExternalLinksInBrowser = enabled
                uiState = uiState.copy(openExternalInBrowser = enabled)
            },
            onSelectFontScale = { pct ->
                prefs.fontScalePercent = pct
                uiState = uiState.copy(fontScalePercent = pct)
                app.getOrCreateRuntime().settings.fontSizeFactor = pct / 100f
            },
            onCopyUrl = {
                val urlToCopy = uiState.currentUrl
                if (SecurityPolicy.isHttpsUrl(urlToCopy)) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Antigravity URL", urlToCopy))
                    Toast.makeText(this, "Enlace copiado", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenExternal = {
                openExternally(uiState.currentUrl)
            },
            onClearCache = {
                app.getOrCreateRuntime().storageController
                    .clearData(StorageController.ClearFlags.ALL_CACHES)
                    .accept({
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this, "Caché limpiada", Toast.LENGTH_SHORT).show()
                                mainSession.reload()
                            }
                        }
                    }, {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this, "No se pudo limpiar la caché", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
            },
            onSignOutAndClearAll = {
                app.getOrCreateRuntime().storageController
                    .clearData(StorageController.ClearFlags.ALL)
                    .accept({
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this, "Sesión cerrada", Toast.LENGTH_SHORT).show()
                                mainSession.loadUri(prefs.homeUrl)
                            }
                        }
                    }, {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this, "Error al limpiar datos", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
            },
            onPillPositionChanged = { yFrac, onRight ->
                prefs.pillOffsetYFraction = yFrac
                prefs.pillOnRightSide = onRight
                uiState = uiState.copy(pillOffsetYFraction = yFrac, pillOnRightSide = onRight)
            },
            onCheckUpdate = {
                checkForUpdates(silent = false)
            },
            onDownloadUpdate = {
                downloadAndInstallUpdate()
            },
            onOpenReleases = {
                app.updater.openReleasesInBrowser()
            }
        )

        overlayCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MainOverlayContent(state = uiState, actions = actions)
            }
        }

        bottomDockCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                BottomQuickDock(state = uiState, actions = actions)
            }
        }

        popupHeaderCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PopupHeaderOverlay(
                    url = uiState.popupUrl ?: "",
                    isSecure = uiState.popupIsSecure,
                    onClose = { closePopupSession() }
                )
            }
        }
    }

    private fun attachMainSession(
        session: GeckoSession,
        loadIfNeeded: Boolean,
        targetUrl: String? = null
    ) {
        mainSession = session
        configureSessionDelegates(session, isPopup = false)
        attachBridgeDelegate(session)

        geckoView.setSession(session)

        if (loadIfNeeded) {
            if (!targetUrl.isNullOrBlank()) {
                session.loadUri(targetUrl)
            } else if (!app.hasLoadedInitialPage) {
                session.loadUri(prefs.homeUrl)
            }
        }
    }

    private fun configureSessionDelegates(session: GeckoSession, isPopup: Boolean) {
        session.navigationDelegate = object : NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url.isNullOrBlank()) return
                if (!isPopup) {
                    app.lastKnownUrl = url
                }
                runOnUiThread {
                    if (isPopup) {
                        uiState = uiState.copy(
                            popupUrl = url,
                            popupIsSecure = SecurityPolicy.isHttpsUrl(url)
                        )
                    } else {
                        uiState = uiState.copy(currentUrl = url, errorMessage = null)
                    }
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (!isPopup) {
                    app.lastKnownCanGoBack = canGoBack
                    runOnUiThread {
                        uiState = uiState.copy(canGoBack = canGoBack)
                    }
                }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                val uri = request.uri
                val lower = uri.lowercase()

                // Allow normal web navigation and safe internal schemes inside GeckoView
                if (lower.startsWith("https://") ||
                    lower.startsWith("http://") ||
                    lower.startsWith("about:") ||
                    lower.startsWith("blob:") ||
                    lower.startsWith("data:") ||
                    lower.startsWith("javascript:")
                ) {
                    return GeckoResult.allow()
                }

                // External non-HTTP schemes (mailto:, tel:) require explicit user gesture and allowlist
                if (request.hasUserGesture && SecurityPolicy.isAllowedExternalScheme(uri)) {
                    openExternally(uri)
                } else {
                    Log.w(TAG, "Blocked untrusted URI scheme navigation")
                }
                return GeckoResult.deny()
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession> {
                // External links (GitHub, docs, etc.) open in the system browser if enabled
                if (prefs.openExternalLinksInBrowser && !SecurityPolicy.isInAppPopupUrl(uri)) {
                    if (SecurityPolicy.isAllowedExternalScheme(uri)) {
                        openExternally(uri)
                    }
                    return GeckoResult.fromValue(null)
                }
                // Google Sign-In / OAuth popups open cleanly inside our in-app popup GeckoView
                val newPopup = openPopupSession(uri)
                return GeckoResult.fromValue(newPopup)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                if (!isPopup) {
                    val friendly = when (error.code) {
                        WebRequestError.ERROR_OFFLINE,
                        WebRequestError.ERROR_UNKNOWN_HOST ->
                            "Parece que no tienes conexión a internet o no se puede resolver antigravity.google.com."
                        WebRequestError.ERROR_NET_TIMEOUT,
                        WebRequestError.ERROR_CONNECTION_REFUSED ->
                            "Tiempo de espera agotado al conectar con Antigravity."
                        WebRequestError.ERROR_SECURITY_SSL,
                        WebRequestError.ERROR_SECURITY_BAD_CERT,
                        WebRequestError.ERROR_BAD_HSTS_CERT ->
                            "Conexión bloqueada por certificado SSL no válido. Revisa tu red Wi-Fi o la hora del móvil."
                        else ->
                            "Error de conexión (código ${error.code}). Comprueba tu red e inténtalo de nuevo."
                    }
                    runOnUiThread {
                        uiState = uiState.copy(
                            showColdSplash = false,
                            isLoading = false,
                            errorMessage = friendly
                        )
                    }
                }
                return null
            }
        }

        session.progressDelegate = object : ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (!isPopup) {
                    runOnUiThread {
                        uiState = uiState.copy(isLoading = true, progress = 12, errorMessage = null)
                    }
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (!isPopup) {
                    runOnUiThread {
                        uiState = uiState.copy(progress = progress)
                    }
                }
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: ProgressDelegate.SecurityInformation
            ) {
                if (isPopup) {
                    runOnUiThread {
                        uiState = uiState.copy(popupIsSecure = securityInfo.isSecure)
                    }
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!isPopup) {
                    if (success && !uiState.currentUrl.startsWith("about:")) {
                        app.hasLoadedInitialPage = true
                    }
                    runOnUiThread {
                        uiState = uiState.copy(
                            isLoading = false,
                            progress = 100,
                            showColdSplash = false
                        )
                    }
                }
            }
        }

        session.contentDelegate = object : ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!isPopup && !title.isNullOrBlank()) {
                    runOnUiThread {
                        uiState = uiState.copy(pageTitle = title)
                    }
                }
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                if (!isPopup) {
                    app.hasLoadedInitialPage = true
                    runOnUiThread {
                        uiState = uiState.copy(showColdSplash = false)
                    }
                }
            }

            override fun onCloseRequest(session: GeckoSession) {
                if (isPopup) {
                    runOnUiThread { closePopupSession() }
                }
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                runOnUiThread {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    if (fullScreen) {
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                saveWebResponseToDownloads(response)
            }

            override fun onCrash(session: GeckoSession) {
                Log.w(TAG, "GeckoSession crashed")
                if (isPopup) {
                    closePopupSession()
                } else {
                    recoverMainSession()
                }
            }

            override fun onKill(session: GeckoSession) {
                Log.w(TAG, "GeckoSession killed by OS")
                if (isPopup) {
                    closePopupSession()
                } else {
                    recoverMainSession()
                }
            }
        }

        session.permissionDelegate = object : PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: PermissionDelegate.Callback
            ) {
                if (permissions.isNullOrEmpty()) {
                    callback.grant()
                    return
                }
                val missing = permissions.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    callback.grant()
                } else {
                    pendingGeckoPermissionCallback?.reject()
                    pendingGeckoPermissionCallback = callback
                    geckoPermissionLauncher.launch(missing.toTypedArray())
                }
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                val isAntigravity = SecurityPolicy.isStrictAntigravityOrigin(perm.uri)
                return when (perm.permission) {
                    PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION,
                    PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                    PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                    PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> {
                        if (isAntigravity) {
                            GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_ALLOW)
                        } else {
                            GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)
                        }
                    }
                    PermissionDelegate.PERMISSION_STORAGE_ACCESS -> {
                        if (SecurityPolicy.isTrustedGoogleAuthUrl(perm.uri)) {
                            GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_ALLOW)
                        } else {
                            GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)
                        }
                    }
                    else -> GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)
                }
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out PermissionDelegate.MediaSource>?,
                audio: Array<out PermissionDelegate.MediaSource>?,
                callback: PermissionDelegate.MediaCallback
            ) {
                // Only allow Antigravity itself to request mic/camera, and ALWAYS ask the user explicitly
                if (!SecurityPolicy.isStrictAntigravityOrigin(uri)) {
                    callback.reject()
                    return
                }
                val wantsVideo = !video.isNullOrEmpty()
                val wantsAudio = !audio.isNullOrEmpty()
                val devices = when {
                    wantsVideo && wantsAudio -> "la cámara y el micrófono"
                    wantsVideo -> "la cámara"
                    wantsAudio -> "el micrófono"
                    else -> {
                        callback.reject()
                        return
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        callback.reject()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(
                        this@MainActivity,
                        androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert
                    )
                        .setTitle("Permiso de multimedia")
                        .setMessage("Antigravity (${SecurityPolicy.displayHost(uri)}) solicita usar $devices. ¿Permitir?")
                        .setPositiveButton("Permitir") { _, _ ->
                            callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                        }
                        .setNegativeButton("Denegar") { _, _ ->
                            callback.reject()
                        }
                        .setOnCancelListener {
                            callback.reject()
                        }
                        .show()
                }
            }
        }

        session.promptDelegate = GeckoPromptHandler(
            activity = this,
            ioExecutor = app.ioExecutor,
            launchFilePicker = { allowMultiple, mimeTypes, callback ->
                // Dismiss any previous unresolved file prompt before showing the fast attach chooser
                pendingFileCallback?.invoke(emptyList())
                pendingFileCallback = callback
                pendingAttachAllowMultiple = allowMultiple
                pendingAttachMimeTypes = mimeTypes
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        uiState = uiState.copy(
                            showAttachChooser = true,
                            attachAllowsMultiple = allowMultiple
                        )
                    } else {
                        pendingFileCallback = null
                        callback(emptyList())
                    }
                }
            },
            launchFolderPicker = { callback ->
                pendingFolderCallback?.invoke(null)
                pendingFolderCallback = callback
                try {
                    folderPickerLauncher.launch(null)
                } catch (_: Exception) {
                    pendingFolderCallback = null
                    callback(null)
                }
            }
        )
    }

    private fun attachBridgeDelegate(session: GeckoSession) {
        app.onEnhancerReady { ext ->
            runOnUiThread {
                if (isFinishing || isDestroyed || !session.isOpen) return@runOnUiThread
                session.webExtensionController.setMessageDelegate(
                    ext,
                    object : WebExtension.MessageDelegate {
                        override fun onConnect(port: WebExtension.Port) {
                            // Ignore subframe ports so an embedded iframe can't hijack the main bridge
                            if (!port.sender.isTopLevel) {
                                port.disconnect()
                                return
                            }
                            activeBridgePort = port
                            port.setDelegate(object : WebExtension.PortDelegate {
                                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                    handleBridgeReply(message)
                                }

                                override fun onDisconnect(port: WebExtension.Port) {
                                    if (activeBridgePort === port) {
                                        activeBridgePort = null
                                    }
                                }
                            })
                            sendBridgeComfortMode(prefs.comfortCssEnabled)
                            flushPendingSharedImagesIfReady()
                        }
                    },
                    AntigravityApp.BRIDGE_NAME
                )
            }
        }
    }

    private fun sendBridgeAction(action: String) {
        val port = activeBridgePort ?: return
        val payload = JSONObject().put("action", action)
        try {
            port.postMessage(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Bridge postMessage failed for $action", e)
        }
    }

    private fun sendBridgeComfortMode(enabled: Boolean) {
        val port = activeBridgePort ?: return
        val payload = JSONObject()
            .put("action", "setComfortMode")
            .put("enabled", enabled)
        try {
            port.postMessage(payload)
        } catch (_: Exception) {
        }
    }

    private fun handleBridgeReply(message: Any) {
        val json = message as? JSONObject
        val type = json?.optString("type", "") ?: ""
        when (type) {
            "ready" -> {
                sendBridgeComfortMode(prefs.comfortCssEnabled)
                flushPendingSharedImagesIfReady()
            }
            "attachResult" -> {
                val ok = json?.optBoolean("ok", false) == true
                val count = json?.optInt("count", 0) ?: 0
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val msg = when {
                        ok && count > 1 -> "$count imágenes adjuntadas al chat"
                        ok -> "Imagen adjuntada al chat"
                        else -> "No se pudo adjuntar aquí; toca el botón '+' del chat"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            else -> sendBridgeComfortMode(prefs.comfortCssEnabled)
        }
    }

    private fun launchCameraWithPermissionCheck() {
        val hasCamera = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCamera) {
            launchCameraCapture()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraCapture() {
        try {
            val (photoFile, contentUri) = UploadFileStaging.createCameraCaptureTarget(this)
            pendingCameraFile = photoFile
            takePictureLauncher.launch(contentUri)
        } catch (_: Exception) {
            pendingCameraFile = null
            val cb = pendingFileCallback
            pendingFileCallback = null
            cb?.invoke(emptyList())
            Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
            }
            else -> emptyList()
        }.filter { it.scheme.equals("content", ignoreCase = true) }.take(5)

        if (uris.isNotEmpty()) {
            injectImagesIntoChat(uris)
        }
    }

    private fun flushPendingSharedImagesIfReady() {
        val queued = pendingSharedImageUris
        if (queued.isEmpty()) return
        if (activeBridgePort == null || !SecurityPolicy.isStrictAntigravityOrigin(uiState.currentUrl)) return
        pendingSharedImageUris = emptyList()
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                injectImagesIntoChat(queued)
            }
        }, 450L)
    }

    private fun injectImagesIntoChat(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val port = activeBridgePort
        if (port == null || !SecurityPolicy.isStrictAntigravityOrigin(uiState.currentUrl)) {
            pendingSharedImageUris = uris
            Toast.makeText(this, "Esperando a que cargue el chat para adjuntar...", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(
            this,
            if (uris.size > 1) "Adjuntando ${uris.size} imágenes..." else "Adjuntando imagen...",
            Toast.LENGTH_SHORT
        ).show()

        val appCtx = applicationContext
        app.ioExecutor.execute {
            val encodedList = uris.mapNotNull { uri ->
                UploadFileStaging.encodeImageForBridge(appCtx, uri)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val livePort = activeBridgePort
                if (encodedList.isEmpty() || livePort == null) {
                    Toast.makeText(this, "No se pudo leer la imagen", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val filesJson = JSONArray()
                for (img in encodedList) {
                    filesJson.put(
                        JSONObject()
                            .put("name", img.name)
                            .put("mimeType", img.mimeType)
                            .put("base64", img.base64)
                    )
                }
                val payload = JSONObject()
                    .put("action", "attachImages")
                    .put("files", filesJson)
                try {
                    livePort.postMessage(payload)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to post attachImages to bridge", e)
                    Toast.makeText(this, "Error al enviar la imagen al chat", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openPopupSession(initialUrl: String): GeckoSession {
        closePopupSession()
        val settings = app.buildSessionSettings(desktopMode = false)
        val popup = GeckoSession(settings)
        // IMPORTANT: GeckoView's onNewSession requires an UNOPENED GeckoSession;
        // GeckoView calls popup.open(runtime, newSessionId) itself to preserve window.opener.
        configureSessionDelegates(popup, isPopup = true)
        popupSession = popup

        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                popupContainer.visibility = View.VISIBLE
                popupGeckoView.setSession(popup)
                uiState = uiState.copy(
                    popupUrl = initialUrl,
                    popupIsSecure = SecurityPolicy.isHttpsUrl(initialUrl)
                )
            }
        }
        return popup
    }

    private fun closePopupSession() {
        val current = popupSession ?: return
        popupSession = null
        current.navigationDelegate = null
        current.progressDelegate = null
        current.contentDelegate = null
        current.permissionDelegate = null
        current.promptDelegate = null

        runOnUiThread {
            popupGeckoView.releaseSession()
            popupContainer.visibility = View.GONE
            uiState = uiState.copy(popupUrl = null, popupIsSecure = false)
        }
        try {
            if (current.isOpen) {
                current.close()
            }
        } catch (_: Exception) {
        }
    }

    private fun recoverMainSession() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread

            val now = System.currentTimeMillis()
            while (crashRecoveryTimestamps.isNotEmpty() && now - crashRecoveryTimestamps.first() > 30_000L) {
                crashRecoveryTimestamps.removeFirst()
            }
            if (crashRecoveryTimestamps.size >= 3) {
                uiState = uiState.copy(
                    showColdSplash = false,
                    isLoading = false,
                    errorMessage = "El motor web se cerró varias veces seguidas. Pulsa Reintentar para abrir una sesión nueva."
                )
                return@runOnUiThread
            }
            crashRecoveryTimestamps.addLast(now)

            val resumeUrl = uiState.currentUrl.takeIf { SecurityPolicy.isHttpsUrl(it) } ?: prefs.homeUrl
            detachMainSessionDelegates()
            geckoView.releaseSession()
            val fresh = app.recreateMainSession()
            attachMainSession(fresh, loadIfNeeded = true, targetUrl = resumeUrl)
        }
    }

    private fun saveWebResponseToDownloads(response: WebResponse) {
        val contentDisposition = response.headers.entries
            .firstOrNull { it.key.equals("content-disposition", ignoreCase = true) }?.value
        val mimeType = response.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
            ?.substringBefore(";")
            ?.trim()
            ?.takeIf { it.contains("/") }
            ?: "application/octet-stream"
        val guessed = URLUtil.guessFileName(response.uri, contentDisposition, mimeType)
        val fileName = SecurityPolicy.sanitizeDownloadFileName(guessed)

        val inputStream = response.body
        if (inputStream == null) {
            if (SecurityPolicy.isAllowedExternalScheme(response.uri)) {
                openExternally(response.uri)
            }
            return
        }

        val appCtx = applicationContext
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, "Descargando $fileName...", Toast.LENGTH_SHORT).show()
            }
        }

        app.ioExecutor.execute {
            var pendingMediaUri: Uri? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = appCtx.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Cannot create MediaStore download entry")
                    pendingMediaUri = uri

                    val outStream = resolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Cannot open MediaStore output stream")
                    outStream.use { out ->
                        copyBounded(inputStream, out, MAX_DOWNLOAD_BYTES)
                    }

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    pendingMediaUri = null
                } else {
                    // API 26-28: use app-scoped external downloads dir (no WRITE_EXTERNAL_STORAGE crash)
                    val downloadsDir = appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: appCtx.filesDir
                    downloadsDir.mkdirs()
                    val outFile = uniqueFile(downloadsDir, fileName)
                    FileOutputStream(outFile).use { out ->
                        copyBounded(inputStream, out, MAX_DOWNLOAD_BYTES)
                    }
                }
                mainHandler.post {
                    Toast.makeText(appCtx, "Guardado en Descargas: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                if (pendingMediaUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        appCtx.contentResolver.delete(pendingMediaUri, null, null)
                    } catch (_: Exception) {
                    }
                }
                mainHandler.post {
                    Toast.makeText(appCtx, "Error al descargar $fileName", Toast.LENGTH_SHORT).show()
                }
            } finally {
                try {
                    inputStream.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long
    ) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw IllegalStateException("Download exceeds maximum allowed size")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var counter = 1
        while (candidate.exists() && counter < 1000) {
            candidate = File(dir, "${base}_$counter$ext")
            counter++
        }
        return candidate
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    uiState.showAttachChooser -> {
                        uiState = uiState.copy(showAttachChooser = false)
                        val cb = pendingFileCallback
                        pendingFileCallback = null
                        cb?.invoke(emptyList())
                    }
                    uiState.showControlSheet -> {
                        uiState = uiState.copy(showControlSheet = false)
                    }
                    popupSession != null -> {
                        closePopupSession()
                    }
                    uiState.canGoBack -> {
                        mainSession.goBack()
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressedMs < 2000L) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        } else {
                            lastBackPressedMs = now
                            Toast.makeText(
                                this@MainActivity,
                                "Pulsa atrás otra vez para salir",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyKeepAlive(enabled: Boolean) {
        if (enabled) {
            val started = KeepAliveService.start(this)
            prefs.keepAliveEnabled = started
            uiState = uiState.copy(keepAliveEnabled = started)
            val msg = if (started) {
                "Sesión protegida en segundo plano"
            } else {
                "Android no permitió iniciar el servicio en 2º plano ahora"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            prefs.keepAliveEnabled = false
            uiState = uiState.copy(keepAliveEnabled = false)
            KeepAliveService.stop(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationHelper.hasNotificationPermission(this)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleIntentExtras(intent: Intent?) {
        val tag = intent?.getStringExtra(NotificationHelper.EXTRA_WEB_NOTIFICATION_TAG)
        if (!tag.isNullOrBlank()) {
            app.handleNotificationClick(tag)
        }
    }

    private fun extractIncomingUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return SecurityPolicy.validateIncomingDeepLink(intent.dataString)
    }

    private fun checkForUpdates(silent: Boolean) {
        if (uiState.checkingUpdate || uiState.downloadingUpdate) return
        if (!silent) {
            uiState = uiState.copy(checkingUpdate = true, updateStatusMessage = null)
        }
        lifecycleScope.launch {
            val result = app.updater.checkForUpdate()
            if (isFinishing || isDestroyed) return@launch
            result.fold(
                onSuccess = { info ->
                    app.cachedUpdateInfo = info
                    uiState = uiState.copy(
                        checkingUpdate = false,
                        updateInfo = info,
                        updateStatusMessage = null
                    )
                },
                onFailure = { err ->
                    uiState = uiState.copy(
                        checkingUpdate = false,
                        updateStatusMessage = if (silent) null else (err.message ?: "No se pudo comprobar actualizaciones.")
                    )
                }
            )
        }
    }

    private fun downloadAndInstallUpdate() {
        val info = uiState.updateInfo ?: return
        if (uiState.downloadingUpdate) return
        uiState = uiState.copy(
            downloadingUpdate = true,
            downloadProgress = 0f,
            updateStatusMessage = null
        )
        lifecycleScope.launch {
            val result = app.updater.downloadApk(info.downloadUrl) { progress ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        uiState = uiState.copy(downloadProgress = progress)
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            result.fold(
                onSuccess = { apkFile ->
                    uiState = uiState.copy(downloadingUpdate = false, downloadProgress = 1f)
                    val launched = app.updater.launchApkInstaller(apkFile)
                    if (!launched) {
                        uiState = uiState.copy(
                            updateStatusMessage = "Activa 'Instalar aplicaciones desconocidas' y vuelve a pulsar 'Descargar e instalar'."
                        )
                        Toast.makeText(
                            this@MainActivity,
                            "Concede permiso para instalar APKs y vuelve a pulsar",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onFailure = { err ->
                    uiState = uiState.copy(
                        downloadingUpdate = false,
                        downloadProgress = 0f,
                        updateStatusMessage = err.message ?: "Error al descargar la actualización."
                    )
                }
            )
        }
    }

    private fun openExternally(url: String) {
        if (!SecurityPolicy.isAllowedExternalScheme(url)) {
            Log.w(TAG, "Blocked external launch for disallowed scheme")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No se encontró ninguna app para abrir el enlace", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MAX_DOWNLOAD_BYTES = 150L * 1024L * 1024L
    }
}
