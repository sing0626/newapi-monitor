# NewAPI Monitor

專為 **NewAPI / OneAPI** 站點設計嘅雙平台原生監控工具（**Android Jetpack Compose** + **iOS SwiftUI**）。所有 API Key 同管理存取 Token 只加密保存喺手機本機（Android Keystore / iOS Keychain），直接連線至你自己設定嘅站點，絕不經過任何第三方伺服器。

| 平台 | 技術棧 | 目前版本 | 系統要求 | 說明文件 |
| :--- | :--- | :--- | :--- | :--- |
| **Android** | Kotlin、Jetpack Compose、Material 3 | **1.5.0** (`versionCode 7`) | Android 8.0+ (API 26) | [README-ANDROID.md](README-ANDROID.md) |
| **iOS / iPadOS** | Swift、SwiftUI、Liquid Glass | **1.5.0** (`build 7`) | iOS 17.0+ (iOS 26 SDK) | [README-IOS.md](README-IOS.md) |

---

## 核心特色

- **多實例即時總覽**：同時管理多個 NewAPI 站點，最多 3 個實例並行刷新；90 秒新鮮度判斷，失敗、未知單位或 `TOKENS` 站點顯示 `—`，絕唔會當成 `$0` 誤導總額。
- **歷史日期與跨實例報表**：自訂任意歷史日期範圍（最多 366 日），支援與前段同日數或上月同期比較，提供模型／令牌消費明細及防 Excel 公式注入嘅 CSV 匯出。
- **手動 SSE 串流測速**：對 `/v1/chat/completions` 進行真實串流探測，精確測量首字延遲（TTFT）、總耗時、輸出 tokens 及 `tokens/s` 吞吐速度（必須手動確認先會發送可能計費請求，且不保存生成內容）。
- **跨平台加密備份互通**：Android 與 iOS 共用同一套 `newapi-monitor` v1 備份結構與加密標準（`PBKDF2-HMAC-SHA256` 210,000 次 + `AES-256-GCM` 附 AAD 驗證）。預設匯出不含憑證；含憑證備份強制要求至少 10 字元密碼加密。
- **完全離線演示模式（Demo Mode）**：無需輸入任何真實站點或金鑰，即可離線體驗完整儀表板、報表、測速與日誌流程。

---

## iOS 版（免 Apple Developer 證書打包 `.ipa`）

`ios/` 目錄提供純原生 Swift + SwiftUI 實作，並透過 GitHub Actions（[`.github/workflows/build-ios.yml`](.github/workflows/build-ios.yml)）喺 `macos-15` 自動執行單元測試並打包出免證書 `NewAPIMonitor-unsigned.ipa`：

1. 前往本 Repo 嘅 **Actions → Build iOS unsigned IPA**，下載最新成功 Run 嘅 `NewAPIMonitor-iOS-unsigned` Artifact。
2. 解壓取得 `NewAPIMonitor-unsigned.ipa`。
3. 使用 **Sideloadly** 或 **SideStore** 配合你個人免費 Apple ID 簽名安裝落 iPhone / iPad。

詳細 iOS 首版功能範圍、安全限制及本機 macOS 編譯指令請參閱 **[README-IOS.md](README-IOS.md)**。

---

## Android 版（1.5.0 完整功能）

### 功能

- 總覽：多實例餘額、今日／本月消費及獨立更新狀態；點擊實例可開啟詳細圖表、趨勢、燒錢預測、模型成本及即時日誌。大額以 K／M／B／T 顯示，點擊金額查看完整數字。
- 多實例：直接由首頁同時刷新全部實例，最多 3 個並行；畫面可見時每 30–60 秒更新，轉背景會取消請求。報告、模型測速及通知各有獨立次頁，次頁隱藏底部導覽並提供返回鍵。
- 首頁自訂：緊湊卡片、釘選及排序、隱藏首頁金額，亦可指定實例不計入總餘額；消費合計仍包括該實例。
- 個別月度預算：在實例卡的「編輯預算」底部面板獨立設定 USD 預算；警報開啟後，達 90%／100% 各記錄及通知一次。WorkManager 約每 15 分鐘更新設有個別預算的實例；系統省電可能延後排程。
- 日期報告：任意歷史月份或最多 366 日日期範圍，按裝置本地日界計算，可選前段同日數或上月同期比較（短月份月尾對齊最後一日）。
- 跨實例報告：逐站點比較金額、前期變化及模型成本，標示無法讀取的來源及部分日誌；可匯出帶 Excel 公式防護的 CSV。
- 成本追查：報告按模型／令牌統計，點擊後保留日期及名稱篩選，分頁查閱對應消費日誌。
- 進階測速：選擇不同實例測同一模型，記錄首字延遲、全程耗時、供應商回傳的輸出 tokens 及 tokens/s；每輪最多 10 次，逐個執行，每次 30 秒超時。
- 模型清單：由所選實例讀取 `/v1/models`，可搜尋、收藏及保存 1–3 個模型的測試組合；比較表展示成功率、首字延遲和輸出速度。只有按「開始測試」才會發送可能收費的串流請求。
- 實例警報：可獨立設定低餘額、每日消費、靜音時段及冷卻分鐘；背景檢查約每 15 分鐘，系統省電可能延後。
- 通知紀錄：最近 200 筆本機警報，支援全部已讀及清除。
- 令牌管理、渠道管理、帳戶與訂閱、官方價格／分組、全域搜尋、月度報告分享卡、快速設定磁貼。每個桌面小工具可獨立選擇實例或全部實例、隱藏金額，並顯示預算進度及資料涵蓋情況。
- 深色／OLED／淺色、四種語言、應用鎖、完全離線演示模式。

