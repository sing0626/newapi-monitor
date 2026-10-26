package com.newapimonitor.ui

import com.newapimonitor.ui.theme.tr
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.MainActivity
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.AppButtonKind
import com.newapimonitor.ui.components.DemoBadge
import com.newapimonitor.ui.components.StatusDot
import com.newapimonitor.ui.dashboard.DashboardScreen
import com.newapimonitor.ui.dashboard.DashboardViewModel
import com.newapimonitor.ui.logs.LogsScreen
import com.newapimonitor.ui.logs.LogsViewModel
import com.newapimonitor.ui.report.ReportScreen
import com.newapimonitor.ui.pricing.PricingScreen
import com.newapimonitor.ui.settings.SettingsScreen
import com.newapimonitor.ui.settings.SettingsViewModel
import com.newapimonitor.ui.search.GlobalSearchDialog
import com.newapimonitor.ui.speedtest.SpeedTestScreen
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.Accent2
import com.newapimonitor.ui.theme.BgColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.DimColor
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.OkGreen
import com.newapimonitor.ui.theme.TopBarColor
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.tokens.TokensScreen
import com.newapimonitor.ui.tokens.TokensViewModel
import com.newapimonitor.ui.theme.WarnAmber
import com.kyant.backdrop.*
import com.kyant.backdrop.backdrops.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val TAB_DASHBOARD = "dashboard"
const val TAB_LOGS = "logs"
const val TAB_TOKENS = "tokens"
const val TAB_SETTINGS = "settings"
const val TAB_SPEEDTEST = "speedtest"
const val TAB_REPORT = "report"
const val TAB_PRICING = "pricing"
const val TAB_BILLING = "billing"
const val TAB_ADMIN = "admin"
const val TAB_MONITOR = "monitor"
const val TAB_INSTANCE = "instance"
const val TAB_STREAM = "stream"
const val TAB_ALERTS = "alerts"
const val TAB_MONTHLY = "monthly"

