package com.newapimonitor.domain

/**
 * 認證 header 格式（對應網頁版 api.js 嘅 'bearer' | 'raw'）：
 *  - BEARER：Authorization: Bearer <token>
 *  - RAW   ：Authorization: <token>（NewAPI 亦兼容嘅單值格式）
 * 只有 RAW 會持久化（對應 store.js：o.authStyle === 'raw' ? 'raw' : null）。
 */
enum class AuthStyle { BEARER, RAW }

/**
 * 錯誤類別（對應 api.js apiError(kind, ...)）。
 * Android 冇瀏覽器 CORS，NETWORK 提示文案相應調整。
 */
enum class ErrorKind {
    AUTH, NETWORK, HTTP, PARSE, API;

    val title: String
        get() = when (this) {
            AUTH -> "認證失敗（401）"
            NETWORK -> "網絡錯誤"
            HTTP -> "伺服器錯誤"
            PARSE -> "回應解析失敗"
            API -> "API 返回錯誤"
        }

    fun hint(status: Int? = null): String = when (this) {
        AUTH -> "Access Token 喺 NewAPI 控制台 → 個人設置 → 生成系統訪問令牌；API Key 係 sk- 開頭嘅令牌 key。檢查有冇填錯實例或過期。"
        NETWORK -> "可能原因：① Base URL 唔正確或站點離線；② 裝置冇網絡連接、DNS 解析失敗或被防火牆阻擋。"
        HTTP -> "站點回應異常狀態碼（HTTP " + (status ?: "?") + "），檢查 Base URL 係咪指向 NewAPI 根路徑。"
        PARSE -> "端點回應唔係 JSON，確認 Base URL 指向嘅係 NewAPI 而非其他服務。"
        API -> "站點 API 返回錯誤訊息，檢查實例設定同權限。"
    }
}

/** UI 層用嘅錯誤模型（唔帶 stacktrace，全部數據經 view model 先到畫面） */
data class AppError(
    val kind: ErrorKind,
    val message: String,
    val status: Int? = null,
) {
    val displayTitle: String
        get() = when (kind) {
            ErrorKind.HTTP -> "伺服器錯誤（HTTP ${status ?: ""}）"
            else -> kind.title
        }
}
