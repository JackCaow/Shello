import Foundation

/// Tab identity includes its remote target; multiple shells on one host stay independent.
public enum SessionTarget {
    public static let tmuxLookup = "if command -v tmux >/dev/null 2>&1; then T=tmux; elif [ -x /opt/homebrew/bin/tmux ]; then T=/opt/homebrew/bin/tmux; elif [ -x /usr/local/bin/tmux ]; then T=/usr/local/bin/tmux; else exit 127; fi; "

    public static func profile(of id: String) -> String { id.firstIndex(of: "@").map { String(id[..<$0]) } ?? id }
    public static func target(of id: String) -> String? {
        guard let at = id.firstIndex(of: "@") else { return nil }
        return decodeBase64URL(String(id[id.index(after: at)...]))
    }
    public static func id(profile: String, target: String) throws -> String {
        try validate(target)
        return profile + "@" + encodeBase64URL(Data(target.utf8))
    }
    public static func validate(_ target: String?) throws {
        guard let t = target, t.count <= 180, !t.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              (t.hasPrefix("=") && t.count > 1) || t.range(of: "^ghostty_[0-9a-f]{32}$", options: .regularExpression) != nil
              || t.range(of: "^plain_[0-9a-f]{32}$", options: .regularExpression) != nil || isAgent(t)
        else { throw ShelloError.invalid("Invalid session target") }
    }
    public static func fresh(tmux: Bool) -> String {
        (tmux ? "ghostty_" : "plain_") + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }
    public static func isAgent(_ t: String?) -> Bool {
        t?.range(of: "^agent:(claude|codex):[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$", options: .regularExpression) != nil
    }
    /// Command run on the exec channel for a target.
    public static func command(_ target: String) throws -> String {
        try validate(target)
        if isAgent(target) {
            let parts = target.split(separator: ":").map(String.init)
            return AgentCommand.base + "resume " + ShellQuote.quote(parts[1]) + " " + ShellQuote.quote(parts[2])
        }
        // -u forces UTF-8; override a NO_COLOR inherited by a long-lived tmux server only when creating the session.
        return tmuxLookup + "exec \"$T\" -u " + (target.hasPrefix("=")
            ? "attach-session -t " + ShellQuote.quote(target)
            : "new-session -A -s " + ShellQuote.quote(target) + " -e NO_COLOR= -e COLORTERM=truecolor")
    }
    static func encodeBase64URL(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func decodeBase64URL(_ s: String) -> String? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b).flatMap { String(data: $0, encoding: .utf8) }
    }
}

public enum ShelloError: Error, Equatable, LocalizedError {
    case invalid(String), failed(String)
    public var errorDescription: String? { switch self { case .invalid(let m), .failed(let m): return m } }
}

public enum ShellQuote {
    public static func quote(_ value: String) -> String { "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'" }
}

public enum TmuxSessions {
    public struct Entry: Equatable { public let name: String; public let windows: Int; public let attached: Int }
    /// UTF-8 output is required even when an exec channel has no UTF-8 locale; numeric fields first keep names with spaces intact.
    public static let command = SessionTarget.tmuxLookup +
        "\"$T\" -u list-sessions -F '#{session_windows} #{session_attached} #{session_name}' 2>/dev/null || { r=$?; [ $r = 1 ] || exit $r; }"
    public static func parse(_ output: String) throws -> [Entry] {
        var entries = [Entry]()
        for line in output.split(separator: "\n", omittingEmptySubsequences: true) {
            let f = line.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: false)
            guard f.count == 3, let w = Int(f[0]), let a = Int(f[1]), f[0].allSatisfy(\.isNumber), f[1].allSatisfy(\.isNumber),
                  (try? SessionTarget.validate("=" + f[2])) != nil else { throw ShelloError.invalid("Invalid tmux session listing") }
            entries.append(Entry(name: String(f[2]), windows: w, attached: a))
        }
        return entries
    }
}

public enum NetworkAddress {
    public static func ipv4(_ host: String?) -> [Int]? {
        guard let host, host.range(of: "^[0-9]{1,3}(\\.[0-9]{1,3}){3}$", options: .regularExpression) != nil else { return nil }
        var out = [Int]()
        for p in host.split(separator: ".") { if p.count > 1 && p.hasPrefix("0") { return nil }; guard let n = Int(p), n <= 255 else { return nil }; out.append(n) }
        return out
    }
    public static func isTailscale(_ host: String) -> Bool { guard let a = ipv4(host) else { return false }; return a[0] == 100 && (64...127).contains(a[1]) }
    public static func isPairingAddress(_ host: String) -> Bool {
        guard let a = ipv4(host) else { return false }
        return a[0] == 10 || (a[0] == 172 && (16...31).contains(a[1])) || (a[0] == 192 && a[1] == 168) || a[0] == 127 || isTailscale(host)
    }
}

public enum AgentCommand {
    /// Same remote script location and command as the Android app, so one host serves both.
    public static let base = "python3 \"$HOME/.local/state/ghostty-android/ghostty_hook.py\" "
}