/** App 根：頂欄（品牌＋實例快速切換＋演示徽章）＋ 四 tab 內容 ＋ 底部 NavigationBar ＋ 生物辨識鎖定遮罩 */
@Composable
fun AppRoot(
    repo: NewApiRepository,
    isLocked: Boolean = false,
    onRequestUnlock: () -> Unit = {},
) {
    if (isLocked) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BrandLogo(Modifier.size(64.dp))
                Text("NewAPI Monitor 已鎖定", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TxtColor)
                Text("請進行生物辨識或輸入密碼以解鎖", fontSize = 13.sp, color = MutedColor)
                Spacer(Modifier.height(8.dp))
                AppButton("點擊解鎖", onRequestUnlock, kind = AppButtonKind.PRIMARY)
            }
        }
        return
    }

    val settings by repo.store.settings.collectAsState()
    val instances by repo.store.instances.collectAsState()
    var tab by rememberSaveable {
        // 冷啟動深入連結（動態捷徑／QS 磁貼）：首組合讀一次 pending tab，用完即清
        mutableStateOf(MainActivity.pendingTab.also { MainActivity.pendingTab = null } ?: TAB_DASHBOARD)
    }
    var returnTab by rememberSaveable { mutableStateOf(TAB_DASHBOARD) }
    val secondary = tab !in listOf(TAB_DASHBOARD, TAB_LOGS, TAB_TOKENS, TAB_SETTINGS)
    val monitorVm: com.newapimonitor.ui.monitor.MonitorViewModel = viewModel(initializer = { com.newapimonitor.ui.monitor.MonitorViewModel(repo) })
    val monitorState by monitorVm.state.collectAsState()
    fun openPage(target: String) { returnTab = tab; tab = target }
    fun goBack() {
        if (tab in listOf(TAB_REPORT, TAB_MONITOR) && monitorState.drilldown != null) monitorVm.closeDetail()
        else tab = returnTab.also { returnTab = TAB_DASHBOARD }
    }
    BackHandler(enabled = secondary) { goBack() }
    var searchDialogOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 熱啟動深入連結（onNewIntent 設定 MainActivity.pendingTab）：AppRoot 已組合緊就即刻跟跳
    LaunchedEffect(Unit) {
        snapshotFlow { MainActivity.pendingTab }.collect { target ->
            if (target != null) {
                returnTab = TAB_DASHBOARD
                tab = target
                MainActivity.pendingTab = null
            }
        }
    }

    // Existing activity-scoped ViewModel registrations; lifecycle behavior is unchanged here.
    val dashVm: DashboardViewModel = viewModel(initializer = { DashboardViewModel(repo) })
    val logsVm: LogsViewModel = viewModel(initializer = { LogsViewModel(repo) })
    val tokensVm: TokensViewModel = viewModel(initializer = { TokensViewModel(repo) })
    val settingsVm: SettingsViewModel = viewModel(initializer = { SettingsViewModel(repo) })

    // 內容 export 做 layer，玻璃 bar 用佢做 backdrop 取樣（vibrancy）。
    val backdrop = rememberAppBackdrop()

    if (searchDialogOpen) {
        GlobalSearchDialog(
            repo = repo,
            onDismiss = { searchDialogOpen = false },
            onNavigateTab = { targetTab ->
                tab = targetTab
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                AppTopBar(
                    title = when (tab) {
                        TAB_LOGS -> tr("日誌")
                        TAB_TOKENS -> tr("令牌")
                        TAB_SETTINGS -> tr("設定")
                        TAB_SPEEDTEST -> tr("模型探針")
                        TAB_REPORT, TAB_MONITOR -> tr("消費報告")
                        TAB_STREAM -> tr("模型測速")
                        TAB_ALERTS -> tr("通知")
                        TAB_MONTHLY -> tr("月度報告")
                        TAB_INSTANCE -> tr("實例詳情")
                        TAB_BILLING -> tr("帳戶與訂閱")
                        TAB_ADMIN -> tr("渠道管理")
                        TAB_PRICING -> tr("價格與分組")
                        else -> tr("總覽")
                    },
                    allSources = tab == TAB_DASHBOARD,
                    onBack = if (secondary) ({ goBack() }) else null,
                    showSource = tab in listOf(TAB_DASHBOARD, TAB_LOGS, TAB_TOKENS, TAB_SETTINGS, TAB_REPORT, TAB_MONITOR, TAB_INSTANCE, TAB_MONTHLY),
                    onNotifications = if (tab == TAB_DASHBOARD) ({ openPage(TAB_ALERTS) }) else null,
                    demo = settings.demo,
                    instances = instances,
                    activeId = settings.activeId,
                    onPickInstance = { id ->
                        scope.launch {
                            repo.store.setDemo(false)
                            repo.store.setActive(id)
                            if (tab == TAB_DASHBOARD) openPage(TAB_INSTANCE)
                        }
                    },
                    onPickDemo = { scope.launch { repo.store.setDemo(true) } },
                    onPickAll = { tab = TAB_DASHBOARD },
                    onOpenSearch = { searchDialogOpen = true },
                )
            },
            bottomBar = {
                if (!secondary) GlassBottomBar(current = tab, onSelect = { tab = it }, backdrop = backdrop)
                else Spacer(Modifier.navigationBarsPadding())
            },
        ) { padding ->
            Box(
                Modifier
                    // Draw behind the overlay; each scroll container reserves its measured bottom clearance.
                    .padding(top = padding.calculateTopPadding())
                    .fillMaxSize()
                    .imePadding()
                    .layerBackdrop(backdrop)
            ) {
                // 版面收窄至 560dp（對應 .view-root max-width）
                Box(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxSize()
                        .align(Alignment.TopCenter)
                ) {
                    when (tab) {
                        // 即時切換、冇 cross-fade——玻璃 bar 嘅 snap 同頁面切換要同步（skill pitfall #13）。
                        TAB_LOGS -> LogsScreen(repo = repo, onNavigateSettings = { tab = TAB_SETTINGS }, bottomContentPadding = padding.calculateBottomPadding())
                        TAB_TOKENS -> TokensScreen(repo = repo, onNavigateSettings = { tab = TAB_SETTINGS }, bottomContentPadding = padding.calculateBottomPadding())
                        TAB_SETTINGS -> SettingsScreen(
                            repo = repo,
                            onSetDemo = { v -> scope.launch { repo.store.setDemo(v) } },
                            onNavigateSpeedTest = { openPage(TAB_SPEEDTEST) },
                            onNavigatePricing = { openPage(TAB_PRICING) },
                            onNavigateBilling = { openPage(TAB_BILLING) },
                            onNavigateAdmin = { openPage(TAB_ADMIN) },
                            onNavigateMonitor = { openPage(TAB_REPORT) },
                            bottomContentPadding = padding.calculateBottomPadding(),
                        )
                        TAB_SPEEDTEST -> SpeedTestScreen(
                            repo = repo,
                            onNavigateBack = { goBack() },
                            bottomContentPadding = padding.calculateBottomPadding(),
                        )
                        TAB_PRICING -> PricingScreen(
                            repo = repo,
                            onNavigateBack = { goBack() },
                            bottomContentPadding = padding.calculateBottomPadding(),
                        )
                        TAB_BILLING -> com.newapimonitor.ui.billing.BillingScreen(
                            repo = repo,
                            onNavigateBack = { goBack() },
                            bottomContentPadding = padding.calculateBottomPadding(),
                        )
                        TAB_ADMIN -> com.newapimonitor.ui.admin.AdminHubScreen(
                            repo = repo,
                            onNavigateBack = { goBack() },
                            bottomContentPadding = padding.calculateBottomPadding(),
                        )
                        TAB_REPORT, TAB_MONITOR, TAB_STREAM, TAB_ALERTS -> com.newapimonitor.ui.monitor.MonitorScreen(
                            repo = repo,
                            onNavigateBack = { goBack() },
                            bottomContentPadding = padding.calculateBottomPadding(),
                            section = when (tab) { TAB_STREAM -> 2; TAB_ALERTS -> 3; else -> 1 },
                        )
                        TAB_MONTHLY -> ReportScreen(repo, onNavigateBack = { goBack() }, bottomContentPadding = padding.calculateBottomPadding())
                        TAB_INSTANCE -> DashboardScreen(
                            repo = repo,
                            onNavigateSettings = { tab = TAB_SETTINGS },
                            bottomContentPadding = padding.calculateBottomPadding(),
                            onNavigateReport = { openPage(TAB_MONTHLY) },
                        )
                        else -> com.newapimonitor.ui.monitor.OverviewScreen(
                            repo = repo,
                            onOpenInstance = { id -> scope.launch {
                                if (id != "demo") { repo.store.setDemo(false); repo.store.setActive(id) }
                                openPage(TAB_INSTANCE)
                            } },
                            onOpenReport = { openPage(TAB_REPORT) },
                            onOpenProbe = { openPage(TAB_STREAM) },
                            onOpenAlerts = { openPage(TAB_ALERTS) },
                            onOpenSettings = { tab = TAB_SETTINGS },
                            bottomContentPadding = padding.calculateBottomPadding(),
                        )
                    }
                }
            }
        }

    }
}

