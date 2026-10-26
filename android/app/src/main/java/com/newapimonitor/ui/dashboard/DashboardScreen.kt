package com.newapimonitor.ui.dashboard

import com.newapimonitor.ui.theme.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.domain.BurnForecast
import com.newapimonitor.domain.DailySpend
import com.newapimonitor.domain.DashboardSnapshot
import com.newapimonitor.domain.ErrorStats
import com.newapimonitor.domain.LogMath
import com.newapimonitor.domain.ModelCostStats.ModelCost
import com.newapimonitor.domain.SiteNotice
import com.newapimonitor.ui.common.DisposableLifecycleEffect
import com.newapimonitor.ui.components.CountUpText
import com.newapimonitor.ui.components.CountdownRing
import com.newapimonitor.ui.components.DonutChart
import com.newapimonitor.ui.components.EmptyFigureIcon
import com.newapimonitor.ui.components.EmptyStateCard
import com.newapimonitor.ui.components.ErrorCard
import com.newapimonitor.ui.components.FigureKind
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.components.HBarRow
import com.newapimonitor.ui.components.LogRowItem
import com.newapimonitor.ui.components.SectionHeader
import com.newapimonitor.ui.components.SegOpt
import com.newapimonitor.ui.components.Segmented
import com.newapimonitor.ui.components.SkeletonDashboard
import com.newapimonitor.ui.components.SparkEmptyHint
import com.newapimonitor.ui.components.StatusDot
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.Accent2
import com.newapimonitor.ui.theme.BarTrackColor
import com.newapimonitor.ui.theme.BgColor
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.DimColor
import com.newapimonitor.ui.theme.ErrRed
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.HeroGradientColors
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.isLightMode
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.OkGreen
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.WarnAmber
import com.newapimonitor.ui.theme.modelBrandColor
import com.newapimonitor.ui.theme.tabular
import com.newapimonitor.util.Fmt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 儀表板：狀態列（實例名＋狀態燈＋倒數環）、餘額大字 count-up、今日/今月、
 * 7 日消費 bar chart、餘額趨勢 sparkline、模型 donut＋Top5 bar、最近 10 筆即時流水；
 * keyOnly／空狀態／錯誤卡；下拉刷新（nestedScroll 自寫，唔同倒數環打架）。
 */
