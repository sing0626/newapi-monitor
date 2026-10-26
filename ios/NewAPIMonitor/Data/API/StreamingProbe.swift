import Foundation

/// A paid request is sent exactly once: no auth negotiation or application retry.
/// Only timings and usage survive event processing; generated text is discarded.
enum StreamingProbe {
    struct Accumulator: Sendable {
        private(set) var firstAtMs: Int64? = nil
        private(set) var tokens: Int64? = nil
        private(set) var done = false

        mutating func processEvent(_ value: String, elapsedMs: Int64) throws {
            guard !done else { throw APIParsing.failure("event after DONE") }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed == "[DONE]" { done = true; return }
            guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else {
                throw APIParsing.failure("empty stream event")
            }
            let root = try APIParsing.object(APIParsing.decode(data, envelope: false))
            var hasUsage = false
            if let rawUsage = root["usage"], !(rawUsage is NSNull) {
                let usage = try APIParsing.object(rawUsage)
                if let rawTokens = usage["completion_tokens"], !(rawTokens is NSNull) {
                    guard let count = APIParsing.integer(rawTokens), count >= 0 else {
                        throw APIParsing.failure("stream completion_tokens")
                    }
                    tokens = count
                    hasUsage = true
                }
            }
            if let rawChoices = root["choices"] {
                guard let choices = rawChoices as? [[String: Any]] else { throw APIParsing.failure("stream choices") }
                guard !choices.isEmpty || hasUsage else { throw APIParsing.failure("empty stream choices") }
                for choice in choices {
                    guard let delta = choice["delta"] as? [String: Any] else { throw APIParsing.failure("stream delta") }
                    var hasText = false
                    for key in ["content", "reasoning_content"] {
                        if let raw = delta[key], !(raw is NSNull) {
                            guard let text = raw as? String else { throw APIParsing.failure("stream content") }
                            hasText = hasText || !text.isEmpty
                        }
                    }
                    if hasText && firstAtMs == nil { firstAtMs = elapsedMs }
                }
            } else if !hasUsage {
                throw APIParsing.failure("stream event payload")
            }
        }

        func rate(endMs: Int64) -> Double? {
            guard let first = firstAtMs, let count = tokens, count > 1, endMs > first else { return nil }
            return Double(count - 1) * 1000 / Double(endMs - first)
        }
    }

    /// Byte-oriented framing bounds memory even when the peer never sends a newline.
    /// LF, CRLF and CR are supported; multiline data is joined at blank-line dispatch.
    struct EventDecoder: Sendable {
        static let maxBytes = 2 * 1024 * 1024
        static let maxLineBytes = 64 * 1024
        static let maxEventBytes = 256 * 1024
        static let maxLines = 20_000
        private var line = Data()
        private var dataLines: [String] = []
        private var eventBytes = 0
        private var totalBytes = 0
        private var lines = 0
        private var skipLF = false
        private var eventType = ""
        private(set) var accumulator = Accumulator()

        mutating func feed(_ byte: UInt8, elapsedMs: Int64) throws {
            totalBytes += 1
            guard totalBytes <= Self.maxBytes else { throw APIParsing.failure("stream exceeds 2 MB") }
            if skipLF {
                skipLF = false
                if byte == 10 { return }
            }
            if byte == 13 || byte == 10 {
                try dispatchLine(elapsedMs: elapsedMs)
                skipLF = byte == 13
            } else {
                guard line.count < Self.maxLineBytes else { throw APIParsing.failure("stream line exceeds 64 KB") }
                line.append(byte)
            }
        }

        private mutating func dispatchLine(elapsedMs: Int64) throws {
            lines += 1
            guard lines <= Self.maxLines else { throw APIParsing.failure("too many stream lines") }
            guard var text = String(data: line, encoding: .utf8) else { throw APIParsing.failure("stream UTF-8") }
            line.removeAll(keepingCapacity: true)
            if lines == 1 && text.hasPrefix("\u{FEFF}") { text.removeFirst() }
            if text.isEmpty {
                if eventType == "error" { throw AppError(kind: .api, message: "Stream returned error") }
                if !dataLines.isEmpty {
                    let payload = dataLines.joined(separator: "\n")
                    dataLines.removeAll(keepingCapacity: false)
                    eventBytes = 0
                    try accumulator.processEvent(payload, elapsedMs: elapsedMs)
                }
                eventType = ""
                return
            }
            if text.hasPrefix(":") { return }
            let separator = text.firstIndex(of: ":")
            let field = separator.map { String(text[..<$0]) } ?? text
            var value = separator.map { String(text[text.index(after: $0)...]) } ?? ""
            if value.hasPrefix(" ") { value.removeFirst() }
            switch field {
            case "data":
                eventBytes += value.utf8.count + 1
                guard eventBytes <= Self.maxEventBytes else { throw APIParsing.failure("stream event exceeds 256 KB") }
                dataLines.append(value)
            case "event": eventType = value
            default: break // id/retry/extension fields never cause a reconnect.
            }
        }

