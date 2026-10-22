import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import WebKit
import ShelloCore
import ShelloServices
import ShelloTerminal
import ShelloTerminalUI

struct TerminalScreen: View {
    @EnvironmentObject var env: AppEnvironment
    @EnvironmentObject var sessions: SessionManager
    @Environment(\.theme) var theme
    @StateObject private var controller: TerminalController
    @State private var importing = false
    @State private var photoItems = [PhotosPickerItem]()
    @State private var showPhotos = false
    @State private var showCamera = false
    @State private var confirmClose = false

    init(env: AppEnvironment) { _controller = StateObject(wrappedValue: TerminalController(env: env)) }

    var body: some View {
        VStack(spacing: 0) {
            header
            ZStack {
                TerminalHost(controller: controller)
                if let t = sessions.selected { StatusOverlay(tab: t, controller: controller) }
                if controller.showPad { DirectionPad(controller: controller) }
                if controller.zenCenter != nil { ZenRingView(controller: controller) }
                if !controller.selection.isEmpty { VStack { Spacer(); SelectionBar(controller: controller).padding(8) } }
            }
            .clipped()
            if controller.showShortcuts && !controller.hideTouchControls { ShortcutPanel(controller: controller) }
            if let t = sessions.selected, t.draftMode { Composer(tab: t, controller: controller, importing: $importing, showPhotos: $showPhotos, showCamera: $showCamera) }
            else if !controller.hideTouchControls {
                if controller.minimized { MinimizedBar(controller: controller) } else { InputToolbar(controller: controller) }
            }
        }
        .background(theme.terminalSheet.ignoresSafeArea(edges: .bottom))
        .clipShape(UnevenRoundedRectangle(topLeadingRadius: 28, bottomLeadingRadius: 0, bottomTrailingRadius: 0, topTrailingRadius: 28))
        .background(theme.chrome.ignoresSafeArea())
        .sheet(isPresented: $controller.showTools) { ToolsSheet(controller: controller, importing: $importing, showPhotos: $showPhotos, showCamera: $showCamera).presentationDetents([.medium, .large]).sheetStyle(theme) }
        .sheet(isPresented: $controller.showOverview) { SessionOverview(controller: controller).presentationDetents([.large]).sheetStyle(theme) }
        .sheet(isPresented: $controller.showHistory) { HistorySheet(controller: controller).presentationDetents([.medium, .large]).sheetStyle(theme) }
        .sheet(isPresented: $controller.showPreview) { WebPreviewSheet().presentationDetents([.large]).sheetStyle(theme) }
        .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 4, matching: .any(of: [.images, .videos]))
        .onChange(of: photoItems) { items in Task { await load(items) } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { r in
            if case .success(let urls) = r { for u in urls { controller.attach(url: u, temporary: false) } }
        }
        .fullScreenCover(isPresented: $showCamera) { CameraPicker { url in showCamera = false; if let url { controller.attach(url: url, name: "photo-\(Int(Date().timeIntervalSince1970)).jpg", temporary: true) } }.ignoresSafeArea() }
        .themed(theme)
    }

    private func load(_ items: [PhotosPickerItem]) async {
        for item in items {
            if let file = try? await item.loadTransferable(type: PickedFile.self) { controller.attach(url: file.url, name: file.name, temporary: true) }
        }
        photoItems = []
    }