@Composable
fun DashboardScreen(
    repo: NewApiRepository,
    onNavigateSettings: () -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onNavigateReport: () -> Unit = {},
    onNavigateMonitor: () -> Unit = {},
) {
    val vm: DashboardViewModel = viewModel(initializer = { DashboardViewModel(repo) })
    val state by vm.state.collectAsState()
    // 設定頁「儀表板卡片」收埋咗嘅卡 id；balance hero 永遠顯示
    val hidden by vm.hiddenCards.collectAsState()

    // ON_STOP 暫停 / ON_START 恢復；切走 tab（dispose）同樣暫停
    DisposableLifecycleEffect(onStart = vm::onResume, onStop = vm::onPause)

    val latest = state.latest
    val display = state.display
    val demoActive = state.sourceKey == "demo"

    PullRefreshScaffold(refreshing = state.refreshing, onRefresh = vm::refreshNow) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /* 卡 id：status / instances / balance / budget / report / week /
               history / models / cost / heatmap / stability / feed（DashboardLayoutStore.ALL_IDS） */
            if ("status" !in hidden) {
                StatusCard(
                    name = state.name,
                    latest = latest,
                    paused = state.paused,
                    intervalMs = state.intervalMs,
                    nextPollAtMs = state.nextPollAtMs,
                    demoActive = demoActive,
                )
            }

            GlassCard {
                display?.takeIf { it.ok }?.let { shown ->
                    val freshness = if (latest?.ok == true) "已更新" else "舊資料：本輪連線失敗"
                    Text("$freshness · ${Fmt.logTime(shown.fetchedAtMs / 1000)}", color = MutedColor, fontSize = 11.sp)
                    Text("今日／本月日誌：" + (if (shown.todayUsd == null) "今日不可用" else if (shown.todayTruncated) "今日部分資料" else "今日已載入") + " · " +
                        (if (shown.monthUsd == null) "本月不可用" else if (shown.monthTruncated) "本月部分資料" else "本月已載入"), color = MutedColor, fontSize = 11.sp)
                }
            }

            when {
                state.loadingFirst && display == null -> SkeletonDashboard()
                display?.noInstance == true -> EmptyStateCard(
                    kind = FigureKind.CHART,
                    title = tr("仲未設定實例"),
                    sub = "加入一個 NewAPI 實例（Base URL + Access Token / API Key）開始監察。",
                ) {
                    com.newapimonitor.ui.components.AppButton(tr("去設定"), onNavigateSettings, kind = com.newapimonitor.ui.components.AppButtonKind.PRIMARY, small = true)
                    com.newapimonitor.ui.components.AppButton(tr("睇演示模式"), { vm.setDemo(true) }, small = true)
                }
                display?.noTokens == true -> EmptyStateCard(
                    kind = FigureKind.CHART,
                    title = tr("實例未填任何令牌"),
                    sub = "至少要 Access Token（管理 API）或 API Key（sk-…）其中之一。",
                ) {
                    com.newapimonitor.ui.components.AppButton(tr("去設定"), onNavigateSettings, kind = com.newapimonitor.ui.components.AppButtonKind.PRIMARY, small = true)
                }
                else -> display?.let { snap ->
                    if (!snap.ok) {
                        val primary = snap.primaryError()
                        ErrorCard(
                            title = primary?.displayTitle ?: tr("發生錯誤"),
                            message = primary?.message ?: "",
                            hint = primary?.kind?.hint(primary.status) ?: "",
                            onRetry = vm::refreshNow,
                            onSettings = onNavigateSettings,
                        )
                    } else {
                        latest?.errors?.takeIf { it.isNotEmpty() }?.let { errs ->
                            val first = errs.values.first()
                            val parts = errs.entries.joinToString("；") { (k, e) ->
                                "${partialLabel(k)}：${e.displayTitle}"
                            }
                            ErrorCard(
                                title = tr("部分數據未能更新"),
                                message = parts,
                                hint = first.kind.hint(first.status),
                                warn = true,
                            )
                        }
                        // 餘額 hero 永遠顯示（唔設開關）
                        BalanceCard(snap)
                        // 站點公告（/api/notice；內容空 → 唔出卡）
                        state.notice?.takeIf { it.content.isNotBlank() }?.let { NoticeCard(it) }
                        if ("report" !in hidden) {
                            ReportEntryCard(
                                monthUsd = snap.monthUsd,
                                monthTruncated = snap.monthTruncated,
                                onClick = onNavigateReport,
                            )
                        }
                        if ("budget" !in hidden) {
                            BudgetRingCard(
                                budgetUsd = state.budgetUsd,
                                monthUsd = snap.monthUsd,
                                monthTruncated = snap.monthTruncated,
                                onNavigateSettings = onNavigateSettings,
                            )
                        }
                        if (!snap.keyOnly && snap.weekDays.isNotEmpty() && "week" !in hidden) {
                            WeekSpendCard(snap.weekDays, snap.weekTotalUsd, snap.weekApprox)
                        }
                        if ("history" !in hidden) {
                            HistoryCard(
                                samples = state.samples,
                                history = state.history,
                                range = state.historyRange,
                                onRange = vm::setHistoryRange,
                                intervalMs = state.intervalMs,
                            )
                        }
                        if (snap.keyOnly) {
                            KeyOnlyCards(onNavigateSettings)
                        } else {
                            if ("models" !in hidden) {
                                ModelCard(snap)
                            }
                            if (state.monthDataReady && "cost" !in hidden) {
                                ModelCostCard(costs = state.modelCosts, truncated = state.monthTruncated)
                            }
                            if ("heatmap" !in hidden) {
                                HeatmapCard(snap.hourly)
                            }
                            if ("stability" !in hidden) {
                                StabilityCard(snap.errorStats)
                            }
                            if ("feed" !in hidden) {
                                FeedCard(state.feed, snap.qpu)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 下拉刷新（nestedScroll 自寫；material3 1.2.1 / foundation 1.6.8 都冇現成 API） ---------------- */

/**
 * 內容到頂再向下拉 → 食 overscroll 做「拉動距離」（帶阻尼），鬆手超過閾值觸發 onRefresh。
 * 只喺 onPostScroll（子 scrollable 食剩）介入，唔會同 verticalScroll 打架；
 * 刷新中固定顯示轉圈，pollOnce 完成（成功／失敗）→ refreshing=false 歸位。
 */
@Composable
private fun PullRefreshScaffold(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val triggerPx = with(density) { 64.dp.toPx() }
    val maxPx = with(density) { 110.dp.toPx() }
    val holdPx = with(density) { 40.dp.toPx() }
    val drag = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val currentRefreshing = rememberUpdatedState(refreshing)
    val currentOnRefresh = rememberUpdatedState(onRefresh)

    val connection = remember(triggerPx, maxPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag) return Offset.Zero
                val dy = available.y
                if (dy > 0f) {
                    // 內容已到頂仲向下拉 → 阻尼累積
                    drag.floatValue = min(drag.floatValue + dy * 0.55f, maxPx)
                    return Offset(0f, dy)
                }
                if (drag.floatValue > 0f && dy < 0f) {
                    // 反向拉 → 回彈
                    val take = max(dy, -drag.floatValue)
                    drag.floatValue += take
                    return Offset(0f, take)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag.floatValue >= triggerPx && !currentRefreshing.value) currentOnRefresh.value()
                drag.floatValue = 0f
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(refreshing) {
        // 刷新結束 → 清走任何殘餘拉動距離
        if (!refreshing && drag.floatValue > 0f) drag.floatValue = 0f
    }

    // 方案 H：純淨背景，唔再畫 aurora 光暈
    Box(
        Modifier
            .fillMaxSize()
            .nestedScroll(connection)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 橡皮筋跟手感（鬆手／刷新中歸零）
                    translationY = if (refreshing) holdPx * 0.6f else drag.floatValue * 0.45f
                }
        ) {
            content()
        }
        if (refreshing || drag.floatValue > 0f) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = if (refreshing) holdPx else drag.floatValue * 0.9f
                        alpha = if (refreshing) 1f else (drag.floatValue / triggerPx).coerceIn(0f, 1f)
                    }
            ) {
                CircularProgressIndicator(
                    color = Accent2,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

private fun partialLabel(key: String): String = when (key) {
    "user" -> "用戶資料"
    "billing" -> "計費端點"
    "logs", "logs2" -> "日誌統計"
    "status" -> "狀態"
    else -> key
}

/* ---------------- 狀態列 ---------------- */

@Composable
private fun StatusCard(
    name: String,
    latest: DashboardSnapshot?,
    paused: Boolean,
    intervalMs: Long,
    nextPollAtMs: Long,
    demoActive: Boolean,
) {
    GlassCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        color = when {
                            latest == null -> DimColor
                            !latest.ok -> ErrRed
                            demoActive -> WarnAmber
                            else -> OkGreen
                        },
                        pulse = latest?.ok == true,
                    )
                    Text(
                        name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    statusSub(latest),
                    fontSize = 11.sp,
                    color = MutedColor,
                    style = TabularStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            CountdownRing(targetMs = nextPollAtMs, intervalMs = intervalMs, paused = paused)
        }
    }
}

private fun statusSub(latest: DashboardSnapshot?): String = when {
    latest == null -> "連線中…"
    latest.ok -> buildString {
        latest.status?.systemName?.let { append(it) }
        latest.status?.version?.let {
            if (isNotEmpty()) append(" · ")
            append(it)
        }
        if (isNotEmpty()) append(" · ")
        append("更新於 ").append(Fmt.clock(latest.fetchedAtMs / 1000))
        if (latest.keyOnly) append(" · API Key 模式")
    }
    latest.noInstance -> "未設定實例"
    latest.noTokens -> "未填任何令牌"
    else -> "離線 · " + (latest.primaryError()?.displayTitle ?: "發生錯誤")
}

/* ---------------- 站點公告卡（GET /api/notice） ---------------- */

/** 站點公告：預設收 6 行，撳一下展開／收起；HTML tag 粗略剝走（上游內容多為 Markdown／HTML） */
@Composable
private fun NoticeCard(notice: SiteNotice) {
    var expanded by remember(notice.content) { mutableStateOf(false) }
    GlassCard {
        SectionHeader(tr("站點公告"), chip = tr("實例公告"))
        Text(
            notice.content.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim(),
            fontSize = 11.5.sp,
            color = Txt2Color,
            lineHeight = 17.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
        )
        Text(
            if (expanded) tr("收起") else tr("展開全文"),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Accent1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/* ---------------- 餘額卡（方案 H hero：全幅 accent 漸變金融卡） ---------------- */

@Composable
private fun BalanceCard(snap: DashboardSnapshot) {
    val shape = RoundedCornerShape(18.dp)
    val hero = HeroGradientColors
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = hero[0].copy(alpha = 0.35f),
                spotColor = hero[1].copy(alpha = 0.35f),
            )
            .clip(shape)
            .background(Brush.linearGradient(hero))
            .drawBehind {
                // 右上／右下兩粒半透明裝飾圓（.hero::after / .hero::before）
                drawCircle(
                    Color.White.copy(alpha = 0.14f),
                    radius = 75.dp.toPx(),
                    center = Offset(size.width - 45.dp.toPx(), 35.dp.toPx()),
                )
                drawCircle(
                    Color.White.copy(alpha = 0.09f),
                    radius = 65.dp.toPx(),
                    center = Offset(size.width - 85.dp.toPx(), size.height - 5.dp.toPx()),
                )
            }
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("剩餘額度", Modifier.weight(1f), titleColor = Color.White)
                Text(
                    "QPU " + Fmt.num(snap.qpu),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.92f),
                    style = TabularStyle,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x29FFFFFF))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            var exactBalance by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { exactBalance = true }.padding(top = 10.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(snap.balanceUsd?.let(Fmt::usdCompact) ?: "未提供", color = Color.White, fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Text("USD", color = Color.White.copy(alpha = .8f), fontSize = 11.sp, modifier = Modifier.padding(bottom = 7.dp))
            }
            if (exactBalance) androidx.compose.material3.AlertDialog(onDismissRequest = { exactBalance = false },
                title = { Text("完整金額") }, text = { Text("${snap.balanceUsd?.let { Fmt.usd(it, 5) } ?: "未提供"} USD") },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { exactBalance = false }) { Text("關閉") } })
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (snap.keyOnly && snap.billing != null) {
                    BalCell("總額上限", Fmt.usd(snap.billing.hardLimitUsd, 2), Modifier.weight(1f))
                    BalCellCountUp("已用總計", snap.billing.totalUsageUsd, Modifier.weight(1f))
                } else {
                    BalCellCountUp("今日已用", snap.todayUsd, Modifier.weight(1f))
                    BalCell(
                        "今月已用",
                        (snap.monthUsd?.let { Fmt.usd(it, 2) } ?: "—") + if (snap.monthTruncated) "+" else "",
                        Modifier.weight(1f),
                    )
                }
            }
            ForecastRow(snap)
        }
    }
}

