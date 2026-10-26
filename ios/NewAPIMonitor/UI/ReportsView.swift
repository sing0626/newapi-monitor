import SwiftUI
import Charts
import UniformTypeIdentifiers

struct ReportsView: View {
    @EnvironmentObject private var store: AppStore
    var sourceID: String? = nil
    var embedded = false
    @StateObject private var operation = ViewOperation()
    @State private var start = Calendar.current.date(byAdding: .day, value: -6, to: Date()) ?? Date()
    @State private var end = Date()
    @State private var compareMonth = false
    @State private var report: CrossReport?
    @State private var exportDocument = TextExportDocument()
    @State private var showExporter = false
    @State private var confirmExport = false
    @State private var visible = false

    var body: some View {
        Group {
            if embedded { content } else { NavigationStack { content } }
        }
        .onAppear { visible = true }
        .onDisappear { visible = false; operation.cancel() }
        .onChange(of: store.selectedTab) { _, _ in operation.cancel() }
        .onChange(of: store.generation) { _, _ in operation.cancel(); report = nil; exportDocument = TextExportDocument() }
        .onChange(of: start) { _, _ in invalidateReport() }
        .onChange(of: end) { _, _ in invalidateReport() }
        .onChange(of: compareMonth) { _, _ in invalidateReport() }
        .fileExporter(isPresented: $showExporter, document: exportDocument, contentType: .commaSeparatedText,
                      defaultFilename: "newapi-report") { result in
            if case .failure(let error) = result { store.message = AppStore.describe(error) }
            exportDocument = TextExportDocument()
        }
        .confirmationDialog("CSV 會包含未遮蔽金額、實例及模型名稱，請妥善保管。", isPresented: $confirmExport, titleVisibility: .visible) {
            Button("匯出 CSV") {
                if let report { exportDocument = TextExportDocument(text: ReportCsv.render(report)); showExporter = true }
            }
        }
    }