        func finish() throws {
            // SSE only dispatches at a blank line; EOF is not a synthetic boundary.
            guard accumulator.done else { throw APIParsing.failure("stream ended without DONE event") }
            guard accumulator.firstAtMs != nil else { throw APIParsing.failure("stream contained no generated output") }
        }
    }

    private struct Measurement: Sendable {
        let accumulator: Accumulator
        let endMs: Int64
    }

    static func run(instance: Instance, model: String) async -> StreamProbeResult {
        await run(instance: instance, model: model, configuration: NetworkPolicy.configuration(), deadlineNanoseconds: 30_000_000_000)
    }

    // Internal seam for local URLProtocol tests; public entry always uses 30 seconds.
    static func run(instance: Instance, model: String, configuration: URLSessionConfiguration,
                    deadlineNanoseconds: UInt64) async -> StreamProbeResult {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let startUptime = ProcessInfo.processInfo.systemUptime
        let cleanModel = model.trimmingCharacters(in: .whitespacesAndNewlines)
        func elapsedMs() -> Int64 { Int64((ProcessInfo.processInfo.systemUptime - startUptime) * 1000) }
        do {
            try Task.checkCancellation()
            let key = try NetworkPolicy.credential(instance.apiKey)
            guard !cleanModel.isEmpty, cleanModel.count <= 150 else { throw APIParsing.failure("model name") }
            let base = try NetworkPolicy.baseURL(instance.baseUrl)
            let url = try NetworkPolicy.endpoint(base: base, path: "/v1/chat/completions")
            let payload: [String: Any] = [
                "model": cleanModel, "max_tokens": 64, "stream": true,
                "stream_options": ["include_usage": true],
                "messages": [["role": "user", "content": "Count from 1 to 10."]]
            ]
            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
            request.httpMethod = "POST"
            request.httpShouldHandleCookies = false
            request.httpBody = try JSONSerialization.data(withJSONObject: payload)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
            request.setValue(instance.authStyleKey == .raw ? key : "Bearer \(key)", forHTTPHeaderField: "Authorization")
            let session = NetworkPolicy.session(configuration: configuration)
            defer { session.invalidateAndCancel() }
            let probeRequest = request
            let measurement = try await withTaskCancellationHandler {
                try await withThrowingTaskGroup(of: Measurement.self) { group in
                    group.addTask {
                        try await measure(session: session, request: probeRequest, startUptime: startUptime)
                    }
                    group.addTask {
                        try await Task.sleep(nanoseconds: deadlineNanoseconds)
                        // A wall-clock deadline, not a reset-on-byte idle timeout.
                        session.invalidateAndCancel()
                        throw AppError(kind: .network, message: "Stream exceeded its total deadline")
                    }
                    defer { group.cancelAll(); session.invalidateAndCancel() }
                    guard let result = try await group.next() else { throw CancellationError() }
                    return result
                }
            } onCancel: {
                session.invalidateAndCancel()
            }
            try Task.checkCancellation()
            return StreamProbeResult(sourceId: instance.id, sourceName: instance.name, model: cleanModel,
                                     ok: true, firstTokenMs: measurement.accumulator.firstAtMs,
                                     totalMs: measurement.endMs, completionTokens: measurement.accumulator.tokens,
                                     tokensPerSecond: measurement.accumulator.rate(endMs: measurement.endMs),
                                     failure: nil, timestamp: nowMs)
        } catch {
            let failure: String
            if Task.isCancelled { failure = "Probe cancelled" }
            else if let appError = error as? AppError { failure = appError.message }
            else { failure = "Connection failed, timed out, or was cancelled" }
            return StreamProbeResult(sourceId: instance.id, sourceName: instance.name, model: cleanModel,
                                     ok: false, firstTokenMs: nil, totalMs: elapsedMs(), completionTokens: nil,
                                     tokensPerSecond: nil, failure: failure, timestamp: nowMs)
        }
    }

    private static func measure(session: URLSession, request: URLRequest, startUptime: TimeInterval) async throws -> Measurement {
        let (bytes, response) = try await session.bytes(for: request)
        defer { bytes.task.cancel() }
        guard let http = response as? HTTPURLResponse else { throw APIParsing.failure("HTTP response") }
        guard (200...299).contains(http.statusCode) else {
            throw AppError(kind: [401, 403].contains(http.statusCode) ? .auth : .http,
                           message: "HTTP \(http.statusCode)", status: http.statusCode)
        }
        let mime = http.value(forHTTPHeaderField: "Content-Type")?.split(separator: ";").first?
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard mime == "text/event-stream" else { throw APIParsing.failure("text/event-stream Content-Type") }
        var decoder = EventDecoder()
        for try await byte in bytes {
            try Task.checkCancellation()
            let elapsed = Int64((ProcessInfo.processInfo.systemUptime - startUptime) * 1000)
            try decoder.feed(byte, elapsedMs: elapsed)
            if decoder.accumulator.done { break }
        }
        try Task.checkCancellation()
        try decoder.finish()
        return Measurement(accumulator: decoder.accumulator,
                           endMs: Int64((ProcessInfo.processInfo.systemUptime - startUptime) * 1000))
    }
}