/** 燒錢預測行：按「今日速度／7日平均」估計餘額仲燒幾耐（估計唔到就唔顯示） */
@Composable
private fun ForecastRow(snap: DashboardSnapshot) {
    if (snap.keyOnly) return
    val fc = remember(snap.balanceUsd, snap.todayUsd, snap.weekDays) {
        BurnForecast.forecast(
            balanceUsd = snap.balanceUsd,
            todaySpendUsd = snap.todayUsd,
            nowMs = System.currentTimeMillis(),
            daily7dUsd = snap.weekDays.map { it.usd },
        )
    }
    val days = fc.daysLeft
    if (days == null || !days.isFinite() || days <= 0.0 || (fc.dailyAvg7d ?: 0.0) <= 0.0) return
    val txt = when {
        days >= 60 -> "超過兩個月"
        days >= 30 -> "超過一個月"
        days >= 1 -> "約 " + Fmt.num(days) + " 日"
        else -> "約 " + max(1L, kotlin.math.round(days * 24).toLong()) + " 小時"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x24FFFFFF))
            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏳", fontSize = 12.sp)
        Text(
            buildString {
                append(" 按")
                append(fc.basis ?: "")
                fc.dailyAvg7d?.let { append("（日均 ").append(Fmt.usd(it, 2)).append("）") }
                append(txt).append("先燒完")
            },
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.88f),
            style = TabularStyle,
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BalCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .12f)).padding(12.dp)) {
        Text(tr(label), color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun BalCellCountUp(label: String, value: Double?, modifier: Modifier = Modifier) {
    BalCell(label, value?.let(Fmt::usdCompact) ?: "未提供", modifier)
}

/* ---------------- 月度報告入口 ---------------- */

/** 月度報告入口（消費日曆＋月度統計）：餘額 hero 後面嘅 slim 卡，右側帶本月已用 */
@Composable
private fun ReportEntryCard(monthUsd: Double?, monthTruncated: Boolean, onClick: () -> Unit) {
    GlassCard(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("月度報告 ›"),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = TxtColor,
            )
            Spacer(Modifier.weight(1f))
            Text(
                (if (monthTruncated) tr("（約）") + " " else "") +
                    (monthUsd?.let { Fmt.usd(it, 2) } ?: "—"),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Txt2Color,
                style = TabularStyle,
                maxLines = 1,
            )
        }
    }
}

