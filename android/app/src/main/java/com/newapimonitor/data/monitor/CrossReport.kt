package com.newapimonitor.data.monitor

import com.newapimonitor.domain.CostBreakdown
import com.newapimonitor.domain.ReportRange

data class SourceReport(val id: String, val name: String, val report: PeriodReport? = null, val failure: String? = null)
data class CrossReport(val range: ReportRange, val sources: List<SourceReport>) {
    val available get() = sources.count { it.report?.current?.totalUsd != null }
    val total get() = sources.mapNotNull { it.report?.current?.totalUsd }.takeIf { it.isNotEmpty() }?.sum()
}

object ReportCsv {
    /** Quote every cell and neutralize spreadsheet formulas from server-controlled names. */
    private fun cell(text: String): String {
        val guarded = if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || text.startsWith('\t') || text.startsWith('\r')) "'$text" else text
        return "\"" + guarded.replace("\"", "\"\"") + "\""
    }
    fun render(report: CrossReport): String {
        val rows = mutableListOf(listOf("section", "instance", "start", "end", "model_or_token", "usd", "requests", "coverage"))
        rows += listOf("all_sources", "", report.range.start.toString(), report.range.end.toString(), "",
            report.total?.toString().orEmpty(), "", "${report.available}/${report.sources.size}")
        report.sources.forEach { source ->
            val data = source.report
            rows += listOf("total", source.name, report.range.start.toString(), report.range.end.toString(), "",
                data?.current?.totalUsd?.toString().orEmpty(), "", if (data?.current?.totalUsd == null) source.failure ?: "unavailable" else "aggregate")
            if (data != null) {
                rows += listOf("comparison", source.name, data.comparisonRange.start.toString(), data.comparisonRange.end.toString(), "",
                    data.previous.totalUsd?.toString().orEmpty(), "", if (data.previous.totalUsd == null) "unavailable" else "aggregate")
                listOf(false, true).forEach { token -> CostBreakdown.group(data.current.rows.orEmpty(), data.current.qpu, token).forEach { group ->
                    rows += listOf(if (token) "token" else "model", source.name, report.range.start.toString(), report.range.end.toString(), group.name,
                        group.usd.toString(), group.requests.toString(), if (data.current.partial) "partial logs" else "loaded logs")
                } }
            }
        }
        return "\uFEFF" + rows.joinToString("\r\n") { it.joinToString(",", transform = ::cell) }
    }
}