    private var header: some View {
        VStack(spacing: 6) {
            Capsule().fill(theme.secondary.opacity(0.5)).frame(width: 56, height: 4).padding(.top, 8)
            HStack(spacing: 12) {
                Button { env.route = nil } label: {
                    Image(systemName: "minus").font(.system(size: 14, weight: .bold)).foregroundColor(.black)
                        .frame(width: 30, height: 30).background(Color(red: 1, green: 0.8, blue: 0), in: Circle())
                }.frame(minWidth: 48, minHeight: 44).accessibilityIdentifier("terminal.back").accessibilityLabel(tr("minimize_session"))
                Button { controller.showOverview = true } label: {
                    Image(systemName: "list.bullet.indent").font(.system(size: 14, weight: .bold)).foregroundColor(.black)
                        .frame(width: 30, height: 30).background(Color(red: 0, green: 0.85, blue: 0.42), in: Circle())
                }.frame(minWidth: 48, minHeight: 44).accessibilityIdentifier("terminal.overview").accessibilityLabel(tr("sessions"))
                if let t = sessions.selected { HeaderTitle(tab: t).layoutPriority(1) }
                Spacer(minLength: 0)
                Menu {
                    if let t = sessions.selected {
                        if t.isConnected { Button(tr("disconnect_ssh")) { sessions.disconnect(t) } } else { Button(tr("reconnect_ssh")) { sessions.connect(t) } }
                        Button(tr("scroll_to_bottom")) { controller.view?.scrollToBottom() }
                        Button(tr("close"), role: .destructive) { confirmClose = true }
                    }
                } label: {
                    Text(sessions.selected?.isConnected == true ? "SSH" : "···").appFont(size: 13, weight: .medium)
                        .foregroundColor(Color(red: 0.4, green: 0.65, blue: 1)).padding(.horizontal, 12).frame(height: 32)
                        .background(Color(red: 0.4, green: 0.65, blue: 1).opacity(0.18), in: Capsule())
                }.frame(minHeight: 44).accessibilityIdentifier("terminal.menu").accessibilityLabel(tr("session_tools"))
                    .accessibilityValue(sessions.selected?.isConnected == true ? "SSH" : "···")
            }.padding(.horizontal, 12)
        }
        .frame(maxWidth: .infinity).background(theme.terminalSheet)
        .alert(tr("close"), isPresented: $confirmClose) {
            Button(tr("close"), role: .destructive) { controller.perform("close_session") }.accessibilityIdentifier("terminal.close.confirm")
            Button(tr("cancel"), role: .cancel) {}
        }
    }
}

struct HeaderTitle: View {
    @Environment(\.theme) var theme
    @EnvironmentObject var env: AppEnvironment
    @ObservedObject var tab: TerminalTab
    var body: some View {
        Text("\(env.profile(tab.profileID)?.name ?? "?") · \(tab.sessionName)").appFont(size: 16).foregroundColor(theme.secondary).lineLimit(1)
            .accessibilityIdentifier("tab.\(tab.sessionName)")
    }
}

struct PickedFile: Transferable {
    let url: URL, name: String
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .item) { received in
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("picked-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let dest = dir.appendingPathComponent(received.file.lastPathComponent)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return PickedFile(url: dest, name: received.file.lastPathComponent)
        }
    }
}

struct TabChip: View {
    @Environment(\.theme) var theme
    @EnvironmentObject var env: AppEnvironment
    @ObservedObject var tab: TerminalTab
    let selected: Bool; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Circle().fill(dot).frame(width: 7, height: 7)
                Text("\(env.profile(tab.profileID)?.name ?? "?") · \(tab.sessionName)").font(.footnote.weight(selected ? .semibold : .regular)).lineLimit(1)
            }.padding(.horizontal, 10).frame(height: 32)
                .background(selected ? theme.input : Color.clear, in: Capsule()).overlay(Capsule().stroke(selected ? theme.accent.opacity(0.6) : theme.border))
                .foregroundColor(selected ? theme.text : theme.secondary)
        }.accessibilityIdentifier("tab.\(tab.sessionName)")
    }
    private var dot: Color {
        switch tab.state { case .connected: return theme.accent; case .failed: return theme.danger; default: return theme.warning }
    }
}

// MARK: terminal host

struct TerminalHost: UIViewRepresentable {
    @EnvironmentObject var env: AppEnvironment
    @EnvironmentObject var sessions: SessionManager
    let controller: TerminalController

    func makeCoordinator() -> Coordinator { Coordinator() }
    final class Coordinator { var tabID: String?; var lastFont = 0; var lastTheme = "" }

