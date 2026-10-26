package com.newapimonitor.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.BgColor
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.ErrRed
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.InnerStrongFillColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor

enum class AppButtonKind { PRIMARY, GHOST, DANGER }

/** 按鈕（.btn / .btn-primary / .btn-ghost / .btn-danger / .btn-sm）：H 實色面，press scale 回饋 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: AppButtonKind = AppButtonKind.GHOST,
    small: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "btnPress")
    val shape = RoundedCornerShape(if (small) 11.dp else 14.dp)
    val padding = if (small) {
        PaddingValues(horizontal = 12.dp, vertical = 7.dp)
    } else {
        PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    }
    val textColor = when (kind) {
        AppButtonKind.PRIMARY -> Color.White
        AppButtonKind.DANGER -> ErrRed
        AppButtonKind.GHOST -> TxtColor
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (small) Modifier else Modifier.defaultMinSize(minHeight = 46.dp))
            .shadow(
                elevation = if (kind == AppButtonKind.PRIMARY) 8.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = Accent1.copy(alpha = 0.32f),
                spotColor = Accent1.copy(alpha = 0.32f),
            )
            .clip(shape)
            .then(
                when (kind) {
                    // H：實心 Accent 面（漸變只留俾 hero）
                    AppButtonKind.PRIMARY -> Modifier.background(Accent1)
                    AppButtonKind.GHOST -> Modifier.background(Color.Transparent).border(1.dp, CardStrokeColor, shape)
                    AppButtonKind.DANGER -> Modifier.background(Color(0x14F87171)).border(1.dp, Color(0x59F87171), shape)
                }
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = textColor,
            fontSize = if (small) 12.sp else 13.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

data class SegOpt(val value: String, val label: String)

/** 分段控制器（H .chips）：圓角 pill chips；選中＝深色實心（light）／近白實心（dark）＋對比字 */
@Composable
fun Segmented(
    options: List<SegOpt>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val active = o.value == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(120), label = "segPress")
            Box(
                Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (active) {
                            Modifier.background(TxtColor)
                        } else {
                            Modifier.background(InnerFillColor).border(1.dp, StrokeSoft, RoundedCornerShape(50))
                        }
                    )
                    .clickable(interactionSource = interaction, indication = null) { onSelect(o.value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    o.label,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) BgColor else Txt2Color,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 下拉選擇（.sel + 原生 select）：模型篩選用 */
@Composable
fun SelectField(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "全部模型",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(InnerFillColor)
                .border(1.dp, CardStrokeColor, RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (value.isBlank()) placeholder else value,
                fontSize = 13.5.sp,
                color = TxtColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Canvas(Modifier.padding(start = 6.dp).size(width = 10.dp, height = 6.dp)) {
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
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(placeholder, color = TxtColor, fontSize = 13.5.sp) },
                onClick = {
                    onSelect("")
                    open = false
                },
            )
            options.take(200).forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text(m, color = TxtColor, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** 開關列（.switch-row）：label + sub + M3 Switch（漸變近似色） */
@Composable
fun SwitchRow(
    label: String,
    sub: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TxtColor)
            Text(sub, fontSize = 11.sp, color = MutedColor, lineHeight = 16.sp, modifier = Modifier.padding(top = 1.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent1,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = InnerStrongFillColor,
                uncheckedThumbColor = CardFillColor,
                uncheckedBorderColor = CardStrokeColor,
            ),
        )
    }
}

/** 小圓點徽章（.badge）：實例卡憑證狀態 */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.NEUTRAL,
) {
    val (fg, bg, border) = when (tone) {
        BadgeTone.NEUTRAL -> Triple(MutedColor, InnerFillColor, CardStrokeColor)
        BadgeTone.OK -> Triple(Color(0xFF86EFAC), Color(0x1752D399), Color(0x5934D399))
        BadgeTone.KEY -> Triple(Color(0xFF67E8F9), Color(0x1722D3EE), Color(0x5922D3EE))
        BadgeTone.ACTIVE -> Triple(Color.White, Color.Transparent, Color.Transparent)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (tone == BadgeTone.ACTIVE) {
                    // H：實心 accent pill（漸變只留俾 hero）
                    Modifier.background(Accent1)
                } else {
                    Modifier.background(bg).border(1.dp, border, RoundedCornerShape(50))
                }
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

enum class BadgeTone { NEUTRAL, OK, KEY, ACTIVE }

/** DemoBadge：頂欄「演示數據」琥珀徽章（.demo-badge，微脈衝） */
@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransitionCompat().animateFloatCompat()
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x1FFBBF24))
            .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.2f + 0.15f * pulse), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            "演示數據",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            color = Color(0xFFFCD34D),
        )
    }
}

@Composable
private fun rememberInfiniteTransitionCompat() = rememberInfiniteTransition(label = "demoBadge")

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateFloatCompat() =
    this.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Restart),
        label = "glow",
    )

/** 狀態燈（.st-light）：在線綠／離線紅／演示琥珀，帶 glow 光暈＋脈衝 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
) {
    val t by rememberInfiniteTransitionCompat().animateFloatCompat()
    androidx.compose.foundation.Canvas(modifier.size(9.dp)) {
        val r = size.minDimension / 2f
        if (pulse) {
            // 擴散脈衝環（js lightPulse / demoPulse）
            drawCircle(
                color = color.copy(alpha = 0.45f * (1f - t)),
                radius = r + 7.dp.toPx() * t,
            )
        }
        // glow halo
        drawCircle(color = color.copy(alpha = 0.35f), radius = r + 2.dp.toPx())
        drawCircle(color = color, radius = r)
    }
}
