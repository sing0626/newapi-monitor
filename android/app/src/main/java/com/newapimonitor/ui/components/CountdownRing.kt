package com.newapimonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.Accent2
import com.newapimonitor.ui.theme.BarTrackColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.TabularStyle
import kotlin.math.ceil

/**
 * 下次刷新倒數環（js .st-ring + renderClock）：
 * rAF 逐幀推進 — 文字只在秒數變化時重組，arc 每幀更新 → 完全平滑。
 * frac = remain/interval，arc 由滿圈逐漸清空，快到 0s 時刷新。
 */
@Composable
fun CountdownRing(
    targetMs: Long,
    intervalMs: Long,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    var remainMs by remember { mutableStateOf(if (paused) 0f else intervalMs.toFloat()) }

    LaunchedEffect(targetMs, intervalMs, paused) {
        if (paused || targetMs <= 0L) {
            remainMs = 0f
            return@LaunchedEffect
        }
        while (true) {
            remainMs = ((targetMs - System.currentTimeMillis()).coerceAtLeast(0L)).toFloat()
            withFrameNanos { } // 逐幀推進
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val strokeW = 3.dp.toPx()
                val r = (size.minDimension - strokeW) / 2f
                drawCircle(
                    color = BarTrackColor,
                    radius = r,
                    style = Stroke(width = strokeW),
                )
                val frac = if (intervalMs > 0) (remainMs / intervalMs).coerceIn(0f, 1f) else 0f
                drawArc(
                    brush = Brush.linearGradient(listOf(Accent1, Accent2)),
                    startAngle = -90f,
                    sweepAngle = 360f * frac,
                    useCenter = false,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            if (paused) "⏸" else "${ceil(remainMs / 1000f).toInt()}s",
            fontSize = 10.sp,
            color = MutedColor,
            style = TabularStyle,
        )
    }
}