    func makeUIView(context: Context) -> TerminalView {
        let v = TerminalView(frame: .zero)
        v.accessibilityIdentifier = "terminal.view"
        controller.view = v
        v.onGesture = { [weak controller] g, p in
            guard let controller else { return false }
            switch g { case .doubleHoldStart, .doubleHoldMove, .doubleHoldEnd, .doubleHoldCancel: return controller.zen(g, at: p); default: return controller.perform(gesture: g) }
        }
        v.onSelectionChanged = { [weak controller] t in controller?.selection = t }
        v.onFocusChanged = { [weak controller] on in controller?.keyboardVisible = on }
        v.onModifiersChanged = { [weak controller] in controller?.modifiersConsumed() }
        return v
    }

    func updateUIView(_ v: TerminalView, context: Context) {
        let c = context.coordinator
        let s = env.settings
        if c.lastFont != s.fontSize { v.setFontSize(CGFloat(s.fontSize)); c.lastFont = s.fontSize }
        v.selectionEnabled = s.selection
        v.optionAsMeta = env.prefs.flag("option_as_meta", default: true)
        guard let tab = sessions.selected else { v.setSession(nil); c.tabID = nil; return }
        if c.tabID != tab.id {
            c.tabID = tab.id
            sessions.columnsRows = (v.columns, v.rows)
            v.setSession(tab.session)
            controller.applyTheme(to: tab.session)
            v.onInput = { [weak sessions, weak tab] data in if let tab { sessions?.send(tab, data) } }
            v.onResize = { [weak sessions, weak tab] cols, rows in if let tab { sessions?.resize(tab, columns: cols, rows: rows) }; sessions?.columnsRows = (cols, rows) }
            v.notifyResize()
            if !tab.draftMode { _ = v.becomeFirstResponder() }
            if tab.state == .idle { sessions.connect(tab) }
        }
        if c.lastTheme != s.theme { c.lastTheme = s.theme; controller.applyTheme(to: tab.session) }
        v.backgroundColor = UIColor(s.current.terminalSheet)
        let appearance: UIKeyboardAppearance = s.current.light ? .light : .dark
        if v.keyboardAppearance != appearance { v.keyboardAppearance = appearance; v.reloadInputViews() }
    }
}

// MARK: overlays

struct StatusOverlay: View {
    @Environment(\.theme) var theme
    @EnvironmentObject var env: AppEnvironment
    @EnvironmentObject var sessions: SessionManager
    @ObservedObject var tab: TerminalTab
    let controller: TerminalController
    @State private var now = Date()
    let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack {
            switch tab.state {
            case .connecting:
                banner { HStack { ProgressView(); T("connecting_endpoint", env.profile(tab.profileID)?.host ?? "") } }.accessibilityIdentifier("status.connecting")
            case .failed(let f, let retry):
                banner {
                    VStack(spacing: 8) {
                        Text(message(f, retry)).multilineTextAlignment(.center).accessibilityIdentifier("status.failed")
                        HStack {
                            if f == .closed || f == .tmuxUnavailable {
                                Button(tr("sessions")) { controller.perform("close_session") }.buttonStyle(.bordered).accessibilityIdentifier("status.choose")
                            }
                            if f != .closed { Button(tr("retry")) { sessions.connect(tab) }.buttonStyle(.borderedProminent).accessibilityIdentifier("status.retry") }
                        }
                    }
                }
            case .idle, .needsTrust: banner { Button(tr("reconnect_ssh")) { sessions.connect(tab) }.accessibilityIdentifier("status.reconnect") }
            case .connected: EmptyView()
            }
            Spacer()
        }.padding(10).onReceive(timer) { now = $0 }
    }
    private func message(_ f: TerminalTab.Failure, _ retry: Bool) -> String {
        if f == .closed { return tr("remote_tmux_session_ended_choose_another_session_or_create_one") }
        if retry, let at = tab.reconnectAt { return tr("reconnect_countdown", max(0, Int(at.timeIntervalSince(now).rounded(.up)))) }
        return failureText(f)
    }
    private func banner<C: View>(@ViewBuilder _ c: () -> C) -> some View {
        c().font(.footnote).foregroundColor(theme.text).padding(12).background(theme.surface.opacity(0.96), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.border))
    }
}

