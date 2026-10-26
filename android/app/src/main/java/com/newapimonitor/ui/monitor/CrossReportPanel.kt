package com.newapimonitor.ui.monitor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.data.monitor.*
import com.newapimonitor.domain.CostBreakdown
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.theme.*
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.*

@Composable internal fun ReportExportButton(report: CrossReport) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val content = pending; pending = null
        if (uri != null && content != null) scope.launch {
            busy = true
            status = try { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            }; "報告已匯出" } catch (e: CancellationException) { throw e } catch (_: Exception) { "匯出失敗，請重試" }
            busy = false
        }
    }
    OutlinedButton(enabled = !busy, onClick = { scope.launch {
        busy = true
        try {
            pending = withContext(Dispatchers.Default) { ReportCsv.render(report) }
            export.launch("newapi-report-${report.range.start}-${report.range.end}.csv")
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { status = "未能準備報告，請重試"
        } finally { busy = false }
    } }, modifier = Modifier.fillMaxWidth()) { Text("匯出報告 CSV") }
    if (status.isNotEmpty()) Text(status, color = MutedColor, fontSize = 12.sp)
}

@Composable internal fun CrossReportPanel(report: CrossReport) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("跨實例消費", style = MaterialTheme.typography.titleMedium, color = TxtColor)
        Text("${report.range.start} 至 ${report.range.end}", color = MutedColor, fontSize = 12.sp)
        MoneyAmount(report.total, placeholder = "統計不可用", large = true)
        Text(if (report.available == report.sources.size) "已涵蓋全部 ${report.available} 個實例"
            else "涵蓋 ${report.available}/${report.sources.size} 個實例；失敗來源不當成零。",
            color = if (report.available == report.sources.size) MutedColor else WarnAmber, fontSize = 12.sp)
        ReportExportButton(report)
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("站點比較", color = TxtColor, style = MaterialTheme.typography.titleMedium)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row { listOf("實例", "本期 USD", "前期 USD", "變化").forEach { Text(it, Modifier.width(116.dp).padding(vertical = 12.dp), color = MutedColor, fontSize = 12.sp) } }
            report.sources.forEach { source ->
                val current = source.report?.current?.totalUsd; val previous = source.report?.previous?.totalUsd
                Row { listOf(source.name, current?.let { Fmt.usd(it) } ?: "不可用", previous?.let { Fmt.usd(it) } ?: "不可用",
                    CostBreakdown.change(current, previous)?.let { String.format(java.util.Locale.US, "%+.1f%%", it) } ?: "—").forEach {
                    Text(it, Modifier.width(116.dp).padding(end = 8.dp, bottom = 12.dp), color = Txt2Color, fontSize = 12.sp)
                } }
            }
        }
    }
    report.sources.forEach { source -> GlassCard(Modifier.fillMaxWidth()) {
        Text(source.name, color = TxtColor, style = MaterialTheme.typography.titleMedium)
        val data = source.report
        if (data == null) Text(source.failure ?: "讀取失敗", color = ErrRed)
        else {
            MoneyAmount(data.current.totalUsd, placeholder = "統計不可用")
            Text("比較 ${data.comparisonRange.start} 至 ${data.comparisonRange.end}", color = MutedColor, fontSize = 11.sp)
            Text(when { data.current.rows == null -> "模型成本明細不可用"; data.current.partial -> "模型成本：部分日誌（最多 1,000 筆）"; else -> "模型成本：已載入日誌" }, color = MutedColor, fontSize = 12.sp)
            data.current.failure?.let { Text(it, color = WarnAmber, fontSize = 12.sp) }
            CostBreakdown.group(data.current.rows.orEmpty(), data.current.qpu, false).take(10).forEach {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(it.name.ifBlank { "未命名" }, color = Txt2Color, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(Fmt.usd(it.usd, 4), color = TxtColor, fontSize = 12.sp)
                }
            }
        }
    } }
}
