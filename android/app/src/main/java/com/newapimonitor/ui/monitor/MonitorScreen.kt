package com.newapimonitor.ui.monitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.domain.*
import com.newapimonitor.data.monitor.*
import com.newapimonitor.ui.common.DisposableLifecycleEffect
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.AppButtonKind
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.theme.*
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

@Composable
fun MonitorScreen(repo: NewApiRepository, onNavigateBack: () -> Unit, bottomContentPadding: Dp = 0.dp, section: Int = 1) {
    val vm: MonitorViewModel = viewModel(initializer = { MonitorViewModel(repo) })
    val state by vm.state.collectAsState()
    val instances by repo.store.instances.collectAsState()
    val settings by repo.store.settings.collectAsState()
    val alerts by vm.alerts.collectAsState()
    val scope = rememberCoroutineScope()
    val contentScroll = rememberScrollState()
    DisposableLifecycleEffect(onStart = { vm.selectSection(section); vm.resume() }, onStop = vm::pause)
    LaunchedEffect(section) { vm.selectSection(section); contentScroll.scrollTo(0) }
    LaunchedEffect(state.drilldown?.name) { contentScroll.scrollTo(0) }
    androidx.activity.compose.BackHandler {
        if (state.drilldown != null) vm.closeDetail() else onNavigateBack()
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .verticalScroll(contentScroll).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomContentPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (settings.demo) Hint("離線演示 · 金額及測速均為模擬資料")
        state.error?.let { Text(it, color = ErrRed) }
        when (section) {
            1 -> ReportPanel(state, settings.demo, vm, repo, instances)
            2 -> ProbePanel(instances, settings.demo, state, vm)
            3 -> {
                GlassCard(Modifier.fillMaxWidth()) {
                    Title("本機通知紀錄 · 未讀 ${alerts.count { !it.read }}")
                    Hint("保留最近 200 筆。內容只存本機；清除所有資料亦會清除此紀錄。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton("全部已讀", { scope.launch { vm.alertHistory.markRead() } }, small = true)
                        var confirm by remember { mutableStateOf(false) }
                        AppButton("清除紀錄", { confirm = true }, small = true, kind = AppButtonKind.DANGER)
                        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清除通知紀錄？") },
                            confirmButton = { TextButton(onClick = { scope.launch { vm.alertHistory.clear() }; confirm = false }) { Text("清除") } },
                            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
                    }
                }
                if (alerts.isEmpty()) GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                    Title("暫時沒有通知")
                    Hint("警報會在這裏保留。可前往設定開啟餘額及預算通知。")
                }
                alerts.forEach { record -> GlassCard(Modifier.fillMaxWidth()) {
                    Title((if (!record.read) "● " else "") + record.title)
                    Text(record.body, color = Txt2Color)
                    Hint((instances.firstOrNull { it.id == record.sourceId }?.name ?: "實例") + " · " + Fmt.logTime(record.time / 1000))
                } }
            }
        }
    }
}