struct SelectionBar: View {
    @Environment(\.theme) var theme
    @EnvironmentObject var env: AppEnvironment
    @ObservedObject var controller: TerminalController
    var body: some View {
        HStack(spacing: 8) {
            Button(tr("copy_selection")) { UIPasteboard.general.string = controller.selection; env.showToast(tr("copied")); controller.view?.clearSelection() }.accessibilityIdentifier("selection.copy")
            Button(tr("paste_into_draft")) { controller.tab?.draft += controller.selection; controller.tab?.draftMode = true; controller.view?.clearSelection() }.accessibilityIdentifier("selection.draft")
            Button(tr("clear_selection")) { controller.view?.clearSelection() }.accessibilityIdentifier("selection.clear")
        }.font(.footnote.weight(.semibold)).padding(10).background(theme.surface, in: Capsule()).overlay(Capsule().stroke(theme.border)).foregroundColor(theme.accent)
    }
}

/// Draggable arrow pad. Corner and centre buttons follow the gesture mappings, like Android.
struct DirectionPad: View {
    @Environment(\.theme) var theme
    @ObservedObject var controller: TerminalController
    @State private var offset = CGSize(width: 0, height: 60)
    @GestureState private var drag = CGSize.zero
    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) { corner("corner_ul", "delete.left"); arrow(.up, "chevron.up"); corner("corner_ur", "c.circle") }
            HStack(spacing: 6) { arrow(.left, "chevron.left"); center; arrow(.right, "chevron.right") }
            HStack(spacing: 6) { Color.clear.frame(width: 46, height: 46); arrow(.down, "chevron.down"); Color.clear.frame(width: 46, height: 46) }
        }
        .padding(8).background(theme.surface.opacity(0.92), in: RoundedRectangle(cornerRadius: 18)).overlay(RoundedRectangle(cornerRadius: 18).stroke(theme.border))
        .offset(x: offset.width + (controller.padLocked ? 0 : drag.width), y: offset.height + (controller.padLocked ? 0 : drag.height))
        .gesture(DragGesture().updating($drag) { v, s, _ in s = v.translation }.onEnded { v in if !controller.padLocked { offset.width += v.translation.width; offset.height += v.translation.height } })
        .accessibilityIdentifier("pad").accessibilityLabel(tr("direction_pad"))
    }
    private func arrow(_ k: TerminalSession.Key, _ icon: String) -> some View {
        RepeatButton(icon: icon) { controller.key(k) }.accessibilityIdentifier("pad.\(k)")
    }
    private func corner(_ key: String, _ icon: String) -> some View {
        Button { controller.perform(controller.prefs.mapping(key)) } label: { Image(systemName: icon).frame(width: 46, height: 46).background(theme.input, in: RoundedRectangle(cornerRadius: 12)).foregroundColor(theme.secondary) }
    }
    private var center: some View {
        Button { controller.key(.enter) } label: { Image(systemName: controller.padLocked ? "lock.fill" : "return").frame(width: 46, height: 46).background(theme.input, in: RoundedRectangle(cornerRadius: 12)).foregroundColor(theme.text) }
            .onLongPressGesture { controller.padLocked.toggle() }.accessibilityIdentifier("pad.enter")
    }
}

struct RepeatButton: View {
    @Environment(\.theme) var theme
    let icon: String; let fire: () -> Void
    @State private var timer: Timer?
    var body: some View {
        Image(systemName: icon).frame(width: 46, height: 46).background(theme.input, in: RoundedRectangle(cornerRadius: 12)).foregroundColor(theme.text)
            .gesture(DragGesture(minimumDistance: 0).onChanged { _ in
                guard timer == nil else { return }
                fire(); haptic()
                timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { _ in
                    timer = Timer.scheduledTimer(withTimeInterval: 0.07, repeats: true) { _ in fire() }
                }
            }.onEnded { _ in timer?.invalidate(); timer = nil })
    }
}

// MARK: toolbar & panels

