package com.newapimonitor.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.ui.components.Sparkline
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.CyanLight
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.isLightMode
import com.newapimonitor.util.Fmt
import kotlin.math.roundToInt

/** Index nearest the drawn sparkline point (3 dp inset on either side). */
internal fun sparklineIndex(x: Float, width: Float, count: Int, pad: Float): Int {
    if (count <= 1 || !x.isFinite() || !width.isFinite() || width <= 2 * pad) return 0
    return (((x - pad) / (width - 2 * pad)) * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

/** A local, on-device balance history selection; only aggregate balance and time are shown. */
@Composable
internal fun InteractiveSparkline(
    points: List<DashboardViewModel.Sample>,
    label: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val values = remember(points) { points.map { it.v } }
    Box(modifier) {
        Sparkline(values, Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(points) {
                    detectTapGestures { position ->
                        val idx = sparklineIndex(position.x, size.width.toFloat(), points.size, 3.dp.toPx())
                        selected = if (selected == idx) null else idx
                    }
                }
                .pointerInput(points) {
                    detectDragGestures(
                        onDragStart = { position ->
                            selected = sparklineIndex(position.x, size.width.toFloat(), points.size, 3.dp.toPx())
                        },
                        onDrag = { change, _ ->
                            selected = sparklineIndex(change.position.x, size.width.toFloat(), points.size, 3.dp.toPx())
                            change.consume()
                        },
                    )
                }
        ) {
            val index = selected?.takeIf { it in points.indices } ?: return@Canvas
            val pad = 3.dp.toPx()
            val low = values.minOrNull() ?: return@Canvas
            val high = values.maxOrNull() ?: return@Canvas
            val x = pad + index * (size.width - 2 * pad) / (points.size - 1)
            val y = if (high - low < 1e-9 || !low.isFinite() || !high.isFinite()) size.height / 2f
                else pad + (1 - (values[index] - low) / (high - low)).toFloat() * (size.height - 2 * pad)
            if (!y.isFinite()) return@Canvas
            // H：白卡上用 accent 先睇得到（dark 沿用白線＋青點）
            val marker = if (isLightMode) Accent1 else CyanLight
            drawLine(
                if (isLightMode) Accent1.copy(alpha = 0.45f) else Color(0xAAFFFFFF),
                Offset(x, 0f),
                Offset(x, size.height),
                1.dp.toPx(),
            )
            drawCircle(marker, 5.dp.toPx(), Offset(x, y))
            drawCircle(Color.White, 2.dp.toPx(), Offset(x, y))
        }
        selected?.takeIf { it in points.indices }?.let { index ->
            val point = points[index]
            Text(
                text = "${label(point.t)} · ${Fmt.usd(point.v, 2)}",
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xF01E1B4B))
                    .border(1.dp, Color(0x998B5CF6), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = TabularStyle,
                maxLines = 1,
            )
        }
    }
}
