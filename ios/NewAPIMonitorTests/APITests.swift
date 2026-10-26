import XCTest
@testable import NewAPIMonitor

final class APITests: XCTestCase {
    func testBaseURLValidationAndPrefixPreservation() throws {
        let base = try NetworkPolicy.baseURL(" https://example.com/gateway/ ")
        let url = try NetworkPolicy.endpoint(base: base, path: "/api/log/self", query: [URLQueryItem(name: "p", value: "2")])
        XCTAssertEqual(url.absoluteString, "https://example.com/gateway/api/log/self?p=2")
        for invalid in ["example.com", "file:///etc/passwd", "ftp://example.com", "https://", "https://u:p@example.com", "https://example.com?x=1", "https://example.com#x", "https://example.com/a/../b", "https://example.com/%2e%2e", "https://example.com\\@evil.test", "https://example.com:0", "https://example.com:65536", "https://exa mple.com"] {
            XCTAssertThrowsError(try NetworkPolicy.baseURL(invalid), invalid)
        }
        XCTAssertThrowsError(try NetworkPolicy.endpoint(base: base, path: "//evil.test/api"))
        XCTAssertThrowsError(try NetworkPolicy.credential("token\r\nInjected:yes"))
    }

    func testSessionDisablesPersistentStateAndRedirects() throws {
        let configuration = NetworkPolicy.configuration()
        XCTAssertNil(configuration.httpCookieStorage)
        XCTAssertNil(configuration.urlCache)
        XCTAssertNil(configuration.urlCredentialStorage)
        XCTAssertFalse(configuration.httpShouldSetCookies)
        XCTAssertEqual(configuration.timeoutIntervalForResource, 30)
        let session = NetworkPolicy.session()
        defer { session.invalidateAndCancel() }
        let original = URL(string: "https://example.com/api/user/self")!
        let task = session.dataTask(with: original)
        let delegate = RejectRedirects()
        for destination in ["https://example.com/other", "https://evil.test/", "http://example.com/"] {
            let response = HTTPURLResponse(url: original, statusCode: 302, httpVersion: nil, headerFields: ["Location": destination])!
            var called = false
            delegate.urlSession(session, task: task, willPerformHTTPRedirection: response,
                                newRequest: URLRequest(url: URL(string: destination)!)) { redirected in
                called = true
                XCTAssertNil(redirected)
            }
            XCTAssertTrue(called)
        }
    }

    func testFinancialParsingNeverInventsZero() throws {
        for value in [NSNull(), true, "NaN", "Infinity", "", "bad"] as [Any] {
            XCTAssertNil(APIParsing.number(value))
            XCTAssertThrowsError(try APIParsing.money(["quota": value], "quota"))
        }
        XCTAssertThrowsError(try APIParsing.money([:], "quota"))
        XCTAssertEqual(try APIParsing.money(["quota": "125.5"], "quota"), 125.5)
        XCTAssertEqual(APIParsing.integer("9223372036854775807"), Int64.max)
        XCTAssertNil(APIParsing.integer(1.5))
        XCTAssertNil(APIParsing.integer(true))
    }