struct InputToolbar: View {
    @Environment(\.theme) var theme
    @ObservedObject var controller: TerminalController
    var body: some View {
        ToolbarStrip(ids: controller.prefs.list("layout_toolbar")) { ToolButton(controller: controller, id: $0) }
        .accessibilityIdentifier("toolbar")
        .padding(.horizontal, 12).padding(.top, 6).padding(.bottom, 8)
        .gesture(DragGesture(minimumDistance: 24).onEnded { v in
            if abs(v.translation.height) > abs(v.translation.width) { controller.perform(controller.prefs.mapping(v.translation.height < 0 ? "toolbar_up" : "toolbar_down")) }
        })
    }
}

struct ToolButton: View {
    @Environment(\.theme) var theme
    @ObservedObject var controller: TerminalController
    let id: String
    var label: String { InputText.label(for: id, controller.prefs) }
    var active: Bool {
        switch id {
        case "ctrl": return controller.ctrl
        case "alt": return controller.alt
        case "shift": return controller.shift
        case "super": return controller.superKey
        case "pad": return controller.showPad
        case "shortcuts": return controller.showShortcuts
        case "chat": return controller.tab?.draftMode == true
        case "keyboard": return controller.keyboardVisible
        default: return false
        }
    }
    var body: some View {
        ToolKeyFace(id: id, prefs: controller.prefs, active: active)
            .onTapGesture(count: 2) { if let m = doubleMapping { controller.perform(m) } else { controller.perform(id) } }
            .onTapGesture { controller.perform(id); haptic() }
            .onLongPressGesture(minimumDuration: 0.45) { if let m = longMapping { controller.perform(m) } }
            .accessibilityIdentifier("tool.\(id)").accessibilityLabel(label).accessibilityAddTraits(.isButton)
    }
    var doubleMapping: String? { id == "ctrl" ? controller.prefs.mapping("ctrl_double") : id == "keyboard" ? controller.prefs.mapping("keyboard_double") : id == "shortcuts" ? controller.prefs.mapping("shortcut_double") : nil }
    var longMapping: String? { id == "ctrl" ? controller.prefs.mapping("ctrl_long") : id == "keyboard" ? controller.prefs.mapping("keyboard_long") : nil }
}

struct MinimizedBar: View {
    @Environment(\.theme) var theme
    @ObservedObject var controller: TerminalController
    var body: some View {
        HStack { Spacer()
            Button { controller.minimized = false } label: { Image(systemName: "chevron.up.circle.fill").font(.system(size: 30)).foregroundColor(theme.accent).padding(8) }
                .accessibilityIdentifier("zen.restore").accessibilityLabel(tr("input_zen_ring"))
        }.background(theme.surface.opacity(0.01))
    }
}

struct ShortcutPanel: View {
    @Environment(\.theme) var theme
    @ObservedObject var controller: TerminalController
    var body: some View {
        let groups = controller.prefs.list("groups")
        VStack(spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack { ForEach(groups, id: \.self) { g in
                    Button { controller.group = g } label: {
                        Group {
                            if controller.prefs.flag("group_icons_only", default: false) { ShortcutGroupIcon(group: g) }
                            else { Text(g == "Favorites" ? "☆" : g).font(.footnote.weight(.semibold)) }
                        }.padding(.horizontal, 12).frame(minWidth: 44, minHeight: 44)
                            .background(controller.group == g ? theme.accent : theme.input, in: Capsule()).foregroundColor(controller.group == g ? theme.onAccent : theme.text)
                    }.accessibilityIdentifier("group.\(g)").accessibilityLabel(g == "Favorites" ? tr("input_favorites") : g)
                        .accessibilityValue(controller.prefs.flag("group_icons_only", default: false) ? tr("input_show_group_icons_only") : g)
                        .accessibilityAddTraits(controller.group == g ? .isSelected : [])
                } }.padding(.horizontal, 8)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack { ForEach(controller.prefs.list("group_" + controller.group), id: \.self) { id in
                    if let a = controller.prefs.action(id) {
                        Button { controller.perform(id); if !controller.lockedShortcuts { controller.showShortcuts = false } } label: {
                            Text(a.label).appFont(size: 14).padding(.horizontal, 12).frame(height: 36).background(theme.input, in: RoundedRectangle(cornerRadius: 10)).foregroundColor(theme.text)
                        }.accessibilityIdentifier("shortcut.\(id)")
                    }
                } }.padding(.horizontal, 8)
            }
        }.padding(.vertical, 8).background(theme.chrome, in: RoundedRectangle(cornerRadius: 24)).padding(.horizontal, 12).accessibilityIdentifier("shortcuts.panel")
            .onAppear { let g = controller.prefs.list("groups"); if !g.contains(controller.group), let f = g.first { controller.group = f } }
    }
}

