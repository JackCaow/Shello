import SwiftUI
import Combine
import GameController
import UniformTypeIdentifiers
import QuickLookThumbnailing
import ImageIO
import ShelloCore
import ShelloServices

/// Attachment references stay separate from editable text until submission.
enum DraftSubmission {
    static func payload(text: String, paths: [String?]) -> String? {
        guard paths.allSatisfy({ $0 != nil }) else { return nil }
        let files = paths.compactMap { $0 }.map(ShellQuote.quote).joined(separator: " ")
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !files.isEmpty else { return nil }
        return text + (files.isEmpty || text.isEmpty || text.last?.isWhitespace == true ? "" : " ") + files
    }
}

@MainActor
final class ComposerAttachment: ObservableObject, Identifiable {
    let tabID: String
    let transfer: AttachmentTransfer
    let ownsSource: Bool
    nonisolated let id: UUID
    private let source: URL
    @Published private(set) var thumbnail: UIImage?
    private var request: QLThumbnailGenerator.Request?
    private var discarded = false

    init(tabID: String, transfer: AttachmentTransfer, ownsSource: Bool) {
        self.tabID = tabID; self.transfer = transfer; self.ownsSource = ownsSource
        id = transfer.id; source = transfer.source
    }
    func loadThumbnail() async {
        guard thumbnail == nil, !discarded else { return }
        let kind = AttachmentKind.resolve(transfer.name)
        guard [.image, .video, .pdf, .word, .presentation, .spreadsheet].contains(kind) else { return }
        let url = transfer.source
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        if kind == .image {
            let image = await Task.detached(priority: .userInitiated) {
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 192, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { return UIImage?.none }
                return UIImage(cgImage: image)
            }.value
            if let image, !discarded, !Task.isCancelled { thumbnail = image; return }
        }
        let r = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: 64, height: 64), scale: UIScreen.main.scale, representationTypes: .thumbnail)
        request = r
        let image: UIImage? = await withCheckedContinuation { continuation in
            QLThumbnailGenerator.shared.generateBestRepresentation(for: r) { representation, _ in
                continuation.resume(returning: representation?.uiImage)
            }
        }
        request = nil
        if !discarded, !Task.isCancelled { thumbnail = image }
    }
    func discard() {
        discarded = true
        if let request { QLThumbnailGenerator.shared.cancel(request) }
        transfer.cancel()
        if ownsSource { try? FileManager.default.removeItem(at: transfer.source) }
    }
    deinit {
        let transfer = transfer
        Task { @MainActor in transfer.cancel() }
        if ownsSource { try? FileManager.default.removeItem(at: source) }
    }
}