/** 頂欄（.topbar）：品牌 logo + 大字頁title + 實例快速切換 chip + 搜尋圓角方格 + 演示徽章 */
@Composable
private fun AppTopBar(
    title: String,
    demo: Boolean,
    instances: List<com.newapimonitor.domain.Instance>,
    activeId: String?,
    onPickInstance: (String) -> Unit,
    onPickDemo: () -> Unit,
    onOpenSearch: () -> Unit = {},
    allSources: Boolean = false,
    onPickAll: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    showSource: Boolean = true,
    onNotifications: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // 方案 H：只轉底色 — dark/oled 沿用深色半透明；light 用淺色半透明（避免淺色 app 一條黑橫帶）
            .background(TopBarColor)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp).semantics { contentDescription = "返回上一頁" }) {
                Text("‹", fontSize = 32.sp, color = TxtColor)
            } else { BrandLogo(Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)) }
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.01).em,
                color = TxtColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showSource) InstanceSwitcher(demo, instances, activeId, onPickInstance, onPickDemo, allSources, onPickAll)
            Spacer(Modifier.width(8.dp))
            if (onNotifications != null) IconButton(onClick = onNotifications,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "開啟通知" }) {
                Canvas(Modifier.size(21.dp)) {
                    val s = size.width / 24f
                    val bell = Path().apply {
                        moveTo(4*s,18*s); lineTo(6*s,15*s); lineTo(6*s,10*s)
                        cubicTo(6*s,2*s,18*s,2*s,18*s,10*s)
                        lineTo(18*s,15*s); lineTo(20*s,18*s); close()
                    }
                    drawPath(bell, Accent1, style = Stroke(1.7f*s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawCircle(Accent1, 1.5f*s, Offset(12*s,21*s))
                }
            }
            if (onBack == null && onNotifications == null) Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(InnerFillColor)
                    .border(1.dp, CardStrokeColor, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onOpenSearch() },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(15.dp)) {
                    val s = size.width / 24f
                    val strokeColor = Accent1
                    drawCircle(
                        color = strokeColor,
                        radius = 6.5f * s,
                        center = Offset(10.5f * s, 10.5f * s),
                        style = Stroke(width = 2.4f * s),
                    )
                    drawLine(
                        color = strokeColor,
                        start = Offset(15.5f * s, 15.5f * s),
                        end = Offset(21f * s, 21f * s),
                        strokeWidth = 2.6f * s,
                        cap = StrokeCap.Round,
                    )
                }
            }
            if (demo && !allSources && onBack == null) {
                Spacer(Modifier.width(8.dp))
                DemoBadge()
            }
        }
    }
}