// MARK: composer

struct Composer: View {
    @Environment(\.theme) var theme
    @EnvironmentObject var env: AppEnvironment
    @ObservedObject var tab: TerminalTab
    @ObservedObject var controller: TerminalController
    @Binding var importing: Bool
    @Binding var showPhotos: Bool
    @Binding var showCamera: Bool
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !controller.currentAttachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) { ForEach(controller.currentAttachments) { AttachmentChip(controller: controller, attachment: $0) } }.padding(.top, 8).padding(.horizontal, 4)
                }
            }
            TextField(tr("type_a_message"), text: $tab.draft, axis: .vertical).lineLimit(1...6).focused($focused)
                .appFont(size: 18).lineSpacing(3).foregroundColor(theme.text).multilineTextAlignment(.leading).padding(.horizontal, 6).padding(.top, 8).frame(minHeight: 44, alignment: .top)
                .accessibilityIdentifier("composer.text")
            HStack(spacing: 4) {
                Menu {
                    Button { showCamera = true } label: { Label(tr("attachment_camera"), systemImage: "camera") }
                    Button { showPhotos = true } label: { Label(tr("attachment_photos"), systemImage: "photo") }
                    Button { importing = true } label: { Label(tr("attachment_files"), systemImage: "doc") }
                } label: { Image(systemName: "plus").font(.system(size: 22)).frame(width: 48, height: 48).foregroundColor(theme.text) }
                    .accessibilityIdentifier("composer.attach").accessibilityLabel(tr("add_attachment"))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) { ForEach(controller.prefs.list("layout_composer"), id: \.self) { ToolButton(controller: controller, id: $0) } }.padding(.trailing, 20)
                }.fadingTrailingEdge()
                Button { controller.setDraftMode(false) } label: { Image(systemName: "xmark").font(.system(size: 18)).frame(width: 48, height: 48).foregroundColor(theme.text) }
                    .accessibilityIdentifier("composer.close").accessibilityLabel(tr("close_editor_and_keep_draft"))
                Button { controller.perform("dictation") } label: { Image(systemName: "mic").font(.system(size: 20)).frame(width: 48, height: 48).foregroundColor(theme.secondary) }
                    .accessibilityIdentifier("composer.mic").accessibilityLabel(tr("input_dictation"))
                Button { controller.sendDraft(enter: true) } label: {
                    Image(systemName: "arrow.up").font(.system(size: 19, weight: .semibold))
                        .foregroundColor(canSend ? theme.onAccent : theme.secondary)
                        .frame(width: 40, height: 40).background(canSend ? theme.accent : theme.key, in: Circle()).frame(width: 48, height: 48)
                }
                    .disabled(!canSend)
                    .onLongPressGesture(minimumDuration: 0.45) { controller.sendDraft(enter: false) }
                    .accessibilityIdentifier("composer.send").accessibilityLabel(tr("send")).accessibilityAddTraits(.isButton)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(theme.chrome, in: RoundedRectangle(cornerRadius: 30))
        .padding(.horizontal, 12).padding(.top, 6).padding(.bottom, 8)
        .onAppear { focused = true }
    }
    private var canSend: Bool { tab.isConnected && DraftSubmission.payload(text: tab.draft, paths: controller.currentAttachments.map { $0.transfer.remotePath }) != nil }
}

