package com.omegax.antigravityremote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val AgBg = Color(0xFF1F1F1F)
private val AgSurface = Color(0xFF25262B)
private val AgSurfaceElevated = Color(0xFF2E3038)
private val AgBorder = Color(0xFF3A3D47)
private val AgAccent = Color(0xFF8AB4F8)
private val AgViolet = Color(0xFFC58AF9)
private val AgEmerald = Color(0xFF34D399)
private val AgWarning = Color(0xFFFBBF24)
private val AgDanger = Color(0xFFF28B82)
private val AgText = Color(0xFFE8EAED)
private val AgTextMuted = Color(0xFF9AA0A6)

private val AgColorScheme = darkColorScheme(
    primary = AgAccent,
    onPrimary = Color(0xFF101318),
    secondary = AgViolet,
    background = AgBg,
    surface = AgSurface,
    surfaceVariant = AgSurfaceElevated,
    onBackground = AgText,
    onSurface = AgText,
    onSurfaceVariant = AgTextMuted,
    error = AgDanger
)

data class RemoteUiState(
    val currentUrl: String = AppPrefs.DEFAULT_HOME_URL,
    val pageTitle: String = "Antigravity Remote",
    val progress: Int = 0,
    val isLoading: Boolean = true,
    val showColdSplash: Boolean = true,
    val canGoBack: Boolean = false,
    val isKeyboardVisible: Boolean = false,
    val errorMessage: String? = null,
    val popupUrl: String? = null,
    val popupIsSecure: Boolean = false,
    val showControlSheet: Boolean = false,
    val desktopMode: Boolean = false,
    val keepScreenOn: Boolean = false,
    val keepAliveEnabled: Boolean = false,
    val showBottomDock: Boolean = false,
    val comfortCssEnabled: Boolean = true,
    val openExternalInBrowser: Boolean = true,
    val fontScalePercent: Int = 100,
    val pillOffsetYFraction: Float = 0.72f,
    val pillOnRightSide: Boolean = true,
    val updateInfo: AppUpdateInfo? = null,
    val checkingUpdate: Boolean = false,
    val downloadingUpdate: Boolean = false,
    val downloadProgress: Float = 0f,
    val updateStatusMessage: String? = null
)

data class RemoteActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onHome: () -> Unit,
    val onScrollToBottom: () -> Unit,
    val onFocusInput: () -> Unit,
    val onOpenSheet: () -> Unit,
    val onDismissSheet: () -> Unit,
    val onToggleDesktopMode: (Boolean) -> Unit,
    val onToggleKeepScreenOn: (Boolean) -> Unit,
    val onToggleKeepAlive: (Boolean) -> Unit,
    val onToggleBottomDock: (Boolean) -> Unit,
    val onToggleComfortCss: (Boolean) -> Unit,
    val onToggleExternalLinks: (Boolean) -> Unit,
    val onSelectFontScale: (Int) -> Unit,
    val onCopyUrl: () -> Unit,
    val onOpenExternal: () -> Unit,
    val onClearCache: () -> Unit,
    val onSignOutAndClearAll: () -> Unit,
    val onPillPositionChanged: (yFraction: Float, onRight: Boolean) -> Unit,
    val onCheckUpdate: () -> Unit,
    val onDownloadUpdate: () -> Unit,
    val onOpenReleases: () -> Unit
)

@Composable
fun AntigravityTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AgColorScheme, content = content)
}

@Composable
fun MainOverlayContent(
    state: RemoteUiState,
    actions: RemoteActions
) {
    AntigravityTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Ultra-thin top loading indicator
            if (state.isLoading && !state.showColdSplash && state.progress in 1..99) {
                val animatedProgress by animateFloatAsState(
                    targetValue = state.progress / 100f,
                    label = "loadProgress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .align(Alignment.TopCenter),
                    color = AgAccent,
                    trackColor = Color.Transparent
                )
            }

            // 2. Draggable floating quick pill (hidden when bottom dock is on, keyboard open, or modal active)
            if (!state.showBottomDock &&
                !state.isKeyboardVisible &&
                !state.showColdSplash &&
                state.errorMessage == null &&
                state.popupUrl == null
            ) {
                FloatingQuickPill(
                    state = state,
                    actions = actions
                )
            }

            // 3. Offline / Load Error Overlay
            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ErrorOverlay(
                    message = state.errorMessage ?: "No se pudo conectar con Antigravity.",
                    onRetry = actions.onReload,
                    onGoHome = actions.onHome
                )
            }

            // 4. Cold-start splash screen (eliminates white flash & raw initial paint)
            AnimatedVisibility(
                visible = state.showColdSplash,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ColdStartSplash()
            }

            // 5. Full Quick Controls Bottom Sheet
            if (state.showControlSheet) {
                QuickControlSheet(state = state, actions = actions)
            }
        }
    }
}

