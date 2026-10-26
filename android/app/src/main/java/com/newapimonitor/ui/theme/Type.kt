package com.newapimonitor.ui.theme

import androidx.compose.ui.text.TextStyle

/**
 * tabular 數字（css font-variant-numeric: tabular-nums）：
 * 大數字、tokens、時間一律使用，count-up 動畫唔會跳位。
 */
val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

fun tabular(style: TextStyle = TextStyle()): TextStyle = style.copy(fontFeatureSettings = "tnum")