/** 頂欄實例快速切換：實例名 chip（細箭嘴）→ DropdownMenu 列實例＋演示模式，當前項打勾 */
@Composable
private fun InstanceSwitcher(
    demo: Boolean,
    instances: List<com.newapimonitor.domain.Instance>,
    activeId: String?,
    onPickInstance: (String) -> Unit,
    onPickDemo: () -> Unit,
    allSources: Boolean = false,
    onPickAll: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val label = if (allSources) tr("全部實例") else if (demo) tr("演示實例") else (instances.firstOrNull { it.id == activeId }?.name ?: tr("未設定實例"))
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(InnerFillColor)
                .border(1.dp, CardStrokeColor, RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = true }
                .padding(start = 10.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (demo) WarnAmber else OkGreen, Modifier.size(7.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (demo) WarnAmber else Txt2Color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Canvas(Modifier.padding(start = 5.dp).size(width = 9.dp, height = 5.dp)) {
                val chevron = Path().apply {
                    moveTo(1f, 1f)
                    lineTo(4.5f, 4.5f)
                    lineTo(8f, 1f)
                }
                drawPath(
                    chevron,
                    color = if (demo) WarnAmber else MutedColor,
                    style = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("全部實例", color = if (allSources) Accent1 else TxtColor) },
                onClick = { open = false; onPickAll() })
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "演示模式",
                            fontSize = 13.sp,
                            fontWeight = if (demo) FontWeight.Bold else FontWeight.Normal,
                            color = if (demo) WarnAmber else TxtColor,
                        )
                        Spacer(Modifier.weight(1f))
                        if (demo) CheckMark()
                    }
                },
                onClick = {
                    open = false
                    onPickDemo()
                },
            )
            if (instances.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(tr("（仲未有任何實例，去設定新增）"), fontSize = 12.sp, color = DimColor) },
                    onClick = {},
                    enabled = false,
                )
            } else {
                instances.forEach { inst ->
                    val current = !demo && inst.id == activeId
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    inst.name,
                                    fontSize = 13.sp,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    color = if (current) Accent1 else TxtColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.weight(1f))
                                if (current) CheckMark()
                            }
                        },
                        onClick = {
                            open = false
                            onPickInstance(inst.id)
                        },
                    )
                }
            }
        }
    }
}

