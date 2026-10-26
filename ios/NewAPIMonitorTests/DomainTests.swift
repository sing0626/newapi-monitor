import XCTest
@testable import NewAPIMonitor

final class DomainTests: XCTestCase {
    private let nowMs: Int64 = 1_790_000_000_000

    private func snapshot(balance: Double? = 4, today: Double? = 12, month: Double? = 110) -> MonitorSnapshot {
        let date = Date(timeIntervalSince1970: Double(nowMs) / 1000)
        return MonitorSnapshot(id: "a", balanceUsd: balance, todayUsd: today, monthUsd: month,
                               balanceAt: nowMs, spendAt: nowMs, attemptedAt: nowMs,
                               day: Fmt.dayString(from: date), month: Fmt.monthString(from: date),
                               balanceFailure: nil, spendFailure: nil, sourceStamp: "test")
    }

    private var instance: Instance {
        Instance(id: "a", name: "Test", baseUrl: "https://example.com", accessToken: "", apiKey: "",
                 quotaPerUnit: nil, authStyle: nil, authStyleKey: nil, createdAt: nowMs)
    }

    func testQuotaPriorityAndInvalidValues() {
        XCTAssertEqual(Quota.resolveQpu(instanceOverride: 100, statusQpu: 200), 100)
        XCTAssertEqual(Quota.resolveQpu(instanceOverride: .nan, statusQpu: 200), 200)
        XCTAssertEqual(Quota.resolveQpu(instanceOverride: -1, statusQpu: .infinity), 500_000)
        XCTAssertEqual(Quota.toUsd(quota: 1_000_000, qpu: 500_000), 2)
    }

    func testBillingDoesNotAssumeUSD() {
        XCTAssertNil(DisplayUnits.unknown.billingUsd(42))
        XCTAssertNil(DisplayUnits.tokens.billingUsd(42))
        XCTAssertEqual(DisplayUnits.cny(usdExchangeRate: 7).billingUsd(14), 2)
        XCTAssertEqual(DisplayUnits.custom(symbol: "X", usdExchangeRate: 3).billingUsd(14), 14)
        XCTAssertNil(DisplayUnits.usd.billingUsd(.nan))
    }

    func testFreshnessRejectsOldFutureFailedAndPreviousDayValues() {
        let good = snapshot()
        XCTAssertTrue(good.balanceFresh(nowMs: nowMs))
        XCTAssertTrue(good.spendFresh(nowMs: nowMs))
        XCTAssertFalse(good.balanceFresh(nowMs: nowMs + 90_000))
        XCTAssertFalse(good.balanceFresh(nowMs: nowMs - 1))
        var failed = good
        failed.attemptedAt += 1
        XCTAssertFalse(failed.balanceFresh(nowMs: nowMs + 1))
        var yesterday = good
        yesterday.day = "2000-01-01"
        XCTAssertFalse(yesterday.spendFresh(nowMs: nowMs))
        XCTAssertFalse(snapshot(balance: nil).balanceFresh(nowMs: nowMs))
    }

    func testAlertsDoNotFireFromStaleData() {
        var settings = AlertSettings()
        settings.enabled = true
        settings.balanceBelowUsd = 5
        settings.dailySpendAboveUsd = 10
        let output = AlertEngine.evaluate(instance: instance, snapshot: snapshot(), monthlyBudgetUsd: 100,
                                          globalSettings: settings, instanceRule: nil,
                                          previousState: .init(), nowMs: nowMs + 90_000)
        XCTAssertTrue(output.records.isEmpty)
    }

    func testAlertBudgetPhasesAndHysteresis() {
        var settings = AlertSettings()
        settings.enabled = true
        settings.balanceBelowUsd = 5
        settings.dailySpendAboveUsd = 10
        let first = AlertEngine.evaluate(instance: instance, snapshot: snapshot(), monthlyBudgetUsd: 100,
                                         globalSettings: settings, instanceRule: nil,
                                         previousState: .init(), nowMs: nowMs)
        XCTAssertEqual(Set(first.records.map(\.type)), Set(["BALANCE_LOW", "DAILY_SPEND", "MONTHLY_BUDGET_90", "MONTHLY_BUDGET_100"]))
        XCTAssertFalse(first.updatedState.armedBalanceOk)
        let again = AlertEngine.evaluate(instance: instance, snapshot: snapshot(), monthlyBudgetUsd: 100,
                                         globalSettings: settings, instanceRule: nil,
                                         previousState: first.updatedState, nowMs: nowMs + 1)
        XCTAssertTrue(again.records.isEmpty)
        let recovered = AlertEngine.evaluate(instance: instance, snapshot: snapshot(balance: 6), monthlyBudgetUsd: 100,
                                             globalSettings: settings, instanceRule: nil,
                                             previousState: first.updatedState, nowMs: nowMs + 1)
        XCTAssertTrue(recovered.updatedState.armedBalanceOk)
    }

    func testInstanceRuleCanLeaveThresholdUnsetWithoutGlobalFallback() {
        var global = AlertSettings()
        global.enabled = true
        global.balanceBelowUsd = 50
        var rule = InstanceAlertRule()
        rule.enabled = true
        rule.quietStart = ""
        rule.quietEnd = ""
        let output = AlertEngine.evaluate(instance: instance, snapshot: snapshot(), monthlyBudgetUsd: nil,
                                          globalSettings: global, instanceRule: rule,
                                          previousState: .init(), nowMs: nowMs)
        XCTAssertTrue(output.records.isEmpty)
    }

