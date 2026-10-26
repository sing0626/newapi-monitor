import SwiftUI

struct InstanceDetailView: View {
    @EnvironmentObject private var store: AppStore
    let instanceID: String
    @State private var editing = false
    private var instance: Instance? { store.source(instanceID) }

    var body: some View {
        List {
            if store.settings.demo { Section { OfflineBanner() } }
            if let instance {
                Section("站點") {
                    Text(instance.name).font(.headline)
                    Text(instance.baseUrl).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                    if !store.settings.demo {
                        Label(instance.hasAccess ? "已設定管理 Token" : "未設定管理 Token", systemImage: "key")
                        Label(instance.hasKey ? "已設定 API Key" : "未設定 API Key", systemImage: "key.fill")
                    }
                }
                Section("查閱") {
                    NavigationLink { ReportsView(sourceID: instanceID, embedded: true) } label: { Label("此實例報表", systemImage: "chart.bar") }
                    NavigationLink { ServerListView(instanceID: instanceID, kind: .logs) } label: { Label("日誌・分頁", systemImage: "list.bullet.rectangle") }
                    NavigationLink { ServerListView(instanceID: instanceID, kind: .models) } label: { Label("模型清單", systemImage: "cube") }
                    NavigationLink { ServerListView(instanceID: instanceID, kind: .tokens) } label: { Label("Token（唯讀）", systemImage: "key") }
                    NavigationLink { ServerListView(instanceID: instanceID, kind: .channels) } label: { Label("渠道（唯讀）", systemImage: "point.3.connected.trianglepath.dotted") }
                }
                Section {
                    Text("本版本不會新增、修改或刪除伺服器 Token／渠道。日誌內容文字不會顯示或寫入磁碟。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            } else { ContentUnavailableView("實例已變更", systemImage: "server.rack", description: Text("請返回總覽重新選擇。")) }
        }
        .navigationTitle(instance?.name ?? "實例")
        .toolbar {
            if let instance, !store.settings.demo {
                Button("編輯") { editing = true }
                    .sheet(isPresented: $editing) { InstanceEditorView(instance: instance) }
            }
        }
    }
}

enum ServerListKind: String {
    case logs = "日誌"
    case models = "模型"
    case tokens = "Token（唯讀）"
    case channels = "渠道（唯讀）"
}

struct ServerListView: View {
    @EnvironmentObject private var store: AppStore
    let instanceID: String
    let kind: ServerListKind
    @StateObject private var operation = ViewOperation()
    @State private var logs: [LogEntry] = []
    @State private var models: [String] = []
    @State private var tokens: [TokenInfo] = []
    @State private var channels: [ChannelInfo] = []
    @State private var page = 0
    @State private var hasMore = false
    @State private var total: Int?
    @State private var loaded = false
    @State private var filterDates = false
    @State private var start = Calendar.current.date(byAdding: .day, value: -6, to: Date()) ?? Date()
    @State private var end = Date()

    var body: some View {
        List {
            if store.settings.demo { Section { OfflineBanner() } }
            if kind == .tokens || kind == .channels {
                Section { Label("只供查閱・不會修改伺服器資料", systemImage: "lock.shield") }
            }
            if kind == .logs {
                Section("日誌範圍") {
                    Toggle("按本地日期篩選", isOn: $filterDates)
                    if filterDates {
                        DatePicker("開始", selection: $start, displayedComponents: .date)
                        DatePicker("結束", selection: $end, displayedComponents: .date)
                    }
                    Text("最多 366 個本地日；修改日期後請重新讀取。日誌金額使用實例自訂換算率，未設定則以 500,000 估算。")
                        .font(.caption).foregroundStyle(.secondary)
                }.disabled(operation.busy)
            }
            Section {
                Button(loaded ? "重新讀取" : "讀取資料") { load(reset: true) }.disabled(operation.busy || !store.canOperate)
                if operation.busy { ProgressView("讀取中…") }
                InlineError(text: operation.error)
                if loaded && empty { Text("未有可顯示資料").foregroundStyle(.secondary) }
            }
            switch kind {
            case .models:
                Section("\(models.count) 個模型") {
                    ForEach(models, id: \.self) { Text($0).textSelection(.enabled) }
                }
            case .logs:
                Section("已讀取 \(logs.count)\(total.map { " / \($0)" } ?? "") 筆") {
                    ForEach(logs) { row in LogRow(entry: row, qpu: store.source(instanceID)?.quotaPerUnit ?? 500_000) }
                    if hasMore {
                        Button("載入下一頁") { load(reset: false) }.disabled(operation.busy)
                    }
                }
            case .tokens:
                Section("\(tokens.count) 個 Token・不顯示密鑰") {
                    ForEach(tokens) { token in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(token.name).font(.headline)
                            Text("狀態：\(status(token.status)) · 群組：\(token.group)").font(.subheadline)
                            Text(token.expiredTime < 0 ? "無到期日" : "到期：\(Fmt.dateTime(token.expiredTime))").font(.caption).foregroundStyle(.secondary)
                            Text(token.unlimitedQuota ? "配額不限" : "剩餘 quota：\(store.preferences.hideMoney ? "••••" : String(format: "%.0f", token.remainQuota))")
                                .font(.caption)
                        }.padding(.vertical, 4)
                    }
                }
            case .channels:
                Section("\(channels.count) 個渠道") {
                    ForEach(channels) { channel in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(channel.name).font(.headline)
                            Text("狀態：\(status(channel.status)) · 類型：\(channel.type)").font(.subheadline)
                            Text("伺服器回報延遲：\(channel.responseTime.map { "\($0) ms" } ?? "—")")
                                .font(.caption).foregroundStyle(.secondary)
                        }.padding(.vertical, 4)
                    }
                }
            }
        }
        .navigationTitle(kind.rawValue)
        .onDisappear { operation.cancel() }
        .onChange(of: store.selectedTab) { _, _ in operation.cancel() }
        .onChange(of: store.generation) { _, _ in clear() }
        .onChange(of: filterDates) { _, _ in clear() }
        .onChange(of: start) { _, _ in clear() }
        .onChange(of: end) { _, _ in clear() }
    }

    private var empty: Bool {
        switch kind { case .logs: return logs.isEmpty; case .models: return models.isEmpty; case .tokens: return tokens.isEmpty; case .channels: return channels.isEmpty }
    }
    private func status(_ value: Int) -> String {
        switch value { case 1: return "啟用"; case 2: return "停用"; case 3: return "封禁"; default: return "未知（\(value)）" }
    }
    private func clear() {
        operation.cancel(); logs = []; models = []; tokens = []; channels = []
        loaded = false; page = 0; total = nil; hasMore = false
    }
    private func load(reset: Bool) {
        guard let instance = store.source(instanceID) else { return }
        let generation = store.generation, demo = store.settings.demo
        let range = ReportRange(start: start, end: end)
        if filterDates, kind == .logs {
            do { try ReportsView.validateRange(range) } catch { operation.error = AppStore.describe(error); return }
        }
        let nextPage = reset ? 1 : page + 1
        let startSec = filterDates ? range.startTimestampSec : nil
        let endSec = filterDates ? range.endTimestampSec : nil
        operation.start(store: store) {
            let client = NewApiClient(instance: instance)
            switch kind {
            case .models:
                let result: [String]
                if demo { result = DemoEngine.generateDashboardSnapshot().models }
                else { result = try await client.models() }
                try ViewOperation.check(store, generation: generation)
                models = Array(Set(result)).sorted()
            case .tokens:
                var result: [TokenInfo]
                if demo { result = DemoEngine.demoTokens() } else { result = try await client.tokens() }
                try ViewOperation.check(store, generation: generation)
                for index in result.indices { result[index].key = "" }
                tokens = result
            case .channels:
                let result: [ChannelInfo]
                if demo { result = DemoEngine.demoChannels() } else { result = try await client.channels() }
                try ViewOperation.check(store, generation: generation)
                channels = result
            case .logs:
                var incoming: [LogEntry]
                let more: Bool
                let count: Int?
                if demo {
                    let rows = DemoEngine.generateDashboardSnapshot().recentLogs.filter {
                        (startSec == nil || $0.createdAt >= startSec!) && (endSec == nil || $0.createdAt <= endSec!)
                    }
                    incoming = rows; more = false; count = rows.count
                } else {
                    let result = try await client.logs(page: nextPage, start: startSec, end: endSec)
                    incoming = result.rows; more = result.hasMore; count = result.total
                }
                try ViewOperation.check(store, generation: generation)
                for index in incoming.indices { incoming[index].content = "" }
                if reset { logs = [] }
                let known = Set(logs.map(\.id))
                var seen = known
                logs.append(contentsOf: incoming.filter { seen.insert($0.id).inserted })
                page = nextPage; hasMore = more && !incoming.isEmpty; total = count
            }
            loaded = true
        }
    }
}
