import Foundation

enum DemoEngine {
    static let qpu: Double = 500_000.0
    static let demoInstanceId = "demo-instance"

    static let weightedModels: [(String, Int)] = [
        ("gpt-4o", 24),
        ("gpt-4o-mini", 22),
        ("claude-sonnet-4-20250514", 18),
        ("deepseek-chat", 13),
        ("gemini-2.5-pro", 9),
        ("claude-opus-4-1", 6),
        ("o3-mini", 5),
        ("qwen-max", 3)
    ]

    static func generateDashboardSnapshot(nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> DashboardSnapshot {
        let nowSec = nowMs / 1000
        let calendar = Calendar.current
        let todayStartSec = Int64(calendar.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(nowSec))).timeIntervalSince1970)

        var logs: [LogEntry] = []
        var weekDays: [DailySpend] = []
        var hourly = Array(repeating: 0.0, count: 24)

        for dayOffset in (0...6).reversed() {
            let dayDate = calendar.date(byAdding: .day, value: -dayOffset, to: Date(timeIntervalSince1970: TimeInterval(todayStartSec)))!
            let dayStart = Int64(calendar.startOfDay(for: dayDate).timeIntervalSince1970)
            let countForDay = dayOffset == 0 ? 38 : (12 + (dayOffset * 3) % 9)
            var dayQuota: Double = 0
            var dayTokens: Int64 = 0

            for i in 0..<countForDay {
                let model = weightedModels[(i + dayOffset) % weightedModels.count].0
                let isErr = (i % 25 == 7)
                let promptTok = Int64(250 + (i * 137) % 1800)
                let compTok = isErr ? 0 : Int64(120 + (i * 293) % 1400)
                let quotaVal = isErr ? 0.0 : Double(promptTok + compTok * 3) * (model.contains("opus") || model.contains("pro") ? 8.5 : 2.2)
                let createdSec: Int64
                if dayOffset == 0 {
                    let span = max(60, nowSec - todayStartSec)
                    createdSec = todayStartSec + Int64((Double(i) / Double(countForDay)) * Double(span))
                } else {
                    createdSec = dayStart + Int64((i * 2100) % 82_000)
                }

                if !isErr {
                    dayQuota += quotaVal
                    dayTokens += (promptTok + compTok)
                    if dayOffset == 0 {
                        let hr = calendar.component(.hour, from: Date(timeIntervalSince1970: TimeInterval(createdSec)))
                        if (0..<24).contains(hr) {
                            hourly[hr] += quotaVal / qpu
                        }
                    }
                }

                let tokenNames = ["ide-chat", "prod-app", "cli-dev", "agent-pipeline"]
                logs.append(
                    LogEntry(
                        id: "demo-\(dayOffset)-\(i)",
                        type: isErr ? 5 : 2,
                        createdAt: createdSec,
                        modelName: model,
                        tokenName: tokenNames[i % tokenNames.count],
                        promptTokens: promptTok,
                        completionTokens: compTok,
                        quota: quotaVal,
                        useTime: 1 + (i % 6),
                        isStream: i % 4 != 0,
                        username: "demo_user",
                        content: isErr ? "Upstream 429 rate limit exceeded (simulated)" : ""
                    )
                )
            }

            let dayStr = Fmt.dayString(from: dayDate)
            weekDays.append(
                DailySpend(
                    date: dayStr,
                    usd: dayQuota / qpu,
                    count: countForDay,
                    tokens: dayTokens
                )
            )
        }

        logs.sort { $0.createdAt > $1.createdAt }
        let todaySpend = weekDays.last?.usd ?? 3.42
        let weekTotal = weekDays.map(\.usd).reduce(0, +)
        let monthSpend = weekTotal * 2.85

        var modelBuckets: [String: (quota: Double, count: Int, tokens: Int64)] = [:]
        for log in logs where log.type == 2 {
            let cur = modelBuckets[log.modelName] ?? (0, 0, 0)
            modelBuckets[log.modelName] = (cur.quota + log.quota, cur.count + 1, cur.tokens + log.promptTokens + log.completionTokens)
        }
        let totalModelQuota = max(1.0, modelBuckets.values.map(\.quota).reduce(0, +))
        let modelStats = modelBuckets.map { key, val in
            ModelStat(
                model: key,
                quota: val.quota,
                usd: val.quota / qpu,
                count: val.count,
                tokens: val.tokens,
                share: val.quota / totalModelQuota
            )
        }.sorted { $0.usd > $1.usd }

        let err24h = logs.filter { $0.type == 5 && $0.createdAt >= nowSec - 86_400 }.count
        let req24h = max(1, logs.filter { $0.createdAt >= nowSec - 86_400 }.count)