@Composable
fun PopupHeaderOverlay(
    url: String,
    isSecure: Boolean,
    onClose: () -> Unit
) {
    val host = remember(url) { SecurityPolicy.displayHost(url) }
    val verifiedHttps = isSecure && SecurityPolicy.isHttpsUrl(url)

    AntigravityTheme {
        Surface(
            color = AgSurface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (verifiedHttps) Icons.Rounded.Lock else Icons.Rounded.Warning,
                        contentDescription = if (verifiedHttps) "Conexión segura" else "Conexión no verificada",
                        tint = if (verifiedHttps) AgEmerald else AgWarning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = host,
                        color = AgText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cerrar ventana",
                            tint = AgText
                        )
                    }
                }
                HorizontalDivider(color = AgBorder, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
fun BottomQuickDock(
    state: RemoteUiState,
    actions: RemoteActions
) {
    if (!state.showBottomDock || state.popupUrl != null || state.isKeyboardVisible) return
    AntigravityTheme {
        Surface(
            color = Color(0xFF18191D),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                HorizontalDivider(color = AgBorder.copy(alpha = 0.65f), thickness = 0.6.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        label = "Atrás",
                        enabled = state.canGoBack,
                        onClick = actions.onBack
                    )
                    DockIconButton(
                        icon = Icons.Rounded.Home,
                        label = "Inicio",
                        onClick = actions.onHome
                    )
                    DockIconButton(
                        icon = Icons.Rounded.ArrowDownward,
                        label = "Final",
                        onClick = actions.onScrollToBottom
                    )
                    DockIconButton(
                        icon = Icons.Rounded.Refresh,
                        label = "Recargar",
                        onClick = actions.onReload
                    )
                    Box {
                        DockIconButton(
                            icon = Icons.Rounded.Tune,
                            label = "Ajustes",
                            tint = AgAccent,
                            onClick = actions.onOpenSheet
                        )
                        val hasUpdate = state.updateInfo?.updateAvailable == true
                        if (hasUpdate || state.keepAliveEnabled) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-6).dp, y = 6.dp)
                                    .clip(CircleShape)
                                    .background(if (hasUpdate) AgViolet else AgEmerald)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = AgText,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else AgTextMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun FloatingQuickPill(
    state: RemoteUiState,
    actions: RemoteActions
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.showControlSheet) {
        if (state.showControlSheet) expanded = false
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val pillSizePx = with(density) { 42.dp.toPx() }
        val edgePaddingPx = with(density) { 10.dp.toPx() }
        val maxValidY = (maxHeightPx - pillSizePx - edgePaddingPx).coerceAtLeast(edgePaddingPx)

        var onRight by remember(state.pillOnRightSide) { mutableStateOf(state.pillOnRightSide) }
        var offsetY by remember(state.pillOffsetYFraction, maxHeightPx) {
            mutableFloatStateOf((state.pillOffsetYFraction * maxHeightPx).coerceIn(edgePaddingPx, maxValidY))
        }
        var dragXAccum by remember { mutableFloatStateOf(0f) }

        val alignment = if (onRight) Alignment.TopEnd else Alignment.TopStart

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .align(alignment)
                .offset {
                    IntOffset(
                        x = if (onRight) -edgePaddingPx.roundToInt() else edgePaddingPx.roundToInt(),
                        y = offsetY.roundToInt()
                    )
                }
        ) {
            if (onRight) {
                ExpandedQuickActions(
                    visible = expanded,
                    state = state,
                    actions = actions,
                    onCollapse = { expanded = false }
                )
            }

            // Main draggable handle pill
            Surface(
                shape = CircleShape,
                color = Color(0xD9202229),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (expanded) AgAccent.copy(alpha = 0.85f) else AgBorder.copy(alpha = 0.8f)
                ),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(42.dp)
                    .pointerInput(maxHeightPx, maxWidthPx) {
                        detectDragGestures(
                            onDragStart = {
                                dragXAccum = 0f
                                expanded = false
                            },
                            onDragEnd = {
                                val nextRight = when {
                                    dragXAccum > maxWidthPx * 0.22f -> true
                                    dragXAccum < -maxWidthPx * 0.22f -> false
                                    else -> onRight
                                }
                                onRight = nextRight
                                val frac = if (maxHeightPx > 0f) {
                                    (offsetY / maxHeightPx).coerceIn(AppPrefs.PILL_Y_MIN, AppPrefs.PILL_Y_MAX)
                                } else 0.72f
                                actions.onPillPositionChanged(frac, nextRight)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragXAccum += dragAmount.x
                                offsetY = (offsetY + dragAmount.y).coerceIn(edgePaddingPx, maxValidY)
                            }
                        )
                    }
                    .clickable { expanded = !expanded }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.Close else Icons.Rounded.Tune,
                        contentDescription = "Controles rápidos",
                        tint = if (expanded) AgAccent else AgText,
                        modifier = Modifier.size(19.dp)
                    )
                    val hasUpdate = state.updateInfo?.updateAvailable == true
                    if ((hasUpdate || state.keepAliveEnabled) && !expanded) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = (-7).dp, y = 7.dp)
                                .clip(CircleShape)
                                .background(if (hasUpdate) AgViolet else AgEmerald)
                        )
                    }
                }
            }

            if (!onRight) {
                ExpandedQuickActions(
                    visible = expanded,
                    state = state,
                    actions = actions,
                    onCollapse = { expanded = false }
                )
            }
        }
    }
}