    private var content: some View {
        List {
            if store.settings.demo { Section { OfflineBanner() } }
            Section("本地日期範圍") {
                DatePicker("開始日期", selection: $start, displayedComponents: .date)
                DatePicker("結束日期", selection: $end, displayedComponents: .date)
                Toggle("與上月同段比較", isOn: $compareMonth)
                Text(compareMonth ? "比較日曆上月相應日期。" : "比較之前相同日數（不重疊）。")
                    .font(.caption).foregroundStyle(.secondary)
                Text("包含開始及結束日，最多 366 日。報表由手動讀取，不會自動發送付費請求。")
                    .font(.caption).foregroundStyle(.secondary)
            }.disabled(operation.busy)
            Section {
                Button(operation.busy ? "正在讀取…" : "產生報表") { load() }
                    .disabled(operation.busy || !store.canOperate || store.sources.isEmpty)
                if operation.busy { ProgressView(); Button("取消讀取", role: .cancel) { operation.cancel() } }
                InlineError(text: operation.error)
            }
            if let report {
                Section("期間總額") {
                    MoneyRow(title: "合計", value: store.money(report.totalUsd))
                    Text("有總額資料：\(report.availableCount)/\(report.sources.count) 個實例")
                    Text("範圍：\(report.range.startDayString) — \(report.range.endDayString)")
                        .font(.caption).foregroundStyle(.secondary)
                    if report.availableCount < report.sources.count || report.sources.contains(where: { $0.report?.current.partial == true || $0.report?.current.failure != nil }) {
                        Label("部分／未完整資料，總額不能視為完整帳單。", systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                    }
                    Button("匯出 CSV") { confirmExport = true }
                }
                ForEach(report.sources) { source in
                    Section(source.name) {
                        if let data = source.report {
                            MoneyRow(title: "本期", value: store.money(data.current.totalUsd))
                            MoneyRow(title: "比較期", value: store.money(data.previous.totalUsd))
                            Text("比較：\(data.comparisonRange.startDayString) — \(data.comparisonRange.endDayString)")
                                .font(.caption).foregroundStyle(.secondary)
                            if let current = data.current.totalUsd, let previous = data.previous.totalUsd {
                                MoneyRow(title: "差額", value: store.money(current - previous))
                            }
                            if data.current.partial { Label("本期日誌不完整；分類只反映已載入資料", systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
                            if data.previous.partial { Label("比較期日誌不完整", systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
                            InlineError(text: data.current.failure)
                            InlineError(text: data.previous.failure.map { "比較期：\($0)" })
                            breakdown(data)
                        }
                        InlineError(text: source.failure)
                    }
                }
            } else if store.sources.isEmpty {
                ContentUnavailableView("未有實例", systemImage: "chart.bar", description: Text("請先加入站點或開啟離線示範。"))
            }
        }
        .navigationTitle(sourceID == nil ? "跨實例報表" : "實例報表")
    }

    @ViewBuilder
    private func breakdown(_ data: PeriodReport) -> some View {
        let groups = CostBreakdown.group(rows: data.current.rows ?? [], qpu: data.current.qpu, byToken: false)
        if !groups.isEmpty {
            Text("模型分類・已載入日誌").font(.headline)
            if !store.preferences.hideMoney {
                Chart(Array(groups.prefix(8))) { group in
                    BarMark(x: .value("USD", group.usd), y: .value("模型", group.name))
                }
                .frame(height: max(160, CGFloat(min(groups.count, 8)) * 36))
                .accessibilityLabel("模型消費分類，最多顯示前八項")
            }
            ForEach(Array(groups.prefix(20))) { group in
                VStack(alignment: .leading, spacing: 4) {
                    MoneyRow(title: group.name, value: store.money(group.usd))
                    Text("\(group.requests) 次 · \(group.tokens) tokens").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func invalidateReport() { operation.cancel(); report = nil }

    static func validateRange(_ range: ReportRange) throws {
        try range.validate()
    }

    private func load() {
        let range = ReportRange(start: start, end: end)
        do {
            try Self.validateRange(range)
            try Self.validateRange(compareMonth ? range.previousMonthPeriod() : range.previousPeriod())
        } catch { operation.error = AppStore.describe(error); return }
        let sources = sourceID.map { id in store.sources.filter { $0.id == id } } ?? store.sources
        let demo = store.settings.demo, generation = store.generation, month = compareMonth
        report = nil
        operation.start(store: store) {
            var results = sources.map { SourceReport(id: $0.id, name: $0.name, report: nil, failure: "尚未讀取") }
            for (index, instance) in sources.enumerated() {
                try ViewOperation.check(store, generation: generation)
                do {
                    var result: PeriodReport
                    if demo { result = Self.demoReport(range: range, compareMonth: month) }
                    else { result = try await NewApiClient(instance: instance).report(range: range, compareMonth: month) }
                    try ViewOperation.check(store, generation: generation)
                    // Keep only numerical/log metadata; discard server content immediately.
                    result.current.rows = result.current.rows?.map { row in var value = row; value.content = ""; return value }
                    result.previous.rows = result.previous.rows?.map { row in var value = row; value.content = ""; return value }
                    results[index] = SourceReport(id: instance.id, name: instance.name, report: result, failure: nil)
                } catch {
                    try ViewOperation.check(store, generation: generation)
                    results[index] = SourceReport(id: instance.id, name: instance.name, report: nil, failure: AppStore.describe(error))
                }
                guard visible else { throw CancellationError() }
                report = CrossReport(range: range, sources: results)
            }
        }
    }

    private static func demoReport(range: ReportRange, compareMonth: Bool) -> PeriodReport {
        let demo = DemoEngine.generateDashboardSnapshot()
        let comparison = compareMonth ? range.previousMonthPeriod() : range.previousPeriod()
        let logs = demo.recentLogs.filter { $0.createdAt >= range.startTimestampSec && $0.createdAt <= range.endTimestampSec }
        let current = ReportSlice(totalUsd: logs.filter { $0.type == 2 }.map { $0.quota / demo.qpu }.reduce(0, +),
            rows: logs, partial: true, failure: "離線模擬：只用示範最近日誌，唔係真實帳單。", atMs: AppStore.now, qpu: demo.qpu)
        let previous = ReportSlice(totalUsd: nil, rows: [], partial: true,
            failure: "離線示範無比較期資料。", atMs: AppStore.now, qpu: demo.qpu)
        return PeriodReport(range: range, current: current, previous: previous, comparisonRange: comparison)
    }
}
