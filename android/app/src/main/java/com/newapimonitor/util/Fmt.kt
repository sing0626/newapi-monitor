package com.newapimonitor.util

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/** 格式化工具（移植 js/api.js 頂部工具函數），純 JVM 可測 */
object Fmt {

    /** 1,234,567（分組、無小數） */
    fun num(n0: Double): String {
        val n = if (n0.isFinite()) n0 else 0.0
        return String.format(Locale.US, "%,.0f", n)
    }

    fun compact(n0: Double): String {
        val n = if (n0.isFinite()) n0 else 0.0
        val a = abs(n)
        return when {
            a >= 1e9 -> trimDotZero(String.format(Locale.US, "%.1f", n / 1e9)) + "B"
            a >= 1e6 -> trimDotZero(String.format(Locale.US, "%.1f", n / 1e6)) + "M"
            a >= 1e3 -> trimDotZero(String.format(Locale.US, "%.1f", n / 1e3)) + "K"
            else -> round(n).toLong().toString()
        }
    }

    /** Compact USD keeps cents for small balances, with an exact-value disclosure in the UI. */
    fun usdCompact(value: Double): String {
        if (abs(value) < 1000) return usd(value)
        // Promote values that would round to 1000B (common for nearly 1T quotas).
        val magnitude = if (abs(value) >= 999_950_000_000.0)
            String.format(Locale.US, "%.2f", abs(value) / 1e12).trimEnd('0').trimEnd('.') + "T"
        else compact(abs(value))
        return (if (value < 0) "-$" else "$") + magnitude
    }

    private fun trimDotZero(s: String): String = if (s.endsWith(".0")) s.dropLast(2) else s

    /** 美元：$1,234.56；極細正值顯示 <$0.01（js fmtUsd） */
    fun usd(v0: Double, digits: Int = 2): String {
        val v = if (v0.isFinite()) v0 else 0.0
        val d = if (digits < 0) 2 else digits
        val tiny = 0.5 * Math.pow(10.0, -d.toDouble())
        if (v > 0 && v < tiny) {
            return "<$" + String.format(Locale.US, "%." + d + "f", Math.pow(10.0, -d.toDouble()))
        }
        val sign = if (v < 0) "-" else ""
        return sign + "$" + String.format(Locale.US, "%,." + d + "f", abs(v))
    }

    /** HH:MM:SS（本地時區） */
    fun clock(tsSec: Long): String {
        val t = Instant.ofEpochSecond(tsSec).atZone(ZoneId.systemDefault())
        return String.format(Locale.US, "%02d:%02d:%02d", t.hour, t.minute, t.second)
    }

    /** 同日 → HH:MM:SS，否則 MM-DD HH:MM（js fmtLogTime） */
    fun logTime(tsSec: Long, nowMs: Long = System.currentTimeMillis()): String {
        val z = ZoneId.systemDefault()
        val d = Instant.ofEpochSecond(tsSec).atZone(z)
        val now = Instant.ofEpochMilli(nowMs).atZone(z)
        return if (d.toLocalDate() == now.toLocalDate()) {
            clock(tsSec)
        } else {
            String.format(Locale.US, "%02d-%02d %02d:%02d", d.monthValue, d.dayOfMonth, d.hour, d.minute)
        }
    }

    /** 耗時：0.4s / 9s / 1m05s（js fmtDur） */
    fun dur(sec0: Double): String {
        val sec = if (sec0.isFinite()) sec0 else 0.0
        if (sec < 10) return String.format(Locale.US, "%.1fs", sec)
        if (sec < 60) return round(sec).toLong().toString() + "s"
        val m = (sec / 60).toInt()
        val s = round(sec % 60).toLong()
        return "${m}m" + pad2(s) + "s"
    }

    fun ago(tsSec: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diff = (nowMs / 1000.0 - tsSec).coerceAtLeast(0.0)
        return when {
            diff < 60 -> "剛剛"
            diff < 3600 -> (diff / 60).toInt().toString() + " 分鐘前"
            diff < 86400 -> (diff / 3600).toInt().toString() + " 小時前"
            else -> (diff / 86400).toInt().toString() + " 日前"
        }
    }

    fun todayStartTs(nowMs: Long = System.currentTimeMillis()): Long {
        val z = ZoneId.systemDefault()
        return Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate().atStartOfDay(z).toEpochSecond()
    }

    fun monthStartTs(nowMs: Long = System.currentTimeMillis()): Long {
        val z = ZoneId.systemDefault()
        return Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate()
            .withDayOfMonth(1).atStartOfDay(z).toEpochSecond()
    }

    private fun pad2(n: Long): String = if (n < 10) "0$n" else n.toString()
}
