import Foundation
import Combine
import UserNotifications

/// Only this metadata type is written to disk. Instance contains secrets and must never be encoded here.
private struct StoredInstance: Codable {
    var id: String
    var name: String
    var baseUrl: String
    var quotaPerUnit: Double?
    var authStyle: AuthStyle?
    var authStyleKey: AuthStyle?
    var createdAt: Int64

    init(_ instance: Instance) {
        id = instance.id; name = instance.name; baseUrl = instance.baseUrl
        quotaPerUnit = instance.quotaPerUnit; authStyle = instance.authStyle
        authStyleKey = instance.authStyleKey; createdAt = instance.createdAt
    }

    func hydrate(_ pair: KeychainStore.SecretPair?) -> Instance {
        Instance(id: id, name: name, baseUrl: baseUrl, accessToken: pair?.accessToken ?? "",
                 apiKey: pair?.apiKey ?? "", quotaPerUnit: quotaPerUnit, authStyle: authStyle,
                 authStyleKey: authStyleKey, createdAt: createdAt)
    }
}

private struct StoreDocument: Codable {
    var version = 1
    var instances: [StoredInstance] = []
    var settings = AppSettings(intervalSec: 45)
    var appearance = Appearance()
    var preferences = MonitorPreferences()
    var budgets: [String: Double] = [:]
    var alerts = AlertSettings()
    var rules: [String: InstanceAlertRule] = [:]
    var history: [AlertRecord] = []
    var alertStates: [String: AlertEngine.State] = [:]
    var snapshots: [String: MonitorSnapshot]? = nil
}

struct StoreFailure: LocalizedError {
    var message: String
    var errorDescription: String? { message }
}

struct ImportPreview: Identifiable {
    let id = UUID()
    let parsed: BackupManager.ParsedBackup
    let dto: BackupFileDto
    let newCount: Int
    let matchedCount: Int
}

@MainActor
final class AppStore: ObservableObject {
    @Published private(set) var instances: [Instance] = []
    @Published private(set) var settings = AppSettings(intervalSec: 45)
    @Published private(set) var appearance = Appearance()
    @Published private(set) var preferences = MonitorPreferences()
    @Published private(set) var budgets: [String: Double] = [:]
    @Published private(set) var alerts = AlertSettings()
    @Published private(set) var history: [AlertRecord] = []
    @Published private(set) var snapshots: [String: MonitorSnapshot] = [:]
    @Published private(set) var failures: [String: String] = [:]
    @Published private(set) var generation = UUID()
    @Published private(set) var isForeground = false
    @Published private(set) var isProtectedDataAvailable = true
    @Published private(set) var loadFailure: String?
    @Published private(set) var refreshing = false
    @Published var message: String?
    @Published var selectedTab = 0
    @Published var homeVisible = false

    private var document = StoreDocument()
    private let fileURL: URL
    private let keychain: KeychainStore.Client
    private var refreshTask: Task<Void, Never>?
    private var refreshID = UUID()
    private var cancellers: [UUID: () -> Void] = [:]
    private var hasStoredDocument = false

    init(fileURL: URL? = nil, keychain: KeychainStore.Client = .live) {
        self.fileURL = fileURL ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("NewAPIMonitor", isDirectory: true).appendingPathComponent("metadata-v1.json")
        self.keychain = keychain
        reload()
    }

    static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    var canOperate: Bool { isForeground && isProtectedDataAvailable && loadFailure == nil }
    var canRefreshHome: Bool { canOperate && selectedTab == 0 && homeVisible }
    var sources: [Instance] {
        if settings.demo { return [Self.demoInstance] }
        return instances.sorted {
            let a = preferences.pinned.contains($0.id), b = preferences.pinned.contains($1.id)
            if a != b { return a }
            let ai = preferences.order.firstIndex(of: $0.id) ?? Int.max
            let bi = preferences.order.firstIndex(of: $1.id) ?? Int.max
            if ai != bi { return ai < bi }
            return $0.createdAt < $1.createdAt
        }
    }
    static let demoInstance = Instance(id: DemoEngine.demoInstanceId, name: "演示站點（離線模擬）",
        baseUrl: "https://demo.invalid", accessToken: "", apiKey: "", quotaPerUnit: DemoEngine.qpu,
        authStyle: nil, authStyleKey: nil, createdAt: 0)

    func money(_ value: Double?) -> String { Fmt.usd(value, hide: preferences.hideMoney) }
    func source(_ id: String) -> Instance? { sources.first { $0.id == id } }
    func registerCancellation(_ cancel: @escaping () -> Void) -> UUID {
        let id = UUID(); cancellers[id] = cancel; return id
    }
    func unregisterCancellation(_ id: UUID?) { if let id { cancellers.removeValue(forKey: id) } }

    func invalidate(clearSnapshots: Bool = false) {
        generation = UUID()
        refreshTask?.cancel(); refreshTask = nil; refreshID = UUID(); refreshing = false
        for cancel in Array(cancellers.values) { cancel() }
        if clearSnapshots {
            snapshots = [:]
        }
        failures = [:]
    }

    func setLifecycle(active: Bool, protected: Bool) {
        guard active != isForeground || protected != isProtectedDataAvailable else { return }
        let becameProtectedAndActive = (!isProtectedDataAvailable && protected) || (loadFailure != nil && active && protected)
        isForeground = active; isProtectedDataAvailable = protected
        invalidate(clearSnapshots: false)
        if becameProtectedAndActive {
            reload()
        }
    }

    func stopHomeRefresh() {
        refreshTask?.cancel(); refreshTask = nil; refreshID = UUID(); refreshing = false
    }

    func reload() {
        invalidate(clearSnapshots: false)
        do {
            let candidate: StoreDocument
            if FileManager.default.fileExists(atPath: fileURL.path) {
                let data = try Data(contentsOf: fileURL)
                guard data.count <= 8 * 1024 * 1024 else { throw StoreFailure(message: "本機資料過大") }
                candidate = try JSONDecoder().decode(StoreDocument.self, from: data)
                hasStoredDocument = true
            } else if let mirrorData = KeychainStore.loadMetadataMirror(using: keychain),
                      mirrorData.count <= 8 * 1024 * 1024,
                      let recovered = try? JSONDecoder().decode(StoreDocument.self, from: mirrorData) {
                candidate = recovered
                try? persist(recovered)
            } else {
                guard !hasStoredDocument else { throw StoreFailure(message: "本機資料檔已遺失") }
                candidate = StoreDocument()
            }
            guard candidate.version == 1 else { throw StoreFailure(message: "本機資料版本不受支援") }
            var hydrated: [Instance] = []
            for metadata in candidate.instances {
                let pair = try KeychainStore.loadCredentials(for: metadata.id, using: keychain)
                hydrated.append(try Self.validated(metadata.hydrate(pair)))
            }
            try validateDocument(candidate, instances: hydrated)
            document = candidate
            publish(candidate, instances: hydrated)
            loadFailure = nil
        } catch {
            // Do not publish an empty replacement or write anything after a failed read/hydration.
            loadFailure = "無法讀取本機資料／鑰匙圈。原資料保持不變，編輯及連線已停用。解鎖後可重試。\n\(Self.describe(error))"
        }
    }

    static func normalizedURL(_ input: String) throws -> String {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 2048,
              !text.contains(where: { $0.isWhitespace || $0.isNewline }),
              var parts = URLComponents(string: text), parts.scheme?.lowercased() == "https",
              let host = parts.host, !host.isEmpty, !host.contains("%"),
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.port == nil || (1...65535).contains(parts.port!),
              !text.contains("\\"), parts.url != nil else {
            throw StoreFailure(message: "請輸入有效 HTTPS 網址，不可包含帳密、查詢參數或 #fragment。")
        }
        parts.scheme = "https"
        parts.host = host.lowercased()
        if parts.port == 443 { parts.port = nil }
        // Never lowercase or decode the path: reverse proxies can use case-sensitive prefixes.
        while parts.percentEncodedPath.hasSuffix("/") { parts.percentEncodedPath.removeLast() }
        guard let result = parts.string else { throw StoreFailure(message: "網址無效") }
        return result
    }

