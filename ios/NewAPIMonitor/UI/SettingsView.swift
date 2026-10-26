import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var addInstance = false
    @State private var backup = false
    @State private var alertSettings = false

    var body: some View {
        NavigationStack {
            Form {
                if let failure = store.loadFailure {
                    Section("讀取失敗・資料保持不變") {
                        InlineError(text: failure)
                        Button("重新讀取本機資料及鑰匙圈") { store.reload() }
                    }
                }
                Section("監察") {
                    Toggle("離線示範模式", isOn: Binding(get: { store.settings.demo }, set: { value in apply { try store.updateSettings(demo: value) } }))
                    Text("示範會停用所有真實站點讀取及付費請求，所有分頁使用標示嘅模擬資料。")
                        .font(.caption).foregroundStyle(.secondary)
                    Picker("首頁刷新間隔", selection: Binding(get: { store.settings.intervalSec }, set: { value in apply { try store.updateSettings(interval: value) } })) {
                        Text("30 秒").tag(30); Text("45 秒").tag(45); Text("60 秒").tag(60)
                    }
                    Text("只喺 App 前景且首頁可見時刷新；背景及鎖屏會取消進行中工作。")
                        .font(.caption).foregroundStyle(.secondary)
                }.disabled(store.loadFailure != nil)
                Section("外觀及私隱") {
                    Toggle("隱藏金額", isOn: Binding(get: { store.preferences.hideMoney }, set: { value in apply { try store.updateSettings(hideMoney: value) } }))
                    Picker("主題", selection: Binding(get: { store.appearance.mode }, set: { value in apply { try store.updateSettings(mode: value) } })) {
                        Text("跟隨系統").tag("system"); Text("深色").tag("dark"); Text("深色 OLED").tag("oled"); Text("淺色").tag("light")
                    }
                    Picker("主題色", selection: Binding(get: { store.appearance.accent }, set: { value in apply { try store.updateSettings(accent: value) } })) {
                        Text("紫").tag("purple"); Text("藍").tag("blue"); Text("綠").tag("green"); Text("橙").tag("orange")
                    }
                    Text("介面使用繁體中文／粵語；隱藏金額不會刪除資料，匯出仍包含實際金額。")
                        .font(.caption).foregroundStyle(.secondary)
                }.disabled(store.loadFailure != nil)
                Section("實例管理") {
                    if store.settings.demo { Text("示範模式已鎖定真實實例編輯，關閉示範後可管理。 ").foregroundStyle(.secondary) }
                    ForEach(store.instances) { instance in
                        NavigationLink { InstanceSettingsLink(instance: instance) } label: {
                            VStack(alignment: .leading) { Text(instance.name); Text(instance.baseUrl).font(.caption).foregroundStyle(.secondary) }
                        }.disabled(store.settings.demo || store.loadFailure != nil)
                    }
                    Button("加入實例") { addInstance = true }.disabled(store.settings.demo || store.loadFailure != nil)
                }
                Section("備份") {
                    Button("匯入／匯出備份") { backup = true }.disabled(store.loadFailure != nil)
                    Text("預設不含憑證；如包含憑證必須加密。匯入會先預覽，再明確確認合併。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("前景本機警報") {
                    Button("設定餘額／消費／預算警報") { alertSettings = true }.disabled(store.loadFailure != nil)
                    Button("允許本機通知…") { Task { await store.requestNotificationPermission() } }
                    Text("需要手動授權；只喺前景首頁刷新後評估。無背景排程，唔保證即時警報。通知唔顯示金額或實例名稱。")
                        .font(.caption).foregroundStyle(.secondary)
                    NavigationLink("警報記錄（\(store.history.count)/200）") { AlertHistoryView() }
                }
                Section("關於") {
                    Text("NewAPI Monitor · iOS 首發 0.1.0")
                    Text("原生 SwiftUI · HTTPS · 鑰匙圈憑證\nToken／渠道唯讀；唔聲稱具備 Android 版全部功能。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("設定")
            .sheet(isPresented: $addInstance) { InstanceEditorView(instance: nil) }
            .sheet(isPresented: $backup) { BackupView() }
            .sheet(isPresented: $alertSettings) { AlertSettingsView() }
        }
    }
    private func apply(_ action: () throws -> Void) {
        do { try action() } catch { store.message = AppStore.describe(error) }
    }
}

private struct InstanceSettingsLink: View {
    let instance: Instance
    @State private var open = true
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Text("實例設定").sheet(isPresented: $open, onDismiss: { dismiss() }) { InstanceEditorView(instance: instance) }
    }
}

struct BackupView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var includeCredentials = false
    @State private var encrypted = false
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var importPassword = ""
    @State private var document = TextExportDocument()
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var preview: ImportPreview?
    @State private var busy = false
    @State private var error: String?
    @State private var success: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("匯出") {
                    Toggle("包含存取 Token 及 API Key", isOn: $includeCredentials)
                        .onChange(of: includeCredentials) { _, value in if value { encrypted = true } }
                    Toggle("密碼加密（AES-GCM）", isOn: $encrypted).disabled(includeCredentials)
                    if encrypted {
                        SecureField("密碼（至少 10 個 UTF-16 單位）", text: $password)
                        SecureField("再次輸入密碼", text: $confirmPassword)
                        Text("請使用長而獨特嘅密碼；密碼無法復原。中文通常佔 1、部分 emoji 佔 2 個 UTF-16 單位。")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Button("準備匯出檔案", action: export).disabled(busy || !store.canOperate)
                    Text("預設不含憑證。檔案仍包含站點、預算及警報設定；請妥善保管。系統分享／檔案保存位置由你選擇。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("匯入・先預覽後合併") {
                    SecureField("加密檔案密碼（如需要）", text: $importPassword)
                    Button("選擇備份 JSON…") { error = nil; success = nil; showImporter = true }
                        .disabled(busy || !store.canOperate)
                    Text("以標準化 HTTPS 網址＋名稱配對。已有實例會更新設定；傳入空白憑證會保留本機憑證。未出現嘅本機實例不會刪除。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let preview {
                    Section("匯入預覽・尚未修改任何資料") {
                        Text("新增：\(preview.newCount) 個 · 合併：\(preview.matchedCount) 個")
                        Text("外觀／警報／預算設定可能更新；不會開啟任何連線或付費測試。")
                            .font(.caption).foregroundStyle(.secondary)
                        ForEach(Array(preview.dto.instances.enumerated()), id: \.offset) { _, item in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.name).font(.headline)
                                Text(item.baseUrl).font(.caption).foregroundStyle(.secondary)
                                Text(item.accessToken.isEmpty && item.apiKey.isEmpty ? "未帶入憑證・保留本機值" : "包含憑證・非空值會更新本機值")
                                    .font(.caption).foregroundStyle(.orange)
                            }
                        }
                        Button("確認合併以上資料", role: .destructive) {
                            do {
                                try store.confirmImport(preview); self.preview = nil
                                importPassword = ""; success = "已成功合併備份。"
                            } catch { self.error = AppStore.describe(error) }
                        }.disabled(busy || !store.canOperate)
                        Button("取消匯入", role: .cancel) { self.preview = nil; importPassword = "" }
                    }
                }
                if busy { ProgressView("處理備份中…") }
                InlineError(text: error)
                if let success { Text(success).foregroundStyle(.green) }
            }
            .navigationTitle("備份與還原")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() } } }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json, .plainText, .data]) { result in
                do {
                    let url = try result.get()
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size <= 16 * 1024 * 1024 else { throw StoreFailure(message: "檔案過大，最多 16 MB 加密檔。") }
                    let data = try Data(contentsOf: url)
                    guard data.count <= 16 * 1024 * 1024, var text = String(data: data, encoding: .utf8) else {
                        throw StoreFailure(message: "檔案過大或唔係 UTF-8 JSON。")
                    }
                    if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
                    if BackupCipher.isEncrypted(text) { text = try BackupCipher.decrypt(text: text, password: importPassword) }
                    preview = try store.prepareImport(text)
                    error = nil; success = nil
                } catch { self.error = AppStore.describe(error); preview = nil }
            }
            .fileExporter(isPresented: $showExporter, document: document, contentType: .json,
                          defaultFilename: encrypted ? "newapi-encrypted-backup" : "newapi-backup") { result in
                switch result {
                case .success: success = "備份已匯出。"
                case .failure(let failure): error = AppStore.describe(failure)
                }
                document = TextExportDocument(); password = ""; confirmPassword = ""
            }
            .onDisappear { document = TextExportDocument(); preview = nil; password = ""; confirmPassword = ""; importPassword = "" }
            .onChange(of: store.isProtectedDataAvailable) { _, available in
                if !available { preview = nil; password = ""; confirmPassword = ""; importPassword = ""; document = TextExportDocument() }
            }
        }
    }

    private func export() {
        error = nil; success = nil
        do {
            guard !includeCredentials || encrypted else { throw StoreFailure(message: "含憑證備份必須加密。") }
            if encrypted {
                guard password.utf16.count >= 10, password == confirmPassword else {
                    throw StoreFailure(message: "密碼至少 10 個 UTF-16 單位，兩次輸入必須相同。")
                }
            }
            var text = try store.exportBackup(includeCredentials: includeCredentials)
            if encrypted { text = try BackupCipher.encrypt(plain: text, password: password) }
            document = TextExportDocument(text: text); showExporter = true
        } catch { self.error = AppStore.describe(error) }
    }
}

