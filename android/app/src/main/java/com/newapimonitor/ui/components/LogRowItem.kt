package com.newapimonitor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.ui.theme.CostColor
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.VioletLight
import com.newapimonitor.util.Fmt

/**
 * 一筆調用流水（.log-row）：模型色左緣光條 + 模型名／時間 + tokens／耗時／$ 消耗／STREAM 標記。
 * isNew=true 時播 slide-in 入場動畫（js .row-in）。
 */
@Composable
fun LogRowItem(
    entry: LogEntry,
    qpu: Double,
    modifier: Modifier = Modifier,
    isNew: Boolean = false,
    isLast: Boolean = false,
) {
    val rc = ChartPalette.colorAt(ChartPalette.indexForModel(entry.modelName))
    // 入場動畫：只在「新」記錄首次進 composition 時播（js seenLogKeys + row-in）
    val visState = remember { MutableTransitionState(!isNew).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visState,
        enter = slideInVertically(tween(420, easing = EaseOutCubic)) { -it } + fadeIn(tween(420)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // 模型色點：左緣漸變光條（.log-row::before）
                    val barH = (size.height - 26.dp.toPx()).coerceAtLeast(0f)
                    drawRoundRect(
                        color = rc.copy(alpha = 0.55f),
                        topLeft = Offset(0f, 13.dp.toPx()),
                        size = Size(3.dp.toPx(), barH),
                        cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
                    )
                }
                .padding(start = 14.dp, end = 2.dp, top = 11.dp, bottom = 11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    entry.modelName ?: "unknown",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TxtColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    Fmt.logTime(entry.createdAt),
                    fontSize = 11.sp,
                    color = MutedColor,
                    style = TabularStyle,
                    maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 3.dp),
            ) {
                entry.tokenName?.takeIf { it.isNotEmpty() }?.let { tn ->
                    Text(
                        tn,
                        fontSize = 11.sp,
                        color = MutedColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    "P ${Fmt.num(entry.promptTokens.toDouble())} · C ${Fmt.num(entry.completionTokens.toDouble())}",
                    fontSize = 11.sp,
                    color = MutedColor,
                    style = TabularStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(Fmt.dur(entry.useTime), fontSize = 11.sp, color = MutedColor, style = TabularStyle, maxLines = 1)
                Text(
                    Fmt.usd((entry.quota ?: 0.0) / qpu, 4),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CostColor,
                    style = TabularStyle,
                    maxLines = 1,
                )
                if (entry.isStream) StreamTag()
            }
        }
    }
    if (!isLast) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp)
                .height(1.dp)
                .background(HairlineColor)
        )
    }
}

/** STREAM 標記（.stream-tag） */
@Composable
fun StreamTag() {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x248B5CF6))
            .border(1.dp, Color(0x4D8B5CF6), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text("STREAM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VioletLight)
    }
}
