import Foundation

struct ReportRange: Equatable, Sendable {
    var start: Date
    var end: Date

    var startDayString: String { Fmt.dayString(from: start) }
    var endDayString: String { Fmt.dayString(from: end) }

    var startTimestampSec: Int64 {
        Int64(Calendar.current.startOfDay(for: start).timeIntervalSince1970)
    }

    var endTimestampSec: Int64 {
        let dayStart = Calendar.current.startOfDay(for: end)
        let nextDay = Calendar.current.date(byAdding: .day, value: 1, to: dayStart) ?? end
        return Int64(nextDay.timeIntervalSince1970) - 1
    }

    var dayCount: Int {
        let s = Calendar.current.startOfDay(for: start)
        let e = Calendar.current.startOfDay(for: end)
        let diff = Calendar.current.dateComponents([.day], from: s, to: e).day ?? 0
        return max(1, diff + 1)
    }

    func validate(now: Date = Date()) throws {
        let calendar = Calendar.current
        let first = calendar.startOfDay(for: start)
        let last = calendar.startOfDay(for: end)
        guard first <= last, last <= calendar.startOfDay(for: now), dayCount <= 366 else {
            throw AppError(kind: .parse, message: "日期範圍必須為過去或今日，開始不遲於結束，最多 366 日")
        }
    }

    func previousPeriod() -> ReportRange {
        let cal = Calendar.current
        let s = cal.startOfDay(for: start)
        let days = dayCount
        let prevEnd = cal.date(byAdding: .day, value: -1, to: s) ?? s
        let prevStart = cal.date(byAdding: .day, value: -days, to: s) ?? s
        return ReportRange(start: prevStart, end: prevEnd)
    }

    func previousMonthPeriod() -> ReportRange {
        let cal = Calendar.current
        let prevStart = cal.date(byAdding: .month, value: -1, to: start) ?? start
        let prevEnd = cal.date(byAdding: .month, value: -1, to: end) ?? end
        return ReportRange(start: prevStart, end: prevEnd)
    }
}

struct CostGroup: Identifiable, Equatable, Sendable {
    var id: String { name }
    var name: String
    var usd: Double
    var requests: Int
    var tokens: Int64
}

enum CostBreakdown {
    static func group(rows: [LogEntry], qpu: Double, byToken: Bool) -> [CostGroup] {
        var map: [String: (usd: Double, count: Int, tokens: Int64)] = [:]
        for row in rows where row.type == nil || row.type == 2 {
            let rawKey = byToken ? row.tokenName : row.modelName
            let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "（未命名）" : rawKey
            let usd = Quota.toUsd(quota: row.quota, qpu: qpu)
            guard usd.isFinite, usd >= 0 else { continue }
            let cur = map[key] ?? (0, 0, 0)
            map[key] = (cur.usd + usd, cur.count + 1, cur.tokens + row.promptTokens + row.completionTokens)
        }
        return map.map { key, val in
            CostGroup(name: key, usd: val.usd, requests: val.count, tokens: val.tokens)
        }.sorted { $0.usd > $1.usd }
    }
}

struct ReportSlice: Equatable, Sendable {
    var totalUsd: Double?
    var rows: [LogEntry]?
    var partial: Bool
    var failure: String?
    var atMs: Int64
    var qpu: Double
}

struct PeriodReport: Equatable, Sendable {
    var range: ReportRange
    var current: ReportSlice
    var previous: ReportSlice
    var comparisonRange: ReportRange
}

struct SourceReport: Identifiable, Equatable, Sendable {
    var id: String
    var name: String
    var report: PeriodReport?
    var failure: String?
}

struct CrossReport: Equatable, Sendable {
    var range: ReportRange
    var sources: [SourceReport]

    var availableCount: Int {
        sources.filter { $0.report?.current.totalUsd != nil }.count
    }

    var totalUsd: Double? {
        let valid = sources.compactMap { $0.report?.current.totalUsd }
        guard !valid.isEmpty else { return nil }
        return valid.reduce(0, +)
    }
}

enum ReportCsv {
    private static func cell(_ text: String) -> String {
        let trimmedStart = text.drop(while: { $0.isWhitespace })
        let dangerousPrefixes: [Character] = ["=", "+", "-", "@"]
        let guarded: String
        if let first = trimmedStart.first, dangerousPrefixes.contains(first) || text.hasPrefix("\t") || text.hasPrefix("\r") {
            guarded = "'\(text)"
        } else {
            guarded = text
        }
        let escaped = guarded.replacingOccurrences(of: "\"", with: "\"\"")
        return "\"\(escaped)\""
    }

    static func render(_ report: CrossReport) -> String {
        var rows: [[String]] = [
            ["section", "instance", "start", "end", "model_or_token", "usd", "requests", "coverage"]
        ]
        rows.append([
            "all_sources",
            "",
            report.range.startDayString,
            report.range.endDayString,
            "",
            report.totalUsd.map { String(format: "%.4f", $0) } ?? "",
            "",
            "\(report.availableCount)/\(report.sources.count)"
        ])

        for source in report.sources {
            let data = source.report
            rows.append([
                "total",
                source.name,
                report.range.startDayString,
                report.range.endDayString,
                "",
                data?.current.totalUsd.map { String(format: "%.4f", $0) } ?? "",
                "",
                data?.current.totalUsd == nil ? (source.failure ?? "unavailable") : "aggregate"
            ])
            if let data {
                rows.append([
                    "comparison",
                    source.name,
                    data.comparisonRange.startDayString,
                    data.comparisonRange.endDayString,
                    "",
                    data.previous.totalUsd.map { String(format: "%.4f", $0) } ?? "",
                    "",
                    data.previous.totalUsd == nil ? "unavailable" : "aggregate"
                ])
                for byToken in [false, true] {
                    let groups = CostBreakdown.group(rows: data.current.rows ?? [], qpu: data.current.qpu, byToken: byToken)
                    for group in groups {
                        rows.append([
                            byToken ? "token" : "model",
                            source.name,
                            report.range.startDayString,
                            report.range.endDayString,
                            group.name,
                            String(format: "%.4f", group.usd),
                            "\(group.requests)",
                            data.current.partial ? "partial logs" : "loaded logs"
                        ])
                    }
                }
            }
        }

        let body = rows.map { row in row.map(cell).joined(separator: ",") }.joined(separator: "\r\n")
        return "\u{FEFF}" + body
    }
}