/* ---------------- 月度預算進度環 ---------------- */

/**
 * 月度預算卡：
 *  - 未設定預算 → 瘦身卡出引導＋「去設定」
 *  - 已設定 → Canvas 進度環（track 全圓 + sweep 漸變進度弧），環中心大百分比＋已用 $X / $Y；
 *    ≥90% 百分比轉琥珀，≥100% 轉紅並出「已超支」；<90% 另出「剩低 $Z」
 *  - 月份窗口封頂（monthTruncated）→ 標題旁「（約）」chip
 */
@Composable
private fun BudgetRingCard(
    budgetUsd: Double?,
    monthUsd: Double?,
    monthTruncated: Boolean,
    onNavigateSettings: () -> Unit,
) {
    if (budgetUsd == null || !budgetUsd.isFinite() || budgetUsd <= 0.0) {
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            SectionHeader(tr("月度預算"))
            Text(
                tr("設定每月預算後，呢度會顯示本月用量進度。"),
                fontSize = 12.sp,
                color = MutedColor,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            com.newapimonitor.ui.components.AppButton(
                tr("去設定"),
                onNavigateSettings,
                small = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
        return
    }
    val spent = monthUsd?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val ratio = spent / budgetUsd
    val pctColor = when {
        ratio >= 1.0 -> ErrRed
        ratio >= 0.9 -> WarnAmber
        else -> TxtColor
    }
    GlassCard {
        SectionHeader(tr("月度預算"), chip = if (monthTruncated) tr("（約）") else null)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                BudgetRing(
                    progress = ratio.toFloat().coerceIn(0f, 1f),
                    modifier = Modifier.size(128.dp),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(102.dp),
                ) {
                    Text(
                        round(ratio * 100).toInt().toString() + "%",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = pctColor,
                        style = TabularStyle,
                        maxLines = 1,
                    )
                    Text(
                        tr("已用") + " " + Fmt.usd(spent, 2) + " / " + Fmt.usd(budgetUsd, 2),
                        fontSize = 9.5.sp,
                        color = MutedColor,
                        style = TabularStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    when {
                        ratio >= 1.0 -> Text(
                            tr("已超支"),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ErrRed,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        ratio < 0.9 -> Text(
                            tr("剩低") + " " + Fmt.usd((budgetUsd - spent).coerceAtLeast(0.0), 2),
                            fontSize = 10.sp,
                            color = MutedColor,
                            style = TabularStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 預算進度環：BarTrackColor 全圓軌道 + sweep 漸變進度弧（12 點鐘起步，圓頭 12dp 描邊） */
@Composable
private fun BudgetRing(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 12.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = BarTrackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (progress > 0f) {
            // -90° 旋轉令弧由 12 點鐘開始（漸變隨變換一齊轉）
            rotate(-90f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Accent1, HeroGradientColors.last())),
                    startAngle = 0f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/* ---------------- 7 日消費卡 ---------------- */

private val WEEKDAY_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 7 日消費統計卡：Canvas bar chart（漸變 bar + 圓角 + 暗色軌道槽），
 * 支援點擊/滑動手勢選中顯示浮動 Tooltip（日期 + 確切消費金額），
 * 右上 chip 顯示 7 日總計；fallback 數據（本地日誌聚日、窗口封頂）標「約」。
 */
@Composable
private fun WeekSpendCard(days: List<DailySpend>, totalUsd: Double?, approx: Boolean) {
    GlassCard {
        var selectedIdx by remember { mutableStateOf<Int?>(null) }

        SectionHeader(
            "7 日消費",
            chip = totalUsd?.let { (if (approx) "約 " else "") + Fmt.usd(it, 2) } ?: "—",
        )
        val grow = remember { Animatable(0f) }
        LaunchedEffect(days) {
            grow.animateTo(1f, tween(600, easing = EaseOutCubic))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            WeekBars(
                days = days,
                grow = grow.value,
                selectedIdx = selectedIdx,
                onSelectIdx = { idx ->
                    selectedIdx = if (selectedIdx == idx) null else idx
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 浮動 Tooltip
            selectedIdx?.let { idx ->
                if (idx in days.indices) {
                    val d = days[idx]
                    val isToday = d.dayStartSec == com.newapimonitor.util.Fmt.todayStartTs()
                    val dayStr = if (isToday) tr("今日") else weekdayLabel(d.dayStartSec)
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xE61E1B4B))
                            .border(1.dp, Color(0x668B5CF6), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$dayStr · ${Fmt.usd(d.usd, 2)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            style = TabularStyle,
                        )
                    }
                }
            }
        }
        // 槽位標籤（同 bars 一樣 7 等分；今日高亮青色；點中者底線或加亮）
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            days.forEachIndexed { i, d ->
                val isSelected = selectedIdx == i
                val isToday = d.dayStartSec == com.newapimonitor.util.Fmt.todayStartTs()
                Text(
                    weekdayLabel(d.dayStartSec),
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isSelected -> TxtColor
                        isToday -> Accent1
                        else -> MutedColor
                    },
                    style = TabularStyle,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { selectedIdx = if (selectedIdx == i) null else i },
                )
            }
        }
        if (approx) {
            Text(
                "數據由本地日誌統計，可能唔完整（「約」）",
                fontSize = 10.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 7 條漸變圓角 bar：有數據畫實 bar，零值畫暗槽；支援點擊選取 */
@Composable
private fun WeekBars(
    days: List<DailySpend>,
    grow: Float,
    selectedIdx: Int?,
    onSelectIdx: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(
        modifier = modifier.pointerInput(days) {
            detectTapGestures { offset ->
                val n = days.size
                if (n > 0) {
                    val gap = 5.dp.toPx()
                    val slot = (size.width - gap * (n - 1)) / n
                    val tappedIdx = ((offset.x) / (slot + gap)).toInt().coerceIn(0, n - 1)
                    onSelectIdx(tappedIdx)
                }
            }
        }
    ) {
        val n = days.size
        if (n == 0) return@Canvas
        val gap = 6.dp.toPx()
        val slot = (size.width - gap * (n - 1)) / n
        val barW = slot * 0.62f
        val maxUsd = maxOf(days.maxOf { it.usd }, 1e-6)
        val trackCorner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        val barTopR = minOf(barW / 2f, 10.dp.toPx()) // 圓角 pill 頂（mockup .vbar-fill）
        val todayTs = Fmt.todayStartTs()
        days.forEachIndexed { i, d ->
            val cx = gap + i * (slot + gap) + slot / 2f
            val left = cx - barW / 2f
            val isSelected = selectedIdx == i

            // H 軌道槽（track #e4e0f2／#2a2438）：永遠可見，空日都有一格；選中染 accent
            drawRoundRect(
                color = if (isSelected) Accent1.copy(alpha = 0.32f) else BarTrackColor,
                topLeft = Offset(left, size.height * 0.06f),
                size = Size(barW, size.height * 0.88f),
                cornerRadius = trackCorner,
            )
            if (d.usd > 0) {
                val full = size.height * 0.88f
                val h = (d.usd / maxUsd).toFloat().coerceIn(0.10f, 1f) * full * grow.coerceIn(0f, 1f)
                val top = size.height - h
                val isToday = d.dayStartSec == todayTs
                if (isToday) {
                    // 今日 bar glow（.vbar-today box-shadow）：hero 端色兩層半透明 rect 疊出 soft halo
                    val glowC = HeroGradientColors[1]
                    val g1 = 8.dp.toPx()
                    val g2 = 3.5f.dp.toPx()
                    drawRoundRect(
                        color = glowC.copy(alpha = 0.20f),
                        topLeft = Offset(left - g1, top - g1 * 1.5f),
                        size = Size(barW + g1 * 2, h + g1 * 1.5f),
                        cornerRadius = CornerRadius(barTopR + g1, barTopR + g1),
                    )
                    drawRoundRect(
                        color = glowC.copy(alpha = 0.30f),
                        topLeft = Offset(left - g2, top - g2 * 1.6f),
                        size = Size(barW + g2 * 2, h + g2 * 1.6f),
                        cornerRadius = CornerRadius(barTopR + g2, barTopR + g2),
                    )
                }
                // .vbar-fill：平日 Accent1 實色（alpha .9）；選中淡一級；今日 = hero 漸變
                val barPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(left, top, left + barW, size.height),
                            topLeft = CornerRadius(barTopR, barTopR),
                            topRight = CornerRadius(barTopR, barTopR),
                            bottomRight = trackCorner,
                            bottomLeft = trackCorner,
                        )
                    )
                }
                if (isToday) {
                    drawPath(
                        barPath,
                        brush = Brush.verticalGradient(HeroGradientColors, startY = top, endY = size.height),
                    )
                } else {
                    drawPath(barPath, color = Accent1.copy(alpha = if (isSelected) 0.55f else 0.9f))
                }
            }
        }
    }
}

/** H mockup .chip2 藥丸 chip（儀表板本地版；active = 反色實心） */
@Composable
private fun DashChip(label: String, active: Boolean, onClick: () -> Unit) {
    val activeText = if (isLightMode) CardFillColor else BgColor
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) TxtColor else InnerFillColor)
            .border(1.dp, if (active) Color.Transparent else StrokeSoft, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) activeText else Txt2Color,
            style = TabularStyle,
            maxLines = 1,
        )
    }
}

private fun weekdayLabel(dayStartSec: Long): String = try {
    val dow = java.time.Instant.ofEpochSecond(dayStartSec)
        .atZone(java.time.ZoneId.systemDefault()).dayOfWeek.value // 1=Mon..7=Sun
    WEEKDAY_LABELS[dow - 1]
} catch (e: Exception) {
    "·"
}

/* ---------------- 餘額歷史卡 ---------------- */

/** 餘額歷史：即時（session 取樣）／持久化歷史（24 小時 / 7 日 / 30 日）切換 */
@Composable
private fun HistoryCard(
    samples: List<DashboardViewModel.Sample>,
    history: List<DashboardViewModel.Sample>,
    range: String,
    onRange: (String) -> Unit,
    intervalMs: Long,
) {
    val nowMs = System.currentTimeMillis()
    val (pts, meta) = remember(range, samples, history, intervalMs, nowMs / 60_000L) {
        when (range) {
            DashboardViewModel.HistoryRange.SESSION -> {
                Pair(samples, "${samples.size} 個取樣 · 每 ${intervalMs / 1000}s")
            }
            else -> {
                val spanMs = when (range) {
                    DashboardViewModel.HistoryRange.H24 -> 86_400_000L
                    DashboardViewModel.HistoryRange.D7 -> 7 * 86_400_000L
                    else -> 30L * 86_400_000L
                }
                val cut = nowMs - spanMs
                val filtered = history.filter { it.t >= cut }
                val label = when (range) {
                    DashboardViewModel.HistoryRange.H24 -> "24 小時"
                    DashboardViewModel.HistoryRange.D7 -> "7 日"
                    else -> "30 日"
                }
                var mn = Double.POSITIVE_INFINITY
                var mx = Double.NEGATIVE_INFINITY
                filtered.forEach { p -> if (p.v < mn) mn = p.v; if (p.v > mx) mx = p.v }
                val m = "${filtered.size} 點 · $label" +
                    if (filtered.size >= 2) " · ${Fmt.usd(mn, 2)}–${Fmt.usd(mx, 2)}" else ""
                Pair(filtered, m)
            }
        }
    }
    GlassCard {
        SectionHeader("餘額歷史", chip = meta)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val rangeOpts = listOf(
                DashboardViewModel.HistoryRange.SESSION to "即時",
                DashboardViewModel.HistoryRange.H24 to "24 小時",
                DashboardViewModel.HistoryRange.D7 to tr("7 日"),
                DashboardViewModel.HistoryRange.D30 to "30 日",
            )
            rangeOpts.forEach { (value, label) ->
                DashChip(label, active = range == value) { onRange(value) }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            if (pts.size >= 2) {
                InteractiveSparkline(
                    points = pts,
                    label = { timestamp ->
                        val date = java.time.Instant.ofEpochMilli(timestamp)
                            .atZone(java.time.ZoneId.systemDefault())
                        "%02d-%02d %02d:%02d".format(
                            java.util.Locale.US, date.monthValue, date.dayOfMonth, date.hour, date.minute,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                if (range == DashboardViewModel.HistoryRange.SESSION) {
                    SparkEmptyHint(pts.size, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "歷史數據未夠（要喺本機監察一段時間先累積到）",
                            fontSize = 11.sp,
                            color = MutedColor,
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- 時段熱力圖卡 ---------------- */

/**
 * 時段熱力圖（今月窗口取樣）：2 行 × 12 格 Canvas，強度 → 紫→青漸變；
 * meta chip 顯示最勁小時。全 0 顯示空提示。
 */
@Composable
private fun HeatmapCard(hourly: List<Double>) {
    GlassCard {
        val peakHour = if (hourly.size == 24) hourly.indices.maxByOrNull { hourly[it] } ?: -1 else -1
        val peak = if (peakHour >= 0) hourly[peakHour] else 0.0
        SectionHeader(
            "時段熱力圖",
            chip = if (peak > 0) "最勁 %02d:00 · %s".format(peakHour, Fmt.usd(peak, 2)) else "—",
        )
        if (hourly.size != 24 || hourly.none { it > 0 }) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tr("今月窗口暫無消費數據"), fontSize = 11.sp, color = MutedColor)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(InnerFillColor)
            ) {
                HeatmapGrid(hourly, Modifier.fillMaxSize())
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("00", fontSize = 10.sp, color = DimColor, style = TabularStyle)
                Text("06", fontSize = 10.sp, color = DimColor, style = TabularStyle)
                Text("12", fontSize = 10.sp, color = DimColor, style = TabularStyle)
                Text("18", fontSize = 10.sp, color = DimColor, style = TabularStyle)
                Text("23", fontSize = 10.sp, color = DimColor, style = TabularStyle)
            }
        }
    }
}

@Composable
private fun HeatmapGrid(hourly: List<Double>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        if (hourly.size != 24) return@Canvas
        val maxV = maxOf(hourly.max(), 1e-9)
        val gap = 2.dp.toPx()
        val cols = 12
        val rows = 2
        val cellW = (size.width - gap * (cols - 1)) / cols
        val cellH = (size.height - gap) / rows
        val corner = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())
        for (h in 0 until 24) {
            val r = h / cols
            val c = h % cols
            val v = hourly[h]
            val frac = (v / maxV).coerceIn(0.0, 1.0)
            val color = if (v > 0) {
                // 紫（低）→ 青（高）漸變 + 透明度跟強度
                val base = listOf(Color(0xFF8B5CF6), Color(0xFF22D3EE))
                val mixed = lerpColor(base[0], base[1], frac.toFloat())
                mixed.copy(alpha = (0.28f + 0.68f * frac.toFloat()))
            } else {
                BarTrackColor
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(c * (cellW + gap), r * (cellH + gap)),
                size = Size(cellW, cellH),
                cornerRadius = corner,
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha,
)

/* ---------------- 穩定性卡 ---------------- */

/** 今日錯誤統計：0 錯誤 → 綠勾；有錯誤 → 紅 accent＋主要出錯模型 */
@Composable
private fun StabilityCard(stats: ErrorStats.Stats?) {
    GlassCard {
        SectionHeader(
            "穩定性",
            chip = stats?.let { "${(it.rate * 100).let { r -> if (r >= 1) Fmt.num(r) else String.format("%.1f", r) }}% 錯誤率" },
        )
        if (stats == null || (stats.windowCount == 0 && stats.consumeCount == 0)) {
            Text(
                "今日暫無請求數據",
                fontSize = 12.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 10.dp),
            )
            return@GlassCard
        }
        if (stats.windowCount == 0) {
            Row(
                Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OkGreen)
                Text(
                    " 今日全部請求成功",
                    fontSize = 12.sp,
                    color = Txt2Color,
                )
            }
        } else {
            val topModel = stats.byModel.entries.maxByOrNull { it.value }
            Row(
                Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✗", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ErrRed)
                Text(
                    buildString {
                        append(" 今日錯誤 ")
                        append(stats.windowCount)
                        append(" 筆 / 成功 ")
                        append(stats.consumeCount)
                        append(" 筆")
                        topModel?.let { append(" · 主要：").append(it.key).append(" × ").append(it.value) }
                    },
                    fontSize = 12.sp,
                    color = Txt2Color,
                    style = TabularStyle,
                )
            }
        }
    }
}

/* ---------------- 模型卡 ---------------- */

@Composable
private fun ModelCard(snap: DashboardSnapshot) {
    GlassCard {
        SectionHeader("模型調用", chip = "今日 ${Fmt.num(snap.todayCount.toDouble())} 筆")
        val top = snap.modelStats.take(5)
        val totalUsd = top.sumOf { it.usd }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DonutChart(
                segments = top.mapIndexed { i, s -> Pair(maxOf(s.usd, 1e-9), modelBrandColor(s.model)) },
                centerVal = Fmt.num(snap.todayCount.toDouble()),
                centerLabel = "今日調用",
                size = 104.dp,
                thickness = 13.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (top.isEmpty()) {
                    Text(tr("今日暫無調用記錄"), fontSize = 12.sp, color = DimColor)
                } else {
                    top.forEachIndexed { i, s ->
                        ModelLegendRow(
                            name = s.model,
                            pct = if (totalUsd > 0) s.usd / totalUsd * 100 else 0.0,
                            usd = s.usd,
                        )
                    }
                }
            }
        }
        if (top.isNotEmpty()) {
            val maxUsd = maxOf(top[0].usd, 1e-9)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                top.forEachIndexed { i, s ->
                    HBarRow(
                        index = i,
                        label = s.model,
                        sub = "${Fmt.num(s.requests.toDouble())} 次 · ${Fmt.compact(s.tokens.toDouble())} tok · ${Fmt.usd(s.usd, 4)}",
                        pct = s.usd / maxUsd * 100,
                        color = modelBrandColor(s.model),
                        animateGrow = true,
                    )
                }
            }
        }
    }
}

/** 模型品牌色圓角方塊字母 icon（H mockup .mic：32px 方角 10） */
@Composable
private fun ModelIcon(name: String) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(modelBrandColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
    }
}

/** H 模型用量行（mockup .mrow）：字母 icon + 名 + 佔比 sub，右對齊 bold 金額 */
@Composable
private fun ModelLegendRow(name: String, pct: Double, usd: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ModelIcon(name)
        Column(Modifier.padding(start = 8.dp).weight(1f, fill = false)) {
            Text(
                name,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TxtColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${pct.toInt()}% ${tr("佔比")}",
                fontSize = 10.sp,
                color = MutedColor,
                style = TabularStyle,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            Fmt.usd(usd, 2),
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TxtColor,
            style = TabularStyle,
            maxLines = 1,
        )
    }
}

/* ---------------- 模型性價比卡 ---------------- */

/**
 * 模型性價比（本月）：ModelCostStats 由本月日誌計出每百萬 token 平均成本，按單價降序。
 * ≥2 個模型時首行「最貴」紅 chip、尾行「最平」綠 chip；窗口封頂標「（約）」。
 */
@Composable
private fun ModelCostCard(costs: List<ModelCost>, truncated: Boolean) {
    GlassCard {
        SectionHeader(tr("模型性價比"), chip = if (truncated) tr("（約）") else null)
        Text(
            tr("根據本月日誌估算：每百萬 token 平均成本"),
            fontSize = 10.5.sp,
            color = MutedColor,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (costs.isEmpty()) {
            Text(
                tr("本月未有模型調用數據"),
                fontSize = 12.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            val n = costs.size
            Column(
                Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                costs.forEachIndexed { i, c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModelIcon(c.name)
                        Column(Modifier.padding(start = 8.dp).weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    c.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TxtColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (n >= 2 && i == 0) {
                                    CostChip(tr("最貴"), ErrRed, Modifier.padding(start = 6.dp))
                                }
                                if (n >= 2 && i == n - 1) {
                                    CostChip(tr("最平"), OkGreen, Modifier.padding(start = 6.dp))
                                }
                            }
                            Text(
                                Fmt.num(c.calls.toDouble()) + " " + tr("次"),
                                fontSize = 10.sp,
                                color = MutedColor,
                                style = TabularStyle,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                Fmt.usd(c.usdPer1M, 2),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TxtColor,
                                style = TabularStyle,
                                maxLines = 1,
                            )
                            Text(
                                tr("每 1M token"),
                                fontSize = 10.sp,
                                color = MutedColor,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 小色 chip（最貴／最平）：淡色底 + 同色字 */
@Composable
private fun CostChip(text: String, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
    }
}

/* ---------------- 即時流水 ---------------- */

@Composable
private fun FeedCard(items: List<DashboardViewModel.FeedItem>, qpu: Double) {
    GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        SectionHeader("即時流水", chip = "最近 ${items.size} 筆", modifier = Modifier.padding(vertical = 8.dp))
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EmptyFigureIcon(FigureKind.LIST, Modifier.size(40.dp))
                Text(tr("今日暫無調用"), fontSize = 12.sp, color = MutedColor)
            }
        } else {
            items.forEachIndexed { index, item ->
                key(LogMath.logKey(item.entry)) {
                    LogRowItem(
                        entry = item.entry,
                        qpu = qpu,
                        isNew = item.isNew,
                        isLast = index == items.lastIndex,
                    )
                }
            }
        }
    }
}

/* ---------------- keyOnly 提示卡 ---------------- */

@Composable
private fun KeyOnlyCards(onNavigateSettings: () -> Unit) {
    EmptyStateCard(
        kind = FigureKind.KEY,
        title = tr("需要 Access Token"),
        sub = "模型調用分布要 Access Token 先查到（/api/user/models + /api/log/self）。\n目前只用 API Key 顯示計費總額。",
    ) {
        com.newapimonitor.ui.components.AppButton(tr("去設定"), onNavigateSettings, small = true)
    }
    EmptyStateCard(
        kind = FigureKind.LIST,
        title = "實時流水需要 Access Token",
        sub = "API Key 模式只有 /v1/dashboard/billing/* 總額數據。",
    )
}
