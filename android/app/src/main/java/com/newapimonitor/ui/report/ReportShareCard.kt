package com.newapimonitor.ui.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.newapimonitor.domain.SpendCalendar
import com.newapimonitor.ui.theme.translated
import com.newapimonitor.util.Fmt

/** Renders only report aggregates; keys, token names and instance credentials never enter the image. */
object ReportShareCard {
    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private val bg = Color.rgb(20, 16, 31)
    private val card = Color.rgb(37, 30, 51)
    private val text = Color.rgb(242, 240, 250)
    private val muted = Color.rgb(169, 159, 194)
    private val accent = Color.rgb(139, 92, 246)

    fun render(state: ReportState, language: String): Bitmap {
        require(!state.loading && !state.noInstance && state.year > 0 && state.month in 1..12)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(bg)

        fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int) {
            paint.shader = null
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)
        }
        fun label(value: String, x: Float, baseline: Float, size: Float, color: Int, bold: Boolean = false) {
            paint.shader = null
            paint.color = color
            paint.style = Paint.Style.FILL
            paint.textSize = size
            paint.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText(value, x, baseline, paint)
        }

        label("NewAPI Monitor", 64f, 104f, 40f, text, true)
        label(translated("月度報告", language) + "  ·  ${state.year}-${state.month.toString().padStart(2, '0')}", 64f, 152f, 28f, muted)

        val hero = RectF(48f, 190f, 1032f, 470f)
        paint.shader = LinearGradient(hero.left, hero.top, hero.right, hero.bottom,
            intArrayOf(Color.rgb(109, 90, 224), Color.rgb(147, 51, 234), Color.rgb(217, 70, 239)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hero, 36f, 36f, paint)
        paint.shader = null
        label(translated("本月總消費", language), 84f, 278f, 32f, Color.WHITE)
        label(Fmt.usd(state.totalUsd, 2), 84f, 394f, 78f, Color.WHITE, true)

        roundRect(48f, 500f, 1032f, 1050f, 28f, card)
        label(translated("消費日曆", language), 84f, 570f, 34f, text, true)
        val grid = SpendCalendar.build(state.year, state.month, state.daily, state.nowMs)
        val cellWidth = 110f
        val cellHeight = 55f
        val gapX = 12f
        val gapY = 9f
        val startX = 84f
        val startY = 638f
        SpendCalendar.WEEKDAY_HEADER.forEachIndexed { i, ch ->
            label(ch.toString(), startX + i * (cellWidth + gapX) + 43f, startY - 22f, 22f, muted)
        }
        grid.weeks.forEachIndexed { row, week ->
            week.forEachIndexed { col, day ->
                val x = startX + col * (cellWidth + gapX)
                val y = startY + row * (cellHeight + gapY)
                if (day != null) {
                    val strength = if (day.isFuture) 0f else SpendCalendar.TIER_ALPHAS[day.tier.coerceIn(0, 4)]
                    val fill = if (strength == 0f) Color.rgb(55, 46, 78) else Color.argb((70 + strength * 185).toInt().coerceIn(0, 255), 139, 92, 246)
                    roundRect(x, y, x + cellWidth, y + cellHeight, 12f, fill)
                    if (day.isToday) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 4f
                        paint.color = Color.rgb(196, 181, 253)
                        canvas.drawRoundRect(RectF(x + 2f, y + 2f, x + cellWidth - 2f, y + cellHeight - 2f), 12f, 12f, paint)
                        paint.style = Paint.Style.FILL
                    }
                    label(day.day.toString(), x + 13f, y + 31f, 23f, text)
                }
            }
        }
        val footerY = 1120f
        label(translated("本月", language) + " · " + grid.spendDays + " " + translated("日", language), 64f, footerY, 27f, muted)
        val model = state.priciest?.name?.take(35)
        if (!model.isNullOrBlank()) {
            label(translated("最貴模型", language) + "  " + model, 64f, footerY + 50f, 26f, text)
        }
        if (state.logsTruncated) {
            label(translated("（約）", language), 64f, footerY + 95f, 24f, muted)
        }
        label("NewAPI Monitor", 64f, 1300f, 24f, muted)
        return bitmap
    }
}
