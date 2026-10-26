package com.newapimonitor.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.monitor.MonitorSnapshot
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.domain.Instance
import com.newapimonitor.ui.common.DisposableLifecycleEffect
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.components.StatusDot
import com.newapimonitor.ui.theme.*
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** All-source totals use the same fresh snapshots as the individual cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    repo: NewApiRepository,
    onOpenInstance: (String) -> Unit,
    onOpenReport: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSettings: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    val vm: MonitorViewModel = viewModel(initializer = { MonitorViewModel(repo) })
    val preferenceStore = remember(repo) { com.newapimonitor.data.monitor.MonitorPreferencesStore(repo.store.appContext) }
    val options by preferenceStore.preferences.collectAsState(com.newapimonitor.data.monitor.MonitorPreferences())
    val credentialError by repo.store.credentialError.collectAsState()
    var homeOptions by remember { mutableStateOf(false) }
    var ruleSource by remember { mutableStateOf<Instance?>(null) }
    val state by vm.state.collectAsState()
    val settings by repo.store.settings.collectAsState()
    val instances by repo.store.instances.collectAsState()
    val snapshots by vm.snapshots.collectAsState()
    val budgets by vm.budgets.collectAsState()
    val alerts by vm.alerts.collectAsState()
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var editing by remember { mutableStateOf<Instance?>(null) }
    DisposableLifecycleEffect(onStart = { vm.selectSection(0); vm.resume() }, onStop = vm::pause)
    LaunchedEffect(Unit) { while (true) { delay(15_000); clock = System.currentTimeMillis() } }
    val sources = if (settings.demo) listOf(Instance(id = "demo", name = "演示實例")) else options.sorted(instances) { it.id }
    val rows = if (settings.demo) listOf(MonitorSnapshot("demo", 123.45, 1.25, 22.0, clock, clock, clock))
        else snapshots.filter { row -> instances.any { it.id == row.id && MonitorSnapshot.stamp(it) == row.sourceStamp } }
            .map { it.copy(todayUsd = it.todayUsd.takeIf { _ -> it.day == LocalDate.now().toString() },
                monthUsd = it.monthUsd.takeIf { _ -> it.month == YearMonth.now().toString() }) }
    val balances = rows.filter { it.balanceFresh(clock) && it.id !in options.excluded }
    val spends = rows.filter { it.spendFresh(clock) }
    val months = spends.filter { it.monthUsd != null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomContentPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        credentialError?.let { Text(it, color = ErrRed, fontSize = 12.sp) }
        if (settings.demo) Text("離線演示 · 所有金額及測速均為模擬資料", color = WarnAmber, fontSize = 12.sp)
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Accent1.copy(alpha = .20f), Accent2.copy(alpha = .04f))))
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("總餘額", color = Txt2Color, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(if (state.refreshing) "正在更新…" else "已更新 ${balances.size}/${sources.size}",
                        color = if (balances.size == sources.size && sources.isNotEmpty()) Accent2 else MutedColor, fontSize = 12.sp)
                }
                MoneyAmount(balances.takeIf { it.isNotEmpty() }?.sumOf { it.balanceUsd!! },
                    placeholder = if (state.refreshing) "載入中" else "暫無資料", large = true, hidden = options.hideMoney)
                HorizontalDivider(color = CardStrokeColor)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("今日消費", color = MutedColor, fontSize = 12.sp)
                        MoneyAmount(spends.takeIf { it.isNotEmpty() }?.sumOf { it.todayUsd!! }, hidden = options.hideMoney)
                        Coverage(spends.size, sources.size)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("本月消費", color = MutedColor, fontSize = 12.sp)
                        MoneyAmount(months.takeIf { it.isNotEmpty() }?.sumOf { it.monthUsd!! }, hidden = options.hideMoney)
                        Coverage(months.size, sources.size)
                    }
                }
                Text("合計只計最近 90 秒成功更新的資料" + if (options.excluded.isEmpty()) "" else " · 已排除 ${sources.count { it.id in options.excluded }} 個餘額", color = MutedColor, fontSize = 11.sp)
            }
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent1)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction("消費報告", "↗", onOpenReport, Modifier.weight(1f))
            QuickAction("模型測速", "≋", onOpenProbe, Modifier.weight(1f))
            QuickAction(if (alerts.any { !it.read }) "通知 · ${alerts.count { !it.read }}" else "通知", "◷", onOpenAlerts, Modifier.weight(1f))
        }
        state.error?.let { Text(it, color = ErrRed, fontSize = 12.sp) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("實例", color = TxtColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { homeOptions = true }) { Text("自訂", fontSize = 12.sp) }
            TextButton(onClick = vm::refresh, enabled = !state.refreshing && !settings.demo && sources.isNotEmpty()) {
                Text(if (state.refreshing) "更新中…" else "全部刷新", fontSize = 12.sp)
            }
        }
        if (sources.isEmpty()) GlassCard(Modifier.fillMaxWidth()) {
            Text("加入第一個實例", color = TxtColor, fontWeight = FontWeight.Bold)
            Text("連接 NewAPI 站點，查看餘額及消費。", color = MutedColor, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = onOpenSettings) { Text("新增實例") }
        }
        sources.forEach { source ->
            val row = rows.firstOrNull { it.id == source.id }
            val fresh = row?.balanceFresh(clock) == true
            val budget = budgets[source.id]
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(if (options.compact) 12.dp else 16.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onOpenInstance(source.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (fresh) OkGreen else if (row?.balanceFailure != null) ErrRed else WarnAmber, Modifier.size(7.dp))
                    Spacer(Modifier.width(8.dp))
                    Text((if (source.id in options.pinned) "◆ " else "") + source.name, color = TxtColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("查看詳情 ›", color = Accent1, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                MoneyAmount(row?.balanceUsd, placeholder = if (state.refreshing) "載入中" else "未能取得", large = false, hidden = options.hideMoney)
                Text(when { row == null -> if (state.refreshing) "正在連接站點" else "等待更新 · 可撳全部刷新重試"
                    fresh -> "已更新 · ${Fmt.logTime(row.balanceAt / 1000)}"
                    row.balanceAt > 0 -> "舊資料 · ${Fmt.logTime(row.balanceAt / 1000)}"
                    else -> "連線失敗 · 可撳全部刷新重試" },
                    color = if (fresh) MutedColor else WarnAmber, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                row?.balanceFailure?.let { Text(it, color = ErrRed, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (source.id in options.excluded) Text("餘額不計入合計", color = MutedColor, fontSize = 11.sp)
                if (!options.compact) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = CardStrokeColor)
                if (!options.compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpendCell("今日", row?.todayUsd, Modifier.weight(1f), options.hideMoney)
                    SpendCell("本月", row?.monthUsd, Modifier.weight(1f), options.hideMoney)
                }
                if (options.compact) Text("今日 " + (if (options.hideMoney) "••••" else row?.todayUsd?.let(Fmt::usdCompact) ?: "未提供") +
                    " · 本月 " + (if (options.hideMoney) "••••" else row?.monthUsd?.let(Fmt::usdCompact) ?: "未提供"),
                    color = Txt2Color, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                if (row?.spendFresh(clock) != true) Text(
                    row?.spendFailure ?: if (state.refreshing) "消費資料更新中…" else if ((row?.spendAt ?: 0L) > 0L) "消費為舊資料" else "消費資料暫未提供",
                    color = WarnAmber, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                if (!settings.demo) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (budget == null) "未設定月度預算" else if (options.hideMoney) "已設定月度預算" else "月度預算 ${Fmt.usdCompact(budget)}",
                            color = MutedColor, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { ruleSource = source }) { Text("警報", fontSize = 12.sp) }
                        TextButton(onClick = { editing = source }) { Text("編輯預算", fontSize = 12.sp) }
                    }
                    if (budget != null && row?.monthUsd != null) {
                        val ratio = row.monthUsd / budget
                        LinearProgressIndicator(progress = { ratio.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                            color = if (ratio >= .9) WarnAmber else Accent1)
                        Text("已用 ${String.format(Locale.US, "%.1f", ratio * 100)}%" + if (row.spendFresh(clock)) "" else " · 舊資料",
                            color = MutedColor, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
    if (homeOptions) HomeOptionsSheet(options, sources, preferenceStore) { homeOptions = false }
    ruleSource?.let { source -> InstanceAlertSheet(repo, source) { ruleSource = null } }
    editing?.let { source ->
        var input by remember(source.id) { mutableStateOf(budgets[source.id]?.toString().orEmpty()) }
        var invalid by remember(source.id) { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { editing = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("月度預算", color = TxtColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(source.name, color = MutedColor, fontSize = 14.sp)
                OutlinedTextField(input, { input = it; invalid = false }, label = { Text("預算 USD（留空關閉）") },
                    isError = invalid, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                if (invalid) Text("請輸入大於零的有效金額", color = ErrRed, fontSize = 12.sp)
                Text("開啟警報後，用量達 90% 及 100% 時各通知一次。", color = MutedColor, fontSize = 12.sp)
                Button(onClick = {
                    val amount = input.toDoubleOrNull()
                    invalid = input.isNotBlank() && (amount == null || !amount.isFinite() || amount <= 0)
                    if (!invalid) { vm.budget(source.id, input); editing = null }
                }, modifier = Modifier.fillMaxWidth()) { Text("儲存預算") }
            }
        }
    }
}

@Composable private fun Coverage(count: Int, total: Int) {
    if (count < total) Text("涵蓋 $count/$total 實例", color = WarnAmber, fontSize = 10.sp)
}

@Composable private fun SpendCell(label: String, amount: Double?, modifier: Modifier, hidden: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MutedColor, fontSize = 11.sp)
        MoneyAmount(amount, hidden = hidden)
    }
}

@Composable private fun QuickAction(label: String, symbol: String, action: () -> Unit, modifier: Modifier) {
    GlassCard(modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp), onClick = action) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(24.dp)) {
                val s = size.width / 24f
                val stroke = Stroke(width = 1.8f * s, cap = StrokeCap.Round)
                when (symbol) {
                    "↗" -> {
                        val path = Path().apply {
                            moveTo(3*s,19*s); lineTo(3*s,5*s)
                            moveTo(3*s,19*s); lineTo(21*s,19*s)
                            moveTo(7*s,14*s); lineTo(12*s,9*s); lineTo(15*s,12*s); lineTo(21*s,5*s)
                        }
                        drawPath(path, Accent1, style = stroke)
                    }
                    "≋" -> {
                        drawArc(Accent1, 180f, 180f, false, Offset(3*s,4*s), Size(18*s,18*s), style = stroke)
                        drawLine(Accent1, Offset(12*s,13*s), Offset(17*s,8*s), stroke.width, StrokeCap.Round)
                        drawCircle(Accent1, 1.6f*s, Offset(12*s,13*s))
                        drawLine(Accent1, Offset(4*s,19*s), Offset(20*s,19*s), stroke.width, StrokeCap.Round)
                    }
                    else -> {
                        drawCircle(Accent1, 9*s, Offset(12*s,12*s), style = stroke)
                        drawLine(Accent1, Offset(12*s,6*s), Offset(12*s,12*s), stroke.width, StrokeCap.Round)
                        drawLine(Accent1, Offset(12*s,12*s), Offset(16*s,14*s), stroke.width, StrokeCap.Round)
                    }
                }
            }
            Text(label, color = Txt2Color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Currency is a separate small label. Tap any amount for its unabridged value. */
@Composable
internal fun MoneyAmount(value: Double?, placeholder: String = "未提供", large: Boolean = false, hidden: Boolean = false) {
    var exact by remember { mutableStateOf(false) }
    Row(Modifier.clickable(enabled = value != null && !hidden) { exact = true }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (hidden) "••••" else value?.let(Fmt::usdCompact) ?: placeholder, color = TxtColor,
            fontSize = if (large) 38.sp else 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (value != null && !hidden) Text("USD", color = MutedColor, fontSize = 10.sp, modifier = Modifier.padding(bottom = if (large) 7.dp else 4.dp))
    }
    if (exact && value != null && !hidden) AlertDialog(onDismissRequest = { exact = false }, title = { Text("完整金額") },
        text = { Text("${Fmt.usd(value, 5)} USD") }, confirmButton = { TextButton(onClick = { exact = false }) { Text("關閉") } })
}
