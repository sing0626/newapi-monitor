package com.newapimonitor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.domain.LogMath
import com.newapimonitor.ui.theme.Accent2
import com.newapimonitor.ui.theme.BarTrackColor
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.ChartPaletteColors
import com.newapimonitor.ui.theme.CostColor
import com.newapimonitor.ui.theme.DimColor
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.isLightMode
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay

/** 模型配色（charts.js PALETTE + colorForModel 穩定 hash） */
object ChartPalette {
    fun colorAt(index: Int): Color =
        ChartPaletteColors[((index % ChartPaletteColors.size) + ChartPaletteColors.size) % ChartPaletteColors.size]

    fun indexForModel(name: String?): Int = LogMath.colorIndexForModel(name, ChartPaletteColors.size)
}

private data class Seg(val value: Double, val color: Color)

/** Catmull-Rom → 三次貝茲（張力 1/6），移植 js charts.js _smoothPath */
private fun catmullRomInto(path: Path, pts: List<Offset>) {
    if (pts.isEmpty()) return
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return
    val t = 1f / 6f
    for (i in 0 until pts.size - 1) {
        val p0 = pts.getOrElse(i - 1) { pts[i] }
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts.getOrElse(i + 2) { p2 }
        path.cubicTo(
            p1.x + (p2.x - p0.x) * t, p1.y + (p2.y - p0.y) * t,
            p2.x - (p3.x - p1.x) * t, p2.y - (p3.y - p1.y) * t,
            p2.x, p2.y,
        )
    }
}

