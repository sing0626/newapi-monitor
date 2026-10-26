package com.newapimonitor.ui.report

import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import com.newapimonitor.data.store.AppearanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.domain.SpendCalendar
import com.newapimonitor.ui.common.DisposableLifecycleEffect
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.AppButtonKind
import com.newapimonitor.ui.components.ChartPalette
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.components.HBarRow
import com.newapimonitor.ui.components.SectionHeader
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.Accent2
import com.newapimonitor.ui.theme.BarTrackColor
import com.newapimonitor.ui.theme.DimColor
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.OkGreen
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.WarnAmber
import com.newapimonitor.ui.theme.tr
import com.newapimonitor.util.Fmt
import java.util.Locale

/**
 * 月度報告（方案 H 實心卡）：
 *  - 摘要卡：月份標籤＋本月總消費大字＋較上月多／慳對比行（上次冇數據 → 「上月無數據」）
 *  - 消費日曆：GitHub-style 月格（SpendCalendar 純函數供格），Accent1 alpha 四檔，
 *    今日 Accent1 描邊、未來日暗槽；撳格 → 格下顯示「9月14日 · $12.40」
 *  - 消費統計：總調用／總 token／高峰時段（Heatmap）／最貴／最平模型（ModelCostStats）
 *  - 燒錢令牌前三：TokenSpendStats
 * 卡面數據抓取失敗一律收起，空月出「本月暫無消費數據」，loading 出轉圈。
 */
