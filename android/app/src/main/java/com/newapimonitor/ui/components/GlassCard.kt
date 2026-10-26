package com.newapimonitor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.ui.theme.BrandGradientColors
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.isLightMode

// H 卡陰影：light = rgba(40,30,80,.06) 柔紫灰；dark/oled = 黑
private val ShadowAmbientLight = Color(0x0F281E50)
private val ShadowSpotLight = Color(0x1E281E50)
private val ShadowAmbientDark = Color(0x33000000)
private val ShadowSpotDark = Color(0x4D000000)

/**
 * 實心卡（方案 H · 彩色金融卡風，.bcard）：實色面 + 1px 描邊 + 柔和陰影，radius 18。
 * onClick 唔係 null 時有 press 回饋（scale 0.982）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.982f else 1f,
        animationSpec = tween(120),
        label = "glassPress",
    )
    PressableGlassBox(
        modifier = modifier,
        shape = shape,
        scale = scale,
        onClick = onClick,
        interaction = interaction,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** GlassCard 嘅底層 Box（其他自訂卡都復用呢套實心卡質感） */
@Composable
fun PressableGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    scale: Float = 1f,
    elevation: Dp = 8.dp,
    onClick: (() -> Unit)? = null,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.() -> Unit,
) {
    val light = isLightMode
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = if (light) ShadowAmbientLight else ShadowAmbientDark,
                spotColor = if (light) ShadowSpotLight else ShadowSpotDark,
            )
            .clip(shape)
            .background(CardFillColor)
            .border(1.dp, CardStrokeColor, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                } else {
                    Modifier
                }
            )
    ) {
        content()
    }
}

/** 卡片標題列（.card-title-row）：漸變小刻度 + 標題 + 右側 chip；
 *  喺 hero 漸變上面傳 titleColor（例如白）就會用佢做標題色同刻度色。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    chip: String? = null,
    titleColor: Color? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(end = 7.dp)
                .height(12.dp)
                .width(3.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (titleColor != null) {
                        Brush.verticalGradient(listOf(titleColor.copy(alpha = 0.95f), titleColor.copy(alpha = 0.55f)))
                    } else {
                        Brush.verticalGradient(BrandGradientColors)
                    }
                )
        )
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = titleColor ?: MutedColor,
        )
        Spacer(Modifier.weight(1f))
        if (chip != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(InnerFillColor)
                    .border(1.dp, StrokeSoft, RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    chip,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Txt2Color,
                    style = TabularStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
