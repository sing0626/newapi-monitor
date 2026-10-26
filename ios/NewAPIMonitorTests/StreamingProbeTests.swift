import XCTest
@testable import NewAPIMonitor

final class StreamingProbeTests: XCTestCase {
    private let textEvent = "data: {\"choices\":[{\"delta\":{\"content\":\"discard me\"}}]}\n\n"
    private let doneEvent = "data: [DONE]\n\n"

    func testMultilineDispatchCRLFCommentsAndUsage() throws {
        var decoder = StreamingProbe.EventDecoder()
        let stream = ": heartbeat\r\nevent: message\r\ndata: {\"choices\":\r\ndata: [{\"delta\":{\"reasoning_content\":\"discard me\"}}]}\r\n\r\n"
        for byte in stream.utf8 { try decoder.feed(byte, elapsedMs: 100) }
        XCTAssertEqual(decoder.accumulator.firstAtMs, 100)
        let usage = "data: {\"choices\":[],\"usage\":{\"completion_tokens\":6}}\r\n\r\ndata: [DONE]\r\n\r\n"
        for byte in usage.utf8 { try decoder.feed(byte, elapsedMs: 600) }
        try decoder.finish()
        XCTAssertEqual(decoder.accumulator.tokens, 6)
        XCTAssertEqual(decoder.accumulator.rate(endMs: 600), 10)
    }

    func testEventNotDispatchedUntilBlankLine() throws {
        var decoder = StreamingProbe.EventDecoder()
        for byte in "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n".utf8 {
            try decoder.feed(byte, elapsedMs: 1)
        }
        XCTAssertNil(decoder.accumulator.firstAtMs)
        try decoder.feed(10, elapsedMs: 2)
        XCTAssertEqual(decoder.accumulator.firstAtMs, 2)
        XCTAssertThrowsError(try decoder.finish())
    }

    func testMalformedEventsAndInvalidUTF8AreErrors() throws {
        for invalid in ["not json", "{}", "{\"choices\":[]}", "{\"choices\":\"bad\"}", "{\"choices\":[{}]}", "{\"choices\":[{\"delta\":{\"content\":7}}]}", "{\"usage\":{\"completion_tokens\":true}}", "{\"error\":{\"message\":\"secret\"}}", ""] {
            var accumulator = StreamingProbe.Accumulator()
            XCTAssertThrowsError(try accumulator.processEvent(invalid, elapsedMs: 0), invalid)
        }
        var decoder = StreamingProbe.EventDecoder()
        try decoder.feed(0xFF, elapsedMs: 0)
        XCTAssertThrowsError(try decoder.feed(10, elapsedMs: 0))
    }

    func testLineEventAndTotalLimits() throws {
        var lineDecoder = StreamingProbe.EventDecoder()
        for _ in 0..<StreamingProbe.EventDecoder.maxLineBytes { try lineDecoder.feed(65, elapsedMs: 0) }
        XCTAssertThrowsError(try lineDecoder.feed(65, elapsedMs: 0))

        var eventDecoder = StreamingProbe.EventDecoder()
        let line = "data: " + String(repeating: "x", count: 60_000) + "\n"
        XCTAssertThrowsError(try feed(String(repeating: line, count: 5), into: &eventDecoder))

        var totalDecoder = StreamingProbe.EventDecoder()
        let comment = ":" + String(repeating: "x", count: 60_000) + "\n"
        XCTAssertThrowsError(try feed(String(repeating: comment, count: 36), into: &totalDecoder))

        var linesDecoder = StreamingProbe.EventDecoder()
        XCTAssertThrowsError(try feed(String(repeating: ":\n", count: StreamingProbe.EventDecoder.maxLines + 1), into: &linesDecoder))
    }

    func testDONEWithoutOutputAndUnterminatedEventFail() throws {
        var decoder = StreamingProbe.EventDecoder()
        try feed(doneEvent, into: &decoder)
        XCTAssertThrowsError(try decoder.finish())
        var truncated = StreamingProbe.EventDecoder()
        try feed(textEvent + "data: [DONE]\n", into: &truncated)
        XCTAssertThrowsError(try truncated.finish())
    }

    func testCRAndBOMAndNullUsage() throws {
        var decoder = StreamingProbe.EventDecoder()
        let stream = "\u{FEFF}data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}],\"usage\":null}\r\r" + textEvent + doneEvent
        try feed(stream, into: &decoder)
        try decoder.finish()
        XCTAssertNil(decoder.accumulator.tokens)
        XCTAssertNil(decoder.accumulator.rate(endMs: 100))
    }

    func testPaid401NeverRetries() async {
        let transport = TestTransport { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer key")
            return .stream("", status: 401)
        }
        defer { transport.unregister() }
        let result = await StreamingProbe.run(instance: transport.instance, model: "model",
                                               configuration: transport.configuration, deadlineNanoseconds: 1_000_000_000)
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.failure, "HTTP 401")
        XCTAssertEqual(transport.requests.count, 1)
    }

    func testSuccessfulProbeDiscardsTextAndUsesUsage() async {
        let stream = textEvent + "data: {\"choices\":[],\"usage\":{\"completion_tokens\":6}}\n\n" + doneEvent
        let transport = TestTransport { _ in .stream(stream) }
        defer { transport.unregister() }
        let result = await StreamingProbe.run(instance: transport.instance, model: "model",
                                               configuration: transport.configuration, deadlineNanoseconds: 1_000_000_000)
        XCTAssertTrue(result.ok)
        XCTAssertNotNil(result.firstTokenMs)
        XCTAssertEqual(result.completionTokens, 6)
        XCTAssertNil(result.failure)
        XCTAssertEqual(transport.requests.count, 1)
    }

    func testStalledProbeHasTotalDeadline() async {
        let transport = TestTransport { _ in .stream(": waiting\n", stall: true) }
        defer { transport.unregister() }
        let result = await StreamingProbe.run(instance: transport.instance, model: "model",
                                               configuration: transport.configuration, deadlineNanoseconds: 100_000_000)
        XCTAssertFalse(result.ok)
        XCTAssertLessThan(result.totalMs, 3000)
        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertGreaterThan(transport.stopCount, 0)
    }

    func testCancellationStopsUnderlyingProbe() async throws {
        let transport = TestTransport { _ in .stream(": waiting\n", stall: true) }
        defer { transport.unregister() }
        let task = Task {
            await StreamingProbe.run(instance: transport.instance, model: "model",
                                     configuration: transport.configuration, deadlineNanoseconds: 30_000_000_000)
        }
        for _ in 0..<100 {
            if !transport.requests.isEmpty { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        task.cancel()
        let result = await task.value
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.failure, "Probe cancelled")
        XCTAssertLessThan(result.totalMs, 3000)
    }

    private func feed(_ value: String, into decoder: inout StreamingProbe.EventDecoder) throws {
        for byte in value.utf8 { try decoder.feed(byte, elapsedMs: 10) }
    }
}