@Composable
fun ReportScreen(
    repo: NewApiRepository,
    onNavigateBack: () -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val vm: ReportViewModel = viewModel(initializer = { ReportViewModel(repo) })
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var shareFailed by remember { mutableStateOf(false) }
    val shareReport: () -> Unit = {
        if (!sharing && !state.loading && !state.noInstance) {
            sharing = true
            shareFailed = false
            val snapshot = state
            scope.launch {
                try {
                    val language = AppearanceStore(context.applicationContext).appearance.first().language
                    val uri = withContext(Dispatchers.IO) {
                        val image = ReportShareCard.render(snapshot, language)
                        try {
                            val dir = File(context.cacheDir, "report-cards").apply { mkdirs() }
                            dir.listFiles()?.forEach { it.delete() }
                            val file = File(dir, "report-${System.currentTimeMillis()}.png")
                            file.outputStream().use { output ->
                                check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
                            }
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        } finally {
                            image.recycle()
                        }
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                } catch (_: Exception) {
                    shareFailed = true
                } finally {
                    sharing = false
                }
            }
        }
    }

    // ON_STOP 暫停（cancel in-flight）/ ON_START 重抓；切走 tab（dispose）同樣暫停
    DisposableLifecycleEffect(onStart = vm::resume, onStop = vm::pause)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard(state = state, onNavigateBack = onNavigateBack)
        if (!state.noInstance && !state.loading) {
            AppButton(
                text = tr("分享本月戰績"),
                onClick = shareReport,
                enabled = !sharing,
                modifier = Modifier.fillMaxWidth(),
            )
            if (shareFailed) Text(tr("圖卡生成失敗"), color = com.newapimonitor.ui.theme.ErrRed, fontSize = 12.sp)
        }

        if (state.noInstance) {
            GlassCard {
                Text(
                    tr("未設定實例"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TxtColor,
                )
                Text(
                    "加入一個 NewAPI 實例（Base URL + Access Token）或者開演示模式，先有月度報告睇。",
                    fontSize = 11.5.sp,
                    color = MutedColor,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else if (state.loading) {
            LoadingCard()
        } else {
            CalendarCard(state)
            StatsCard(state)
            RealtimeCard(state)
            TopTokensCard(state)
            FlowCard(state)
        }
    }
}

/* ---------------- 摘要卡 ---------------- */

@Composable
private fun SummaryCard(state: ReportState, onNavigateBack: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("月度報告"),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TxtColor,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = tr("返回"),
                onClick = onNavigateBack,
                kind = AppButtonKind.GHOST,
                small = true,
            )
        }
        if (state.year > 0) {
            Text(
                "${state.year} 年 ${state.month} 月",
                fontSize = 12.sp,
                color = MutedColor,
                style = TabularStyle,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        when {
            state.noInstance || state.loading -> {}
            state.daily.isEmpty() -> Text(
                tr("本月暫無消費數據"),
                fontSize = 12.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 12.dp),
            )
            else -> {
                Text(
                    tr("本月總消費"),
                    fontSize = 11.sp,
                    color = MutedColor,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    Fmt.usd(state.totalUsd, 2),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TxtColor,
                    style = TabularStyle,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
                ComparisonLine(state)
            }
        }
    }
}

/** 對比行：較上月多（琥珀）／較上月慳（綠）＋（±X%）；上月窗口抓唔到 → 「上月無數據」 */
@Composable
private fun ComparisonLine(state: ReportState) {
    val diff = state.totalUsd - state.lastTotalUsd
    val pct = if (state.lastTotalUsd > 0.0) diff / state.lastTotalUsd * 100.0 else null
    val pctTxt = pct?.let { "（" + String.format(Locale.US, "%+.0f", it) + "%）" } ?: ""
    val line: Pair<String, Color>? = when {
        !state.lastMonthFetched -> tr("上月無數據") to MutedColor
        diff >= 0.005 -> (tr("較上月多 ") + Fmt.usd(diff, 2) + pctTxt) to WarnAmber
        diff <= -0.005 -> (tr("較上月慳 ") + Fmt.usd(-diff, 2) + pctTxt) to OkGreen
        else -> null // 同上月打平 → 唔出對比行
    }
    if (line != null) {
        Text(
            line.first,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = line.second,
            style = TabularStyle,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/* ---------------- 消費日曆卡 ---------------- */

@Composable
private fun CalendarCard(state: ReportState) {
    GlassCard {
        SectionHeader(
            tr("消費日曆"),
            chip = if (state.daily.isEmpty()) null else Fmt.usd(state.totalUsd, 2),
        )
        Text(
            tr("每日消費；顏色越深支出越多。"),
            fontSize = 11.sp,
            color = MutedColor,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (state.daily.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tr("本月暫無消費數據"), fontSize = 12.sp, color = MutedColor)
            }
            return@GlassCard
        }
        var selectedDay by remember(state.year, state.month) { mutableStateOf<Int?>(null) }
        val grid = remember(state.year, state.month, state.daily, state.nowMs) {
            SpendCalendar.build(state.year, state.month, state.daily, state.nowMs)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SpendCalendar.WEEKDAY_HEADER.forEach { w ->
                Text(
                    w,
                    fontSize = 10.sp,
                    color = DimColor,
                    textAlign = TextAlign.Center,
                    style = TabularStyle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            grid.weeks.forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { cell ->
                        DayCell(
                            cell = cell,
                            selected = cell != null && selectedDay == cell.day,
                            onTap = {
                                selectedDay =
                                    if (cell != null && selectedDay == cell.day) null else cell?.day
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        // 撳格 tooltip：「9月14日 · $12.40」（再撳同一格收起）
        selectedDay?.let { day ->
            grid.weeks.asSequence().flatten().filterNotNull()
                .firstOrNull { it.day == day }
                ?.let { c ->
                    Text(
                        "${state.month}月${c.day}日 · ${Fmt.usd(c.usd, 2)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Accent1,
                        style = TabularStyle,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
        }
        Text(
            "合計 " + Fmt.usd(grid.totalUsd, 2) + " · " + grid.spendDays + " 日有消費",
            fontSize = 10.5.sp,
            color = DimColor,
            style = TabularStyle,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 一格日曆：圓角 6dp 方格。tier 0/未來 = BarTrackColor 暗槽，1..4 = Accent1 alpha 四檔；今日 Accent1 描邊 */
@Composable
private fun DayCell(
    cell: SpendCalendar.Cell?,
    selected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cell == null) {
        Box(modifier.height(32.dp))
        return
    }
    val shape = RoundedCornerShape(6.dp)
    val bg = when {
        cell.isFuture -> BarTrackColor.copy(alpha = 0.45f)
        cell.tier <= 0 -> BarTrackColor
        else -> Accent1.copy(
            alpha = SpendCalendar.TIER_ALPHAS[
                cell.tier.coerceIn(1, SpendCalendar.TIER_ALPHAS.lastIndex),
            ],
        )
    }
    Box(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (cell.isToday || selected) {
                    Modifier.border(
                        width = if (cell.isToday) 1.5.dp else 1.dp,
                        color = if (selected && !cell.isToday) Txt2Color else Accent1,
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            cell.day.toString(),
            fontSize = 9.5.sp,
            fontWeight = if (cell.isToday || selected) FontWeight.Bold else FontWeight.Medium,
            color = if (!cell.isFuture && cell.tier >= 2) Color.White else Txt2Color,
            style = TabularStyle,
            maxLines = 1,
        )
    }
}

/* ---------------- 消費統計卡 ---------------- */

@Composable
private fun StatsCard(state: ReportState) {
    if (!state.logsReady) return // 日誌抓取失敗 → 成張卡收起
    GlassCard {
        SectionHeader("消費統計", chip = if (state.logsTruncated) tr("（約）") else null)
        if (state.calls == 0) {
            Text(
                tr("本月暫無消費數據"),
                fontSize = 12.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Column(Modifier.padding(top = 4.dp)) {
                StatRow(tr("總調用次數"), Fmt.num(state.calls.toDouble()))
                StatRow(tr("總 token 數"), Fmt.compact(state.totalTokens.toDouble()))
                StatRow(
                    tr("高峰時段"),
                    if (state.peakHour >= 0) "%02d:00".format(state.peakHour) else "—",
                )
                StatRow(tr("最貴模型"), state.priciest?.name ?: "—")
                StatRow(tr("最平模型"), state.cheapest?.name ?: "—")
            }
        }
        if (state.logsTruncated) {
            Text(
                tr("報告基於本月日誌（最多 1000 行）；超出部分未計入。"),
                fontSize = 10.sp,
                color = DimColor,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = MutedColor, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = TxtColor,
            style = TabularStyle,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.6f),
        )
    }
}

/* ---------------- 實時用量卡（/api/log/self/stat） ---------------- */

/** 實時用量：RPM／TPM／累計消費。stat==null（端點失敗／不支援／key-only）→ 成張卡收起 */
@Composable
private fun RealtimeCard(state: ReportState) {
    val stat = state.stat ?: return
    GlassCard {
        SectionHeader(tr("實時用量"), chip = tr("站點統計"))
        Column(Modifier.padding(top = 4.dp)) {
            StatRow(tr("每分鐘請求（RPM）"), Fmt.num(stat.rpm.toDouble()))
            StatRow(tr("每分鐘 token（TPM）"), Fmt.compact(stat.tpm.toDouble()))
            StatRow(tr("累計消費"), Fmt.usd(stat.quota / state.qpu, 4))
            StatRow(tr("累計 token"), Fmt.compact(stat.tokenUsed.toDouble()))
        }
        Text(
            tr("數據來自站點實時統計；舊版站點未必支援，收起即係站點無提供。"),
            fontSize = 10.sp,
            color = DimColor,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/* ---------------- 流量分佈卡（/api/data/flow/self） ---------------- */

/** 官方流量分佈：模型／令牌／分組三個維度各 top 5 份額。flow==null → 成張卡收起 */
@Composable
private fun FlowCard(state: ReportState) {
    val flow = state.flow ?: return
    val dims = listOf(
        tr("模型") to flow.models,
        tr("令牌") to flow.tokens,
        tr("分組") to flow.groups,
    ).mapNotNull { (title, items) ->
        val top = items.sortedByDescending { it.value }.take(5)
        val total = top.sumOf { it.value }
        if (top.isEmpty() || total <= 0.0) null else Triple(title, top, total)
    }
    if (dims.isEmpty()) return
    GlassCard {
        SectionHeader(
            tr("流量分佈"),
            chip = if (state.flowClamped) tr("最近 30 日") else tr("本月"),
        )
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            dims.forEach { (title, top, total) ->
                Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = MutedColor)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    top.forEachIndexed { i, item ->
                        HBarRow(
                            index = i,
                            label = item.name,
                            sub = String.format(Locale.US, "%.0f%%", item.value / total * 100.0),
                            pct = item.value / total * 100.0,
                            color = ChartPalette.colorAt(i),
                            animateGrow = true,
                        )
                    }
                }
            }
        }
        Text(
            if (state.flowClamped) tr("官方流量聚合每次查詢最多 30 日；已由本月窗口夾到上限。")
            else tr("份額按站點回報嘅聚合值計。"),
            fontSize = 10.sp,
            color = DimColor,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/* ---------------- 燒錢令牌卡 ---------------- */

@Composable
private fun TopTokensCard(state: ReportState) {
    if (!state.logsReady) return // 日誌抓取失敗 → 成張卡收起
    GlassCard {
        SectionHeader(tr("燒錢令牌前三"))
        if (state.topTokens.isEmpty()) {
            Text(
                tr("本月暫無消費數據"),
                fontSize = 12.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Column(Modifier.padding(top = 4.dp)) {
                state.topTokens.forEachIndexed { i, t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(InnerFillColor)
                                .border(1.dp, StrokeSoft, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                (i + 1).toString(),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Accent1,
                            )
                        }
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(
                                t.name,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TxtColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                Fmt.num(t.calls.toDouble()) + " " + tr("次"),
                                fontSize = 10.sp,
                                color = MutedColor,
                                style = TabularStyle,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                        Text(
                            Fmt.usd(t.usd, 2),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TxtColor,
                            style = TabularStyle,
                            maxLines = 1,
                        )
                    }
                    if (i != state.topTokens.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
                    }
                }
            }
        }
    }
}

/* ---------------- 載入卡 ---------------- */

@Composable
private fun LoadingCard() {
    GlassCard {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = Accent2,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
