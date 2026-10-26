import Foundation

enum AlertEngine {
    struct State: Codable, Equatable, Sendable {
        var lastFiredMs: [String: Int64] = [:]
        var armedBalanceOk: Bool = true
        var bannedTokenIds: Set<Int64> = []
        var tokenExpiryPhases: [String: Int] = [:]
    }

    struct EvaluationOutput: Sendable {
        var records: [AlertRecord]
        var updatedState: State
    }

    static func evaluate(
        instance: Instance,
        snapshot: MonitorSnapshot,
        monthlyBudgetUsd: Double?,
        globalSettings: AlertSettings,
        instanceRule: InstanceAlertRule?,
        previousState: State,
        nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) -> EvaluationOutput {
        var state = previousState
        var records: [AlertRecord] = []

        let ruleEnabled = (instanceRule?.enabled == true) || globalSettings.enabled
        guard ruleEnabled || monthlyBudgetUsd != nil else {
            return EvaluationOutput(records: [], updatedState: state)
        }

        if let rule = instanceRule, rule.enabled, isInQuietHours(nowMs: nowMs, start: rule.quietStart, end: rule.quietEnd) {
            return EvaluationOutput(records: [], updatedState: state)
        }

        let cooldownMinutes = instanceRule?.enabled == true ? instanceRule!.cooldownMinutes : globalSettings.cooldownMinutes
        let cooldownMs = Int64(max(1, cooldownMinutes)) * 60_000

        func inCooldown(_ type: AlertType) -> Bool {
            guard let last = state.lastFiredMs[type.rawValue] else { return false }
            return (nowMs - last) < cooldownMs
        }

        let balanceThreshold = instanceRule?.enabled == true ? instanceRule?.balanceBelow : globalSettings.balanceBelowUsd
        if ruleEnabled, snapshot.balanceFresh(nowMs: nowMs),
           let threshold = balanceThreshold, threshold.isFinite, threshold >= 0,
           let bal = snapshot.balanceUsd,
           bal.isFinite {
            if state.armedBalanceOk && !inCooldown(.balanceLow) && bal <= threshold {
                state.armedBalanceOk = false
                state.lastFiredMs[AlertType.balanceLow.rawValue] = nowMs
                records.append(
                    AlertRecord(
                        id: UUID().uuidString,
                        sourceId: instance.id,
                        title: "餘額過低",
                        body: "「\(instance.name)」餘額 \(Fmt.usd(bal)) 已低於警報線 \(Fmt.usd(threshold))",
                        type: AlertType.balanceLow.rawValue,
                        time: nowMs,
                        read: false
                    )
                )
            } else if bal >= threshold * 1.1 {
                state.armedBalanceOk = true
            }
        }

        let dailyThreshold = instanceRule?.enabled == true ? instanceRule?.dailyAbove : globalSettings.dailySpendAboveUsd
        if ruleEnabled, snapshot.spendFresh(nowMs: nowMs),
           let threshold = dailyThreshold,
           threshold.isFinite, threshold > 0,
           let today = snapshot.todayUsd,
           today.isFinite,
           today >= threshold,
           !inCooldown(.dailySpend),
           !firedSameCalendarDay(lastMs: state.lastFiredMs[AlertType.dailySpend.rawValue], nowMs: nowMs) {
            state.lastFiredMs[AlertType.dailySpend.rawValue] = nowMs
            records.append(
                AlertRecord(
                    id: UUID().uuidString,
                    sourceId: instance.id,
                    title: "今日消費超標",
                    body: "「\(instance.name)」今日消費 \(Fmt.usd(today)) 已達警報線 \(Fmt.usd(threshold))",
                    type: AlertType.dailySpend.rawValue,
                    time: nowMs,
                    read: false
                )
            )
        }

        let effectiveBudget = monthlyBudgetUsd ?? globalSettings.monthlyBudgetUsd
        if snapshot.spendFresh(nowMs: nowMs), let budget = effectiveBudget,
           budget.isFinite,
           budget > 0,
           let monthSpend = snapshot.monthUsd,
           monthSpend.isFinite,
           monthSpend >= 0 {
            let ratio = monthSpend / budget
            let checks: [(AlertType, Double, String)] = [
                (.monthlyBudget90, 0.9, "月度預算警戒"),
                (.monthlyBudget100, 1.0, "月度預算已超支")
            ]
            for (type, limit, title) in checks {
                let lastMs = state.lastFiredMs[type.rawValue]
                if ratio >= limit && !firedSameCalendarMonth(lastMs: lastMs, nowMs: nowMs) && !inCooldown(type) {
                    state.lastFiredMs[type.rawValue] = nowMs
                    let pct = String(format: "%.0f", ratio * 100)
                    records.append(
                        AlertRecord(
                            id: UUID().uuidString,
                            sourceId: instance.id,
                            title: title,
                            body: "「\(instance.name)」本月消費 \(Fmt.usd(monthSpend)) / \(Fmt.usd(budget))（\(pct)%）",
                            type: type.rawValue,
                            time: nowMs,
                            read: false
                        )
                    )
                }
            }
        }

        return EvaluationOutput(records: records, updatedState: state)
    }

    private static func firedSameCalendarDay(lastMs: Int64?, nowMs: Int64) -> Bool {
        guard let lastMs else { return false }
        let cal = Calendar.current
        let d1 = Date(timeIntervalSince1970: TimeInterval(lastMs) / 1000.0)
        let d2 = Date(timeIntervalSince1970: TimeInterval(nowMs) / 1000.0)
        return cal.isDate(d1, inSameDayAs: d2)
    }

    private static func firedSameCalendarMonth(lastMs: Int64?, nowMs: Int64) -> Bool {
        guard let lastMs else { return false }
        let cal = Calendar.current
        let c1 = cal.dateComponents([.year, .month], from: Date(timeIntervalSince1970: TimeInterval(lastMs) / 1000.0))
        let c2 = cal.dateComponents([.year, .month], from: Date(timeIntervalSince1970: TimeInterval(nowMs) / 1000.0))
        return c1.year == c2.year && c1.month == c2.month
    }

    private static func isInQuietHours(nowMs: Int64, start: String, end: String) -> Bool {
        guard let startMins = parseMinutes(start), let endMins = parseMinutes(end), startMins != endMins else {
            return false
        }
        let date = Date(timeIntervalSince1970: TimeInterval(nowMs) / 1000.0)
        let comps = Calendar.current.dateComponents([.hour, .minute], from: date)
        let curMins = (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
        if startMins < endMins {
            return curMins >= startMins && curMins < endMins
        } else {
            return curMins >= startMins || curMins < endMins
        }
    }

    private static func parseMinutes(_ hhmm: String) -> Int? {
        let parts = hhmm.split(separator: ":")
        guard parts.count == 2, let h = Int(parts[0]), let m = Int(parts[1]),
              (0...23).contains(h), (0...59).contains(m) else {
            return nil
        }
        return h * 60 + m
    }
}
