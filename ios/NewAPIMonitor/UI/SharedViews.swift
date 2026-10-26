import SwiftUI
import UniformTypeIdentifiers

struct TextExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json, .commaSeparatedText, .plainText] }
    var text: String
    init(text: String = "") { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents, let value = String(data: data, encoding: .utf8) else {
            throw StoreFailure(message: "檔案不是 UTF-8 文字。")
        }
        text = value
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

/// Every manually triggered view task owns a cancellation handle and generation.
/// A store invalidation cancels the task before any old result can be published.
@MainActor
final class ViewOperation: ObservableObject {
    @Published private(set) var busy = false
    @Published var error: String?
    private var task: Task<Void, Never>?
    private var registration: UUID?
    private weak var store: AppStore?
    private var token = UUID()

    func start(store: AppStore, action: @escaping @MainActor () async throws -> Void) {
        cancel()
        guard store.canOperate else { error = store.loadFailure ?? "App 暫停中，請解鎖並返回前景。"; return }
        self.store = store; error = nil; busy = true
        let id = UUID(); token = id
        registration = store.registerCancellation { [weak self] in self?.cancel() }
        task = Task { [weak self] in
            do { try await action() }
            catch is CancellationError { }
            catch { if !Task.isCancelled { self?.error = AppStore.describe(error) } }
            guard let self, self.token == id else { return }
            self.busy = false
            store.unregisterCancellation(self.registration); self.registration = nil
        }
    }

    func cancel() {
        token = UUID(); task?.cancel(); task = nil; busy = false
        store?.unregisterCancellation(registration); registration = nil
    }

    static func check(_ store: AppStore, generation: UUID) throws {
        try Task.checkCancellation()
        guard store.canOperate, store.generation == generation else { throw CancellationError() }
    }
}

struct OfflineBanner: View {
    var body: some View {
        Label("離線示範・所有資料均為模擬，唔會連線", systemImage: "wifi.slash")
            .font(.callout).foregroundStyle(.orange).accessibilityAddTraits(.isStaticText)
    }
}

struct InlineError: View {
    let text: String?
    var body: some View {
        if let text { Label(text, systemImage: "exclamationmark.triangle").font(.callout).foregroundStyle(.red).textSelection(.enabled) }
    }
}

struct MoneyRow: View {
    let title: String
    let value: String
    var body: some View {
        LabeledContent(title) { Text(value).font(.body.monospacedDigit()).foregroundStyle(.primary) }
    }
}

struct LogRow: View {
    @EnvironmentObject private var store: AppStore
    let entry: LogEntry
    let qpu: Double
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(entry.modelName.isEmpty ? "未指定模型" : entry.modelName).font(.headline)
            Text("\(Fmt.dateTime(entry.createdAt)) · \(entry.isStream ? "串流" : "一般") · \(entry.type == 5 ? "錯誤" : "記錄")")
                .font(.caption).foregroundStyle(.secondary)
            Text("Token：\(entry.tokenName.isEmpty ? "未命名" : entry.tokenName) · 輸入 \(entry.promptTokens) / 輸出 \(entry.completionTokens)")
                .font(.caption).foregroundStyle(.secondary)
            Text(store.money(entry.quota / qpu)).font(.subheadline.monospacedDigit())
            // Server log content may contain prompts or secrets; never display or persist it.
        }.padding(.vertical, 4).accessibilityElement(children: .combine)
    }
}