struct AlertSettingsView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var enabled = false
    @State private var balance = ""
    @State private var daily = ""
    @State private var budget = ""
    @State private var cooldown = 60
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("前景警報") {
                    Toggle("啟用警報評估", isOn: $enabled)
                    TextField("餘額低於／等於 USD（選填）", text: $balance).keyboardType(.decimalPad)
                    TextField("每日消費達 USD（選填）", text: $daily).keyboardType(.decimalPad)
                    TextField("預設每實例月度預算 USD（選填）", text: $budget).keyboardType(.decimalPad)
                    Stepper("冷卻：\(cooldown) 分鐘", value: $cooldown, in: 1...1440)
                }
                Section {
                    Text("預算達 90%／100% 提醒，每實例設定優先。每日及每月警報去重，最多保留 200 筆。匯入嘅個別實例規則仍適用；本版未提供逐實例警報規則編輯器。")
                    Text("只喺前景首頁成功刷新後檢查；示範模式不產生真實通知。通知權限須另按設定頁按鈕授權。")
                }.font(.footnote).foregroundStyle(.secondary)
                InlineError(text: error)
            }
            .navigationTitle("警報設定")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("儲存") { save() } }
            }
            .onAppear {
                enabled = store.alerts.enabled; balance = store.alerts.balanceBelowUsd.map { String($0) } ?? ""
                daily = store.alerts.dailySpendAboveUsd.map { String($0) } ?? ""
                budget = store.alerts.monthlyBudgetUsd.map { String($0) } ?? ""; cooldown = store.alerts.cooldownMinutes
            }
        }
    }
    private func save() {
        do {
            var value = store.alerts
            value.enabled = enabled; value.cooldownMinutes = cooldown
            value.balanceBelowUsd = try AppStore.positiveNumber(balance, label: "餘額警報", allowZero: true)
            value.dailySpendAboveUsd = try AppStore.positiveNumber(daily, label: "每日消費警報")
            value.monthlyBudgetUsd = try AppStore.positiveNumber(budget, label: "每月預算")
            try store.updateAlerts(value); dismiss()
        } catch { self.error = AppStore.describe(error) }
    }
}

struct AlertHistoryView: View {
    @EnvironmentObject private var store: AppStore
    @State private var confirmClear = false
    var body: some View {
        List {
            if store.history.isEmpty { ContentUnavailableView("未有警報", systemImage: "bell", description: Text("只喺前景首頁刷新時評估。")) }
            ForEach(store.history) { record in
                VStack(alignment: .leading, spacing: 6) {
                    Text(record.title).font(.headline)
                    Text(store.preferences.hideMoney ? "金額已隱藏" : record.body).font(.subheadline)
                    Text(Fmt.dateTime(record.time / 1000)).font(.caption).foregroundStyle(.secondary)
                }.padding(.vertical, 4)
            }
        }
        .navigationTitle("警報記錄")
        .toolbar { Button("清除", role: .destructive) { confirmClear = true }.disabled(store.history.isEmpty) }
        .confirmationDialog("清除本機警報記錄？不會重設警報冷卻。", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("清除記錄", role: .destructive) {
                do { try store.clearHistory() } catch { store.message = AppStore.describe(error) }
            }
        }
    }
}