    func testEnvelopesAndAggregateShapes() throws {
        let start = Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970)
        let row: [String: Any] = ["quota": 500_000, "created_at": start]
        for value in [[row], ["items": [row]], ["records": [row]], ["logs": [row]]] as [Any] {
            XCTAssertEqual(try APIParsing.aggregate(value, start: start, end: start + 100, qpu: 500_000), 1)
        }
        XCTAssertEqual(try APIParsing.aggregate([], start: start, end: start + 1, qpu: 500_000), 0)
        for value in [[:], ["items": NSNull()], [["created_at": start]], [["created_at": start, "quota": true]], [["created_at": start, "quota": -1]]] as [Any] {
            XCTAssertThrowsError(try APIParsing.aggregate(value, start: start, end: start + 1, qpu: 500_000))
        }
        XCTAssertThrowsError(try APIParsing.decode(Data("{\"success\":false,\"data\":[]}".utf8), envelope: true))
        XCTAssertThrowsError(try APIParsing.decode(Data("{\"success\":true}".utf8), envelope: true))
        XCTAssertThrowsError(try APIParsing.decode(Data("<html>error</html>".utf8), envelope: true))
    }

    func testLogPaginationAndMissingQuota() throws {
        let row = Self.logRow(id: 1)
        let page = try APIParsing.logPage(["items": [row], "total": 1], page: 1)
        XCTAssertEqual(page.rows.count, 1)
        XCTAssertEqual(page.total, 1)
        XCTAssertFalse(page.hasMore)
        XCTAssertTrue(try APIParsing.logPage(Array(repeating: row, count: 100), page: 1).hasMore)
        XCTAssertThrowsError(try APIParsing.logPage(["items": [row], "total": 200], page: 1))
        var malformed = row
        malformed.removeValue(forKey: "quota")
        XCTAssertThrowsError(try APIParsing.logPage([malformed], page: 1))
        XCTAssertThrowsError(try APIParsing.logPage(["unexpected": []], page: 1))
    }

    func testAccess401FallbackAndUserHeader() async throws {
        let transport = TestTransport { request in
            if request.url!.path == "/api/user/self" {
                if request.value(forHTTPHeaderField: "Authorization") == "Bearer access" { return .json([:], status: 401) }
                return .json(["success": true, "data": ["id": 42]])
            }
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "access")
            XCTAssertEqual(request.value(forHTTPHeaderField: "New-Api-User"), "42")
            return .json(["success": true, "data": ["model-b", "model-a", "model-a"]])
        }
        defer { transport.unregister() }
        var instance = transport.instance
        instance.apiKey = ""
        let client = NewApiClient(instance: instance, configuration: transport.configuration)
        let models = try await client.models()
        XCTAssertEqual(models, ["model-a", "model-b"])
        XCTAssertEqual(transport.requests.count, 3)
        XCTAssertTrue(transport.requests.allSatisfy { $0.httpMethod == "GET" && $0.url?.host == transport.host })
    }

    func testForbiddenDoesNotNegotiateAndKeyNeverUsesAccessToken() async throws {
        let transport = TestTransport { request in
            XCTAssertEqual(request.url?.path, "/v1/models")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer key")
            XCTAssertNil(request.value(forHTTPHeaderField: "New-Api-User"))
            return .json([:], status: 403)
        }
        defer { transport.unregister() }
        do {
            _ = try await NewApiClient(instance: transport.instance, configuration: transport.configuration).models()
            XCTFail("Expected 403")
        } catch let error as AppError { XCTAssertEqual(error.status, 403) }
        XCTAssertEqual(transport.requests.count, 1)
    }

    func testMonitorBalanceSurvivesAggregateFailure() async throws {
        let transport = TestTransport { request in
            switch request.url!.path {
            case "/api/status": return .json(["data": ["quota_per_unit": 100]])
            case "/api/user/self": return .json(["data": ["id": 1, "quota": 250]])
            default: return .json(["data": [["created_at": 1]]])
            }
        }
        defer { transport.unregister() }
        let result = try await NewApiClient(instance: transport.instance, configuration: transport.configuration).monitorSnapshot()
        XCTAssertEqual(result.balanceUsd, 2.5)
        XCTAssertNil(result.balanceFailure)
        XCTAssertNil(result.todayUsd)
        XCTAssertNil(result.monthUsd)
        XCTAssertTrue(result.spendFailure?.contains("Today:") == true)
        XCTAssertTrue(result.spendFailure?.contains("Month:") == true)
        XCTAssertEqual(result.spendAt, 0)
    }

    func testMonitorSpendSurvivesMissingBalance() async throws {
        let transport = TestTransport { request in
            switch request.url!.path {
            case "/api/status": return .json(["data": ["quota_per_unit": 100]])
            case "/api/user/self": return .json(["data": ["id": 1]])
            default: return .json(["data": []])
            }
        }
        defer { transport.unregister() }
        let result = try await NewApiClient(instance: transport.instance, configuration: transport.configuration).monitorSnapshot()
        XCTAssertNil(result.balanceUsd)
        XCTAssertNotNil(result.balanceFailure)
        XCTAssertEqual(result.todayUsd, 0)
        XCTAssertEqual(result.monthUsd, 0)
        XCTAssertNil(result.spendFailure)
    }

    func testMonitorDailyAndMonthlyErrorsAreIndependent() async throws {
        let startOfToday = Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970)
        let transport = TestTransport { request in
            switch request.url!.path {
            case "/api/status": return .json(["data": [:]])
            case "/api/user/self": return .json(["data": ["id": 1, "quota": 500_000]])
            default:
                let start = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?
                    .first(where: { $0.name == "start_timestamp" })?.value
                if start == String(startOfToday) { return .json(["data": []]) }
                return .json([:], status: 500)
            }
        }
        defer { transport.unregister() }
        let result = try await NewApiClient(instance: transport.instance, configuration: transport.configuration).monitorSnapshot()
        XCTAssertEqual(result.todayUsd, 0)
        // On the first of the month both windows intentionally have the same start.
        if Calendar.current.component(.day, from: Date()) != 1 {
            XCTAssertNil(result.monthUsd)
            XCTAssertTrue(result.spendFailure?.hasPrefix("Month:") == true)
            XCTAssertFalse(result.spendFailure?.contains("Today:") == true)
        }
    }

    func testBillingUnknownAndTokenUnitsNeverBecomeDollars() throws {
        XCTAssertNil(DisplayUnits.fromStatus(nil).billingUsd(100))
        let tokens = try APIParsing.status(["quota_display_type": "TOKENS"])
        XCTAssertNil(DisplayUnits.fromStatus(tokens).billingUsd(100))
        let custom = try APIParsing.status(["quota_display_type": "CUSTOM", "custom_currency_exchange_rate": 2])
        XCTAssertEqual(DisplayUnits.fromStatus(custom).billingUsd(100), 100)
        XCTAssertEqual(Quota.resolveQpu(instanceOverride: 25, statusQpu: 100), 25)
        XCTAssertEqual(Quota.resolveQpu(instanceOverride: nil, statusQpu: nil), 500_000)
    }

    func testBillingCNYAndUsageHundredths() async throws {
        let transport = TestTransport { request in
            switch request.url!.path {
            case "/api/status": return .json(["data": ["quota_display_type": "CNY", "usd_exchange_rate": 7]])
            case "/v1/dashboard/billing/subscription": return .json(["hard_limit_usd": 70])
            default: return .json(["total_usage": 1400])
            }
        }
        defer { transport.unregister() }
        var instance = transport.instance
        instance.accessToken = ""
        let result = try await NewApiClient(instance: instance, configuration: transport.configuration).monitorSnapshot()
        XCTAssertEqual(result.balanceUsd, 8)
        XCTAssertNil(result.todayUsd)
        XCTAssertNotNil(result.spendFailure)
    }

    func testReportAggregateIndependentOfBoundedLogs() async throws {
        let transport = TestTransport { request in
            let parts = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
            let query = Dictionary(uniqueKeysWithValues: (parts.queryItems ?? []).map { ($0.name, $0.value ?? "") })
            switch request.url!.path {
            case "/api/status": return .json(["data": ["quota_per_unit": 100]])
            case "/api/user/self": return .json(["data": ["id": 1]])
            case "/api/data/self":
                XCTAssertEqual(query["default_time"], "day")
                return .json(["data": [["created_at": Int64(query["start_timestamp"]!)!, "quota": 12345]]])
            case "/api/log/self":
                XCTAssertEqual(query["type"], "2")
                XCTAssertEqual(query["page_size"], "100")
                let page = Int(query["p"]!)!
                let at = Int64(query["start_timestamp"]!)!
                let rows = (0..<100).map { Self.logRow(id: page * 100 + $0, at: at) }
                return .json(["data": ["items": rows, "total": 1500]])
            default: XCTFail("Unexpected endpoint"); return .json([:], status: 404)
            }
        }
        defer { transport.unregister() }
        let yesterday = Calendar.current.date(byAdding: .day, value: -1, to: Date())!
        let range = ReportRange(start: yesterday, end: yesterday)
        let result = try await NewApiClient(instance: transport.instance, configuration: transport.configuration).report(range: range, compareMonth: false)
        for slice in [result.current, result.previous] {
            XCTAssertEqual(slice.totalUsd, 123.45)
            XCTAssertEqual(slice.rows?.count, 1000)
            XCTAssertTrue(slice.partial)
            XCTAssertNotNil(slice.failure)
        }
        XCTAssertEqual(transport.requests.filter { $0.url?.path == "/api/log/self" }.count, 20)
    }

    func testReportPreservesAggregateWhenLogsFail() async throws {
        let transport = TestTransport { request in
            switch request.url!.path {
            case "/api/status": return .json(["data": [:]])
            case "/api/user/self": return .json(["data": ["id": 1]])
            case "/api/data/self": return .json(["data": []])
            default: return .json([:], status: 500)
            }
        }
        defer { transport.unregister() }
        let yesterday = Calendar.current.date(byAdding: .day, value: -1, to: Date())!
        let result = try await NewApiClient(instance: transport.instance, configuration: transport.configuration)
            .report(range: ReportRange(start: yesterday, end: yesterday), compareMonth: true)
        XCTAssertEqual(result.current.totalUsd, 0)
        XCTAssertNil(result.current.rows)
        XCTAssertTrue(result.current.partial)
        XCTAssertTrue(result.current.failure?.contains("Logs:") == true)
    }

    static func logRow(id: Int, at: Int64 = 1_700_000_000) -> [String: Any] {
        ["id": id, "created_at": at, "type": 2, "quota": 100, "prompt_tokens": 1, "completion_tokens": 2]
    }
}

