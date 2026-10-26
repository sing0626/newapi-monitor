import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var store: AppStore
    @State private var showAdd = false
    @State private var path: [String] = []
    private var refreshKey: String {
        "\(store.generation)-\(store.selectedTab)-\(store.homeVisible)-\(store.canOperate)-\(path.count)"
    }
    private var included: [Instance] { store.sources.filter { !store.preferences.excluded.contains($0.id) } }
    private var balances: [Double] { included.compactMap { store.snapshots[$0.id]?.balanceUsd } }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if store.settings.demo { Section { OfflineBanner() } }
                if let failure = store.loadFailure {
                    Section("資料保護") {
                        InlineError(text: failure)
                        Button("重新讀取（不會覆蓋原資料）") { store.reload() }
                    }
                }
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("可用餘額合計").font(.subheadline).foregroundStyle(.secondary)
                        Text(store.money(balances.isEmpty ? nil : balances.reduce(0, +)))
                            .font(.largeTitle.bold().monospacedDigit()).minimumScaleFactor(0.6)
                        Text("\(balances.count)/\(included.count) 個已納入實例有資料 · USD")
                            .font(.caption).foregroundStyle(.secondary)
                        Text("部分資料不等於完整總額；排除嘅實例仍會顯示及檢查警報。")
                            .font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 8)
                }
                Section("實例") {
                    if store.sources.isEmpty {
                        ContentUnavailableView("仲未有實例", systemImage: "server.rack", description: Text("加入 HTTPS 站點，或到設定開啟離線示範。"))
                        Button("加入第一個實例") { showAdd = true }.disabled(store.loadFailure != nil)
                    }
                    ForEach(store.sources) { instance in
                        NavigationLink(value: instance.id) {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    if store.preferences.pinned.contains(instance.id) { Image(systemName: "pin.fill").foregroundStyle(.orange) }
                                    Text(instance.name).font(.headline)
                                }
                                let snapshot = store.snapshots[instance.id]
                                MoneyRow(title: "餘額", value: store.money(snapshot?.balanceUsd))
                                MoneyRow(title: "今日", value: store.money(snapshot?.todayUsd))
                                MoneyRow(title: "本月", value: store.money(snapshot?.monthUsd))
                                if let budget = store.budgets[instance.id] {
                                    MoneyRow(title: "每月預算", value: store.money(budget))
                                    if let spent = snapshot?.monthUsd, !store.preferences.hideMoney {
                                        ProgressView(value: min(max(spent / budget, 0), 1)).accessibilityLabel("預算使用比例")
                                    }
                                }
                                if store.preferences.excluded.contains(instance.id) { Text("不計入餘額合計").font(.caption).foregroundStyle(.secondary) }
                                InlineError(text: store.failures[instance.id] ?? snapshot?.balanceFailure ?? snapshot?.spendFailure)
                                Text(snapshot.map { "最近更新：\(Fmt.relativeTime(fromMs: $0.attemptedAt))" } ?? "尚未讀取")
                                    .font(.caption).foregroundStyle(.secondary)
                            }.padding(.vertical, 6)
                        }
                    }
                }
                Section {
                    Text("只喺前景首頁顯示時，每 \(store.settings.intervalSec) 秒刷新；同時最多讀取 3 個實例。唔提供背景監察。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("NewAPI Monitor")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { Task { await store.refreshHome() } } label: {
                        if store.refreshing { ProgressView() } else { Image(systemName: "arrow.clockwise") }
                    }.accessibilityLabel("刷新總覽").disabled(!store.canRefreshHome)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                        .accessibilityLabel("加入實例").disabled(store.loadFailure != nil || store.settings.demo)
                }
            }
            .navigationDestination(for: String.self) { InstanceDetailView(instanceID: $0) }
            .refreshable { await store.refreshHome() }
            .sheet(isPresented: $showAdd) { InstanceEditorView(instance: nil) }
        }
        .onAppear { store.homeVisible = path.isEmpty }
        .onDisappear { store.homeVisible = false; store.stopHomeRefresh() }
        .onChange(of: path) { _, value in store.homeVisible = value.isEmpty; if !value.isEmpty { store.stopHomeRefresh() } }
        .onChange(of: store.generation) { _, _ in path = [] }
        .task(id: refreshKey) {
            guard store.canRefreshHome, path.isEmpty else { return }
            while !Task.isCancelled && store.canRefreshHome {
                await store.refreshHome()
                do { try await Task.sleep(for: .seconds(store.settings.intervalSec)) } catch { return }
            }
        }
    }
}