@Composable
private fun ExpandedQuickActions(
    visible: Boolean,
    state: RemoteUiState,
    actions: RemoteActions,
    onCollapse: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.88f),
        exit = fadeOut() + scaleOut(targetScale = 0.88f)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xEB22242C),
            border = BorderStroke(1.dp, AgBorder),
            shadowElevation = 8.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                MiniActionBtn(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    desc = "Atrás",
                    enabled = state.canGoBack
                ) {
                    onCollapse()
                    actions.onBack()
                }
                MiniActionBtn(
                    icon = Icons.Rounded.ArrowDownward,
                    desc = "Ir al final"
                ) {
                    onCollapse()
                    actions.onScrollToBottom()
                }
                MiniActionBtn(
                    icon = Icons.Rounded.Refresh,
                    desc = "Recargar"
                ) {
                    onCollapse()
                    actions.onReload()
                }
                MiniActionBtn(
                    icon = Icons.Rounded.Tune,
                    desc = "Más ajustes",
                    tint = AgAccent
                ) {
                    onCollapse()
                    actions.onOpenSheet()
                }
            }
        }
    }
}

@Composable
private fun MiniActionBtn(
    icon: ImageVector,
    desc: String,
    enabled: Boolean = true,
    tint: Color = AgText,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(38.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (enabled) tint else AgTextMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun ColdStartSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AgBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF1F273B), Color(0xFF281F36))
                        )
                    )
                    .border(1.dp, AgAccent.copy(alpha = 0.35f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "A",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = AgAccent
                )
            }

            Text(
                text = "Antigravity Remote",
                color = AgText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )

            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.4.dp,
                color = AgAccent
            )

            Text(
                text = "Conectando con tus agentes...",
                color = AgTextMuted,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onGoHome: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AgBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = AgSurface,
            border = BorderStroke(1.dp, AgBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WifiOff,
                    contentDescription = null,
                    tint = AgDanger,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = "No se pudo cargar la página",
                    color = AgText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = message,
                    color = AgTextMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    OutlinedButton(
                        onClick = onGoHome,
                        border = BorderStroke(1.dp, AgBorder)
                    ) {
                        Text("Ir al inicio", color = AgText)
                    }
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
                    ) {
                        Text("Reintentar", color = Color(0xFF101318), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickControlSheet(
    state: RemoteUiState,
    actions: RemoteActions
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSignOutConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = actions.onDismissSheet,
        sheetState = sheetState,
        containerColor = AgSurface,
        contentColor = AgText
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Antigravity Remote",
                        color = AgText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = SecurityPolicy.displayHost(state.currentUrl),
                        color = AgTextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.keepAliveEnabled) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AgEmerald.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "2º plano ON",
                            color = AgEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Quick Actions Row
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                QuickActionChip(Icons.Rounded.Home, "Inicio") {
                    actions.onDismissSheet()
                    actions.onHome()
                }
                QuickActionChip(Icons.Rounded.Refresh, "Recargar") {
                    actions.onDismissSheet()
                    actions.onReload()
                }
                QuickActionChip(Icons.Rounded.ArrowDownward, "Ir al final") {
                    actions.onDismissSheet()
                    actions.onScrollToBottom()
                }
                QuickActionChip(Icons.Rounded.Keyboard, "Escribir") {
                    actions.onDismissSheet()
                    actions.onFocusInput()
                }
                QuickActionChip(Icons.Rounded.ContentCopy, "Copiar URL") {
                    actions.onCopyUrl()
                }
                QuickActionChip(Icons.AutoMirrored.Rounded.OpenInNew, "Navegador") {
                    actions.onDismissSheet()
                    actions.onOpenExternal()
                }
            }

            HorizontalDivider(color = AgBorder.copy(alpha = 0.6f))

            // Font Scale Selector (FlowRow prevents horizontal clipping on narrow screens)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Tamaño de texto y código (${state.fontScalePercent}%)",
                    color = AgText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(85, 95, 100, 110, 120).forEach { pct ->
                        val selected = state.fontScalePercent == pct
                        FilterChip(
                            selected = selected,
                            onClick = { actions.onSelectFontScale(pct) },
                            label = {
                                Text(
                                    text = "$pct%",
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AgAccent.copy(alpha = 0.22f),
                                selectedLabelColor = AgAccent,
                                labelColor = AgTextMuted
                            )
                        )
                    }
                }
            }

            HorizontalDivider(color = AgBorder.copy(alpha = 0.6f))

            // Toggles — Session & Comfort
            SettingToggleRow(
                icon = Icons.Rounded.NotificationsActive,
                title = "Mantener sesión viva en 2º plano",
                subtitle = "Evita que Android cierre la conexión al cambiar de app y mejora las alertas",
                checked = state.keepAliveEnabled,
                onCheckedChange = actions.onToggleKeepAlive
            )

            SettingToggleRow(
                icon = Icons.Rounded.DesktopWindows,
                title = "Modo Escritorio",
                subtitle = "Útil para ver diffs anchos o la barra lateral completa",
                checked = state.desktopMode,
                onCheckedChange = actions.onToggleDesktopMode
            )

            SettingToggleRow(
                icon = Icons.Rounded.LightMode,
                title = "Mantener pantalla encendida",
                subtitle = "No apaga la pantalla mientras miras trabajar al agente",
                checked = state.keepScreenOn,
                onCheckedChange = actions.onToggleKeepScreenOn
            )

            SettingToggleRow(
                icon = Icons.Rounded.ViewAgenda,
                title = "Barra inferior fija",
                subtitle = "Cambia el botón flotante por una barra fija abajo",
                checked = state.showBottomDock,
                onCheckedChange = actions.onToggleBottomDock
            )

            SettingToggleRow(
                icon = Icons.Rounded.TouchApp,
                title = "Mejoras táctiles (CSS)",
                subtitle = "Quita el rebote y suaviza el scroll de bloques de código",
                checked = state.comfortCssEnabled,
                onCheckedChange = actions.onToggleComfortCss
            )

            SettingToggleRow(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                title = "Enlaces externos en el navegador",
                subtitle = "Abre GitHub/docs fuera; Google y Antigravity siempre dentro",
                checked = state.openExternalInBrowser,
                onCheckedChange = actions.onToggleExternalLinks
            )

            HorizontalDivider(color = AgBorder.copy(alpha = 0.6f))

            // In-app GitHub Releases Updater Card
            UpdateAppCard(state = state, actions = actions)

            HorizontalDivider(color = AgBorder.copy(alpha = 0.6f))

            // Maintenance actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = actions.onClearCache,
                    border = BorderStroke(1.dp, AgBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        tint = AgText,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Limpiar caché", color = AgText, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = { showSignOutConfirm = true },
                    border = BorderStroke(1.dp, AgDanger.copy(alpha = 0.55f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = null,
                        tint = AgDanger,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cerrar sesión", color = AgDanger, fontSize = 13.sp)
                }
            }
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            containerColor = AgSurfaceElevated,
            title = { Text("¿Cerrar sesión?", color = AgText) },
            text = {
                Text(
                    "Se borrarán las cookies y datos de sesión locales para poder iniciar sesión con otra cuenta de Google.",
                    color = AgTextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutConfirm = false
                        actions.onDismissSheet()
                        actions.onSignOutAndClearAll()
                    }
                ) {
                    Text("Sí, cerrar sesión", color = AgDanger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text("Cancelar", color = AgText)
                }
            }
        )
    }
}