### 資料範圍

總額優先使用站點每日聚合統計；報告模型／令牌明細最多抓取 1,000 筆，會標示「部分資料」。點擊明細可再向伺服器分頁查詢，未命名項目只可從已載入資料篩選。失敗、未知單位及缺少資料均顯示「—」或不可用，唔會當成零加入合計。

多實例合計只包含最近 90 秒成功更新的資料，畫面顯示已更新實例數。只有 API Key 的實例可讀計費餘額，但每日／本月消費需要 Access Token；計費單位未知或為 TOKENS 時不加入 USD 合計。

串流測速會呼叫所選站點的 /v1/chat/completions，要求從 1 數到 10、最多 64 個輸出 tokens，**可能消耗額度**。首字延遲從發送到第一段非空內容（或推理內容）；tokens/s = (completion_tokens − 1) ÷ 首字後至串流結束秒數。上游不提供用量時不估算速度；不支援串流／stream_options 的站點顯示失敗，不自動重試付費請求。生成文字不會保存。

### 備份及安全

- 實例及憑證只存手機本機；Access Token／API Key 在 DataStore 以 Android Keystore 的 AES-256-GCM 金鑰加密，舊明文記錄會在首次載入時遷移。若解密失敗，原有密文保留並提示重新輸入，不會當成空白憑證覆蓋。
- 備份預設不含憑證。勾選包含金鑰時，必須使用至少 10 字元、兩次相同的密碼。
- 加密格式：AES-256-GCM、隨機 16-byte salt／12-byte IV、PBKDF2-HMAC-SHA256（210,000 次），附版本及驗證資料。
- 密碼不會保存；錯誤密碼、被修改的備份或未知版本均不匯入。仍接受原有未加密 Android JSON 備份。
- 無憑證備份匯入同一站點及同名實例時，保留現有憑證；個別實例預算、首頁自訂、收藏與測試組合及實例警報亦可備份還原。桌面小工具配置隨手機桌面小工具 ID 保留於本機。
- 清除所有資料包含實例、設定、外觀、卡片配置、測速、歷史、預算及通知紀錄。

### Android 開發及驗證

本機工具鏈：JDK 21、Gradle 8.13、Android SDK；由 D:/vibeproject/android-tools/env.sh 載入。專案未附 Gradle wrapper。

PowerShell：

    ./scripts/verify.ps1 -Offline

Git Bash：

    source /d/vibeproject/android-tools/env.sh
    cd "/d/vibeproject/NewAPI Monitor/android"
    gradle testDebugUnitTest assembleDebug

若 Windows 短 TEMP 路徑令 Java AF_UNIX 報錯，verify.ps1 會為本次執行設定專案內臨時目錄，不更改系統設定。

APK：android/app/build/outputs/apk/debug/app-debug.apk。只有明確要求時才安裝到手機。

---

## 目錄結構

- `android/app/src/main/java/com/newapimonitor`：原生 Android 應用程式（Kotlin + Jetpack Compose）。
- `android/app/src/test`：Android 單元、API、儲存及 Compose 畫面測試。
- `ios/NewAPIMonitor`：原生 iOS 應用程式（Swift + SwiftUI）。
- `ios/NewAPIMonitorTests`：iOS XCTest 單元與網路／加密備份測試。
- `ios/scripts`：XcodeGen 檢查、模擬器測試及免簽名 IPA 打包腳本。
- `.github/workflows/build-ios.yml`：GitHub Actions 自動測試並打包 Unsigned IPA 工作流。

詳見 [CHANGELOG.md](CHANGELOG.md)。