struct AttachmentChip: View {
    @Environment(\.theme) var theme
    @ObservedObject var controller: TerminalController
    @ObservedObject var attachment: ComposerAttachment
    @ObservedObject private var transfer: AttachmentTransfer
    init(controller: TerminalController, attachment: ComposerAttachment) {
        self.controller = controller; self.attachment = attachment; self.transfer = attachment.transfer
    }
    var body: some View {
        let kind = AttachmentKind.resolve(transfer.name)
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(kind.color.opacity(theme.light ? 0.1 : 0.16))
            if let thumbnail = attachment.thumbnail { Image(uiImage: thumbnail).resizable().scaledToFill() }
            else { Image(systemName: kind.symbol).font(.system(size: 28, weight: .regular)).foregroundColor(kind.color) }
            switch transfer.state {
            case .uploading: ProgressView().padding(6).background(theme.surface.opacity(0.9), in: Circle())
            case .done: EmptyView()
            case .failed: Image(systemName: "exclamationmark.triangle.fill").foregroundColor(theme.danger).padding(6).background(theme.surface, in: Circle())
            }
        }.frame(width: 60, height: 60).clipShape(RoundedRectangle(cornerRadius: 12))
            .accessibilityElement(children: .ignore).accessibilityLabel("\(kind.label): \(transfer.name)").accessibilityValue(status)
            .overlay(alignment: .topTrailing) {
                Button { controller.remove(attachment) } label: {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .bold)).foregroundColor(theme.text)
                        .frame(width: 20, height: 20).background(theme.surface, in: Circle()).overlay(Circle().stroke(theme.border))
                        .frame(width: 44, height: 44)
                }.offset(x: 15, y: -15).accessibilityLabel(tr("attachment_remove", transfer.name)).accessibilityIdentifier("attachment.remove.\(transfer.name)")
            }
            .accessibilityIdentifier("attachment.\(transfer.name)")
            .task { await attachment.loadThumbnail() }
    }
    private var status: String { switch transfer.state { case .uploading: return tr("attachment_pending"); case .done: return tr("attachment_ready", transfer.name); case .failed: return tr("attachment_upload_failed") } }
}

// MARK: sheets

struct ToolsSheet: View {
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var env: AppEnvironment
    @ObservedObject var controller: TerminalController
    @Binding var importing: Bool
    @Binding var showPhotos: Bool
    @Binding var showCamera: Bool
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 10) {
                    SectionLabel(text: tr("terminal_tools"))
                    GroupCard {
                        row("arrow.down.to.line", tr("scroll_to_bottom")) { controller.view?.scrollToBottom(); dismiss() }; Hairline()
                        row("eraser", tr("clear_screen_ctrl_l")) { controller.runBinding("C-l"); dismiss() }; Hairline()
                        row("textformat.size.larger", tr("increase_terminal_font_size")) { env.settings.set("fontSize", min(24, env.settings.fontSize + 1)) }; Hairline()
                        row("textformat.size.smaller", tr("decrease_terminal_font_size")) { env.settings.set("fontSize", max(10, env.settings.fontSize - 1)) }; Hairline()
                        row("clipboard", tr("paste_text")) { controller.paste(); dismiss() }; Hairline()
                        row("safari", tr("input_web_preview")) { controller.showPreview = true }
                    }
                }
                VStack(alignment: .leading, spacing: 10) {
                    SectionLabel(text: tr("add_attachment"))
                    GroupCard {
                        row("camera", tr("attachment_camera")) { dismiss(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showCamera = true } }; Hairline()
                        row("photo", tr("attachment_photos")) { dismiss(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showPhotos = true } }; Hairline()
                        row("doc", tr("attach_file")) { dismiss(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { importing = true } }
                    }
                }
            }.padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 24)
        }
        .chrome(title: tr("session_tools"), closeID: "sheet.close") { dismiss() }
        .background(theme.canvas.ignoresSafeArea()).themed(theme)
    }
    private func row(_ icon: String, _ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { SettingRow(icon: icon, title: title) }.buttonStyle(.plain).accessibilityIdentifier("tools.\(icon)")
    }
}

