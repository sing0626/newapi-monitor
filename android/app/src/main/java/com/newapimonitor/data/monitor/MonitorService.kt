package com.newapimonitor.data.monitor

import com.newapimonitor.data.api.*
import com.newapimonitor.data.store.InstanceRepository
import com.newapimonitor.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class ReportSlice(
    val totalUsd: Double?,
    val rows: List<LogEntry>?,
    val partial: Boolean,
    val failure: String? = null,
    val at: Long = System.currentTimeMillis(),
    val qpu: Double = Quota.DEFAULT_QPU,
)
data class PeriodReport(val range: ReportRange, val current: ReportSlice, val previous: ReportSlice, val comparisonRange: ReportRange = range.previous())

class MonitorService(private val store: InstanceRepository) {
    val cache = MonitorStore(store.appContext)
    private fun client(i: Instance) = NewApiClient(i.baseUrl, i.accessToken, i.apiKey, i.authStyle, i.authStyleKey)

    /** Bounded concurrency; never changes the active instance or shares its credentials. */
    suspend fun refreshAll(instances: List<Instance>, old: List<MonitorSnapshot>): List<MonitorSnapshot> = coroutineScope {
        val permits = Semaphore(3)
        instances.map { instance -> async {
            permits.withPermit { refresh(instance, old.firstOrNull { it.id == instance.id }).also {
                store.withCurrentSource(instance) { cache.save(it) }
            } }
        } }.awaitAll()
    }

    suspend fun refresh(i: Instance, old: MonitorSnapshot?): MonitorSnapshot {
        val today = LocalDate.now()
        val month = YearMonth.from(today).toString()
        val now = System.currentTimeMillis()
        val stamp = MonitorSnapshot.stamp(i)
        val matchingOld = old?.takeIf { it.sourceStamp == stamp }
        var value = (matchingOld ?: MonitorSnapshot(i.id)).copy(
            attemptedAt = now, day = today.toString(), month = month,
            sourceStamp = stamp,
            todayUsd = matchingOld?.todayUsd?.takeIf { matchingOld.day == today.toString() },
            monthUsd = matchingOld?.monthUsd?.takeIf { matchingOld.month == month },
        )
        val c = client(i)
        try {
            withTimeout(20_000) {
                val status = optional { c.fetchStatus() }
                val qpu = Quota.resolveQpu(i.quotaPerUnit, status?.doubleOf("quota_per_unit"))
                if (i.accessToken.isNotBlank()) {
                    val user = optional { c.fetchUserSelf().asJsonObjectOrEmpty() }
                    c.setUserId(user?.longOf("id"))
                    val balance = user?.doubleOf("quota")?.takeIf { it.isFinite() }?.div(qpu)
                    value = value.copy(balanceUsd = balance ?: value.balanceUsd,
                        balanceAt = if (balance != null) now else value.balanceAt,
                        balanceFailure = if (balance == null) "無法讀取餘額" else null)
                    val daily = optional { dailyQuota(c.fetchDataSelf(today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond(), now / 1000), qpu)
                        .filter { !it.first.isBefore(today.withDayOfMonth(1)) && !it.first.isAfter(today) } }
                    value = value.copy(
                        todayUsd = daily?.filter { it.first == today }?.sumOf { it.second } ?: value.todayUsd,
                        monthUsd = daily?.sumOf { it.second } ?: value.monthUsd,
                        spendAt = if (daily != null) now else value.spendAt,
                        spendFailure = if (daily == null) "消費統計不可用" else null,
                    )
                } else {
                    val sub = c.fetchSubscription().doubleOf("hard_limit_usd")
                    val usage = c.fetchUsage().doubleOf("total_usage")?.div(100)
                    require(sub != null && usage != null && sub.isFinite() && usage.isFinite())
                    val usd = DisplayUnits.fromStatus(status).billingUsd(sub - usage)
                    value = value.copy(balanceUsd = usd ?: value.balanceUsd, balanceAt = if (usd != null) now else value.balanceAt,
                        balanceFailure = if (usd == null) "站點顯示單位未知或為 tokens，無法合計 USD" else null,
                        todayUsd = null, monthUsd = null, spendFailure = "需要 Access Token", spendAt = 0)
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            value = value.copy(balanceFailure = "連線逾時", spendFailure = "連線逾時")
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            value = value.copy(balanceFailure = "連線失敗", spendFailure = "連線失敗")
        }
        return value
    }

    suspend fun report(i: Instance, range: ReportRange, previousMonth: Boolean = false): PeriodReport = coroutineScope {
        range.validate()
        val c = client(i)
        val status = optional { c.fetchStatus() }
        val qpu = Quota.resolveQpu(i.quotaPerUnit, status?.doubleOf("quota_per_unit"))
        val user = optional { c.fetchUserSelf().asJsonObjectOrEmpty() }
        c.setUserId(user?.longOf("id"))
        val current = async { slice(c, range, qpu) }
        val comparison = if (previousMonth) range.previousMonth() else range.previous()
        val previous = async { slice(c, comparison, qpu) }
        PeriodReport(range, current.await(), previous.await(), comparison)
    }

    suspend fun reportAcross(sources: List<Instance>, range: ReportRange, previousMonth: Boolean): CrossReport = coroutineScope {
        val permits = Semaphore(3)
        CrossReport(range, sources.map { source -> async { permits.withPermit {
            if (source.accessToken.isBlank()) SourceReport(source.id, source.name, failure = "需要 Access Token")
            else try {
                SourceReport(source.id, source.name, withTimeout(45_000) { report(source, range, previousMonth) })
            } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); SourceReport(source.id, source.name, failure = "查詢逾時")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { SourceReport(source.id, source.name, failure = "讀取失敗") }
        } } }.awaitAll())
    }

    private suspend fun slice(c: NewApiClient, range: ReportRange, qpu: Double): ReportSlice = coroutineScope {
        val totals = async { optional { dailyQuota(c.fetchDataSelf(range.startSeconds(), minOf(range.endSeconds(), System.currentTimeMillis() / 1000)), qpu)
            .filter { !it.first.isBefore(range.start) && !it.first.isAfter(range.end) }.sumOf { it.second } } }
        val logs = async { optional { c.fetchLogWindow(range.startSeconds(), minOf(range.endSeconds(), System.currentTimeMillis() / 1000), maxPages = 10, type = 2) } }
        val w = logs.await()
        ReportSlice(totals.await(), w?.rows?.map(ResponseParsing::parseLogEntry)?.let(LogMath::filterConsume)
            ?.filter { it.createdAt in range.startSeconds()..range.endSeconds() }, w?.truncated ?: false,
            if (w == null) "日誌不可用；模型及令牌分析缺少資料" else null, qpu = qpu)
    }

    companion object {
        fun dailyQuota(data: kotlinx.serialization.json.JsonElement, qpu: Double): List<Pair<LocalDate, Double>> {
            require(data is JsonArray || (data is JsonObject && listOf("items", "records", "logs").any { data[it] is JsonArray }))
            return ResponseParsing.pickLogItems(data).map { row ->
                val quota = row.doubleOf("quota")
                val at = row.longOf("created_at")
                require(quota != null && quota.isFinite() && quota >= 0 && at != null && at > 0)
                java.time.Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault()).toLocalDate() to quota / qpu
            }
        }
        private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
