package com.newapimonitor.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.domain.ErrorAnalysis
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.components.SectionHeader
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.BarTrackColor
import com.newapimonitor.ui.theme.ErrRed
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.modelBrandColor
import com.newapimonitor.ui.theme.tr
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 錯誤深入分析卡（H 語言）：永遠顯示「今日」type=5 錯誤聚合（同上面範圍 chip 無關，chip 標「今日」）—
 *  - 錯誤最多模型 Top3：26dp 品牌色字母 icon + w600 名 + 右 bold 次數「N 次」+ Accent1→ErrRed 比例細條
 *  - 最近錯誤 10 筆：HH:mm + 模型 + 令牌 muted 細字，髮絲線分隔
 * errorRows=null（未載入／抓取失敗）→ 成卡靜默收起；0 錯誤 → 只出「暫無錯誤紀錄」。
 */
@Composable
fun ErrorAnalysisCard(errorRows: List<LogEntry>?) {
    // 未載入／抓取失敗（VM errorRows=null）→ 成卡收起
    if (errorRows == null) return
    val analysis = remember(errorRows) { ErrorAnalysis.compute(errorRows) }
    GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        SectionHeader(tr("錯誤分析"), chip = tr("今日"))
        if (analysis.total == 0) {
            Text(
                tr("暫無錯誤紀錄"),
                fontSize = 12.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 10.dp),
            )
            return@GlassCard
        }
        if (analysis.topModels.isNotEmpty()) {
            Text(
                tr("錯誤最多模型"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MutedColor,
                style = TabularStyle,
                modifier = Modifier.padding(top = 12.dp),
            )
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val maxCount = analysis.topModels.maxOf { it.count }
                analysis.topModels.forEach { g ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ErrModelIcon(g.name)
                        Column(Modifier.weight(1f)) {
                            Text(
                                g.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TxtColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(
                                Modifier
                                    .padding(top = 4.dp)
                                    .height(4.dp)
                                    .fillMaxWidth()
                                    .background(BarTrackColor, RoundedCornerShape(50))
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(g.count.toFloat() / maxCount.toFloat())
                                        .height(4.dp)
                                        .background(
                                            Brush.horizontalGradient(listOf(Accent1, ErrRed)),
                                            RoundedCornerShape(50),
                                        )
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                g.count.toString(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = ErrRed,
                                style = TabularStyle,
                                maxLines = 1,
                            )
                            Text(
                                " " + tr("次"),
                                fontSize = 11.sp,
                                color = MutedColor,
                                style = TabularStyle,
                                modifier = Modifier.padding(bottom = 1.dp),
                            )
                        }
                    }
                }
            }
        }
        if (analysis.recent.isNotEmpty()) {
            Text(
                tr("最近錯誤"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MutedColor,
                style = TabularStyle,
                modifier = Modifier.padding(top = 14.dp),
            )
            Column(Modifier.padding(top = 2.dp)) {
                analysis.recent.forEachIndexed { i, r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            hhmm(r.createdAtSec),
                            fontSize = 11.sp,
                            color = MutedColor,
                            style = TabularStyle,
                        )
                        Text(
                            r.model,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Txt2Color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            r.token?.takeIf { it.isNotEmpty() } ?: "—",
                            fontSize = 10.5.sp,
                            color = MutedColor,
                            style = TabularStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (i < analysis.recent.lastIndex) {
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(HairlineColor)
                        )
                    }
                }
            }
        }
    }
}

/** 模型品牌色圓角方塊字母 icon（H mockup .mic：26dp 方角 8，同日誌列表 LogsModelIcon 一款） */
@Composable
private fun ErrModelIcon(name: String) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(modelBrandColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
    }
}

/** HH:mm（本地時區；錯誤行全部喺今日窗口內） */
private fun hhmm(tsSec: Long): String {
    val t = Instant.ofEpochSecond(tsSec).atZone(ZoneId.systemDefault())
    return String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
}
