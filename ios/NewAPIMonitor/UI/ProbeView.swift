import SwiftUI

struct ProbeView: View {
    @EnvironmentObject private var store: AppStore
    @StateObject private var operation = ViewOperation()
    @State private var sourceID = ""
    @State private var model = ""
    @State private var models: [String] = []
    @State private var repetitions = 1
    @State private var results: [StreamProbeResult] = []
    @State private var confirmPaid = false
    @State private var pending: ProbeRequest?

    private struct ProbeRequest {
        var instance: Instance
        var model: String
        var count: Int
        var generation: UUID
        var demo: Bool
    }

    var body: some View {
        NavigationStack {
            List {
                if store.settings.demo { Section { OfflineBanner() } }
                Section("測試目標") {
                    Picker("實例", selection: $sourceID) {
                        Text("請選擇").tag("")
                        ForEach(store.sources) { Text($0.name).tag($0.id) }
                    }
                    TextField("模型名稱", text: $model).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if !models.isEmpty {
                        Picker("從清單選擇", selection: $model) {
                            Text("自訂模型").tag(model)
                            ForEach(models.filter { $0 != model }, id: \.self) { Text($0).tag($0) }
                        }
                    }
                    Button("讀取模型清單（非生成請求）", action: loadModels)
                        .disabled(operation.busy || sourceID.isEmpty || !store.canOperate)
                    Stepper("連續測試 \(repetitions) 次", value: $repetitions, in: 1...10)
                }.disabled(operation.busy)
                Section {
                    Label("可能產生費用", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    Text("每次會發送一個最多 64 個輸出 token 嘅串流生成請求。最多 10 次，逐個執行；取消後已送出嘅請求仍可能計費。只保留本次計時指標，不保存生成文字。")
                        .font(.callout).foregroundStyle(.secondary)
                    Button(store.settings.demo ? "執行離線模擬" : "開始付費測速…", action: prepareProbe)
                        .buttonStyle(.borderedProminent)
                        .disabled(operation.busy || sourceID.isEmpty || model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !store.canOperate)
                    if operation.busy {
                        ProgressView("執行中…")
                        Button("停止", role: .cancel) { operation.cancel() }
                    }
                    InlineError(text: operation.error)
                }
                Section("本次結果（只存記憶體）") {
                    if results.isEmpty { Text("未有測速結果。唔會自動執行付費測試。 ").foregroundStyle(.secondary) }
                    ForEach(Array(results.enumerated()), id: \.offset) { index, result in
                        VStack(alignment: .leading, spacing: 8) {
                            Label("第 \(index + 1) 次 · \(result.model)", systemImage: result.ok ? "checkmark.circle" : "exclamationmark.triangle")
                                .font(.headline).foregroundStyle(result.ok ? .green : .orange)
                            LabeledContent("首 token", value: result.firstTokenMs.map { "\($0) ms" } ?? "—")
                            LabeledContent("總時間", value: "\(result.totalMs) ms")
                            LabeledContent("輸出用量", value: result.completionTokens.map { "\($0) tokens" } ?? "—")
                            LabeledContent("速度", value: result.tokensPerSecond.map { String(format: "%.1f token/s", $0) } ?? "—（服務端未提供用量）")
                            InlineError(text: result.failure)
                        }.padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle("串流測速")
            .confirmationDialog("確認送出付費生成請求？", isPresented: $confirmPaid, titleVisibility: .visible) {
                Button("確認執行 \(pending?.count ?? 0) 次（可能收費）", role: .destructive) { runPending() }
                Button("取消", role: .cancel) { pending = nil }
            } message: {
                Text("\(pending?.instance.name ?? "")\n\(pending?.model ?? "")\n最多 \(pending?.count ?? 0) 個順序請求；收費依站點政策。")
            }
        }
        .onAppear { if store.source(sourceID) == nil { sourceID = store.sources.first?.id ?? "" } }
        .onDisappear { operation.cancel(); pending = nil; confirmPaid = false }
        .onChange(of: store.selectedTab) { _, _ in operation.cancel(); pending = nil; confirmPaid = false }
        .onChange(of: sourceID) { _, _ in reset() }
        .onChange(of: store.generation) { _, _ in reset(); if store.source(sourceID) == nil { sourceID = store.sources.first?.id ?? "" } }
    }

    private func reset() {
        operation.cancel(); models = []; results = []; pending = nil; confirmPaid = false
    }

    private func loadModels() {
        guard let instance = store.source(sourceID) else { return }
        let generation = store.generation, demo = store.settings.demo
        operation.start(store: store) {
            let values: [String]
            if demo { values = DemoEngine.generateDashboardSnapshot().models }
            else { values = try await NewApiClient(instance: instance).models() }
            try ViewOperation.check(store, generation: generation)
            models = Array(Set(values)).sorted()
            if model.isEmpty { model = models.first ?? "" }
        }
    }

    private func prepareProbe() {
        guard let instance = store.source(sourceID) else { return }
        let clean = model.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...150).contains(clean.count), !clean.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            operation.error = "模型名稱需要 1–150 個字元，不可包含控制字元。"; return
        }
        guard store.settings.demo || instance.hasKey else { operation.error = "請先喺實例設定加入 API Key。"; return }
        pending = ProbeRequest(instance: instance, model: clean, count: min(max(repetitions, 1), 10), generation: store.generation, demo: store.settings.demo)
        if store.settings.demo { runPending() } else { confirmPaid = true }
    }

    private func runPending() {
        guard let request = pending, request.generation == store.generation,
              store.canOperate, store.settings.demo == request.demo else { pending = nil; return }
        pending = nil; results = []
        operation.start(store: store) {
            for index in 0..<request.count {
                try ViewOperation.check(store, generation: request.generation)
                let result: StreamProbeResult
                if request.demo {
                    result = StreamProbeResult(sourceId: request.instance.id, sourceName: request.instance.name,
                        model: request.model, ok: true, firstTokenMs: Int64(180 + index * 13), totalMs: Int64(980 + index * 23),
                        completionTokens: 32, tokensPerSecond: 38.5, failure: nil, timestamp: AppStore.now)
                } else {
                    result = await StreamingProbe.run(instance: request.instance, model: request.model)
                }
                try ViewOperation.check(store, generation: request.generation)
                results.append(result)
                if !result.ok { break }
            }
        }
    }
}
