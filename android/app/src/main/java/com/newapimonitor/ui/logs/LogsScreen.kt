package com.newapimonitor.ui.logs

import com.newapimonitor.ui.theme.tr
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.EmptyStateCard
import com.newapimonitor.ui.components.ErrorCard
import com.newapimonitor.ui.components.FigureKind
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.components.SkeletonLogRow
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.util.Fmt

/**
 * 調用日誌：今日／7 日 chip 切換、按模型篩選、分頁列表、摘要列。
 * 只顯示消費記錄（type=2 參數 + 本地過濾雙保險）。
 * 方案 H 語言：chip 工具列（mockup .chips）+ .mrow 髮絲線流水列表（元件喺 LogsUi.kt，本地定義唔郁共用 Controls）。
 */
@Composable
fun LogsScreen(
    repo: NewApiRepository,
    onNavigateSettings: () -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val vm: LogsViewModel = viewModel(initializer = { LogsViewModel(repo) })
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    // Snapshot the rendered page at launch; the ViewModel survives the document picker's pause.
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val payload = vm.takeExport()
        if (uri != null && payload != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    ?: error("無法開啟檔案")
                Toast.makeText(context, "CSV 已儲存", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(context, "匯出失敗", Toast.LENGTH_SHORT).show() }
        }
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = vm.takeExport()
        if (uri != null && payload != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    ?: error("無法開啟檔案")
                Toast.makeText(context, "JSON 已儲存", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(context, "匯出失敗", Toast.LENGTH_SHORT).show() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 工具列：今日／7 日 chip（mockup .chips）+ 模型篩選 chip
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogsFilterChip(
                    label = tr("今日"),
                    active = state.range == "today",
                    onClick = { vm.setRange("today") },
                )
                LogsFilterChip(
                    label = tr("7 日"),
                    active = state.range == "7d",
                    onClick = { vm.setRange("7d") },
                )
                LogsModelChip(
                    value = state.model,
                    options = state.models,
                    onSelect = vm::setModel,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 摘要列（.logs-summary）：範圍·模型 pill chip + 右側 bold tabular 計數
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogsFilterChip(
                    label = buildString {
                        append(if (state.range == "today") tr("今日") else tr("7 日"))
                        if (state.model.isNotBlank()) append(" · ").append(state.model)
                    },
                    active = false,
                    onClick = null,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                val errorForSummary = state.error
                val totalForSummary = state.total
                Text(
                    when {
                        errorForSummary != null -> "載入失敗"
                        state.noAccessToken -> "—"
                        totalForSummary != null -> "共約 " + Fmt.num(totalForSummary.toDouble()) + " 筆" +
                            (if (totalForSummary >= 10000) "（計數上限 10000）" else "")
                        else -> if (state.loading) "載入中…" else state.rows.size.toString() + " 筆"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TxtColor,
                    style = TabularStyle,
                    maxLines = 1,
                )
            }
        }

        // 錯誤深入分析卡：永遠顯示「今日」type=5 錯誤聚合（同上面範圍／模型 chip 無關）；
        // 未載入／抓取失敗（errorRows=null）時成卡靜默收起
        ErrorAnalysisCard(errorRows = state.errorRows)

        when {
            state.loading -> GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                repeat(5) { SkeletonLogRow() }
            }
            state.error != null -> {
                val err = state.error!!
                ErrorCard(
                    title = err.displayTitle,
                    message = err.message,
                    hint = err.kind.hint(err.status),
                    onRetry = vm::retry,
                )
            }
            state.noAccessToken -> EmptyStateCard(
                kind = FigureKind.KEY,
                title = tr("需要 Access Token"),
                sub = "日誌（/api/log/self）係管理 API，要 Access Token 先查到；\n只有 API Key 嘅實例可用儀表板睇計費總額。",
            ) {
                AppButton(tr("去設定"), onNavigateSettings, small = true)
            }
            state.rows.isEmpty() -> EmptyStateCard(
                kind = FigureKind.LIST,
                title = tr("冇符合嘅日誌"),
                sub = tr("試下切換時間範圍或清除模型篩選。"),
            )
            else -> GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                state.rows.forEachIndexed { index, entry ->
                    LogsRowItem(
                        entry = entry,
                        qpu = state.qpu,
                        isLast = index == state.rows.lastIndex,
                    )
                }
            }
        }

        if (!state.loading && state.error == null && state.rows.isNotEmpty()) {
            GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "只匯出目前第 ${state.page} 頁 ${state.rows.size} 筆（每頁最多 ${state.pageSize} 筆）；不包括其他頁。" +
                        (if (state.hasMore || (state.total ?: 0) > state.page * state.pageSize) "尚有其他頁，匯出並非完整日誌。" else "") +
                        "已省略用戶名、令牌名及原始內容。",
                    fontSize = 11.sp, color = MutedColor,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(tr("匯出 CSV"), onClick = {
                        vm.prepareExport(LogExport.csv(state.rows.toList()))
                        csvLauncher.launch("newapi-logs-page-${state.page}.csv")
                    }, small = true)
                    AppButton(tr("匯出 JSON"), onClick = {
                        vm.prepareExport(LogExport.json(state.rows.toList()))
                        jsonLauncher.launch("newapi-logs-page-${state.page}.json")
                    }, small = true)
                }
            }
        }

        // 分頁器（.pager）：H 卡面
        GlassCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppButton(tr("← 上一頁"), vm::prevPage, small = true, enabled = state.page > 1 && !state.loading)
                Text(
                    "第 ${state.page} 頁" + (state.total?.let { t ->
                        " / 約 " + maxOf(1, ceilDiv(t, state.pageSize.toLong())) + " 頁"
                    } ?: ""),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MutedColor,
                    style = TabularStyle,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                AppButton(tr("下一頁 →"), vm::nextPage, small = true, enabled = state.hasMore && !state.loading)
            }
        }
    }
}

private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
