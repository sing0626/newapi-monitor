package com.newapimonitor.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.newapimonitor.util.Fmt
import kotlin.math.pow

/**
 * count-up 數字動畫（js animateNumber：easeOutCubic 700ms，tabular-nums 防跳位）。
 * 首次顯示直接跳到目標值（from==null），之後每次目標變動都由當前顯示值起跳。
 * 用 withFrameNanos 逐幀推進，p clamp [0,1]（對應 js 修復負 p 外插）。
 * autoSize=true：文字長過 fitChars 就按比例縮字號（長數字唔准 ellipsis 爛尾）。
 */
@Composable
fun CountUpText(
    value: Double?,
    digits: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    durationMs: Int = 700,
    autoSize: Boolean = false,
    fitChars: Int = Int.MAX_VALUE,
) {
    var shown by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(value) {
        val to = value ?: 0.0
        val from = shown
        if (from == null || !from.isFinite()) {
            shown = to
            return@LaunchedEffect
        }
        val durationNs = durationMs * 1_000_000L
        val start = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val p = ((frame - start).toFloat() / durationNs).coerceIn(0f, 1f)
            val e = 1f - (1f - p).pow(3)
            shown = from + (to - from) * e.toDouble()
            if (p >= 1f) break
        }
        shown = to
    }

    val text = Fmt.usd(shown ?: 0.0, digits)
    val base = style.fontSize
    val scaled = if (autoSize && text.length > fitChars && base != androidx.compose.ui.unit.TextUnit.Unspecified) {
        base * (fitChars.toFloat() / text.length).coerceIn(0.55f, 1f)
    } else {
        base
    }
    androidx.compose.material3.Text(
        text = text,
        style = style.copy(fontSize = scaled),
        modifier = modifier,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** 靜態（非 count-up）tabular 數字文字 */
@Composable
fun NumText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    androidx.compose.material3.Text(
        text = text,
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}
