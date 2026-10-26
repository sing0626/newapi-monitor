# NewAPI Monitor — iOS 1.5.0（build 7）

獨立原生 **Swift + SwiftUI** 首版，最低 **iOS / iPadOS 17.0**。Android 原始碼及版本不變。本版不使用 WebView、PWA、跨平台 UI 或第三方 Swift 套件。

> **驗證狀態：已透過 GitHub Actions `macos-15`（Xcode 16.4 / iPhoneOS 18.5 SDK）完成模擬器 XCTest（5 個測試套件／41 項測試全部通過）及真機 `arm64` Release 免簽名打包，產出 `NewAPIMonitor-unsigned.ipa`。真機安裝仍需使用 Sideloadly / SideStore 等工具配合個人免費 Apple ID 自簽名。**

## 已實作的首版範圍

- 原生總覽、報表、測速、設定四個分頁；iPhone / iPad 介面。
- 多實例新增、修改、刪除、釘選、是否計入總餘額、個別月度預算。
- 前景首頁每 30／45／60 秒刷新，最多 3 個實例並行。離開首頁、切換設定／來源、轉背景或鎖屏會取消工作，舊請求結果不能覆蓋新來源。
- 餘額、今日／本月消費分開處理失敗；90 秒內的有效資料才加入合計。未知金額顯示 `—`，不把失敗當零。API Key-only 的 billing 會先判定站點單位；未知／TOKENS 不加入 USD 合計。
- 歷史日期／跨實例報表，最多 366 個本地日；比較上段同日數或上月同期。總額取站點聚合；分類日誌每期最多 1,000 筆，明示部分資料及錯誤。模型分類圖表、帶公式注入防護的 CSV 匯出。
- 實例詳情、分頁日誌、模型清單；**令牌和渠道只供讀取**，不新增、修改、刪除伺服器資源。
- 手動 SSE 串流測速：每次最多 64 個輸出 tokens、30 秒總期限；同一來源及模型最多連續 10 次。必須明確確認可能收費；無自動重試，不保存生成文字。顯示首 token 延遲、總時間、服務端回報用量和 tokens/s；結果只存本次記憶體。
- 完全離線且明確標示的示範模式；不向真實站點讀取資料或發送生成請求。
- 系統／深色／淺色主題、主題色及隱藏金額；介面目前只有繁體中文／粵語。
- 前景刷新後評估低餘額、每日消費、月度預算 90%／100% 警報；最近 200 筆本機記錄。通知需手動授權，通知內容不含金額或站點名稱。
- JSON 備份預設不含憑證；含憑證必須使用至少 10 個 UTF-16 單位的密碼及二次確認，並加密匯出。匯入先預覽再確認合併。

## 安全與資料限制

### 憑證及網路

- 僅接受有效 **HTTPS Base URL**；不允許 URL 帳密、query 或 fragment。主機名標準化，但保留路徑大小寫以支援反向代理前綴。
- Access Token／API Key 使用 iOS Keychain，`AfterFirstUnlockThisDeviceOnly`。本地 JSON 僅含非憑證 metadata，使用檔案保護且排除系統備份。Keychain 讀寫失敗會顯示錯誤，不以空值覆蓋舊憑證。
- 每個來源獨立 client／session；HTTP 使用 ephemeral session，不用 cookie 或磁碟快取。拒絕重新導向，包括同站跳轉；請輸入最終 API Base URL，避免憑證跟隨重導傳往其他位置。
- 讀取 Access Token 端點遇 HTTP 401 可嘗試一次另一種 Bearer／Raw 格式；付費生成請求不作自動認證重試。
- 原始伺服器日誌文字不顯示／不持久化，避免記錄帶入 prompt 或敏感資料。
- 隱藏金額只影響畫面，不會抹除資料；CSV／備份仍可能含實際金額，匯出前會提示。不要把含憑證備份放入 Git。

### 與 Android 備份的相容範圍

使用 `app: newapi-monitor`、`version: 1` 的備份 schema；加密 envelope：

- PBKDF2-HMAC-SHA256，210,000 次，32-byte key、16-byte salt。
- AES-256-GCM，12-byte nonce、16-byte tag。
- AAD：UTF-8 `newapi-monitor-backup:v1`。
- `data` 為 `ciphertext || tag` 的 Base64，nonce 另存 `iv`。
- 明文上限 8 MB；拒絕錯誤密碼、修改過的 ciphertext／tag、不支援的版本及演算法。

匯入以**標準化 HTTPS 網址＋名稱**配對；空白憑證保留現有值，其餘實例不刪除。外觀缺少的欄位保留本機設定。Android 備份的 HTTP 站點／不合法欄位會明確拒絕，不偷偷改址或略過。首次真機互通仍需兩邊匯出／匯入驗證；加密測試已有獨立 Java JCE fixture，並不等於已跑過 iOS 測試。

### 與 Android 1.5.0 的差異

本版尚未提供 WidgetKit、小工具共享容器、APNs、iCloud、背景排程、Face ID 應用鎖、帳戶／訂閱／價格頁、伺服器端令牌／渠道寫入、全域搜尋、模型收藏／測試套件編輯、跨實例測速比較及多語翻譯。部分 Android 備份設定會保留，但不代表該功能已啟用。個別實例警報規則可由備份匯入，本版未有完整編輯器。