// Host-keyed URLProtocol fixtures isolate concurrently running tests. No test
// contacts a real server or sends paid traffic. Locks protect URLSession callbacks.
final class TestTransport: @unchecked Sendable {
    struct Reply {
        var status: Int
        var data: Data
        var contentType: String
        var stall: Bool = false
        static func json(_ value: Any, status: Int = 200) -> Reply {
            Reply(status: status, data: try! JSONSerialization.data(withJSONObject: value), contentType: "application/json")
        }
        static func stream(_ text: String, status: Int = 200, stall: Bool = false) -> Reply {
            Reply(status: status, data: Data(text.utf8), contentType: "text/event-stream", stall: stall)
        }
    }
    let host = "test-\(UUID().uuidString.lowercased()).invalid"
    private let lock = NSLock()
    private var recorded: [URLRequest] = []
    private var stopped = 0
    private let handler: (URLRequest) -> Reply
    init(handler: @escaping (URLRequest) -> Reply) {
        self.handler = handler
        FixtureURLProtocol.register(self)
    }
    var requests: [URLRequest] { lock.lock(); defer { lock.unlock() }; return recorded }
    var stopCount: Int { lock.lock(); defer { lock.unlock() }; return stopped }
    var configuration: URLSessionConfiguration {
        let result = NetworkPolicy.configuration()
        result.protocolClasses = [FixtureURLProtocol.self]
        return result
    }
    var instance: Instance {
        Instance(id: host, name: "Fixture", baseUrl: "https://\(host)", accessToken: "access", apiKey: "key",
                 quotaPerUnit: nil, authStyle: nil, authStyleKey: nil, createdAt: 0)
    }
    func reply(_ request: URLRequest) -> Reply {
        lock.lock(); recorded.append(request); lock.unlock()
        return handler(request)
    }
    func didStop() { lock.lock(); stopped += 1; lock.unlock() }
    func unregister() { FixtureURLProtocol.unregister(host) }
}

final class FixtureURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var fixtures: [String: TestTransport] = [:]
    private var fixture: TestTransport?
    static func register(_ fixture: TestTransport) {
        lock.lock(); defer { lock.unlock() }; fixtures[fixture.host] = fixture
    }
    static func unregister(_ host: String) {
        lock.lock(); defer { lock.unlock() }; fixtures.removeValue(forKey: host)
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); fixture = Self.fixtures[request.url?.host ?? ""]; Self.lock.unlock()
        guard let fixture else { client?.urlProtocol(self, didFailWithError: URLError(.badURL)); return }
        let reply = fixture.reply(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": reply.contentType])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !reply.data.isEmpty { client?.urlProtocol(self, didLoad: reply.data) }
        if !reply.stall { client?.urlProtocolDidFinishLoading(self) }
    }
    override func stopLoading() { fixture?.didStop() }
}
