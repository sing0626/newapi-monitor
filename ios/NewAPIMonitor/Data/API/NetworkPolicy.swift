import Foundation

/// Shared by all credential-bearing requests. HTTP is accepted for explicit local
/// development configurations; the v1 UI should require HTTPS for real instances.
enum NetworkPolicy {
    static func baseURL(_ raw: String) throws -> URL {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value.count <= 2048,
              !value.contains("\\"),
              !value.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0) }),
              var parts = URLComponents(string: value),
              let scheme = parts.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil,
              parts.port.map({ (1...65535).contains($0) }) ?? true else {
            throw AppError(kind: .network, message: "Invalid HTTP(S) Base URL; HTTPS is recommended")
        }
        // Do not allow ambiguous path normalization at proxies or URLSession.
        let path = parts.percentEncodedPath
        guard !path.contains("%"), !path.contains("//"),
              !path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) else {
            throw AppError(kind: .network, message: "Invalid Base URL path")
        }
        parts.scheme = scheme
        while parts.path.hasSuffix("/") { parts.path.removeLast() }
        guard let url = parts.url else {
            throw AppError(kind: .network, message: "Invalid Base URL")
        }
        return url
    }

    static func endpoint(base: URL, path: String, query: [URLQueryItem] = []) throws -> URL {
        guard path.hasPrefix("/"), !path.hasPrefix("//"),
              !path.contains("?"), !path.contains("#"), !path.contains("\\"),
              !path.contains("%"),
              !path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }),
              var parts = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            throw AppError(kind: .network, message: "Invalid API endpoint")
        }
        parts.path += path
        parts.queryItems = query.isEmpty ? nil : query
        guard let url = parts.url, url.scheme == base.scheme,
              url.host == base.host, url.port == base.port else {
            throw AppError(kind: .network, message: "API endpoint changed origin")
        }
        return url
    }

    static func credential(_ raw: String) throws -> String {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty,
              !value.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0) }) else {
            throw AppError(kind: .auth, message: "Missing or invalid credential")
        }
        return value
    }

    static func configuration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.urlCredentialStorage = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 30
        return config
    }

    static func session(configuration: URLSessionConfiguration? = nil) -> URLSession {
        URLSession(configuration: configuration ?? self.configuration(), delegate: RejectRedirects(), delegateQueue: nil)
    }
}

final class RejectRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        // Reject even same-origin redirects: credentials never follow a Location.
        completionHandler(nil)
    }
}
