package com.newapimonitor.ui.logs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.ui.components.StreamTag
import com.newapimonitor.ui.theme.BgColor
import com.newapimonitor.ui.theme.CostColor
import com.newapimonitor.ui.theme.ErrRed
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.isLightMode
import com.newapimonitor.ui.theme.modelBrandColor
import com.newapimonitor.util.Fmt

/**
 * 日誌頁本地 H 元件（方案 H · 彩色金融卡風，design-mockups/h-fintech.html）：
 *  - LogsFilterChip：mockup .chip2 藥丸 chip — 選中實色反轉（light 深底白字／dark 淺底深字），未選 InnerFillColor
 *  - LogsModelChip：模型篩選 chip（同 SelectField 行為：下拉「全部模型」+ 前 200 個模型）
 *  - LogsRowItem：mockup .mrow — 品牌色 .mic 字母 icon + 模型名 w600 + 時間／令牌 muted 細字 + 右對齊 bold tabular 金額
 * 只服務日誌頁；共用 Controls.kt／LogRowItem.kt 唔郁。
 */

private val ChipShape = RoundedCornerShape(50)

/** mockup .chip2：藥丸 chip。onClick=null 時係非互動標籤（摘要列用）。 */
@Composable
fun LogsFilterChip(
    label: String,
    active: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    // 選中：反轉實色 — light 用深字色做底／dark 用淺字色做底（mockup .chip2.on）
    val bg = if (active) TxtColor else InnerFillColor
    val fg = if (active) (if (isLightMode) CardFillColor else BgColor) else Txt2Color
    Box(
        modifier
            .clip(ChipShape)
            .background(bg)
            .border(1.dp, if (active) bg else StrokeSoft, ChipShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 13.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            style = TabularStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 模型篩選 chip（.chip2 語言 + 下拉）：行為同 SelectField —「全部模型」+ options 前 200 個 */
@Composable
fun LogsModelChip(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "全部模型",
) {
    var open by remember { mutableStateOf(false) }
    val active = value.isNotBlank()
    val bg = if (active) TxtColor else InnerFillColor
    val fg = if (active) (if (isLightMode) CardFillColor else BgColor) else Txt2Color
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .background(bg)
                .border(1.dp, if (active) bg else StrokeSoft, ChipShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = true }
                .padding(start = 13.dp, end = 11.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (active) value else placeholder,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChipChevron()
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(placeholder, color = TxtColor, fontSize = 13.sp) },
                onClick = {
                    onSelect("")
                    open = false
                },
            )
            options.take(200).forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text(m, color = TxtColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    onClick = {
                        onSelect(m)
                        open = false
                    },
                )
            }
        }
    }
}

/** chip 右側小 chevron（SelectField 同款 10x6 折線） */
@Composable
private fun ChipChevron() {
    Canvas(Modifier.padding(start = 1.dp).size(width = 10.dp, height = 6.dp)) {
        val chevron = Path().apply {
            moveTo(1f, 1f)
            lineTo(5f, 5f)
            lineTo(9f, 1f)
        }
        drawPath(
            chevron,
            color = MutedColor,
            style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * 一筆調用流水（H mockup .mrow）：髮絲線分隔列表 —
 * 品牌色圓角方塊字母 icon + 模型名 w600（下面 時間·令牌 muted 細字）+ 右對齊 bold tabular $ 金額（下面 P/C·耗時）。
 * type=5 錯誤記錄：紅色左緣光條 + ERR 徽章。isNew=true 時播 slide-in 入場動畫。
 */
@Composable
fun LogsRowItem(
    entry: LogEntry,
    qpu: Double,
    modifier: Modifier = Modifier,
    isNew: Boolean = false,
    isLast: Boolean = false,
) {
    val isError = entry.type == 5
    val brand = modelBrandColor(entry.modelName ?: "unknown")
    val accent = if (isError) ErrRed else brand
    // 入場動畫：只在「新」記錄首次進 composition 時播（js seenLogKeys + row-in）
    val visState = remember { MutableTransitionState(!isNew).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visState,
        enter = slideInVertically(tween(420, easing = EaseOutCubic)) { -it } + fadeIn(tween(420)),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // 模型品牌色左緣光條（呼應 mockup .mic）；type 5 錯誤記錄轉紅
                    val barH = (size.height - 24.dp.toPx()).coerceAtLeast(0f)
                    drawRoundRect(
                        color = accent.copy(alpha = if (isError) 0.8f else 0.55f),
                        topLeft = Offset(0f, 12.dp.toPx()),
                        size = Size(3.dp.toPx(), barH),
                        cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
                    )
                }
                .padding(start = 10.dp, end = 2.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LogsModelIcon(entry.modelName)
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            entry.modelName ?: "unknown",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TxtColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isError) LogsErrBadge()
                        if (entry.isStream) StreamTag()
                    }
                    Text(
                        buildString {
                            entry.tokenName?.takeIf { it.isNotEmpty() }?.let { append(it).append(" · ") }
                            append(Fmt.logTime(entry.createdAt))
                        },
                        fontSize = 10.5.sp,
                        color = MutedColor,
                        style = TabularStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Fmt.usd((entry.quota ?: 0.0) / qpu, 4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CostColor,
                        style = TabularStyle,
                        maxLines = 1,
                    )
                    Text(
                        "P ${Fmt.num(entry.promptTokens.toDouble())} · C ${Fmt.num(entry.completionTokens.toDouble())} · ${Fmt.dur(entry.useTime)}",
                        fontSize = 10.5.sp,
                        color = MutedColor,
                        style = TabularStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
    if (!isLast) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .padding(start = 36.dp)
                .height(1.dp)
                .background(HairlineColor)
        )
    }
}

/** 模型品牌色圓角方塊字母 icon（H mockup .mic：26dp 方角 8，同儀表板 ModelIcon 一款） */
@Composable
private fun LogsModelIcon(name: String?) {
    val n = name ?: "unknown"
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(modelBrandColor(n)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            n.trim().take(1).uppercase().ifEmpty { "?" },
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
    }
}

/** 錯誤記錄徽章（type 5）：紅調藥丸 */
@Composable
private fun LogsErrBadge() {
    Box(
        Modifier
            .clip(ChipShape)
            .background(ErrRed.copy(alpha = 0.14f))
            .border(1.dp, ErrRed.copy(alpha = 0.32f), ChipShape)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text("ERR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ErrRed)
    }
}