/**
 * Area sparkline（js Charts.sparkline）：Catmull-Rom 圓滑曲線 + 紫青漸變填充/描邊 +
 * 兩條暗格線 + 終點脈衝 dot。
 */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "sparkDot").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val pad = 3.dp.toPx()

        var lo = values.min()
        var hi = values.max()
        if (!lo.isFinite() || !hi.isFinite()) { lo = 0.0; hi = 1.0 }
        // 冇變化（span≈0）唔可以成條線黐死底邊兼俾 dot 裁半 — 攤平畫喺中線
        val flat = (hi - lo) < 1e-9
        val span = if (flat) 1.0 else hi - lo
        val n = values.size
        val pts = values.mapIndexed { i, x ->
            val x0 = if (n == 1) w - pad else pad + i * (w - 2 * pad) / (n - 1)
            val y0 = if (flat) h / 2f else pad + (1 - (x - lo) / span).toFloat() * (h - 2 * pad)
            Offset(x0, y0)
        }

        // 暗格線（h*0.34 / h*0.67）
        listOf(0.34f, 0.67f).forEach { f ->
            drawLine(
                color = HairlineColor,
                start = Offset(0f, h * f),
                end = Offset(w, h * f),
                strokeWidth = 1.dp.toPx(),
            )
        }

        val line = Path().also { catmullRomInto(it, pts) }
        val area = Path().apply {
            addPath(line)
            lineTo(pts.last().x, h)
            lineTo(pts.first().x, h)
            close()
        }
        // H：實心卡上 area fill／line 色要 mode-aware（light 用深一級先睇得到）
        drawPath(
            area,
            brush = Brush.verticalGradient(
                if (isLightMode) listOf(Color(0x408B5CF6), Color(0x0822D3EE))
                else listOf(Color(0x598B5CF6), Color(0x0522D3EE)),
                startY = 0f,
                endY = h,
            ),
        )
        drawPath(
            line,
            brush = Brush.horizontalGradient(
                if (isLightMode) listOf(Color(0xFF7C3AED), Color(0xFF0891B2))
                else listOf(Color(0xFFA78BFA), Accent2)
            ),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // 終點 dot + 脈衝環（js .spark-dot dotPulse）
        val last = pts.last()
        drawCircle(CostColor, radius = 2.5.dp.toPx(), center = last)
        val pr = 5.dp.toPx() * (0.55f + pulse * 1.25f)
        drawCircle(
            color = CostColor.copy(alpha = ((1f - pulse) * 0.65f).coerceIn(0f, 1f)),
            radius = pr,
            center = last,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/** sparkline 未夠取樣時嘅提示（js .spark-empty：呼吸點 + 文字） */
@Composable
fun SparkEmptyHint(sampleCount: Int, modifier: Modifier = Modifier) {
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "alpha",
    )
    Row(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .alpha(breathe)
                .background(DimColor, CircleShape)
        )
        Text(
            if (sampleCount >= 1) "已取樣 1 次，等下個 tick…" else "等待取樣…",
            fontSize = 11.sp,
            color = DimColor,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Donut（js Charts.donut）：圓角段 + 弧長 gap，細段自動降級 butt cap；
 * 中心玻璃碟；入場旋轉動畫（js .donutIn）；中心大數字＋小字（overlay）。
 * segments = [(value, color)]。
 */
@Composable
fun DonutChart(
    segments: List<Pair<Double, Color>>,
    centerVal: String,
    centerLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 128.dp,
    thickness: Dp = 15.dp,
    animate: Boolean = false,
) {
    val segs = segments.map { Seg(it.first, it.second) }
    val intro = remember(animate) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (animate) intro.animateTo(1f, tween(650, easing = EaseOutCubic))
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = intro.value
                rotationZ = -24f * (1f - intro.value)
                scaleX = 0.86f + 0.14f * intro.value
                scaleY = 0.86f + 0.14f * intro.value
            }
            .size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeW = thickness.toPx()
            val r = (this.size.minDimension - strokeW) / 2f - 2.dp.toPx()
            val circ = 2.0 * Math.PI * r
            val gap = 3.dp.toPx()
            val total = segs.sumOf { max(0.0, it.value) }
            if (total <= 0) {
                drawCircle(color = BarTrackColor, radius = r, style = Stroke(width = strokeW))
            } else {
                var off = 0.0
                segs.forEach { s ->
                    val frac = max(0.0, s.value) / total
                    val raw = frac * circ
                    var len = raw - gap - strokeW // round cap 令渲染弧長 = len + strokeW，預扣避免同鄰段重疊
                    var cap = StrokeCap.Round
                    if (len < 1.5) {
                        cap = StrokeCap.Butt
                        len = max(0.5, raw - gap * 0.6)
                    }
                    len = max(0.5, len)
                    drawArc(
                        color = s.color,
                        startAngle = -90f + (off / circ * 360.0).toFloat(),
                        sweepAngle = (len / circ * 360.0).toFloat(),
                        useCenter = false,
                        style = Stroke(width = strokeW, cap = cap),
                    )
                    off += raw
                }
                // 中心碟：托住文字，增加層次（H：實心卡色＋描邊）
                val discR = (r - strokeW / 2 - 2.dp.toPx()).coerceAtLeast(0f)
                drawCircle(color = CardFillColor, radius = discR)
                drawCircle(color = CardStrokeColor, radius = discR, style = Stroke(width = 1.dp.toPx()))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerVal, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor, style = TabularStyle)
            Text(centerLabel, fontSize = 9.sp, letterSpacing = 0.8.sp, color = MutedColor)
        }
    }
}

/** Top-N 橫向 bar 列（js Charts.hbars）：首次鋪設逐條生長（stagger delay） */
@Composable
fun HBarRow(
    index: Int,
    label: String,
    sub: String,
    pct: Double,
    color: Color,
    animateGrow: Boolean,
    modifier: Modifier = Modifier,
) {
    val target = (pct.coerceIn(2.0, 100.0) / 100.0).toFloat()
    val progress = remember { Animatable(if (animateGrow) 0f else target) }
    LaunchedEffect(target, animateGrow) {
        if (animateGrow && progress.value < 0.01f) {
            delay((index * 70).toLong())
        }
        progress.animateTo(target, tween(700, easing = EaseOutCubic))
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TxtColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            Text(sub, fontSize = 11.sp, color = MutedColor, style = TabularStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .height(6.dp)
                .fillMaxWidth()
                .background(BarTrackColor, RoundedCornerShape(50))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(
                        Brush.horizontalGradient(listOf(color.copy(alpha = 0.4f), color)),
                        RoundedCornerShape(50),
                    )
            )
        }
    }
}