enum AttachmentKind: String, CaseIterable {
    case word, presentation, spreadsheet, pdf, audio, archive, code, text, image, video, other
    static func resolve(_ name: String) -> AttachmentKind {
        let ext = (name as NSString).pathExtension.lowercased()
        let groups: [(AttachmentKind, [String])] = [
            (.word, ["doc", "docx", "docm", "dot", "dotx", "odt", "rtf", "pages"]),
            (.presentation, ["ppt", "pptx", "pptm", "pps", "ppsx", "odp", "key"]),
            (.spreadsheet, ["xls", "xlsx", "xlsm", "xlsb", "ods", "csv", "tsv", "numbers"]),
            (.pdf, ["pdf"]), (.audio, ["mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "aiff"]),
            (.archive, ["zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst"]),
            (.code, ["json", "xml", "js", "ts", "tsx", "jsx", "swift", "kt", "java", "py", "sh", "c", "h", "cpp", "css", "html", "yaml", "yml"])
        ]
        if let kind = groups.first(where: { $0.1.contains(ext) })?.0 { return kind }
        guard let type = UTType(filenameExtension: ext) else { return .other }
        if type.conforms(to: .image) { return .image }
        if type.conforms(to: .movie) { return .video }
        if type.conforms(to: .audio) { return .audio }
        if type.conforms(to: .text) { return .text }
        return .other
    }
    var symbol: String {
        switch self {
        case .word: return "doc.text"
        case .presentation: return "rectangle.on.rectangle"
        case .spreadsheet: return "tablecells"
        case .pdf: return "doc.richtext"
        case .audio: return "waveform"
        case .archive: return "doc.zipper"
        case .code: return "chevron.left.forwardslash.chevron.right"
        case .text: return "doc.plaintext"
        case .image: return "photo"
        case .video: return "film"
        case .other: return "doc"
        }
    }
    var color: Color {
        switch self {
        case .word: return .blue
        case .presentation: return .orange
        case .spreadsheet: return .green
        case .pdf: return .red
        case .audio: return .purple
        case .archive: return .brown
        case .code: return .cyan
        case .text, .other: return .secondary
        case .image: return .indigo
        case .video: return .pink
        }
    }
    var localizationKey: String { "attachment_type_" + rawValue }
    var label: String { tr(localizationKey) }
}

/// Live updates for both Bluetooth and USB keyboards, including a keyboard already connected at launch.
@MainActor
final class HardwareKeyboardMonitor: ObservableObject {
    @Published private(set) var connected: Bool
    private var subscription: AnyCancellable?
    init(center: NotificationCenter = .default, readConnection: @escaping () -> Bool = { GCKeyboard.coalesced != nil }) {
        connected = readConnection()
        subscription = Publishers.Merge(center.publisher(for: .GCKeyboardDidConnect), center.publisher(for: .GCKeyboardDidDisconnect))
            .receive(on: RunLoop.main).sink { [weak self] _ in self?.connected = readConnection() }
    }
}

/// The preview and real terminal share the same scrolling lane and pinned buttons.
struct ToolbarStrip<Key: View>: View {
    @Environment(\.theme) private var theme
    let ids: [String]
    let key: (String) -> Key
    var body: some View {
        HStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(ids.filter { $0 != "chat" && $0 != "keyboard" }, id: \.self) { key($0) }
                }.padding(.leading, 6).padding(.trailing, 24)
            }.fadingTrailingEdge().accessibilityElement(children: .contain).accessibilityIdentifier("toolbar.scrollable")
            Rectangle().fill(theme.separator).frame(width: 1, height: 18).padding(.horizontal, 4)
            HStack(spacing: 2) { key("chat"); key("keyboard") }.padding(.trailing, 4).accessibilityElement(children: .contain).accessibilityIdentifier("toolbar.pinned")
        }
        .padding(.vertical, 8).padding(.horizontal, 4)
        .background(theme.chrome, in: RoundedRectangle(cornerRadius: 32))
    }
}

struct ToolKeyFace: View {
    @Environment(\.theme) private var theme
    let id: String
    let prefs: InputPreferences
    var active = false
    @ScaledMetric(relativeTo: .body) private var size = 48.0
    private var bare: Bool { ["history", "chat", "keyboard"].contains(id) }
    private var icon: String? {
        InputText.icon(for: id) ?? (prefs.flag("modifier_icons", default: false) ? ["ctrl": "control", "alt": "option", "shift": "shift", "super": "command"][id] : nil)
    }
    var body: some View {
        Group {
            if let icon { Image(systemName: icon).font(.system(size: id == "keyboard" ? 22 : 19)) }
            else { Text(InputText.label(for: id, prefs)).appFont(size: 15, design: .monospaced).lineLimit(1).padding(.horizontal, 4) }
        }
        .frame(minWidth: size, minHeight: size).padding(.horizontal, bare ? 0 : 4)
        .background(active && !bare ? theme.accent : (bare ? Color.clear : theme.key), in: Capsule())
        .foregroundColor(active ? (bare ? theme.accent : theme.onAccent) : theme.text)
    }
}

struct ShortcutGroupIcon: View {
    let group: String
    var body: some View {
        Group {
            switch group {
            case "Claude": Image("agent_claude").resizable().scaledToFit()
            case "Codex": Image("agent_openai").resizable().scaledToFit()
            default: Image(systemName: symbol).resizable().scaledToFit()
            }
        }.frame(width: 22, height: 22).accessibilityHidden(true)
    }
    private var symbol: String {
        switch group {
        case "Favorites": return "star"
        case "Tmux": return "rectangle.split.2x2"
        case "Ctrl": return "control"
        case "Copilot": return "sparkles"
        case "Cursor": return "cursorarrow"
        case "Zellij": return "square.grid.2x2"
        default: return "terminal"
        }
    }
}
