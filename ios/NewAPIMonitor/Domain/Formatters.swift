import Foundation

enum Fmt {
    static func usd(_ value: Double?, hide: Bool = false, compact: Bool = true) -> String {
        if hide { return "••••" }
        guard let v = value, v.isFinite else { return "—" }
        let sign = v < 0 ? "-" : ""
        let absVal = abs(v)
        if compact {
            if absVal >= 1_000_000_000_000 {
                return String(format: "%@$%.2fT", sign, absVal / 1_000_000_000_000)
            } else if absVal >= 1_000_000_000 {
                return String(format: "%@$%.2fB", sign, absVal / 1_000_000_000)
            } else if absVal >= 1_000_000 {
                return String(format: "%@$%.2fM", sign, absVal / 1_000_000)
            } else if absVal >= 10_000 {
                return String(format: "%@$%.2fK", sign, absVal / 1_000)
            }
        }
        if absVal > 0 && absVal < 0.01 {
            return String(format: "%@$%.4f", sign, absVal)
        }
        return String(format: "%@$%.2f", sign, absVal)
    }

    static func fullUsd(_ value: Double?, hide: Bool = false) -> String {
        if hide { return "••••" }
        guard let v = value, v.isFinite else { return "—" }
        return String(format: "$%.4f", v)
    }

    static func compactCount(_ count: Int64) -> String {
        let absVal = abs(Double(count))
        if absVal >= 1_000_000_000 {
            return String(format: "%.2fB", Double(count) / 1_000_000_000)
        } else if absVal >= 1_000_000 {
            return String(format: "%.2fM", Double(count) / 1_000_000)
        } else if absVal >= 1_000 {
            return String(format: "%.1fK", Double(count) / 1_000)
        }
        return "\(count)"
    }

    static func relativeTime(fromMs ms: Int64, nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        guard ms > 0 else { return "從未更新" }
        let diffSec = max(0, (nowMs - ms) / 1000)
        if diffSec < 5 { return "剛剛" }
        if diffSec < 60 { return "\(diffSec) 秒前" }
        let mins = diffSec / 60
        if mins < 60 { return "\(mins) 分鐘前" }
        let hours = mins / 60
        if hours < 24 { return "\(hours) 小時前" }
        return "\(hours / 24) 日前"
    }

    static func dateTime(_ epochSec: Int64) -> String {
        guard epochSec > 0 else { return "—" }
        let date = Date(timeIntervalSince1970: TimeInterval(epochSec))
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm:ss"
        return formatter.string(from: date)
    }

    static func dayString(from date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    static func monthString(from date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "yyyy-MM"
        return formatter.string(from: date)
    }
}