    func testRulesDecodeLegacyDefaultsAndValidateTimes() throws {
        let rule = try JSONDecoder().decode(InstanceAlertRule.self, from: Data("{}".utf8))
        XCTAssertEqual(rule.quietStart, "23:00")
        XCTAssertEqual(rule.cooldownMinutes, 60)
        XCTAssertNoThrow(try rule.validate())
        var invalid = rule
        invalid.quietStart = "25:90"
        XCTAssertThrowsError(try invalid.validate())
    }

    func testDateRangeValidationAndPreviousWindow() throws {
        let calendar = Calendar.current
        let end = calendar.startOfDay(for: Date())
        let start = try XCTUnwrap(calendar.date(byAdding: .day, value: -6, to: end))
        let range = ReportRange(start: start, end: end)
        XCTAssertEqual(range.dayCount, 7)
        XCTAssertNoThrow(try range.validate())
        XCTAssertEqual(range.previousPeriod().dayCount, 7)
        XCTAssertLessThan(range.previousPeriod().endTimestampSec, range.startTimestampSec)
        XCTAssertThrowsError(try ReportRange(start: end, end: start).validate())
        let tooOld = try XCTUnwrap(calendar.date(byAdding: .day, value: -366, to: end))
        XCTAssertThrowsError(try ReportRange(start: tooOld, end: end).validate())
    }

    func testCSVGuardsFormulasAndKeepsUnknownAmountsEmpty() {
        let range = ReportRange(start: Date(), end: Date())
        let names = ["=HYPERLINK(\"evil\")", "\n @SUM(1)", "+cmd", "\tvalue", "quote\"name"]
        let report = CrossReport(range: range, sources: names.enumerated().map {
            SourceReport(id: String($0.offset), name: $0.element, report: nil, failure: "unavailable")
        })
        let csv = ReportCsv.render(report)
        XCTAssertTrue(csv.hasPrefix("\u{FEFF}"))
        XCTAssertTrue(csv.contains("\"'=HYPERLINK(\"\"evil\"\")\""))
        XCTAssertTrue(csv.contains("\"'\n @SUM(1)\""))
        XCTAssertTrue(csv.contains("\"'+cmd\""))
        XCTAssertTrue(csv.contains("\"'\tvalue\""))
        XCTAssertTrue(csv.contains("\"quote\"\"name\""))
        XCTAssertNil(report.totalUsd)
        XCTAssertEqual(report.availableCount, 0)
    }

    func testFormattingInt64MinDoesNotOverflow() {
        XCTAssertFalse(Fmt.compactCount(Int64.min).isEmpty)
        XCTAssertEqual(Fmt.usd(nil), "—")
        XCTAssertEqual(Fmt.usd(.infinity), "—")
    }

    func testLiquidGlassSnapProjectionClampAndVibrancy() {
        let w: CGFloat = 233.5
        XCTAssertEqual(LiquidGlassMath.snapIndex(currentIdx: 0, dragPx: 0.3 * w, velocityX: 0, tabWidth: w, count: 4), 0)
        XCTAssertEqual(LiquidGlassMath.snapIndex(currentIdx: 0, dragPx: 0.6 * w, velocityX: 0, tabWidth: w, count: 4), 1)
        XCTAssertEqual(LiquidGlassMath.snapIndex(currentIdx: 0, dragPx: 0.3 * w, velocityX: w / 0.15, tabWidth: w, count: 4), 1)
        XCTAssertEqual(LiquidGlassMath.snapIndex(currentIdx: 1, dragPx: -0.3 * w, velocityX: -w / 0.15, tabWidth: w, count: 4), 0)
        XCTAssertEqual(LiquidGlassMath.snapIndex(currentIdx: 3, dragPx: 2 * w, velocityX: 5 * w / 0.15, tabWidth: w, count: 4), 3)
        XCTAssertEqual(LiquidGlassMath.snapIndex(currentIdx: 0, dragPx: -5 * w, velocityX: 0, tabWidth: w, count: 4), 0)
        XCTAssertEqual(LiquidGlassMath.snapIndex(currentIdx: 2, dragPx: 10, velocityX: 100, tabWidth: 0, count: 4), 2)
        XCTAssertEqual(LiquidGlassMath.alignment(currentIdx: 0, dragPx: -999, tabWidth: w, count: 4), -1)
        XCTAssertEqual(LiquidGlassMath.alignment(currentIdx: 3, dragPx: 999, tabWidth: w, count: 4), 1)
        XCTAssertEqual(LiquidGlassMath.bubbleOffset(alignment: -1, width: 400, count: 4), 0)
        XCTAssertEqual(LiquidGlassMath.bubbleOffset(alignment: 1, width: 400, count: 4), 300)
        let darkSignal = LiquidGlassMath.signal(rgbBytes: [10, 14, 26, 255])
        let brightSignal = LiquidGlassMath.signal(rgbBytes: [250, 250, 250, 255])
        XCTAssertEqual(darkSignal, 0)
        XCTAssertEqual(brightSignal, 1)
        XCTAssertEqual(LiquidGlassMath.smoothedVibrancy(previous: 0.5, signal: 1.0), 0.75)
    }
}