@Composable
private fun UpdateAppCard(
    state: RemoteUiState,
    actions: RemoteActions
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AgSurfaceElevated,
        border = BorderStroke(
            width = 1.dp,
            color = if (state.updateInfo?.updateAvailable == true) AgViolet.copy(alpha = 0.65f) else AgBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Actualizar Antigravity Remote",
                        color = AgAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Versión instalada: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        color = AgTextMuted,
                        fontSize = 12.sp
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = if (state.updateInfo?.updateAvailable == true) AgViolet else AgAccent,
                    modifier = Modifier.size(22.dp)
                )
            }

            val info = state.updateInfo
            if (info != null) {
                val sizeMb = info.apkSizeBytes?.let {
                    " · %.1f MB".format(it / (1024.0 * 1024.0))
                } ?: ""
                Text(
                    text = if (info.updateAvailable) {
                        "Nueva versión disponible: v${info.latestVersionName}$sizeMb"
                    } else {
                        "Ya tienes la última versión (v${info.latestVersionName})."
                    },
                    color = if (info.updateAvailable) AgViolet else AgEmerald,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (info.updateAvailable && info.releaseNotes.isNotBlank()) {
                    Text(
                        text = info.releaseNotes,
                        color = AgTextMuted,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!state.updateStatusMessage.isNullOrBlank()) {
                Text(
                    text = state.updateStatusMessage,
                    color = AgTextMuted,
                    fontSize = 12.sp
                )
            }

            if (state.downloadingUpdate) {
                LinearProgressIndicator(
                    progress = { state.downloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = AgViolet,
                    trackColor = AgBorder
                )
                Text(
                    text = "Descargando y verificando firma... ${(state.downloadProgress * 100).toInt()}%",
                    color = AgAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = actions.onCheckUpdate,
                    enabled = !state.checkingUpdate && !state.downloadingUpdate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AgAccent,
                        contentColor = Color(0xFF101318)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (state.checkingUpdate) "Buscando..." else "Buscar actualización",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (info != null) {
                    Button(
                        onClick = actions.onDownloadUpdate,
                        enabled = !state.downloadingUpdate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (info.updateAvailable) AgViolet else AgSurface,
                            contentColor = if (info.updateAvailable) Color(0xFF101318) else AgText
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (info.updateAvailable) "Descargar e instalar" else "Reinstalar APK",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            TextButton(
                onClick = actions.onOpenReleases,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = AgTextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Ver releases en GitHub",
                    color = AgTextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AgSurfaceElevated,
        border = BorderStroke(1.dp, AgBorder.copy(alpha = 0.7f)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AgAccent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = AgText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) AgAccent else AgTextMuted,
            modifier = Modifier.size(21.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = AgText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = AgTextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF101318),
                checkedTrackColor = AgAccent,
                uncheckedThumbColor = AgTextMuted,
                uncheckedTrackColor = AgSurfaceElevated
            )
        )
    }
}
