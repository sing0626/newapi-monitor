import Foundation

extension InstanceAlertRule {
    private enum RuleKeys: String, CodingKey {
        case enabled, balanceBelow, dailyAbove, quietStart, quietEnd, cooldownMinutes
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: RuleKeys.self)
        self.init(
            enabled: try values.decodeIfPresent(Bool.self, forKey: .enabled) ?? false,
            balanceBelow: try values.decodeIfPresent(Double.self, forKey: .balanceBelow),
            dailyAbove: try values.decodeIfPresent(Double.self, forKey: .dailyAbove),
            quietStart: try values.decodeIfPresent(String.self, forKey: .quietStart) ?? "23:00",
            quietEnd: try values.decodeIfPresent(String.self, forKey: .quietEnd) ?? "07:00",
            cooldownMinutes: try values.decodeIfPresent(Int.self, forKey: .cooldownMinutes) ?? 60
        )
    }
}
