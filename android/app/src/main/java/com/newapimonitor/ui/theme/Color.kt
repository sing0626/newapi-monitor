package com.newapimonitor.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.newapimonitor.domain.LogMath

/**
 * 方案 H · 彩色金融卡風色板（design-mockups/h-fintech.html）：
 * - light：原版 mockup — 底 #faf9fc、白卡 1px 描邊 #ece9f2、字 #17141f／#9b96ab、track #e4e0f2
 * - dark：同一套 H 語言搬上深底 — 底 #14101f、卡 #251e33、描邊 #3b3152（紫調，對比比初版強）
 * - oled：純黑底 + #161221 卡
 * 卡一律 solid（唔再係玻璃半透明），只有 GlassBottomBar 同餘額 hero 保留透明白。
 */
private var currentMode by mutableStateOf("dark")
private var currentAccent by mutableStateOf("purple")

fun applyAppearance(mode: String, accent: String) {
    currentMode = mode
    currentAccent = accent
}

/** 目前係咪淺色模式（H light 用深色字／實色白卡） */
val isLightMode get() = currentMode == "light"

/* ---------------- 底色／卡面 ---------------- */

/** 頁面底 */
val BgColor get() = when (currentMode) { "oled" -> Color.Black; "light" -> Color(0xFFFAF9FC); else -> Color(0xFF14101F) }

/** 實心卡面（GlassCard 用） */
val CardFillColor get() = when (currentMode) { "oled" -> Color(0xFF161221); "light" -> Color.White; else -> Color(0xFF251E33) }

/** MaterialTheme surface = 卡面 */
val Bg2Color get() = CardFillColor

/** 軟內襯面（輸入框底、搜尋結果容器等） */
val Bg3Color get() = when (currentMode) { "oled" -> Color(0xFF1E1930); "light" -> Color(0xFFF1EFF6); else -> Color(0xFF2F2742) }

/** 卡描邊（1px） */
val CardStrokeColor get() = when (currentMode) { "oled" -> Color(0xFF2E2740); "light" -> Color(0xFFECE9F2); else -> Color(0xFF3B3152) }

/** 髮絲級軟描邊（chip 邊） */
val StrokeSoft get() = when (currentMode) { "oled" -> Color(0xFF251F33); "light" -> Color(0xFFF1EFF6); else -> Color(0xFF2F2742) }

/** 卡內分隔線／格線 */
val HairlineColor get() = if (currentMode == "light") Color(0x1217141F) else Color(0x14FFFFFF)

/** 卡內小元件底（chip／legend 格／輸入位）：實心卡上要有對比 */
val InnerFillColor get() = when (currentMode) { "oled" -> Color(0x14FFFFFF); "light" -> Color(0xFFF3F1FA); else -> Color(0x16FFFFFF) }

/** 較明顯嘅卡內元件底（track／toggle） */
val InnerStrongFillColor get() = when (currentMode) { "oled" -> Color(0x22FFFFFF); "light" -> Color(0xFFECE9F2); else -> Color(0x26FFFFFF) }

/** bar chart 軌道槽（H：light #e4e0f2／dark 紫調深槽） */
val BarTrackColor get() = when (currentMode) { "oled" -> Color(0xFF2A2340); "light" -> Color(0xFFE4E0F2); else -> Color(0xFF372E4E) }

/** 頂欄底：dark/oled 紫調深色半透明；light 用淺色半透明（唔再一條黑橫帶） */
val TopBarColor get() = when (currentMode) { "oled" -> Color(0xC805040A); "light" -> Color(0xF2F7FAFC); else -> Color(0xBE14101F) }

/* ---------------- 文字 ---------------- */

val TxtColor get() = if (currentMode == "light") Color(0xFF17141F) else Color(0xFFF2F0FA)
val Txt2Color get() = if (currentMode == "light") Color(0xFF4C4477) else Color(0xFFC7C0DE)
val MutedColor get() = if (currentMode == "light") Color(0xFF9B96AB) else Color(0xFFA89FC0)
val DimColor get() = if (currentMode == "light") Color(0xFFABA6BD) else Color(0xFF736A94)

/* ---------------- Accent ---------------- */

val Accent1 get() = when (currentAccent) { "blue" -> Color(0xFF2563EB); "green" -> Color(0xFF059669); "orange" -> Color(0xFFEA580C); else -> Color(0xFF8B5CF6) }
val AccentIndigo get() = Accent1.copy(alpha = 0.85f)
val Accent2 get() = when (currentAccent) { "blue" -> Color(0xFF06B6D4); "green" -> Color(0xFF84CC16); "orange" -> Color(0xFFFBBF24); else -> Color(0xFF22D3EE) }

val OkGreen = Color(0xFF34D399)        // --ok
val WarnAmber = Color(0xFFFBBF24)      // --warn
val ErrRed = Color(0xFFF87171)         // --err

val CyanLight = Color(0xFF67E8F9)      // .log-meta .cost / spark-dot
val VioletLight = Color(0xFFC4B5FD)    // .tab.active / .stream-tag

/** 日誌 $ 金額／spark 端點色：白卡上淺青睇唔到，light 轉深青 */
val CostColor get() = if (currentMode == "light") Color(0xFF0891B2) else CyanLight

/** 模型配色 8 色（charts.js PALETTE） */
val ChartPaletteColors = listOf(
    Color(0xFF8B5CF6),
    Color(0xFF22D3EE),
    Color(0xFFF472B6),
    Color(0xFF34D399),
    Color(0xFFFBBF24),
    Color(0xFF60A5FA),
    Color(0xFFA78BFA),
    Color(0xFFFB7185),
)

/** 紫→青品牌漸變 */
val BrandGradientColors get() = listOf(Accent1, AccentIndigo, Accent2)

/* ---------------- 方案 H hero ---------------- */

/** 每個 accent 嘅 hero 漸變對（餘額卡底＋今日 bar 用）：H 語言 鮮色→紫紅 */
val HeroGradientColors get() = when (currentAccent) {
    "blue" -> listOf(Color(0xFF3B82F6), Color(0xFF7C3AED))
    "green" -> listOf(Color(0xFF10B981), Color(0xFF06B6D4))
    "orange" -> listOf(Color(0xFFF59E0B), Color(0xFFF43F5E))
    else -> listOf(Color(0xFF8B5CF6), Color(0xFFD946EF))
}

/** 模型行圓角方塊 icon 嘅 hash 後備色板（H mockup mic） */
val ModelBrandPalette = listOf(
    Color(0xFF6366F1), // indigo
    Color(0xFF0EA5E9), // sky
    Color(0xFF14B8A6), // teal
    Color(0xFFF59E0B), // amber
    Color(0xFFEC4899), // pink
    Color(0xFF8B5CF6), // violet
)

/** 模型品牌色：名稱匹配品牌（OpenAI 綠／Claude 橙／DeepSeek 藍／Gemini 紫），其他穩定 hash 入 6 色板 */
fun modelBrandColor(name: String): Color {
    val n = name.lowercase()
    return when {
        n.contains("gpt") || n.contains("openai") || n.contains("o1") || n.contains("o3") || n.contains("o4") -> Color(0xFF10A37F)
        n.contains("claude") -> Color(0xFFD97757)
        n.contains("deepseek") -> Color(0xFF4D6BFE)
        n.contains("gemini") || n.contains("gemma") -> Color(0xFF8E44EC)
        else -> ModelBrandPalette[LogMath.colorIndexForModel(name, ModelBrandPalette.size)]
    }
}