struct InstanceEditorView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    let instance: Instance?
    @State private var name = ""
    @State private var baseURL = ""
    @State private var accessToken = ""
    @State private var apiKey = ""
    @State private var auth = AuthStyle.bearer
    @State private var keyAuth = AuthStyle.bearer
    @State private var quota = ""
    @State private var budget = ""
    @State private var pinned = false
    @State private var included = true
    @State private var error: String?
    @State private var deleteConfirm = false

    var body: some View {
        NavigationStack {
            Form {
                Section("站點資料") {
                    TextField("名稱", text: $name)
                    TextField("https://example.com/Prefix", text: $baseURL)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text("只接受 HTTPS；保留路徑大小寫。不可包含帳密、?參數或 #fragment。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("憑證・只存於系統鑰匙圈") {
                    SecureField("管理存取 Token（可留空）", text: $accessToken).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Picker("管理認證", selection: $auth) { Text("Bearer").tag(AuthStyle.bearer); Text("Raw").tag(AuthStyle.raw) }
                    SecureField("API Key（可留空）", text: $apiKey).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Picker("API Key 認證", selection: $keyAuth) { Text("Bearer").tag(AuthStyle.bearer); Text("Raw").tag(AuthStyle.raw) }
                    Text("管理 Token 用於日誌及報表；API Key 用於模型及測速。編輯時清空欄位會刪除該憑證。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("顯示及預算") {
                    TextField("Quota per unit（留空自動）", text: $quota).keyboardType(.decimalPad)
                    TextField("每月預算 USD（選填）", text: $budget).keyboardType(.decimalPad)
                    Toggle("置頂", isOn: $pinned)
                    Toggle("計入餘額合計", isOn: $included)
                }
                InlineError(text: error)
                if instance != nil {
                    Section { Button("刪除實例及本機憑證", role: .destructive) { deleteConfirm = true } }
                }
            }
            .navigationTitle(instance == nil ? "加入實例" : "編輯實例")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("儲存", action: save).disabled(store.loadFailure != nil) }
            }
            .confirmationDialog("確定刪除此實例？本機憑證亦會刪除，伺服器資料不受影響。", isPresented: $deleteConfirm, titleVisibility: .visible) {
                Button("刪除", role: .destructive) {
                    do { if let instance { try store.deleteInstance(instance.id) }; dismiss() }
                    catch { self.error = AppStore.describe(error) }
                }
            }
            .onAppear {
                guard let instance else { return }
                name = instance.name; baseURL = instance.baseUrl; accessToken = instance.accessToken; apiKey = instance.apiKey
                auth = instance.authStyle ?? .bearer; keyAuth = instance.authStyleKey ?? .bearer
                quota = instance.quotaPerUnit.map { String($0) } ?? ""
                budget = store.budgets[instance.id].map { String($0) } ?? ""
                pinned = store.preferences.pinned.contains(instance.id); included = !store.preferences.excluded.contains(instance.id)
            }
        }
    }

    private func save() {
        do {
            let value = Instance(id: instance?.id ?? "", name: name, baseUrl: baseURL, accessToken: accessToken, apiKey: apiKey,
                quotaPerUnit: try AppStore.positiveNumber(quota, label: "Quota per unit"), authStyle: auth,
                authStyleKey: keyAuth, createdAt: instance?.createdAt ?? AppStore.now)
            try store.saveInstance(value, budget: AppStore.positiveNumber(budget, label: "每月預算"), pinned: pinned, included: included)
            dismiss()
        } catch { self.error = AppStore.describe(error) }
    }
}