/** 當前項打勾（青色小勾） */
@Composable
private fun CheckMark() {
    Canvas(Modifier.size(width = 12.dp, height = 10.dp)) {
        val check = Path().apply {
            moveTo(1f, 5f)
            lineTo(4.5f, 8.5f)
            lineTo(11f, 1.5f)
        }
        drawPath(
            check,
            color = Accent2,
            style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/* ---------------- Liquid glass nav bar ---------------- */

/**
 * 忠實移植 flutter-liquid-glass skill（sing0626/flutter-liquid-glass-skill，
 * commit d42aa49「1.08x in-place swell, clamped drag, and 1000ms sampling cadence」）：
 * - [rememberAppBackdrop]：內容層 export，vibrancy 取樣用
 * - [GlassBottomBar]：clear-glass 膠囊（blurSigma=0，冇 filter layer）＋方向性光邊
 *   ＋按壓 spring glow；一粒 bubble（純 fill＋border，**唔係** moving lens——
 *   skill pitfall #1：移動中嘅 backdrop filter 會 ghost 一 frame）
 * - tap：260ms easeOutCubic 滑去嗰格；拖：零動畫 1:1 跟手指（clamp [-1,1]）；
 *   放手： snap 落最近 tab 連 fling velocity（0.15s 投影）；撳住：bubble 原位脹大 1.08x
 */
@Composable
internal fun rememberAppBackdrop(): LayerBackdrop = rememberLayerBackdrop {
    drawRect(BgColor)
    drawContent()
}

/**
 * 放手 snap 落邊格（skill onHorizontalDragEnd）：拖動位移（cell 單位）＋ fling velocity
 * 投影 0.15 秒，四捨五入後由當前 index 加減，clamp 喺 [0, count-1]。
 * 抽成純函數等單元測試可以直接驗證投影數學。
 */
internal fun snapIndex(currentIdx: Int, dragPx: Float, velocityX: Float, tabWidth: Float, count: Int): Int {
    if (tabWidth <= 0f) return currentIdx.coerceIn(0, count - 1)
    val units = (dragPx / tabWidth) + (velocityX / tabWidth) * 0.15f
    return (currentIdx + units.roundToInt()).coerceIn(0, count - 1)
}

/** 膠囊形 clear-glass bar（skill glass_nav_bar.dart @ d42aa49 嘅 Compose 移植）。 */
@Composable
internal fun GlassBottomBar(
    current: String,
    onSelect: (String) -> Unit,
    backdrop: LayerBackdrop,
) {
    val items = listOf(
        Triple(TAB_DASHBOARD, tr("總覽"), TabKind.DASHBOARD),
        Triple(TAB_LOGS, tr("日誌"), TabKind.LOGS),
        Triple(TAB_TOKENS, tr("令牌"), TabKind.TOKENS),
        Triple(TAB_SETTINGS, tr("設定"), TabKind.SETTINGS),
    )
    val n = items.size
    val capsule = RoundedCornerShape(100)

    val currentIdx = items.indexOfFirst { it.first == current }.coerceAtLeast(0)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestCurrentIdx by rememberUpdatedState(currentIdx)

    // skill 狀態：_dragPx（null＝冇拖緊）、pressed（bubble 脹大）、_vibrancy。
    var dragPx by remember { mutableStateOf<Float?>(null) }
    var pressed by remember { mutableStateOf(false) }
    var vibrancy by remember { mutableFloatStateOf(0.5f) }
    var innerSize by remember { mutableStateOf(IntSize.Zero) }
    var touch by remember { mutableStateOf(Offset.Zero) }

    // 按壓 spring（skill GlassSurface._spring：ratio 0.55 / stiffness 320）。
    val press = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        press.animateTo(if (pressed) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 320f))
    }

    // bubble 對齊（AnimatedAlign 等價）：拖緊＝零動畫 snap 跟手指；放手＝260ms easeOutCubic 滑去目標格。
    val align = remember { Animatable(currentIdx.toFloat() / (n - 1) * 2f - 1f) }
    // 外部 index 改變（深入連結／返鍵／自己 commit）＝取消拖動預覽（skill didUpdateWidget）。
    LaunchedEffect(currentIdx) { dragPx = null }
    LaunchedEffect(currentIdx, dragPx, innerSize) {
        val w = innerSize.width
        if (w <= 0) return@LaunchedEffect
        val tabWidth = w.toFloat() / n
        val cell = if (dragPx != null) currentIdx + (dragPx ?: 0f) / tabWidth else currentIdx.toFloat()
        val target = (cell / (n - 1) * 2f - 1f).coerceIn(-1f, 1f)
        if (dragPx != null) align.snapTo(target)
        else align.animateTo(target, tween(durationMillis = 260, easing = EaseOutCubic))
    }

    // vibrancy：每 1000ms 將 backdrop 縮成 24px 闊縮圖計亮度（skill _sampleLuminance；
    // 250ms 喺真機會週期性 raster jank，1s 啱好）。Robolectric 冇 render pipeline，跳過。
    val density = LocalDensity.current
    val thumb = rememberGraphicsLayer()
    LaunchedEffect(backdrop) {
        if (Build.FINGERPRINT.contains("robolectric")) return@LaunchedEffect
        while (isActive) {
            delay(1000)
            try {
                val src = backdrop.graphicsLayer
                val s = src.size
                if (s.width < 1f || s.height < 1f) continue
                val w = 24
                val h = (24f * s.height / s.width).roundToInt().coerceIn(1, 512)
                thumb.record(
                    density = density,
                    layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
                    size = IntSize(w, h),
                ) {
                    scale(scaleX = w.toFloat() / s.width, scaleY = h.toFloat() / s.height) { drawLayer(src) }
                }
                val pm = thumb.toImageBitmap().toPixelMap()
                var sum = 0.0; var count = 0; var brightSum = 0.0; var brightCount = 0
                for (y in 0 until h) for (x in 0 until w) {
                    val c = pm[x, y]
                    val lum = (0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue) * 255.0
                    sum += lum; count++
                    if (lum > 90.0) { brightSum += lum; brightCount++ }
                }
                if (count == 0) continue
                val avg = sum / count / 255.0
                val bright = if (brightCount > 0) brightSum / brightCount / 255.0 else avg
                val signal = (((avg + bright) / 2.0) - 0.18) / 0.42
                vibrancy = (vibrancy + ((signal.toFloat() - vibrancy) * 0.5f)).coerceIn(0f, 1f)
            } catch (_: Throwable) {
                // Layer 未 paint 好／app 喺背景／readback 唔支援：跳過呢一輪。
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // GlassSurface（clear glass tier，blurSigma=0→完全唔加 filter layer）：
        // shadow ＋ clip ＋ scrim 漸變（glint＋tint）＋ content ＋ 按壓 radial glow ＋ 方向性光邊。
        Box(
            Modifier
                .fillMaxWidth()
                .testTag("glass-bar")
                .graphicsLayer {
                    // skill GlassSurface pressScale 預設 1.03：成條 bar 按壓輕微脹大。
                    val s = 1f + 0.03f * press.value
                    scaleX = s
                    scaleY = s
                }
                .shadow(
                    elevation = 6.dp,
                    shape = capsule,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.25f),
                    spotColor = Color.Black.copy(alpha = 0.25f),
                )
                .clip(capsule)
                .pointerInput(Unit) {
                    // observe-only Listener（skill GlassSurface）：淨係觀察，唔 consume，
                    // tab 嘅 tap 照常運作。追蹤手指位置（glow center）＋驅動 bubble 脹大。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touch = down.position
                        pressed = true
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.fastAll { !it.pressed }) break
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) touch = change.position
                        }
                        pressed = false
                    }
                }
                .drawWithContent {
                    val t = vibrancy.coerceIn(0f, 1f)
                    // scrim 再加深一個度（用戶指定）：明內容墊後面字都實。
                    drawRect(Color(0xFF1B1429).copy(alpha = 0.97f))
                    drawRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0f to Color.White.copy(alpha = lerp(0.06f, 0.11f, t)),
                                0.3f to Color(0xFF101F33).copy(alpha = lerp(0.24f, 0.40f, t)),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                    drawContent()
                    // 按壓 glow：radial 白光跟手指，BlendMode.Plus 疊光（skill press overlay）。
                    if (press.value > 0f) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.2f * press.value),
                                    Color.Transparent,
                                ),
                                center = Offset(
                                    touch.x.coerceIn(0f, size.width),
                                    touch.y.coerceIn(0f, size.height),
                                ),
                                radius = 1.1f * minOf(size.width, size.height),
                            ),
                            blendMode = BlendMode.Plus,
                        )
                    }
                    // 方向性光邊：一筆 stroke 圓角矩形，左上最光右下最暗，vibrancy 驅動
                    // （skill GlassRimPainter——一定係 gradient stroke，唔係 non-uniform border）。
                    val sw = 1.dp.toPx()
                    val inset = sw / 2f
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0f to Color.White.copy(alpha = lerp(0.45f, 0.7f, t)),
                                0.5f to Color.White.copy(alpha = lerp(0.25f, 0.4f, t)),
                                1f to Color.White.copy(alpha = lerp(0.08f, 0.18f, t)),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - sw, size.height - sw),
                        cornerRadius = CornerRadius(size.height / 2f),
                        style = Stroke(width = sw),
                    )
                }
                // 橫拖：GestureDetector onHorizontalDrag 等價。拖緊 1:1 跟手指（零動畫），
                // 放手 snap 落最近 tab 連 fling velocity（0.15s 投影，skill onHorizontalDragEnd）。
                .pointerInput(n) {
                    var tracker: VelocityTracker? = null
                    detectHorizontalDragGestures(
                        onDragStart = { _ ->
                            tracker = VelocityTracker()
                            dragPx = 0f
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            // 合併（coalesced）move 事件入面嘅歷史點都要餵 tracker，
                            // 否則 velocity 會低估甚至係 0（fling snap 失準）。
                            change.historical.forEach { historical ->
                                tracker?.addPosition(historical.uptimeMillis, historical.position)
                            }
                            tracker?.addPosition(change.uptimeMillis, change.position)
                            dragPx = (dragPx ?: 0f) + delta
                        },
                        onDragEnd = {
                            val w = innerSize.width
                            val tabWidth = if (w > 0) w.toFloat() / n else 0f
                            val velocity = tracker?.calculateVelocity()?.x ?: 0f
                            val idx = snapIndex(latestCurrentIdx, dragPx ?: 0f, velocity, tabWidth, n)
                            dragPx = null
                            latestOnSelect(items[idx].first)
                        },
                        onDragCancel = { dragPx = null },
                    )
                },
        ) {
            // 內距：水平 10、垂直 8（skill Padding symmetric(horizontal:10, vertical:8)）。
            Box(
                Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                    .onSizeChanged { innerSize = it }
                    .fillMaxWidth()
            ) {
                // bubble——Stack child #0：喺 tab 後面滑動。純 fill＋border（skill pitfall #1/#2：
                // 唔好將 backdrop filter／lens 放入會郁嘅嘢入面，會 ghost 同拉伸取樣）。
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(1f / n)
                            .align(Alignment.CenterStart)
                            .offset {
                                val w = innerSize.width.toFloat()
                                val bw = if (w > 0) w / n else 0f
                                IntOffset(((align.value + 1f) / 2f * (w - bw)).roundToInt(), 0)
                            }
                            .graphicsLayer {
                                // 撳住唔放：bubble 原位脹大 1.08x（easeOutCubic 映射 spring 進度）。
                                val s = 1f + 0.08f * EaseOutCubic.transform(press.value)
                                scaleX = s
                                scaleY = s
                            }
                            .testTag("glass-bubble")
                            .padding(horizontal = 2.dp)
                            .clip(RoundedCornerShape(100))
                            .background(Color.White.copy(alpha = 0.14f))
                            .border(1.dp, Color.White.copy(alpha = 0.40f), RoundedCornerShape(100))
                    )
                }
                // tabs——Stack child #1：淨係畫內容＋收 tap。
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    items.forEachIndexed { index, (id, label, kind) ->
                        TabItem(
                            id = id,
                            label = label,
                            kind = kind,
                            selected = current == id,
                            modifier = Modifier.weight(1f),
                            onClick = { latestOnSelect(id) },
                        )
                    }
                }
            }
        }
    }
}

