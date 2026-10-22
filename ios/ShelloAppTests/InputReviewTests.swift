import XCTest
import SwiftUI
import GameController
import ShelloTerminalUI
import ShelloCore
import ShelloServices
@testable import Shello

@MainActor
final class InputReviewTests: XCTestCase {
    private func environment(keyboard: HardwareKeyboardMonitor? = nil) -> AppEnvironment {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        return AppEnvironment(profileDirectory: directory, defaults: UserDefaults(suiteName: UUID().uuidString)!, secrets: MemorySecretStore(), hardwareKeyboard: keyboard)
    }
    func testRemovedAttachmentDoesNotRemainInSubmittedText() {
        let draft = "Please inspect"
        let paths = ["/tmp/first file.pdf", "/tmp/second's notes.docx"]
        let complete = DraftSubmission.payload(text: draft, paths: paths)
        XCTAssertTrue(complete?.contains("first file.pdf") == true)
        let removed = DraftSubmission.payload(text: draft, paths: [paths[1]])
        XCTAssertFalse(removed?.contains("first file.pdf") == true)
        XCTAssertEqual(removed, draft + " " + ShellQuote.quote(paths[1]))
        XCTAssertEqual(DraftSubmission.payload(text: draft, paths: []), draft)
    }
    func testPendingOrFailedAttachmentsBlockSubmissionAndEmptyDraftIsNotEnter() {
        XCTAssertNil(DraftSubmission.payload(text: "text", paths: [nil]))
        XCTAssertNil(DraftSubmission.payload(text: "", paths: []))
        XCTAssertNil(DraftSubmission.payload(text: "  \n", paths: []))
        XCTAssertEqual(DraftSubmission.payload(text: "", paths: ["/tmp/hello world.png"]), ShellQuote.quote("/tmp/hello world.png"))
        XCTAssertEqual(DraftSubmission.payload(text: "line\n", paths: ["/tmp/a"]), "line\n" + ShellQuote.quote("/tmp/a"))
    }
    func testAttachmentTypesAndBundledAgentIcons() {
        for (name, kind) in [("Notes.DOCX", AttachmentKind.word), ("slides.pptx", .presentation), ("budget.csv", .spreadsheet), ("memo.m4a", .audio), ("doc.pdf", .pdf), ("sample.png", .image), ("movie.mp4", .video), ("code.swift", .code), ("bundle.zip", .archive), ("README.txt", .text), ("mystery.zzzz", .other)] {
            XCTAssertEqual(AttachmentKind.resolve(name), kind, name)
        }
        XCTAssertNotNil(UIImage(named: "agent_claude")); XCTAssertNotNil(UIImage(named: "agent_openai"))
        XCTAssertNotEqual(AttachmentKind.word.symbol, AttachmentKind.presentation.symbol)
        XCTAssertNotEqual(AttachmentKind.word.symbol, AttachmentKind.audio.symbol)
    }
    func testTemporaryThumbnailSourceIsRetainedUntilRemoval() throws {
        let env = environment()
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".txt")
        try Data("preview".utf8).write(to: url)
        let transfer = AttachmentTransfer(profileID: "unused", store: env.store, source: url)
        let item = ComposerAttachment(tabID: "tab", transfer: transfer, ownsSource: true)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        item.discard()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        let external = url.appendingPathExtension("external")
        try Data("keep".utf8).write(to: external)
        defer { try? FileManager.default.removeItem(at: external) }
        ComposerAttachment(tabID: "tab", transfer: AttachmentTransfer(profileID: "unused", store: env.store, source: external), ownsSource: false).discard()
        XCTAssertTrue(FileManager.default.fileExists(atPath: external.path))
    }
    func testHardwareConnectDisconnectAndHideSettingAreObserved() async {
        let center = NotificationCenter()
        var connected = false
        let keyboard = HardwareKeyboardMonitor(center: center, readConnection: { connected })
        let env = environment(keyboard: keyboard)
        let controller = TerminalController(env: env)
        env.prefs.setFlag("hide_with_hardware", true)
        XCTAssertFalse(controller.hideTouchControls)
        connected = true; center.post(name: .GCKeyboardDidConnect, object: nil)
        await fulfillment(of: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in keyboard.connected }, object: nil)], timeout: 2)
        XCTAssertTrue(controller.hideTouchControls)
        env.prefs.setFlag("hide_with_hardware", false)
        XCTAssertFalse(controller.hideTouchControls)
        env.prefs.setFlag("hide_with_hardware", true)
        connected = false; center.post(name: .GCKeyboardDidDisconnect, object: nil)
        await fulfillment(of: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !keyboard.connected }, object: nil)], timeout: 2)
        XCTAssertFalse(controller.hideTouchControls)
    }
    func testAttachmentsSurviveControllerRecreationAndStayWithTheirTab() throws {
        let env = environment()
        let p = SSHProfile(name: "fixture", host: "127.0.0.1", user: "test")
        let first = try env.sessions.open(profile: p, target: SessionTarget.fresh(tmux: false), connect: false)
        let second = try env.sessions.open(profile: p, target: SessionTarget.fresh(tmux: false), connect: false)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("unused.txt")
        let one = ComposerAttachment(tabID: first.id, transfer: AttachmentTransfer(profileID: p.id, store: env.store, source: url), ownsSource: false)
        let two = ComposerAttachment(tabID: second.id, transfer: AttachmentTransfer(profileID: p.id, store: env.store, source: url), ownsSource: false)
        env.composerAttachments = [one, two]
        var controller: TerminalController? = TerminalController(env: env)
        XCTAssertEqual(controller?.currentAttachments.map(\.id), [two.id])
        controller = nil
        let reopened = TerminalController(env: env)
        XCTAssertEqual(reopened.currentAttachments.map(\.id), [two.id])
        env.sessions.select(first.id)
        XCTAssertEqual(reopened.currentAttachments.map(\.id), [one.id])
        reopened.remove(one)
        XCTAssertEqual(env.composerAttachments.map(\.id), [two.id])
        env.sessions.close(second)
        XCTAssertTrue(env.composerAttachments.isEmpty)
        env.sessions.closeAll()
    }
    func testUploadedAttachmentRemovalChangesActualSubmission() async throws {
        let vars = ProcessInfo.processInfo.environment
        guard let directory = vars["SHELLO_SSHD_DIR"] else { throw XCTSkip("SHELLO_SSHD_DIR required for real SFTP regression") }
        let dir = URL(fileURLWithPath: directory), port = Int(vars["SHELLO_SSHD_PORT"] ?? "2299") ?? 2299
        let key = try Data(contentsOf: dir.appendingPathComponent("user_ed25519"))
        let fields = try String(contentsOf: dir.appendingPathComponent("host_ed25519.pub")).split(separator: " ")
        let env = environment()
        let profile = SSHProfile(name: "review fixture", host: "127.0.0.1", port: port, user: vars["SHELLO_SSHD_USER"] ?? NSUserName(), auth: .key, tmux: false)
        try env.store.save(profile, secrets: SSHSecrets(privateKey: key, knownHosts: Data("[127.0.0.1]:\(port) \(fields[0]) \(fields[1])\n".utf8)))
        let tab = try env.sessions.open(profile: profile)
        defer { env.sessions.closeAll() }
        await fulfillment(of: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in tab.isConnected }, object: nil)], timeout: 20)
        guard tab.isConnected else { return }
        let controller = TerminalController(env: env)
        let view = TerminalView(frame: CGRect(x: 0, y: 0, width: 390, height: 500))
        view.setSession(tab.session); controller.view = view
        var sent = Data(); view.onInput = { sent.append($0) }
        tab.draft = "echo safe"
        let sources = try ["Notes.docx", "Slides.pptx", "Audio.m4a", "Photo.png"].map { name -> URL in
            let source = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + name)
            let data: Data
            if name.hasSuffix("png") {
                data = UIGraphicsImageRenderer(size: CGSize(width: 96, height: 96)).image { context in
                    UIColor.systemTeal.setFill(); context.fill(CGRect(x: 0, y: 0, width: 96, height: 96))
                    UIColor.systemYellow.setFill(); context.cgContext.fillEllipse(in: CGRect(x: 20, y: 20, width: 56, height: 56))
                }.pngData()!
            } else { data = Data("harmless UI fixture".utf8) }
            try data.write(to: source); controller.attach(url: source, temporary: true); return source
        }
        let items = controller.currentAttachments
        XCTAssertEqual(items.count, 4)
        await fulfillment(of: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in items.allSatisfy { $0.transfer.remotePath != nil } }, object: nil)], timeout: 20)
        XCTAssertTrue(items.allSatisfy { $0.transfer.remotePath != nil })
        XCTAssertEqual(tab.draft, "echo safe", "upload must not inject a path into editable text")
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            let window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0, y: 0, width: 393, height: 320)
            for (i, theme) in [Theme.shello, .lightTheme].enumerated() {
                env.settings.theme = theme.id
                let composer = Composer(tab: tab, controller: controller, importing: .constant(false), showPhotos: .constant(false), showCamera: .constant(false))
                let root = composer.environmentObject(env).environmentObject(env.sessions).themed(theme).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom).background(theme.terminalSheet)
                let host = UIHostingController(rootView: root); window.rootViewController = host; window.makeKeyAndVisible()
                host.view.layoutIfNeeded(); try await Task.sleep(nanoseconds: 1_000_000_000)
                let image = UIGraphicsImageRenderer(bounds: host.view.bounds).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
                let shot = XCTAttachment(image: image); shot.name = "0\(i + 1)-review-attachments-\(theme.id)"; shot.lifetime = .keepAlways; add(shot)
            }
            window.isHidden = true
        }
        for item in items { controller.remove(item) }
        controller.sendDraft(enter: true)
        let text = String(decoding: sent, as: UTF8.self)
        XCTAssertTrue(text.contains("echo safe"))
        XCTAssertFalse(text.contains(".local/share/shello/attachments"))
        XCTAssertTrue(controller.currentAttachments.isEmpty)
        XCTAssertTrue(sources.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
    }
    func testLargeTextExpandsSettingsRowsInsteadOfClipping() {
        let row = SettingRow(title: "Automatically hide the toolbar", subtitle: "Keep touch controls hidden while a physical keyboard is connected.")
        let normal = UIHostingController(rootView: row.themed(.lightTheme).environment(\.dynamicTypeSize, .large))
        let large = UIHostingController(rootView: row.themed(.lightTheme).environment(\.dynamicTypeSize, .accessibility5))
        let constraint = CGSize(width: 320, height: 2000)
        let a = normal.sizeThatFits(in: constraint), b = large.sizeThatFits(in: constraint)
        XCTAssertGreaterThan(b.height, a.height * 1.5)
        XCTAssertLessThanOrEqual(b.width, 320)
    }
    func testLightPreviewHasReadableContrast() {
        let fg = UIColor(Theme.lightTheme.previewText), bg = UIColor(Theme.lightTheme.terminalSheet)
        func luminance(_ color: UIColor) -> CGFloat {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            color.getRed(&r, green: &g, blue: &b, alpha: &a)
            func linear(_ c: CGFloat) -> CGFloat { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
            return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
        }
        XCTAssertGreaterThan((luminance(bg) + 0.05) / (luminance(fg) + 0.05), 4.5)
    }
}
