package com.newapimonitor.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.Accent2
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.DimColor
import com.newapimonitor.ui.theme.ErrRed
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.WarnAmber

/**
 * 錯誤卡（.err-card）：左緣狀態色條 + 色調背景；warn 變體係琥珀色（部分失敗）。
 * 唔准白屏 — 有錯一律有設計過嘅卡 + 重試。
 */
@Composable
fun ErrorCard(
    title: String,
    message: String,
    hint: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val accent = if (warn) WarnAmber else ErrRed
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .shadow(elevation = 8.dp, shape = shape, clip = false)
            .clip(shape)
            .background(CardFillColor)
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.10f), accent.copy(alpha = 0.04f))
                )
            )
            .border(1.dp, CardStrokeColor, shape)
            .drawBehind {
                // 左緣狀態色條（.err-card::after）
                drawRect(
                    brush = Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.05f))),
                    topLeft = Offset.Zero,
                    size = Size(3.dp.toPx(), size.height),
                )
            }
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                (if (warn) "◌ " else "⚠ ") + title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (warn) WarnAmber.copy(alpha = 0.9f) else ErrRed.copy(alpha = 0.85f),
            )
            if (message.isNotEmpty()) {
                Text(message, fontSize = 12.sp, color = TxtColor.copy(alpha = 0.85f), modifier = Modifier.padding(top = 5.dp))
            }
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    fontSize = 11.sp,
                    color = MutedColor,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (onRetry != null || onSettings != null) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onRetry != null) AppButton("重試", onRetry, kind = AppButtonKind.PRIMARY, small = true)
                    if (onSettings != null) AppButton("檢查設定", onSettings, kind = AppButtonKind.GHOST, small = true)
                }
            }
        }
    }
}

enum class FigureKind { KEY, CHART, LIST, INBOX }

/**
 * 空狀態卡（.empty-card）：漸變光暈 + 虛線旋轉光環 + Canvas 插圖（key/chart/list/inbox）。
 */
@Composable
fun EmptyStateCard(
    kind: FigureKind,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    val spin by rememberInfiniteTransition(label = "emptyRing").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(36000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    Box(
        modifier
            .shadow(elevation = 8.dp, shape = shape, clip = false)
            .clip(shape)
            .background(CardFillColor)
            .border(1.dp, CardStrokeColor, shape)
            .drawBehind {
                // 頂部漸變光暈（.empty-card::after）
                drawRect(
                    brush = Brush.radialGradient(
                        listOf(Color(0x298B5CF6), Color.Transparent),
                        center = Offset(size.width / 2f, 0f),
                        radius = size.width * 0.62f,
                    )
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 32.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(86.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    // 內部漸變光碟
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0x248B5CF6), Color(0x0A22D3EE), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.38f),
                            radius = size.minDimension / 2f,
                        ),
                        radius = size.minDimension / 2f,
                    )
                    // 虛線旋轉光環
                    rotate(spin) {
                        drawCircle(
                            color = Color(0x668B5CF6),
                            radius = size.minDimension / 2f - 1.dp.toPx(),
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                                ),
                            ),
                        )
                    }
                }
                EmptyFigureIcon(kind, Modifier.size(42.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TxtColor, textAlign = TextAlign.Center)
            Text(
                sub,
                fontSize = 12.sp,
                color = MutedColor,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 5.dp),
            )
            if (actions != null) {
                Row(
                    Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/** 64 viewport 插圖（js Charts.iconEmpty）：漸變描邊 vector 圖形 */
@Composable
fun EmptyFigureIcon(kind: FigureKind, modifier: Modifier = Modifier) {
    val grad = Brush.linearGradient(listOf(Accent1, Accent2))
    Canvas(modifier) {
        val s = size.width / 64f
        val stroke = Stroke(width = 2.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            FigureKind.KEY -> {
                drawRoundRect(
                    brush = grad,
                    topLeft = Offset(14 * s, 26 * s),
                    size = Size(36 * s, 26 * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7 * s, 7 * s),
                    style = stroke,
                )
                val shackle = Path().apply {
                    moveTo(22 * s, 26 * s)
                    lineTo(22 * s, 20 * s)
                    arcTo(Rect(22 * s, 10 * s, 42 * s, 30 * s), startAngleDegrees = 180f, sweepAngleDegrees = 180f, forceMoveTo = false)
                    lineTo(42 * s, 26 * s)
                }
                drawPath(shackle, brush = grad, style = stroke)
                drawCircle(Color(0xFF22D3EE), radius = 3.4f * s, center = Offset(32 * s, 39 * s))
            }
            FigureKind.CHART -> {
                drawRoundRect(brush = grad, topLeft = Offset(10 * s, 34 * s), size = Size(10 * s, 20 * s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * s, 3 * s), style = stroke)
                drawRoundRect(brush = grad, topLeft = Offset(27 * s, 24 * s), size = Size(10 * s, 30 * s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * s, 3 * s), style = stroke)
                drawRoundRect(brush = grad, topLeft = Offset(44 * s, 14 * s), size = Size(10 * s, 40 * s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * s, 3 * s), style = stroke)
            }
            FigureKind.LIST -> {
                drawRoundRect(
                    brush = grad,
                    topLeft = Offset(12 * s, 10 * s),
                    size = Size(40 * s, 44 * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7 * s, 7 * s),
                    style = stroke,
                )
                val lines = Path().apply {
                    moveTo(21 * s, 24 * s); lineTo(43 * s, 24 * s)
                    moveTo(21 * s, 33 * s); lineTo(43 * s, 33 * s)
                    moveTo(21 * s, 42 * s); lineTo(34 * s, 42 * s)
                }
                drawPath(lines, brush = grad, style = stroke)
            }
            FigureKind.INBOX -> {
                val tray = Path().apply {
                    moveTo(12 * s, 34 * s)
                    lineTo(20 * s, 16 * s)
                    lineTo(44 * s, 16 * s)
                    lineTo(52 * s, 34 * s)
                    lineTo(52 * s, 46 * s)
                    arcTo(Rect(44 * s, 42 * s, 52 * s, 50 * s), startAngleDegrees = 0f, sweepAngleDegrees = 90f, forceMoveTo = false)
                    lineTo(16 * s, 50 * s)
                    arcTo(Rect(12 * s, 42 * s, 20 * s, 50 * s), startAngleDegrees = 90f, sweepAngleDegrees = 90f, forceMoveTo = false)
                    close()
                }
                drawPath(tray, brush = grad, style = stroke)
                val handle = Path().apply {
                    moveTo(12 * s, 34 * s)
                    lineTo(24 * s, 34 * s)
                    arcTo(Rect(24 * s, 26 * s, 40 * s, 42 * s), startAngleDegrees = 180f, sweepAngleDegrees = -180f, forceMoveTo = false)
                    lineTo(52 * s, 34 * s)
                }
                drawPath(handle, brush = grad, style = stroke)
            }
        }
    }
}

/** 部分失敗 banner（js renderBanner warn 分支）：key → 中文標籤 */
fun partialErrorLabel(key: String): String = when (key) {
    "user" -> "用戶資料"
    "billing" -> "計費端點"
    "logs", "logs2" -> "日誌統計"
    "status" -> "狀態"
    else -> key
}