@Composable
private fun ReportPanel(state: MonitorUiState, demo: Boolean, vm: MonitorViewModel, repo: NewApiRepository, instances: List<Instance>) {
    val today = LocalDate.now()
    var start by rememberSaveable { mutableStateOf(today.minusDays(6).toString()) }
    var end by rememberSaveable { mutableStateOf(today.toString()) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var monthError by remember { mutableStateOf(false) }
    var previousMonth by rememberSaveable { mutableStateOf(false) }
    var cross by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(instances.map { it.id }.toSet()) }
    var sourcesInitialized by remember { mutableStateOf(instances.isNotEmpty()) }
    LaunchedEffect(instances) {
        if (!sourcesInitialized && instances.isNotEmpty()) {
            selected = instances.map { it.id }.toSet()
            sourcesInitialized = true
        } else selected = selected.intersect(instances.map { it.id }.toSet())
    }
    var showMonth by rememberSaveable { mutableStateOf(false) }
    if (state.drilldown == null) GlassCard(Modifier.fillMaxWidth()) {
        Title("自訂日期報告")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(cross, { cross = it }); Text("跨實例比較", color = TxtColor)
        }
        if (cross && !demo) instances.forEach { source -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(source.id in selected, { selected = if (it) selected + source.id else selected - source.id })
            Text(source.name + if (source.accessToken.isBlank()) "（需要 Access Token）" else "", color = Txt2Color, fontSize = 12.sp)
        } }
        Hint(if (cross) "逐站點查詢，失敗來源會標示；最多 366 日。" else "按目前實例查詢，最多 366 日。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { start = today.minusDays(6).toString(); end = today.toString() }) { Text("最近 7 日") }
            TextButton(onClick = { start = today.withDayOfMonth(1).toString(); end = today.toString() }) { Text("本月") }
            TextButton(onClick = { showMonth = !showMonth }) { Text("歷史月份") }
        }
        if (showMonth) {
            OutlinedTextField(month, { month = it; monthError = false }, label = { Text("月份 YYYY-MM") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = monthError)
            if (monthError) Text("請輸入有效月份，不能超過本月。", color = ErrRed)
            TextButton(onClick = {
                val parsed = runCatching { YearMonth.parse(month) }.getOrNull()
                monthError = parsed == null || parsed.isAfter(YearMonth.now())
                if (!monthError && parsed != null) {
                    start = parsed.atDay(1).toString(); end = minOf(parsed.atEndOfMonth(), today).toString(); showMonth = false
                }
            }) { Text("套用月份") }
        }
        OutlinedTextField(start, { start = it }, label = { Text("開始 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(end, { end = it }, label = { Text("結束 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("比較期間", color = MutedColor, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !previousMonth, onClick = { previousMonth = false }, label = { Text("前段同日數") })
            FilterChip(selected = previousMonth, onClick = { previousMonth = true }, label = { Text("上月同期") })
        }
        Button(onClick = { if (cross) vm.reportAcross(selected, start, end, previousMonth) else vm.report(start, end, previousMonth) }, enabled = !state.reportLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.reportLoading) "查詢中…" else "查詢")
        }
        if (state.reportLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    state.crossReport?.let { CrossReportPanel(it); return }
    val report = state.report ?: return
    val detail = state.drilldown
    if (detail != null) {
        GlassCard(Modifier.fillMaxWidth()) {
            Title((if (detail.token) "令牌：" else "模型：") + detail.name.ifBlank { "未命名" })
            Hint("${report.range.start} 至 ${report.range.end} · 第 ${detail.page} 頁")
            if (detail.name.isEmpty()) Hint("未命名項目只涵蓋報告已載入嘅日誌。")
            AppButton("返回報告", vm::closeDetail, small = true)
            if (detail.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            detail.failure?.let { Text(it, color = ErrRed); AppButton("重試", { vm.detail(detail.name, detail.token, detail.page) }, small = true) }
            if (!detail.loading && detail.rows.isEmpty() && detail.failure == null) Hint("此頁冇符合條件嘅日誌。")
            detail.rows.forEach { log ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(log.modelName.orEmpty(), color = TxtColor, fontWeight = FontWeight.Bold)
                Hint("${log.tokenName.orEmpty()} · ${Fmt.logTime(log.createdAt)}")
                Hint("${Fmt.usd((log.quota ?: 0.0) / report.current.qpu, 5)} · ${log.promptTokens + log.completionTokens} tokens · ${log.useTime}s")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("上一頁", { vm.detail(detail.name, detail.token, detail.page - 1) }, small = true, enabled = !detail.loading && detail.page > 1)
                AppButton("下一頁", { vm.detail(detail.name, detail.token, detail.page + 1) }, small = true, enabled = !detail.loading && detail.more)
            }
        }
        return
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Title("${report.range.start} 至 ${report.range.end}")
        MoneyAmount(report.current.totalUsd, placeholder = "統計不可用", large = true)
        Hint("比較 ${report.comparisonRange.start} 至 ${report.comparisonRange.end}：${report.previous.totalUsd?.let { Fmt.usd(it) } ?: "無資料"}")
        CostBreakdown.change(report.current.totalUsd, report.previous.totalUsd)?.let { Hint("變化 ${String.format(Locale.US, "%+.1f", it)}%") }
        Hint("更新於 ${Fmt.logTime(report.current.at / 1000)} · " + if (demo) "離線模擬" else "總額來自站點每日統計")
        if (report.current.totalUsd == null) Text("每日統計讀取失敗；未將缺少資料當成零。", color = ErrRed)
        Hint("成本明細：${report.current.rows?.size ?: 0} 筆已載入日誌 · " + when { report.current.rows == null -> "不可用"; report.current.partial -> "部分資料（已達 1,000 筆上限）"; else -> "已載入完整窗口" })
        report.current.failure?.let { Text(it, color = ErrRed) }
        if (report.previous.partial || report.previous.rows == null) Hint("前段期間日誌亦為部分資料或不可用。")
    }
    ReportExportButton(CrossReport(report.range, listOf(SourceReport(repo.store.settings.value.activeId.orEmpty(),
        if (demo) "演示實例" else instances.firstOrNull { it.id == repo.store.settings.value.activeId }?.name ?: "實例", report))))
    listOf(false to "模型成本", true to "令牌成本").forEach { (token, title) ->
        GlassCard(Modifier.fillMaxWidth()) {
            Title(title)
            Hint("撳項目可查對應日期嘅分頁日誌；排序及金額只按已載入日誌計算。")
            val groups = CostBreakdown.group(report.current.rows.orEmpty(), report.current.qpu, token)
            if (groups.isEmpty()) Hint("冇可分析嘅日誌。")
            groups.take(50).forEach { group ->
                AppButton(group.name.ifBlank { "未命名" } + " · ${Fmt.usd(group.usd, 4)} · ${group.requests} 次",
                    { vm.detail(group.name, token) }, modifier = Modifier.fillMaxWidth(), small = true)
            }
        }
    }
}

@Composable
private fun ProbePanel(instances: List<Instance>, demo: Boolean, state: MonitorUiState, vm: MonitorViewModel) {
    var models by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(instances) { selected = selected.intersect(instances.map { it.id }.toSet()) }
    GlassCard(Modifier.fillMaxWidth()) {
        Title("比較串流表現")
        Hint("選擇實例及模型，量度首字延遲與輸出速度。")
        Text("每次最多輸出 64 tokens，可能收費。", color = WarnAmber, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        var method by remember { mutableStateOf(false) }
        TextButton(onClick = { method = !method }) { Text(if (method) "收起測量說明" else "測量方式與限制", fontSize = 12.sp) }
        if (method) {
            Hint("模型由 1 數到 10；每輪最多 10 次，每次限時 30 秒。只喺你按開始時執行。")
            Hint("首字延遲由發送到第一段非空輸出。tokens/s 以供應商回傳的 completion_tokens 計算；缺少用量就顯示 —，不估算。")
        }
        if (!demo) instances.forEach { instance ->
            Row { Checkbox(checked = instance.id in selected, enabled = !state.probing && instance.apiKey.isNotBlank(), onCheckedChange = {
                selected = if (it) selected + instance.id else selected - instance.id
            }); Text(instance.name + if (instance.apiKey.isBlank()) "（需要 API Key）" else "", color = TxtColor, modifier = Modifier.padding(top = 12.dp)) }
        }
        ModelPicker(vm, state, selected, demo, models) { models = it }
        OutlinedTextField(models, { models = it }, label = { Text("每行一個模型，最多 3 個") }, modifier = Modifier.fillMaxWidth(), enabled = !state.probing, minLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("開始測試", { vm.probe(selected, models) }, enabled = !state.probing, small = true, kind = AppButtonKind.PRIMARY)
            AppButton("停止", vm::stopProbe, enabled = state.probing, small = true)
        }
        if (state.probing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Hint(state.probeProgress) }
    }
    ProbeComparison(state, instances)
    state.results.forEach { result -> GlassCard(Modifier.fillMaxWidth()) {
        Title((instances.firstOrNull { it.id == result.sourceId }?.name ?: "演示實例") + " · " + result.model)
        Text(if (result.ok) "完成" else result.failure ?: "失敗", color = if (result.ok) OkGreen else ErrRed)
        Hint("首字 ${result.firstTokenMs?.let { "$it ms" } ?: "—"} · 全程 ${result.totalMs} ms")
        Hint("${result.completionTokens?.toString() ?: "—"} tokens · ${result.tokensPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} tokens/s")
    } }
    GlassCard(Modifier.fillMaxWidth()) {
        Title("本機測速紀錄")
        Hint("每實例保留最近 20 筆；新舊測速共用上限，唔儲存生成內容。")
        if (state.probeHistory.isEmpty()) Hint("未有測速紀錄。")
        state.probeHistory.take(30).forEach { (id, item) ->
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text((instances.firstOrNull { it.id == id }?.name ?: "演示實例") + " · " + item.model, color = TxtColor)
            Hint("${Fmt.logTime(item.timestamp / 1000)} · ${if (item.ok) "完成" else "失敗"} · ${item.latencyMs} ms")
            item.firstTokenMs?.let { Hint("首字 $it ms · ${item.tokensPerSecond?.let { r -> String.format(Locale.US, "%.1f", r) } ?: "—"} tokens/s") }
        }
    }
}

@Composable private fun Title(value: String) { Text(tr(value), color = TxtColor, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
@Composable private fun Hint(value: String) { Text(tr(value), color = MutedColor, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }
