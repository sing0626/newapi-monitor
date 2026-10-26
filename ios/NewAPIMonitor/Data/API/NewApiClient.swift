import Foundation

/// Read-only management client. Each actor owns one source's credentials and session.
actor NewApiClient {
    private enum Auth { case none, access, key }
    private let instance: Instance
    private let base: Result<URL, AppError>
    private let session: URLSession
    private var accessStyle: AuthStyle
    private var userID: Int64?

    init(instance: Instance) {
        self.instance = instance
        self.base = Self.validateBase(instance.baseUrl)
        self.session = NetworkPolicy.session()
        self.accessStyle = instance.authStyle ?? .bearer
    }

    // Test seam: production always uses the ephemeral, no-storage configuration.
    init(instance: Instance, configuration: URLSessionConfiguration) {
        self.instance = instance
        self.base = Self.validateBase(instance.baseUrl)
        self.session = NetworkPolicy.session(configuration: configuration)
        self.accessStyle = instance.authStyle ?? .bearer
    }

    deinit { session.invalidateAndCancel() }

    private static func validateBase(_ raw: String) -> Result<URL, AppError> {
        do { return .success(try NetworkPolicy.baseURL(raw)) }
        catch { return .failure(AppError(kind: .network, message: "Invalid HTTP(S) Base URL; HTTPS is recommended")) }
    }

    func monitorSnapshot() async throws -> MonitorSnapshot {
        _ = try base.get()
        try Task.checkCancellation()
        let now = Date()
        let at = Int64(now.timeIntervalSince1970 * 1000)
        let calendar = Calendar.current
        let today = Int64(calendar.startOfDay(for: now).timeIntervalSince1970)
        guard let monthDate = calendar.date(from: calendar.dateComponents([.year, .month], from: now)) else {
            throw APIParsing.failure("month boundary")
        }
        let month = Int64(monthDate.timeIntervalSince1970)
        let end = Int64(now.timeIntervalSince1970)
        let status = try await optionalStatus()
        let qpu = Quota.resolveQpu(instanceOverride: instance.quotaPerUnit, statusQpu: status?.quotaPerUnit)
        var balance: Double?
        var balanceFailure: String?
        do {
            if instance.hasAccess {
                let user = try await fetchUser()
                balance = try finite(APIParsing.money(user, "quota") / qpu)
            } else {
                let subscription = try APIParsing.object(await get("/v1/dashboard/billing/subscription", auth: .key, envelope: false))
                let usage = try APIParsing.object(await get("/v1/dashboard/billing/usage", auth: .key, envelope: false))
                let remaining = try APIParsing.money(subscription, "hard_limit_usd") - APIParsing.money(usage, "total_usage") / 100
                guard let usd = DisplayUnits.fromStatus(status).billingUsd(remaining) else {
                    throw AppError(kind: .parse, message: "Billing unit is unknown or TOKENS; cannot convert to USD")
                }
                balance = usd
            }
        } catch {
            try rethrowCancellation(error)
            balanceFailure = message(error)
        }
        // Independent requests and error captures: a failed balance/month must not
        // erase a successful daily aggregate (or vice versa).
        async let dailyResult = aggregateResult(start: today, end: end, qpu: qpu)
        async let monthResult = aggregateResult(start: month, end: end, qpu: qpu)
        let (daily, monthly) = try await (dailyResult, monthResult)
        let failures = [daily.failure.map { "Today: \($0)" }, monthly.failure.map { "Month: \($0)" }].compactMap { $0 }
        try Task.checkCancellation()
        return MonitorSnapshot(id: instance.id, balanceUsd: balance, todayUsd: daily.value, monthUsd: monthly.value,
                               balanceAt: balance == nil ? 0 : at,
                               spendAt: daily.value == nil && monthly.value == nil ? 0 : at,
                               attemptedAt: at, day: Fmt.dayString(from: now),
                               month: String(Fmt.dayString(from: now).prefix(7)),
                               balanceFailure: balanceFailure,
                               spendFailure: failures.isEmpty ? nil : failures.joined(separator: "; "),
                               sourceStamp: InstanceSanitizer.sourceStamp(for: instance))
    }

    func models() async throws -> [String] {
        _ = try base.get()
        let names: [String]
        if instance.hasKey {
            let root = try APIParsing.object(await get("/v1/models", auth: .key, envelope: false))
            guard let rows = root["data"] as? [[String: Any]] else { throw APIParsing.failure("model list") }
            names = try rows.map { row in
                guard let id = row["id"] as? String else { throw APIParsing.failure("model id") }
                return id
            }
        } else {
            try await prepareUser()
            guard let rows = try await get("/api/user/models", auth: .access) as? [String] else {
                throw APIParsing.failure("model list")
            }
            names = rows
        }
        guard names.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else {
            throw APIParsing.failure("model id")
        }
        return Array(Set(names)).sorted()
    }

    func logs(page: Int, start: Int64?, end: Int64?) async throws -> LogPage {
        _ = try base.get()
        try validateWindow(start: start, end: end)
        guard page > 0, page <= Int.max / 100 else { throw APIParsing.failure("page") }
        try await prepareUser()
        return try await fetchLogs(page: page, start: start, end: end, consumptionOnly: false)
    }

    func report(range: ReportRange, compareMonth: Bool) async throws -> PeriodReport {
        _ = try base.get()
        try range.validate()
        guard instance.hasAccess else { throw AppError(kind: .auth, message: "Access Token required") }
        let status = try await optionalStatus()
        let qpu = Quota.resolveQpu(instanceOverride: instance.quotaPerUnit, statusQpu: status?.quotaPerUnit)
        try await prepareUser()
        let comparison = compareMonth ? range.previousMonthPeriod() : range.previousPeriod()
        try comparison.validate()
        async let current = slice(range: range, qpu: qpu)
        async let previous = slice(range: comparison, qpu: qpu)
        return try await PeriodReport(range: range, current: current, previous: previous, comparisonRange: comparison)
    }

    func tokens() async throws -> [TokenInfo] {
        _ = try base.get()
        try await prepareUser()
        return try await managementList(path: "/api/token/", parse: APIParsing.token)
    }

    func channels() async throws -> [ChannelInfo] {
        _ = try base.get()
        try await prepareUser()
        return try await managementList(path: "/api/channel/", parse: APIParsing.channel)
    }

    private func managementList<T: Identifiable & Sendable>(path: String, parse: ([String: Any]) throws -> T) async throws -> [T] where T.ID: Hashable {
        var result: [T] = []
        var seen = Set<T.ID>()
        for page in 1...100 {
            let value = try await get(path, query: [item("p", page), item("size", 100)], auth: .access)
            let rows = try APIParsing.items(value).map(parse)
            let (_, more) = try APIParsing.pagination(value, page: page, count: rows.count, size: 100)
            for row in rows {
                guard seen.insert(row.id).inserted else { throw APIParsing.failure("repeated management page") }
                result.append(row)
            }
            if !more { return result }
        }
        throw AppError(kind: .api, message: "Management list exceeds 10,000 rows; refusing an incomplete list")
    }

    private struct AmountResult: Sendable {
        let value: Double?
        let failure: String?
    }

    private func aggregateResult(start: Int64, end: Int64, qpu: Double) async throws -> AmountResult {
        do {
            guard instance.hasAccess else { throw AppError(kind: .auth, message: "Access Token required") }
            guard start <= end else { throw APIParsing.failure("future range has no available data") }
            let value = try await get("/api/data/self", query: [item("start_timestamp", start), item("end_timestamp", end), URLQueryItem(name: "default_time", value: "day")], auth: .access)
            return AmountResult(value: try APIParsing.aggregate(value, start: start, end: end, qpu: qpu), failure: nil)
        } catch {
            try rethrowCancellation(error)
            return AmountResult(value: nil, failure: message(error))
        }
    }

    private func slice(range: ReportRange, qpu: Double) async throws -> ReportSlice {
        let at = Int64(Date().timeIntervalSince1970 * 1000)
        let start = range.startTimestampSec
        let end = min(range.endTimestampSec, at / 1000)
        async let aggregate = aggregateResult(start: start, end: end, qpu: qpu)
        var rows: [LogEntry] = []
        var loadedAnyPage = false
        var partial = false
        var failures: [String] = []
        var seen = Set<String>()
        do {
            guard start <= end else { throw APIParsing.failure("future range has no available data") }
            // At most ten pages of 100 consumption logs per slice. Totals always
            // come from /api/data/self, never from this bounded sample.
            for page in 1...10 {
                let result = try await fetchLogs(page: page, start: start, end: end, consumptionOnly: true)
                loadedAnyPage = true
                for row in result.rows {
                    guard seen.insert(row.id).inserted else { throw APIParsing.failure("repeated log page") }
                    guard (row.type == nil || row.type == 2), row.createdAt >= start, row.createdAt <= end else {
                        partial = true
                        continue
                    }
                    rows.append(row)
                }
                if !result.hasMore { break }
                if page == 10 { partial = true; failures.append("Log detail limited to 1,000 rows") }
            }
            if partial && failures.isEmpty { failures.append("Server returned rows outside the requested consumption window") }
        } catch {
            try rethrowCancellation(error)
            partial = true
            failures.append("Logs: \(message(error))")
        }
        let total = try await aggregate
        if let error = total.failure { failures.append("Aggregate: \(error)"); partial = true }
        try Task.checkCancellation()
        return ReportSlice(totalUsd: total.value, rows: loadedAnyPage ? rows.sorted { $0.createdAt > $1.createdAt } : nil,
                           partial: partial, failure: failures.isEmpty ? nil : failures.joined(separator: "; "), atMs: at, qpu: qpu)
    }

    private func fetchLogs(page: Int, start: Int64?, end: Int64?, consumptionOnly: Bool) async throws -> LogPage {
        var query = [item("p", page), item("page_size", 100)]
        if let start { query.append(item("start_timestamp", start)) }
        if let end { query.append(item("end_timestamp", end)) }
        if consumptionOnly { query.append(item("type", 2)) }
        return try APIParsing.logPage(await get("/api/log/self", query: query, auth: .access), page: page)
    }

    private func fetchUser() async throws -> [String: Any] {
        let user = try APIParsing.object(await get("/api/user/self", auth: .access))
        let id = try APIParsing.requiredInt(user, "id")
        guard id > 0 else { throw APIParsing.failure("user id") }
        userID = id
        return user
    }

    private func prepareUser() async throws {
        guard instance.hasAccess else { throw AppError(kind: .auth, message: "Access Token required") }
        if userID != nil { return }
        do { _ = try await fetchUser() }
        catch {
            // Older installations may not expose self; still attempt the actual
            // endpoint without inventing a user ID. Cancellation is never swallowed.
            try rethrowCancellation(error)
        }
    }

    private func optionalStatus() async throws -> StatusInfo? {
        do { return try APIParsing.status(await get("/api/status", auth: .none)) }
        catch { try rethrowCancellation(error); return nil }
    }

    private func get(_ path: String, query: [URLQueryItem] = [], auth: Auth, envelope: Bool = true) async throws -> Any {
        let url = try NetworkPolicy.endpoint(base: base.get(), path: path, query: query)
        let credential: String?
        switch auth {
        case .none: credential = nil
        case .access: credential = try NetworkPolicy.credential(instance.accessToken)
        case .key: credential = try NetworkPolicy.credential(instance.apiKey)
        }
        let firstStyle = auth == .key ? (instance.authStyleKey ?? .bearer) : accessStyle
        // Only read-only access-token GETs negotiate Authorization format.
        let styles = auth == .access ? [firstStyle, firstStyle == .bearer ? .raw : .bearer] : [firstStyle]
        for (attempt, style) in styles.enumerated() {
            try Task.checkCancellation()
            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
            request.httpMethod = "GET"
            request.httpShouldHandleCookies = false
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            if let credential { request.setValue(style == .raw ? credential : "Bearer \(credential)", forHTTPHeaderField: "Authorization") }
            if auth == .access, let userID { request.setValue(String(userID), forHTTPHeaderField: "New-Api-User") }
            do {
                let (bytes, response) = try await session.bytes(for: request)
                defer { bytes.task.cancel() }
                guard let http = response as? HTTPURLResponse else { throw APIParsing.failure("HTTP response") }
                if http.statusCode == 401 && auth == .access && attempt == 0 { continue }
                guard (200...299).contains(http.statusCode) else {
                    throw AppError(kind: [401, 403].contains(http.statusCode) ? .auth : .http,
                                   message: "HTTP \(http.statusCode)", status: http.statusCode)
                }
                var data = Data()
                for try await byte in bytes {
                    try Task.checkCancellation()
                    guard data.count < 8 * 1024 * 1024 else { throw APIParsing.failure("response exceeds 8 MB") }
                    data.append(byte)
                }
                let value = try APIParsing.decode(data, envelope: envelope)
                if auth == .access { accessStyle = style }
                return value
            } catch {
                try rethrowCancellation(error)
                if let error = error as? AppError { throw error }
                throw AppError(kind: .network, message: "Connection failed or timed out")
            }
        }
        throw AppError(kind: .auth, message: "HTTP 401", status: 401)
    }

    private func item<T: CustomStringConvertible>(_ name: String, _ value: T) -> URLQueryItem {
        URLQueryItem(name: name, value: value.description)
    }

    private func finite(_ amount: Double) throws -> Double {
        guard amount.isFinite else { throw APIParsing.failure("amount") }
        return amount
    }

    private func validateWindow(start: Int64?, end: Int64?) throws {
        guard start.map({ $0 >= 0 }) ?? true, end.map({ $0 >= 0 }) ?? true else { throw APIParsing.failure("timestamp") }
        if let start, let end, start > end { throw APIParsing.failure("time range") }
    }

    private func rethrowCancellation(_ error: Error) throws {
        if error is CancellationError || (error as? URLError)?.code == .cancelled || Task.isCancelled { throw CancellationError() }
    }

    private func message(_ error: Error) -> String {
        (error as? AppError)?.message ?? "Connection failed or timed out"
    }
}