警報**只在前景首頁刷新時檢查**，不是持續背景監控；iOS 不保證 Android WorkManager 式固定間隔。重啟 App 不保留首頁金額快照／測速結果；需要重新讀取。沒有聲稱側載可免簽名永久運行。

## Windows：用 GitHub Actions 編譯與下載 IPA

本機不需要 Mac，編譯由 GitHub Actions `macos-15`（Xcode 16.4）自動執行，配置位於 [`.github/workflows/build-ios.yml`](.github/workflows/build-ios.yml)。

1. 前往 [GitHub Actions — Build iOS unsigned IPA](https://github.com/sing0626/newapi-monitor/actions/workflows/build-ios.yml)。
2. 點選最新成功嘅 Run，喺頁面底部 **Artifacts** 下載 `NewAPIMonitor-iOS-unsigned`。
3. 解壓下載嘅 ZIP，入面嘅 `NewAPIMonitor-unsigned.ipa` 就係側載輸入檔（唔可以直接安裝外層 Artifact ZIP）。
4. 如修改咗 `ios/` 程式碼並 Push，或者手動點選 **Run workflow**，macOS runner 會自動安裝 XcodeGen、檢查資源、跑晒 41 個模擬器 XCTest，全部通過後先會編譯並上傳新嘅真機 `iphoneos` Release IPA。

此流程**不需要 Apple ID、Apple Developer Program、簽名憑證、Provisioning Profile 或 Apple Secrets**，亦不會發佈 App Store／TestFlight。GitHub runner 的免費額度及超額收費取決於 repository 和帳戶計劃，不保證無限免費。

## macOS 本機建置

需要完整 Xcode（建議 Xcode 16+）、可用 iOS Simulator runtime、Python 3、XcodeGen：

```bash
brew install xcodegen
# 選定已安裝的 Xcode，首次使用先在 Xcode 完成元件及授權設定。
xcodebuild -version

# 在 repository 根目錄執行
python3 ios/scripts/check-project.py
bash ios/scripts/test-ios.sh
bash ios/scripts/build-unsigned-ipa.sh
```

`test-ios.sh` 自動選可用 iPhone 模擬器，沒有 runtime 就明確失敗，不略過測試。`build-unsigned-ipa.sh` 每次用新的 build/package.* 目錄，使用：

```text
-sdk iphoneos
-destination generic/platform=iOS
CODE_SIGN_IDENTITY=""
CODE_SIGNING_REQUIRED=NO
CODE_SIGNING_ALLOWED=NO
```

成功後輸出 `ios/build/package.*/NewAPIMonitor-unsigned.ipa` 的完整路徑。IPA 內為 `Payload/NewAPIMonitor.app`，腳本會檢查真機 platform、可執行檔及 arm64 架構。打包檢查不等於簽名驗證或可安裝性驗證。

想在 Xcode 開啟工程：

```bash
xcodegen generate --spec ios/project.yml --project ios
open ios/NewAPIMonitor.xcodeproj
```

產生的 `.xcodeproj` 已加入 `.gitignore`，以 `project.yml` 為單一來源。自行選擇 Personal Team／bundle identifier 後可嘗試 Xcode 真機開發簽名；付費權限未開啟。Release 不在專案層永久關閉簽名，只有 unsigned 打包腳本傳入免簽名參數。

## 免費帳號安裝到 iPhone

**Unsigned IPA ≠ 可直接安裝的 IPA。** 原廠 iOS 仍要求有效簽名／provisioning。

可使用自己信任的側載工具（例如 Sideloadly 或 SideStore，依工具當前說明）及自己的免費 Apple Account 重新簽名。一般免費個人簽名約 7 日需續期，啟用 App 數量及 App ID 有限制；具體依 Apple／工具／iOS 版本。可能需要開啟 Developer Mode 及信任開發者設定。本專案不保存或要求提供 Apple Account 密碼。

無 Apple ID 的 unsigned build 可以完成；**無 Apple ID 又無簽名就直接裝到一般未越獄 iPhone，並不是本流程能做到的事**。不提供企業憑證租用／共享憑證／繞過驗證服務。

## 專案結構

```text
ios/
  project.yml                 XcodeGen application / unit tests / scheme
  NewAPIMonitor/
    App/                      App 入口、前背景及資料保護生命週期
    Domain/                   金額、報表、警報、示範及資料模型
    Data/API/                 隔離來源 client、解析、重導政策、SSE
    Data/Backup/              Android v1 schema、PBKDF2 / AES-GCM
    Data/Store/               Keychain、非憑證 metadata、狀態管理
    UI/                       原生 SwiftUI 頁面
    Resources/                Info.plist、空 entitlements、AppIcon
  NewAPIMonitorTests/          API / SSE / 備份 / Keychain / Domain 測試
  scripts/                    可攜檢查、模擬器測試、免簽名 IPA 打包
```

`check-project.py` 只需 Python 標準庫；圖示已由現有 Android launcher 轉成 1024×1024 不透明 PNG 並納入版本控制，建置毋須 Pillow。僅重新轉換圖示時才需要 `convert-android-icon.py` 及 Pillow。