        return DashboardSnapshot(
            ok: true,
            demo: true,
            instanceId: demoInstanceId,
            name: "演示站點（離線模擬）",
            keyOnly: false,
            noInstance: false,
            noTokens: false,
            fetchedAtMs: nowMs,
            qpu: qpu,
            status: StatusInfo(
                systemName: "NewAPI Demo Station",
                version: "v0.8.2-demo",
                startTime: nowSec - 86_400 * 14,
                quotaPerUnit: qpu,
                quotaDisplayType: "USD",
                usdExchangeRate: 7.2,
                customCurrencySymbol: nil,
                customCurrencyExchangeRate: nil
            ),
            user: UserInfo(
                id: 1001,
                username: "demo_user",
                displayName: "演示用戶（離線模擬）",
                group: "vip",
                quota: 236.4752 * qpu,
                usedQuota: 87.4134 * qpu,
                requestCount: Int64(1284 + logs.count)
            ),
            billing: nil,
            balanceUsd: 236.4752,
            usedTotalUsd: 87.4134,
            todayUsd: todaySpend,
            todayTruncated: false,
            todayCount: weekDays.last?.count ?? 38,
            monthUsd: monthSpend,
            monthTruncated: false,
            weekDays: weekDays,
            weekTotalUsd: weekTotal,
            recentLogs: Array(logs.prefix(25)),
            modelStats: modelStats,
            models: weightedModels.map(\.0),
            errorCount24h: err24h,
            errorRate: Double(err24h) / Double(req24h),
            hourlySpend: hourly,
            notice: "呢個係完全離線嘅演示模式（Demo Mode），所有餘額、消費、模型日誌同測速數據均為本地模擬，唔會發送任何網路請求。",
            errors: [:]
        )
    }

    static func demoTokens(nowSec: Int64 = Int64(Date().timeIntervalSince1970)) -> [TokenInfo] {
        let names = ["ide-chat", "prod-app", "cli-dev", "notebook", "agent-pipeline", "playground", "sdk-ci"]
        let statuses = [1, 1, 1, 1, 1, 2, 3]
        return names.enumerated().map { idx, name in
            TokenInfo(
                id: Int64(1000 + idx),
                name: name,
                key: "",
                status: statuses[idx],
                usedQuota: Double(12 + idx * 9) * qpu,
                remainQuota: idx == 0 ? 0 : Double(50 - idx * 5) * qpu,
                unlimitedQuota: idx == 0,
                expiredTime: idx == 4 ? (nowSec + 86_400 * 2) : -1,
                createdTime: nowSec - Int64(86_400 * (30 - idx * 3)),
                group: idx < 3 ? "vip" : "default",
                modelLimitsEnabled: idx == 1,
                modelLimits: idx == 1 ? "gpt-4o,claude-sonnet-4-20250514" : "",
                allowIps: ""
            )
        }
    }

    static func demoChannels() -> [ChannelInfo] {
        let items: [(Int64, String, Int, Int, Int)] = [
            (100, "openai-primary", 1, 1, 240),
            (101, "anthropic-fallback", 1, 14, 410),
            (102, "gemini-cheap", 2, 24, 320),
            (103, "aws-bedrock", 3, 33, 1850),
            (104, "vertex-turbo", 1, 42, 195)
        ]
        return items.map { id, name, status, type, rt in
            ChannelInfo(id: id, name: name, status: status, type: type, responseTime: rt, priority: 10, weight: 100)
        }
    }

    static func demoPricing() -> [ModelPriceInfo] {
        [
            ModelPriceInfo(model: "gpt-4o", quotaType: 0, modelRatio: 1.25, completionRatio: 4.0, fixedPriceUsd: nil, groups: ["default", "vip"]),
            ModelPriceInfo(model: "gpt-4o-mini", quotaType: 0, modelRatio: 0.075, completionRatio: 4.0, fixedPriceUsd: nil, groups: ["default", "vip"]),
            ModelPriceInfo(model: "claude-sonnet-4-20250514", quotaType: 0, modelRatio: 1.5, completionRatio: 5.0, fixedPriceUsd: nil, groups: ["default", "vip"]),
            ModelPriceInfo(model: "deepseek-chat", quotaType: 0, modelRatio: 0.14, completionRatio: 2.0, fixedPriceUsd: nil, groups: ["default", "vip"]),
            ModelPriceInfo(model: "gemini-2.5-pro", quotaType: 0, modelRatio: 0.625, completionRatio: 4.0, fixedPriceUsd: nil, groups: ["default", "vip"]),
            ModelPriceInfo(model: "claude-opus-4-1", quotaType: 0, modelRatio: 7.5, completionRatio: 5.0, fixedPriceUsd: nil, groups: ["vip"]),
            ModelPriceInfo(model: "o3-mini", quotaType: 0, modelRatio: 0.55, completionRatio: 4.0, fixedPriceUsd: nil, groups: ["default", "vip"])
        ]
    }
}