struct HistorySheet: View {
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    @ObservedObject var controller: TerminalController
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if controller.history.isEmpty { T("no_input_history").appFont(size: 17).foregroundColor(theme.secondary).padding(.top, 24).frame(maxWidth: .infinity) }
                else {
                    GroupCard {
                        ForEach(Array(controller.history.enumerated()), id: \.element) { i, h in
                            if i > 0 { Hairline(inset: 16) }
                            Button { controller.tab?.draft = h; controller.tab?.draftMode = true; dismiss() } label: {
                                Text(h).appFont(size: 17).foregroundColor(theme.text).lineLimit(3).multilineTextAlignment(.leading)
                                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 14)
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }.padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 24)
        }
        .chrome(title: tr("input_input_history"), closeID: "sheet.close") { dismiss() }
        .background(theme.canvas.ignoresSafeArea()).themed(theme)
    }
}

struct WebPreviewSheet: View {
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    @State private var address = "http://localhost:3000"
    @State private var loaded: URL?
    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                TextField(tr("input_web_address_hint"), text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    .appFont(size: 17).padding(.horizontal, 16).frame(minHeight: 56).background(theme.input, in: RoundedRectangle(cornerRadius: 18)).accessibilityIdentifier("preview.address")
                Button { loaded = URL(string: address.contains("://") ? address : "http://" + address) } label: {
                    Text(tr("preview")).appFont(size: 17, weight: .medium).foregroundColor(theme.onAccent).padding(.horizontal, 20).frame(minHeight: 56).background(theme.accent, in: Capsule())
                }.accessibilityIdentifier("preview.go")
            }.padding(.horizontal, 16).padding(.top, 8)
            if let loaded { WebView(url: loaded).clipShape(RoundedRectangle(cornerRadius: 18)).padding(.horizontal, 16).padding(.bottom, 8) } else { Spacer() }
        }
        .chrome(title: tr("input_web_preview"), closeID: "sheet.close") { dismiss() }
        .background(theme.canvas.ignoresSafeArea()).themed(theme)
    }
}

struct WebView: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> WKWebView { let w = WKWebView(); w.load(URLRequest(url: url)); return w }
    func updateUIView(_ w: WKWebView, context: Context) { if w.url != url { w.load(URLRequest(url: url)) } }
}

struct CameraPicker: UIViewControllerRepresentable {
    let done: (URL?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(done) }
    func makeUIViewController(context: Context) -> UIViewController {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            DispatchQueue.main.async { done(nil) }; return UIViewController()
        }
        let p = UIImagePickerController(); p.sourceType = .camera; p.delegate = context.coordinator; return p
    }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (URL?) -> Void; init(_ d: @escaping (URL?) -> Void) { done = d }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            guard let img = info[.originalImage] as? UIImage, let data = img.jpegData(compressionQuality: 0.9) else { done(nil); return }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("camera-\(UUID().uuidString).jpg")
            try? data.write(to: url); done(url)
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { done(nil) }
    }
}

/// Action ring that follows a tap-and-hold; drag to an item and release to run it.
struct ZenRingView: View {
    @Environment(\.theme) var theme
    @ObservedObject var controller: TerminalController
    var body: some View {
        let ids = controller.zenIDs
        ZStack {
            if let c = controller.zenCenter {
                Circle().fill(theme.accent.opacity(0.25)).frame(width: 26, height: 26).position(c)
                ForEach(Array(ids.enumerated()), id: \.offset) { i, id in
                    let o = ZenRing.offset(i, of: ids.count)
                    let on = controller.zenSelected == i
                    Group { if let icon = InputText.icon(for: id) { Image(systemName: icon).font(.system(size: 18)) } else { Text(InputText.label(for: id, controller.prefs)).appFont(size: 14, design: .monospaced) } }
                        .frame(minWidth: 52, minHeight: 52).padding(.horizontal, 4).background(on ? theme.accent : theme.chrome.opacity(0.92), in: Capsule())
                        .foregroundColor(on ? theme.onAccent : theme.text).overlay(Capsule().stroke(theme.border))
                        .scaleEffect(on ? 1.15 : 1).position(x: c.x + o.x, y: c.y + o.y)
                }
            }
        }.allowsHitTesting(false).accessibilityIdentifier("zen.ring")
    }
}