/** 單個 tab 內容（icon＋字）。揀選 pill 由外面粒滑動 bubble 畫，呢度只負責轉色＋icon 細 scale-in。 */
@Composable
private fun TabItem(
    id: String,
    label: String,
    kind: TabKind,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .testTag("tab-$id")
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (scaleIn(animationSpec = tween(180), initialScale = 0f) togetherWith
                    scaleOut(animationSpec = tween(180), targetScale = 0f))
            },
            contentAlignment = Alignment.Center,
            label = "tabIcon",
        ) { sel ->
            Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
                TabIcon(kind, sel)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            // 高對比：未選中純白，選中亮紫（比 web .tab.active 再亮一級）。
            color = if (selected) Color(0xFFD9CDFF) else Color.White,
        )
    }
}

/* ---------------- 品牌圖標（Canvas，對應 index.html brand-logo） ---------------- */

/** 品牌圖標：脈衝 — 同新 launcher icon 一款（漸變脈衝線＋微光底層＋到站圓點） */
@Composable
fun BrandLogo(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.width / 48f
        val grad = Brush.linearGradient(listOf(Accent1, Accent2))
        val path = Path().apply {
            moveTo(4 * s, 24 * s)
            lineTo(16.5f * s, 24 * s)
            lineTo(23 * s, 11 * s)
            lineTo(30.5f * s, 37 * s)
            lineTo(37 * s, 24 * s)
            lineTo(43 * s, 24 * s)
        }
        drawPath(
            path,
            color = Accent2.copy(alpha = 0.16f),
            style = Stroke(width = 7.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(path, brush = grad, style = Stroke(width = 4.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Accent2, radius = 2.4f * s, center = Offset(43 * s, 24 * s))
    }
}

/* ---------------- Tab 圖標（Canvas，對應 index.html tabbar SVG） ---------------- */

enum class TabKind { DASHBOARD, LOGS, TOKENS, SETTINGS }

@Composable
private fun TabIcon(kind: TabKind, selected: Boolean) {
    // 同 label 一套高對比色（透明玻璃上要夠光先睇得清）。
    val color = if (selected) Color(0xFFD9CDFF) else Color.White
    androidx.compose.foundation.Canvas(Modifier.size(23.dp)) {
        val s = size.width / 24f
        when (kind) {
            TabKind.DASHBOARD -> {
                drawRoundRect(color, Offset(3 * s, 3 * s), Size(8 * s, 8 * s), CornerRadius(2.2f * s, 2.2f * s))
                drawRoundRect(color, Offset(13 * s, 3 * s), Size(8 * s, 5.4f * s), CornerRadius(2.2f * s, 2.2f * s))
                drawRoundRect(color, Offset(13 * s, 10.6f * s), Size(8 * s, 10.4f * s), CornerRadius(2.2f * s, 2.2f * s))
                drawRoundRect(color, Offset(3 * s, 13.2f * s), Size(8 * s, 7.8f * s), CornerRadius(2.2f * s, 2.2f * s))
            }
            TabKind.LOGS -> {
                drawRoundRect(color, Offset(3 * s, 4.2f * s), Size(18 * s, 3 * s), CornerRadius(1.5f * s, 1.5f * s))
                drawRoundRect(color, Offset(3 * s, 10.5f * s), Size(18 * s, 3 * s), CornerRadius(1.5f * s, 1.5f * s))
                drawRoundRect(color, Offset(3 * s, 16.8f * s), Size(12 * s, 3 * s), CornerRadius(1.5f * s, 1.5f * s))
            }
            TabKind.TOKENS -> {
                // 一把 key：圓環頭 + 橫軸 + 兩齒（指向右）
                drawCircle(
                    color,
                    radius = 3.4f * s,
                    center = Offset(7.6f * s, 12f * s),
                    style = Stroke(width = 2.4f * s),
                )
                drawRoundRect(color, Offset(10.8f * s, 10.8f * s), Size(10.2f * s, 2.4f * s), CornerRadius(1.2f * s, 1.2f * s))
                drawRoundRect(color, Offset(15.4f * s, 12.4f * s), Size(2.4f * s, 4.4f * s), CornerRadius(1.2f * s, 1.2f * s))
                drawRoundRect(color, Offset(18.8f * s, 12.4f * s), Size(2.4f * s, 3.2f * s), CornerRadius(1.2f * s, 1.2f * s))
            }
            TabKind.SETTINGS -> {
                // 滑桿線 + 圓形旋鈕（鏤空 + 描邊）
                listOf(5f, 10.8f, 16.6f).forEach { y ->
                    drawRoundRect(color, Offset(3 * s, y * s), Size(18 * s, 2.4f * s), CornerRadius(1.2f * s, 1.2f * s))
                }
                listOf(15f to 6.2f, 8f to 12f, 17f to 17.8f).forEach { (x, y) ->
                    drawCircle(BgColor, radius = 2.7f * s, center = Offset(x * s, y * s))
                    drawCircle(
                        color,
                        radius = 2.7f * s,
                        center = Offset(x * s, y * s),
                        style = Stroke(width = 1.4f * s),
                    )
                }
            }
        }
    }
}