    static func validated(_ input: Instance) throws -> Instance {
        var instance = input
        instance.name = instance.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...60).contains(instance.name.count), !instance.name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw StoreFailure(message: "名稱需要 1–60 個字元，且不可包含控制字元。")
        }
        instance.baseUrl = try normalizedURL(instance.baseUrl)
        for secret in [instance.accessToken, instance.apiKey] {
            guard secret.count <= 300, !secret.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
                throw StoreFailure(message: "憑證最多 300 個字元，不可包含換行或控制字元。")
            }
        }
        instance.accessToken = instance.accessToken.trimmingCharacters(in: .whitespacesAndNewlines)
        instance.apiKey = instance.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if let qpu = instance.quotaPerUnit, !qpu.isFinite || qpu <= 0 {
            throw StoreFailure(message: "Quota per unit 必須大於 0。")
        }
        return instance
    }

    static func identity(_ instance: Instance) throws -> String {
        try normalizedURL(instance.baseUrl) + "\n" + instance.name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func positiveNumber(_ text: String, label: String, allowZero: Bool = false) throws -> Double? {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return nil }
        guard let value = Double(clean), value.isFinite, allowZero ? value >= 0 : value > 0 else {
            throw StoreFailure(message: "\(label)需要有效\(allowZero ? "非負" : "正")數字。")
        }
        return value
    }

    private func validateDocument(_ doc: StoreDocument, instances values: [Instance]) throws {
        guard values.count <= 100, Set(values.map(\.id)).count == values.count,
              values.allSatisfy({ !$0.id.isEmpty }),
              (30...60).contains(doc.settings.intervalSec),
              ["dark", "oled", "light", "system"].contains(doc.appearance.mode),
              ["purple", "blue", "green", "orange"].contains(doc.appearance.accent),
              doc.budgets.values.allSatisfy({ $0.isFinite && $0 > 0 }) else {
            throw StoreFailure(message: "本機設定格式無效，未作任何變更。")
        }
        let identities = try values.map(Self.identity)
        guard Set(identities).count == identities.count else { throw StoreFailure(message: "相同名稱及網址嘅實例已存在。") }
        try Self.validateAlerts(doc.alerts)
        for rule in doc.rules.values { try rule.validate() }
    }

    static func validateAlerts(_ value: AlertSettings) throws {
        guard (1...1440).contains(value.cooldownMinutes), value.spikeMultiplier.isFinite, value.spikeMultiplier > 0 else {
            throw StoreFailure(message: "警報冷卻時間或倍率無效。")
        }
        if let v = value.balanceBelowUsd, !v.isFinite || v < 0 { throw StoreFailure(message: "餘額警報值無效。") }
        for v in [value.dailySpendAboveUsd, value.monthlyBudgetUsd].compactMap({ $0 }) {
            if !v.isFinite || v <= 0 { throw StoreFailure(message: "消費警報及預算需要正數。") }
        }
    }

    private func persist(_ doc: StoreDocument) throws {
        let directory = fileURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var directoryURL = directory
        var flags = URLResourceValues(); flags.isExcludedFromBackup = true
        try directoryURL.setResourceValues(flags)
        let data = try JSONEncoder().encode(doc)
        try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        KeychainStore.saveMetadataMirror(data, using: keychain)
        hasStoredDocument = true
    }

    private func publish(_ doc: StoreDocument, instances values: [Instance]) {
        instances = values; settings = doc.settings; appearance = doc.appearance
        preferences = doc.preferences; budgets = doc.budgets; alerts = doc.alerts
        history = Array(doc.history.prefix(200))
        if let savedSnapshots = doc.snapshots {
            let validIDs = Set(values.map(\.id))
            snapshots = savedSnapshots.filter { validIDs.contains($0.key) }
        }
    }

    /// Keychain + file cannot share a transaction. Snapshot affected secrets, roll back on any failure,
    /// and fail closed if rollback itself fails. Never overwrite when initial hydration failed.
    private func commit(_ next: StoreDocument, instances values: [Instance], invalidateRequests: Bool = true) throws {
        guard loadFailure == nil else { throw StoreFailure(message: "資料尚未成功載入，請先重試。") }
        guard isProtectedDataAvailable else { throw StoreFailure(message: "裝置已鎖定。") }
        try validateDocument(next, instances: values)
        let oldByID = Dictionary(uniqueKeysWithValues: instances.map { ($0.id, $0) })
        let newByID = Dictionary(uniqueKeysWithValues: values.map { ($0.id, $0) })
        let changed = Set(oldByID.keys).union(newByID.keys).filter { id in
            let a = oldByID[id], b = newByID[id]
            return a?.accessToken != b?.accessToken || a?.apiKey != b?.apiKey
        }.sorted()
        var originals: [String: KeychainStore.SecretPair] = [:]
        for id in changed { if let pair = try KeychainStore.loadCredentials(for: id, using: keychain) { originals[id] = pair } }
        var touched: [String] = []
        do {
            for id in changed {
                touched.append(id)
                if let value = newByID[id] {
                    try KeychainStore.saveCredentials(for: id, accessToken: value.accessToken, apiKey: value.apiKey, using: keychain)
                } else { try KeychainStore.deleteCredentials(for: id, using: keychain) }
            }
            try persist(next)
        } catch {
            var rollbackFailed = false
            for id in touched.reversed() {
                do {
                    if let pair = originals[id] {
                        try KeychainStore.saveCredentials(for: id, accessToken: pair.accessToken, apiKey: pair.apiKey, using: keychain)
                    } else { try KeychainStore.deleteCredentials(for: id, using: keychain) }
                } catch { rollbackFailed = true }
            }
            if rollbackFailed {
                invalidate()
                loadFailure = "儲存及憑證復原失敗，已停用編輯／連線。請解鎖後重新載入，核對憑證。"
            }
            throw StoreFailure(message: "未能完成儲存。\(rollbackFailed ? "請核對鑰匙圈資料。" : "已保留原資料。") \(Self.describe(error))")
        }
        document = next
        if invalidateRequests { invalidate() }
        publish(next, instances: values)
    }

    func saveInstance(_ input: Instance, budget: Double?, pinned: Bool, included: Bool) throws {
        var value = try Self.validated(input)
        if value.id.isEmpty { value.id = UUID().uuidString; value.createdAt = Self.now }
        var values = instances
        if let index = values.firstIndex(where: { $0.id == value.id }) { values[index] = value }
        else { values.append(value) }
        var next = document; next.instances = values.map(StoredInstance.init)
        next.budgets[value.id] = budget
        if pinned { next.preferences.pinned.insert(value.id) } else { next.preferences.pinned.remove(value.id) }
        if included { next.preferences.excluded.remove(value.id) } else { next.preferences.excluded.insert(value.id) }
        if next.settings.activeId == nil { next.settings.activeId = value.id }
        try commit(next, instances: values)
    }

    func deleteInstance(_ id: String) throws {
        let values = instances.filter { $0.id != id }
        var next = document; next.instances = values.map(StoredInstance.init)
        next.budgets.removeValue(forKey: id); next.rules.removeValue(forKey: id)
        next.alertStates.removeValue(forKey: id)
        next.snapshots?.removeValue(forKey: id)
        next.history.removeAll { $0.sourceId == id }
        next.preferences.pinned.remove(id); next.preferences.excluded.remove(id)
        next.preferences.order.removeAll { $0 == id }
        if next.settings.activeId == id { next.settings.activeId = values.first?.id }
        snapshots.removeValue(forKey: id)
        try commit(next, instances: values)
    }

    func updateSettings(demo: Bool? = nil, interval: Int? = nil, hideMoney: Bool? = nil,
                        mode: String? = nil, accent: String? = nil) throws {
        var next = document
        if let demo { next.settings.demo = demo }
        if let interval { next.settings.intervalSec = interval }
        if let hideMoney { next.preferences.hideMoney = hideMoney }
        if let mode { next.appearance.mode = mode }
        if let accent { next.appearance.accent = accent }
        try commit(next, instances: instances)
    }

    func updateAlerts(_ value: AlertSettings) throws {
        var next = document; next.alerts = value
        try commit(next, instances: instances)
    }

    func clearHistory() throws {
        var next = document; next.history = []
        try commit(next, instances: instances, invalidateRequests: false)
    }

    func prepareImport(_ plain: String) throws -> ImportPreview {
        guard plain.utf8.count <= 8 * 1024 * 1024 else { throw StoreFailure(message: "備份超過 8 MB。") }
        let clean = plain.hasPrefix("\u{FEFF}") ? String(plain.dropFirst()) : plain
        let dto = try JSONDecoder().decode(BackupFileDto.self, from: Data(clean.utf8))
        guard dto.app == "newapi-monitor", dto.version == BackupManager.version,
              dto.instances.count <= 100, let parsed = BackupManager.parse(clean),
              parsed.instances.count == dto.instances.count else {
            throw StoreFailure(message: "備份版本／內容無效，唔會略過損毀嘅實例。")
        }
        var identities = Set<String>()
        for raw in dto.instances {
            let input = Instance(id: "preview", name: raw.name, baseUrl: raw.baseUrl,
                accessToken: raw.accessToken, apiKey: raw.apiKey, quotaPerUnit: raw.quotaPerUnit,
                authStyle: nil, authStyleKey: nil, createdAt: 0)
            let value = try Self.validated(input)
            guard identities.insert(try Self.identity(value)).inserted,
                  raw.authStyle == nil || ["raw", "bearer"].contains(raw.authStyle!),
                  raw.authStyleKey == nil || ["raw", "bearer"].contains(raw.authStyleKey!) else {
                throw StoreFailure(message: "備份包含重複實例或無效認證方式。")
            }
            if let budget = raw.monthlyBudgetUsd, !budget.isFinite || budget <= 0 { throw StoreFailure(message: "備份預算無效。") }
            if let order = raw.homeOrder, !(0...10_000).contains(order) { throw StoreFailure(message: "備份排序無效。") }
            if let rule = raw.alertRule { try rule.validate() }
        }
        if let appearance = dto.appearance {
            if let mode = appearance.mode, !["dark", "oled", "light", "system"].contains(mode) { throw StoreFailure(message: "主題無效。") }
            if let accent = appearance.accent, !["purple", "blue", "green", "orange"].contains(accent) { throw StoreFailure(message: "主題色無效。") }
            if let language = appearance.language, !["yue", "zh-TW", "zh-CN", "en"].contains(language) { throw StoreFailure(message: "語言設定無效。") }
        }
        if let alerts = parsed.alertSettings { try Self.validateAlerts(alerts) }
        for (url, value) in dto.budgets {
            _ = try Self.normalizedURL(url)
            guard value.isFinite, value > 0 else { throw StoreFailure(message: "備份預算無效。") }
        }
        guard dto.favoriteModels.count <= 100, dto.favoriteModels.allSatisfy({ (1...150).contains($0.count) }),
              dto.probeSuites.count <= 20, dto.probeSuites.allSatisfy({ (1...40).contains($0.name.count) && (1...3).contains($0.models.count) && $0.models.allSatisfy { (1...150).contains($0.count) } }) else {
            throw StoreFailure(message: "備份收藏／測速套件無效。")
        }
        let local = Set(try instances.map(Self.identity))
        let matched = identities.intersection(local).count
        guard instances.count + identities.count - matched <= 100 else { throw StoreFailure(message: "最多支援 100 個實例。") }
        return ImportPreview(parsed: parsed, dto: dto, newCount: identities.count - matched, matchedCount: matched)
    }

    func confirmImport(_ preview: ImportPreview) throws {
        var next = document
        var values = instances
        var ordering: [(String, Int)] = []
        for raw in preview.dto.instances {
            var incoming = try Self.validated(Instance(id: "", name: raw.name, baseUrl: raw.baseUrl,
                accessToken: raw.accessToken, apiKey: raw.apiKey, quotaPerUnit: raw.quotaPerUnit,
                authStyle: raw.authStyle.flatMap(AuthStyle.init(rawValue:)),
                authStyleKey: raw.authStyleKey.flatMap(AuthStyle.init(rawValue:)), createdAt: Self.now))
            let identity = try Self.identity(incoming)
            let index = try values.firstIndex { try Self.identity($0) == identity }
            if let index {
                let old = values[index]; incoming.id = old.id; incoming.createdAt = old.createdAt
                if incoming.accessToken.isEmpty { incoming.accessToken = old.accessToken; incoming.authStyle = old.authStyle }
                if incoming.apiKey.isEmpty { incoming.apiKey = old.apiKey; incoming.authStyleKey = old.authStyleKey }
                values[index] = incoming
            } else { incoming.id = UUID().uuidString; values.append(incoming) }
            if let budget = raw.monthlyBudgetUsd { next.budgets[incoming.id] = budget }
            else if let legacy = preview.dto.budgets.first(where: { (try? Self.normalizedURL($0.key)) == incoming.baseUrl }) {
                next.budgets[incoming.id] = legacy.value
            }
            if let pinned = raw.pinned {
                if pinned { next.preferences.pinned.insert(incoming.id) } else { next.preferences.pinned.remove(incoming.id) }
            }
            if let included = raw.balanceIncluded {
                if included { next.preferences.excluded.remove(incoming.id) } else { next.preferences.excluded.insert(incoming.id) }
            }
            if let order = raw.homeOrder { ordering.append((incoming.id, order)) }
            if let rule = raw.alertRule { next.rules[incoming.id] = rule }
        }
        if !ordering.isEmpty {
            let imported = ordering.sorted { $0.1 < $1.1 }.map(\.0)
            next.preferences.order = imported + next.preferences.order.filter { !imported.contains($0) }
        }
        if let appearance = preview.parsed.appearance {
            if let mode = appearance.mode { next.appearance.mode = mode }
            if let accent = appearance.accent { next.appearance.accent = accent }
            if let language = appearance.language { next.appearance.language = language }
        }
        if let value = preview.parsed.alertSettings { next.alerts = value }
        if let value = preview.dto.compactHome { next.preferences.compact = value }
        if let value = preview.dto.hideMoney { next.preferences.hideMoney = value }
        next.preferences.favorites.formUnion(preview.dto.favoriteModels)
        for suite in preview.dto.probeSuites {
            next.preferences.suites.removeAll { $0.name == suite.name }; next.preferences.suites.append(suite)
        }
        next.instances = values.map(StoredInstance.init)
        if next.settings.activeId == nil { next.settings.activeId = values.first?.id }
        try commit(next, instances: values)
    }

    func exportBackup(includeCredentials: Bool) throws -> String {
        guard loadFailure == nil, isProtectedDataAvailable else { throw StoreFailure(message: "資料尚未安全載入。") }
        return try BackupManager.toJson(instances: instances, appearance: appearance, alertSettings: alerts,
            includeCredentials: includeCredentials, budgetsById: budgets, homeOptions: preferences, alertRules: document.rules)
    }

    func refreshHome() async {
        guard canRefreshHome else { return }
        stopHomeRefresh()
        let token = generation, request = UUID()
        refreshID = request; refreshing = true
        let items = sources, demo = settings.demo
        let task = Task { [weak self] in
            guard let self else { return }
            if demo {
                let value = DemoEngine.generateDashboardSnapshot()
                guard self.canRefreshHome, self.generation == token, self.refreshID == request, !Task.isCancelled else { return }
                self.snapshots[value.instanceId] = MonitorSnapshot(id: value.instanceId, balanceUsd: value.balanceUsd,
                    todayUsd: value.todayUsd, monthUsd: value.monthUsd, balanceAt: value.fetchedAtMs,
                    spendAt: value.fetchedAtMs, attemptedAt: value.fetchedAtMs, day: Fmt.dayString(), month: Fmt.monthString(),
                    balanceFailure: nil, spendFailure: nil, sourceStamp: "offline-demo")
            } else {
                // Bounded batches ensure at most three instances are queried concurrently.
                for start in stride(from: 0, to: items.count, by: 3) {
                    if Task.isCancelled { break }
                    let batch = Array(items[start..<min(start + 3, items.count)])
                    await withTaskGroup(of: (String, MonitorSnapshot?, String?).self) { group in
                        for instance in batch {
                            group.addTask {
                                do { return (instance.id, try await NewApiClient(instance: instance).monitorSnapshot(), nil) }
                                catch { return (instance.id, nil, AppStore.describe(error)) }
                            }
                        }
                        for await (id, snapshot, failure) in group {
                            guard !Task.isCancelled, self.generation == token, self.refreshID == request, self.canRefreshHome else {
                                group.cancelAll(); continue
                            }
                            if let snapshot {
                                self.snapshots[id] = snapshot
                                self.document.snapshots = self.snapshots
                                try? self.persist(self.document)
                            }
                            self.failures[id] = failure
                            if let snapshot, let instance = batch.first(where: { $0.id == id }) {
                                self.evaluateAlerts(instance: instance, snapshot: snapshot)
                            }
                        }
                    }
                }
            }
            if self.generation == token, self.refreshID == request { self.refreshing = false }
        }
        refreshTask = task
        await withTaskCancellationHandler(operation: { await task.value }, onCancel: { task.cancel() })
    }

    private func evaluateAlerts(instance: Instance, snapshot: MonitorSnapshot) {
        guard canOperate, !settings.demo else { return }
        var fresh = snapshot
        if !snapshot.balanceFresh(nowMs: Self.now) { fresh.balanceUsd = nil }
        if !snapshot.spendFresh(nowMs: Self.now) { fresh.todayUsd = nil; fresh.monthUsd = nil }
        let output = AlertEngine.evaluate(instance: instance, snapshot: fresh, monthlyBudgetUsd: budgets[instance.id],
            globalSettings: alerts, instanceRule: document.rules[instance.id],
            previousState: document.alertStates[instance.id] ?? AlertEngine.State())
        guard output.updatedState != document.alertStates[instance.id] || !output.records.isEmpty else { return }
        var next = document
        next.alertStates[instance.id] = output.updatedState
        next.history = Array((output.records.reversed() + next.history).prefix(200))
        do {
            try commit(next, instances: instances, invalidateRequests: false)
            let token = generation
            Task { [weak self] in
                let center = UNUserNotificationCenter.current()
                let status = await center.notificationSettings()
                guard let self, self.canOperate, self.generation == token, !self.settings.demo,
                      status.authorizationStatus == .authorized || status.authorizationStatus == .provisional else { return }
                for record in output.records {
                    guard self.canOperate, self.generation == token else { break }
                    let content = UNMutableNotificationContent()
                    content.title = record.title
                    // Lock-screen notifications deliberately contain neither amounts nor server names.
                    content.body = "監察到警報，請開啟 App 查看。"; content.sound = .default
                    do { try await center.add(UNNotificationRequest(identifier: record.id, content: content, trigger: nil)) }
                    catch { self.message = "本機通知未能送出：\(Self.describe(error))" }
                }
            }
        } catch { message = Self.describe(error) }
    }

    func requestNotificationPermission() async {
        do {
            let allowed = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            message = allowed ? "已允許通知。只會喺 App 前景首頁刷新時檢查警報，並非背景監察。" : "通知未獲允許，可到系統設定開啟；App 內警報記錄仍可使用。"
        } catch { message = Self.describe(error) }
    }

    nonisolated static func describe(_ error: Error) -> String {
        if let error = error as? AppError { return error.message }
        if let error = error as? LocalizedError, let text = error.errorDescription { return text }
        return error.localizedDescription
    }
}
