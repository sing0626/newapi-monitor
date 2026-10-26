import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var systemScheme
    @State private var showAdd = false
    @State private var path: [String] = []

    private var theme: GlassTheme {
        GlassTheme(mode: store.appearance.mode, accent: store.appearance.accent, scheme: systemScheme)
    }
    private var refreshKey: String {
        "\(store.generation)-\(store.selectedTab)-\(store.homeVisible)-\(store.canOperate)-\(path.count)"
    }
    private var included: [Instance] { store.sources.filter { !store.preferences.excluded.contains($0.id) } }
    private var balances: [Double] { included.compactMap { store.snapshots[$0.id]?.balanceUsd } }
    private var todays: [Double] { store.sources.compactMap { store.snapshots[$0.id]?.todayUsd } }
    private var months: [Double] { store.sources.compactMap { store.snapshots[$0.id]?.monthUsd } }

    var body: some View {
        NavigationStack(path: $path) {
            ZStack {
                LiquidGlassBackdrop(theme: theme)
                ScrollView {
                    NativeGlassContainer(spacing: 16) {
                        VStack(alignment: .leading, spacing: 16) {
                            if store.settings.demo { OfflineBanner() }
                            if let failure = store.loadFailure {
                                GlassCard(theme: theme) {
                                    Text("資料保護").font(.headline).foregroundStyle(theme.text)
                                    InlineError(text: failure)
                                    Button("重新讀取（不會覆蓋原資料）") { store.reload() }
                                }
                            }
                            heroCard
                            quickActions
                            HStack {
                                Text("實例").font(.headline).foregroundStyle(theme.text)
                                Spacer()
                                Button(store.refreshing ? "更新中…" : "全部刷新") {
                                    Task { await store.refreshHome() }
                                }
                                .font(.caption.bold())
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .nativeLiquidGlassCapsule(interactive: true)
                                .disabled(!store.canRefreshHome || store.refreshing)
                            }
                            if store.sources.isEmpty {
                                GlassCard(theme: theme) {
                                    Text("仲未有實例").font(.headline).foregroundStyle(theme.text)
                                    Text("加入 HTTPS 站點，或到設定開啟離線示範。")
                                        .font(.subheadline)
                                        .foregroundStyle(theme.muted)
                                    Button("加入第一個實例") { showAdd = true }
                                        .buttonStyle(.borderedProminent)
                                        .disabled(store.loadFailure != nil)
                                }
                            }
                            ForEach(store.sources) { instance in
                                NavigationLink(value: instance.id) {
                                    instanceCard(instance)
                                }
                                .buttonStyle(.plain)
                            }
                            Text("只喺前景首頁顯示時，每 \(store.settings.intervalSec) 秒刷新；同時最多讀取 3 個實例。唔提供背景監察。")
                                .font(.footnote)
                                .foregroundStyle(theme.muted)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                }
            }
            .navigationTitle("NewAPI Monitor")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    HStack(spacing: 8) {
                        BrandLogo(theme: theme, size: 22)
                        Button { Task { await store.refreshHome() } } label: {
                            if store.refreshing { ProgressView() } else { Image(systemName: "arrow.clockwise") }
                        }
                        .accessibilityLabel("刷新總覽")
                        .disabled(!store.canRefreshHome)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                        .accessibilityLabel("加入實例")
                        .disabled(store.loadFailure != nil || store.settings.demo)
                }
            }
            .navigationDestination(for: String.self) {
                InstanceDetailView(instanceID: $0)
                    .toolbar(.hidden, for: .tabBar)
            }
            .refreshable { await store.refreshHome() }
            .sheet(isPresented: $showAdd) { InstanceEditorView(instance: nil) }
        }
        .onAppear { store.homeVisible = path.isEmpty }
        .onDisappear { store.homeVisible = false; store.stopHomeRefresh() }
        .onChange(of: path) { _, value in
            store.homeVisible = value.isEmpty
            if !value.isEmpty { store.stopHomeRefresh() }
        }
        .onChange(of: store.generation) { _, _ in path = [] }
        .task(id: refreshKey) {
            guard store.canRefreshHome, path.isEmpty else { return }
            while !Task.isCancelled && store.canRefreshHome {
                await store.refreshHome()
                do { try await Task.sleep(for: .seconds(store.settings.intervalSec)) } catch { return }
            }
        }
    }

    private var heroCard: some View {
        GlassCard(theme: theme, padding: 20, cornerRadius: 24, tint: theme.accent1) {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("可用餘額合計").font(.subheadline).foregroundStyle(theme.secondaryText)
                    Spacer()
                    Text("\(balances.count)/\(included.count) 實例 · USD")
                        .font(.caption.bold())
                        .foregroundStyle(theme.accent2)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .nativeLiquidGlassCapsule()
                }
                Text(store.money(balances.isEmpty ? nil : balances.reduce(0, +)))
                    .font(.system(size: 36, weight: .bold, design: .rounded).monospacedDigit())
                    .foregroundStyle(theme.text)
                    .contentTransition(.numericText())
                    .minimumScaleFactor(0.6)
                Divider()
                HStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("今日消費").font(.caption).foregroundStyle(theme.muted)
                        Text(store.money(todays.isEmpty ? nil : todays.reduce(0, +)))
                            .font(.headline.monospacedDigit())
                            .foregroundStyle(theme.text)
                            .contentTransition(.numericText())
                    }
                    Spacer()
                    VStack(alignment: .leading, spacing: 4) {
                        Text("本月消費").font(.caption).foregroundStyle(theme.muted)
                        Text(store.money(months.isEmpty ? nil : months.reduce(0, +)))
                            .font(.headline.monospacedDigit())
                            .foregroundStyle(theme.text)
                            .contentTransition(.numericText())
                    }
                }
                Text("部分資料不等於完整總額；排除嘅實例仍會顯示及檢查警報。")
                    .font(.caption2)
                    .foregroundStyle(theme.muted)
            }
        }
    }

    private var quickActions: some View {
        HStack(spacing: 10) {
            quickButton("消費報表", icon: "chart.bar.xaxis", tab: 1)
            quickButton("串流測速", icon: "speedometer", tab: 2)
            quickButton("警報與設定", icon: "bell.badge", tab: 3)
        }
    }

    private func quickButton(_ label: String, icon: String, tab: Int) -> some View {
        Button { store.selectedTab = tab } label: {
            GlassCard(theme: theme, padding: 12, cornerRadius: 18, interactive: true) {
                VStack(spacing: 8) {
                    Image(systemName: icon)
                        .font(.headline)
                        .foregroundStyle(theme.accent1)
                        .symbolEffect(.bounce, value: store.selectedTab == tab)
                    Text(label)
                        .font(.caption.bold())
                        .foregroundStyle(theme.secondaryText)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.plain)
    }

    private func instanceCard(_ instance: Instance) -> some View {
        let snapshot = store.snapshots[instance.id]
        let nowMs = AppStore.now
        let fresh = snapshot?.balanceFresh(nowMs: nowMs) == true
        let statusColor: Color = fresh ? GlassTheme.okGreen : (snapshot?.balanceFailure != nil ? GlassTheme.errRed : GlassTheme.warnAmber)

        return GlassCard(theme: theme, cornerRadius: 20, interactive: true) {
            HStack(spacing: 8) {
                StatusDot(color: statusColor)
                if store.preferences.pinned.contains(instance.id) {
                    Image(systemName: "pin.fill").foregroundStyle(.orange)
                }
                Text(instance.name).font(.headline).foregroundStyle(theme.text)
                Spacer()
                Text("詳情 ›").font(.caption.bold()).foregroundStyle(theme.accent1)
            }
            MoneyRow(title: "餘額", value: store.money(snapshot?.balanceUsd))
            MoneyRow(title: "今日", value: store.money(snapshot?.todayUsd))
            MoneyRow(title: "本月", value: store.money(snapshot?.monthUsd))
            if let budget = store.budgets[instance.id] {
                MoneyRow(title: "每月預算", value: store.money(budget))
                if let spent = snapshot?.monthUsd, !store.preferences.hideMoney {
                    ProgressView(value: min(max(spent / budget, 0), 1))
                        .tint(spent / budget >= 0.9 ? GlassTheme.warnAmber : theme.accent1)
                        .accessibilityLabel("預算使用比例")
                }
            }
            if store.preferences.excluded.contains(instance.id) {
                Text("不計入餘額合計").font(.caption).foregroundStyle(theme.muted)
            }
            InlineError(text: store.failures[instance.id] ?? snapshot?.balanceFailure ?? snapshot?.spendFailure)
            Text(snapshot.map { "最近更新：\(Fmt.relativeTime(fromMs: $0.attemptedAt))" } ?? "尚未讀取")
                .font(.caption)
                .foregroundStyle(theme.muted)
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
