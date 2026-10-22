import SwiftUI
import AVFoundation
import PhotosUI
import CoreImage
import ShelloCore
import ShelloServices
import ShelloSSH

/// Camera-only QR decoding (or an image). A scan requires explicit host confirmation before installing a key.
struct PairingView: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    let onSaved: (SSHProfile) -> Void

    enum Network: String, CaseIterable { case auto, lan, tailscale }
    @State private var network = Network.auto
    @State private var windows = false
    @State private var status = ""
    @State private var statusIsError = false
    @State private var ticket: PairingTicket?
    @State private var busy = false
    @State private var photo: PhotosPickerItem?
    @State private var scanning = true
    @State private var trustFailure: HostKeyInfo?
    @State private var showAbout = false

    private var command: String {
        windows ? """
        $shelloPairScript = Join-Path $env:TEMP 'shello-pair.ps1'
        Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/JackCaow/Shello/main/companion/pair.ps1' -OutFile $shelloPairScript
        powershell.exe -NoProfile -ExecutionPolicy Bypass -File $shelloPairScript\(network == .auto ? "" : " -Network \(network.rawValue)")
        """
        : "curl -fsSL https://raw.githubusercontent.com/JackCaow/Shello/main/companion/pair.sh | bash" + (network == .auto ? "" : " -s -- --network \(network.rawValue)")
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ZStack {
                        QRScannerView(active: scanning && ticket == nil && !busy, onCode: scanned, onState: { s in setStatus(s.0, error: s.1) })
                            .accessibilityIdentifier("pair.scanner")
                        RoundedRectangle(cornerRadius: 22).stroke(theme.vivid.opacity(0.9), lineWidth: 3).frame(width: 190, height: 190)
                    }.aspectRatio(1, contentMode: .fit).frame(maxWidth: .infinity).background(Color.black).clipShape(RoundedRectangle(cornerRadius: 26))
                    Text(status).appFont(size: 15).foregroundColor(statusIsError ? theme.danger : theme.secondary).frame(maxWidth: .infinity, alignment: .center).accessibilityIdentifier("pair.status")
                    AdaptiveRow {
                        SegmentedChoice(options: [("macOS / Linux", false, "pair.platform.unix"), ("Windows", true, "pair.platform.windows")], selection: $windows, compact: true).accessibilityIdentifier("pair.platform")
                        Spacer()
                        Button { UIPasteboard.general.string = command; env.showToast(tr("copied")) } label: {
                            Label(tr("copy"), systemImage: "doc.on.doc").appFont(size: 16).foregroundColor(theme.text).padding(.horizontal, 16).frame(minHeight: 48)
                                .background(Color(white: theme.light ? 0.92 : 0.11), in: Capsule()).overlay(Capsule().stroke(theme.border.opacity(0.6), lineWidth: 1))
                        }.accessibilityIdentifier("pair.copy").accessibilityLabel(tr("copy_pairing_command"))
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(command.split(separator: "\n").enumerated()), id: \.offset) { _, line in
                            HStack(alignment: .top, spacing: 8) { Text("$").foregroundColor(theme.vivid); Text(String(line)).foregroundColor(theme.text) }
                        }
                    }.appFont(size: 15, design: .monospaced).textSelection(.enabled).padding(16).frame(maxWidth: .infinity, alignment: .leading)
                        .background(theme.surface, in: RoundedRectangle(cornerRadius: 20)).accessibilityElement(children: .combine).accessibilityLabel(command).accessibilityIdentifier("pair.command")
                    SegmentedChoice(options: [(tr("pair_network_auto"), Network.auto, "pair.network.auto"), (tr("pair_network_lan"), Network.lan, "pair.network.lan"), ("Tailscale", Network.tailscale, "pair.network.tailscale")], selection: $network).accessibilityIdentifier("pair.network")
                    T("pair_network_hint").appFont(size: 14).lineSpacing(3).foregroundColor(theme.secondary)
                    HStack(spacing: 10) {
                        PhotosPicker(selection: $photo, matching: .images) {
                            Label(tr("scan_from_image"), systemImage: "photo").appFont(size: 16).frame(maxWidth: .infinity, minHeight: 48).background(theme.surface, in: Capsule()).foregroundColor(theme.text)
                        }.accessibilityIdentifier("pair.image")
                        Button { reset(tr("cancelled_ready_to_scan_again")) } label: {
                            Text(tr("scan_again")).appFont(size: 16).frame(maxWidth: .infinity, minHeight: 48).background(theme.surface, in: Capsule()).foregroundColor(theme.text)
                        }.accessibilityIdentifier("pair.again")
                    }
                    Button { showAbout = true } label: {
                        Text(tr("about_pairing")).appFont(size: 15).foregroundColor(theme.secondary).padding(.horizontal, 18).frame(minHeight: 44).background(Color(white: theme.light ? 0.92 : 0.11), in: Capsule())
                    }.frame(maxWidth: .infinity).accessibilityIdentifier("pair.about")
                }.padding(.horizontal, 20).padding(.top, 4).padding(.bottom, 24)
            }
            .chrome(title: tr("qr_pairing"), closeID: "pair.close") { dismiss() }
            .toolbar(.hidden, for: .navigationBar)
        }
        .alert(tr("pair_with_this_computer"), isPresented: Binding(get: { ticket != nil && !busy }, set: { if !$0 && !busy { reset(tr("cancelled_ready_to_scan_again")) } }), presenting: ticket) { t in
            Button(tr("pair")) { Task { await pair(t) } }.accessibilityIdentifier("pair.confirm")
            Button(tr("cancel"), role: .cancel) {}
        } message: { t in Text(tr("pair_confirmation_body", "\(t.host):\(t.port)")) }
        .alert(tr("qr_pairing"), isPresented: $showAbout) { Button(tr("got_it")) {} } message: { T("enable_ssh_and_install_tmux_on_your_computer_first_your_private_k") }
        .onChange(of: photo) { item in Task { await decode(item) } }
        .onAppear { if env.testMode, let i = ProcessInfo.processInfo.arguments.firstIndex(of: "-SHELLO_TEST_QR"), i + 1 < ProcessInfo.processInfo.arguments.count { scanned(ProcessInfo.processInfo.arguments[i + 1]) } }
        .themed(theme)
    }

    private func setStatus(_ s: String, error: Bool) { status = s; statusIsError = error }
    private func reset(_ text: String) { ticket = nil; busy = false; setStatus(text, error: false); scanning = true }

    private func scanned(_ text: String) {
        guard ticket == nil, !busy else { return }
        do { ticket = try PairingTicket(text); setStatus(tr("qr_code_recognized_confirm_the_computer"), error: false); haptic() }
        catch { setStatus(tr("invalid_or_expired_qr_code_generate_a_new_pairing_code"), error: true) }
    }
    private func decode(_ item: PhotosPickerItem?) async {
        guard let item, let data = try? await item.loadTransferable(type: Data.self), let text = QRDecoder.decode(data) else { setStatus(tr("no_valid_qr_code_found_in_the_image"), error: true); return }
        scanned(text)
    }

    private func pair(_ t: PairingTicket) async {
        busy = true; setStatus(tr("pairing_securely"), error: false)
        do {
            let result = try await PairingService.pair(ticket: t)
            setStatus(tr("verifying_paired_endpoint", result.profile.user, result.profile.host, result.profile.port), error: false)
            switch await ConnectionSetup.verify(profile: result.profile, secrets: result.secrets) {
            case .verified:
                do { try env.store.save(result.profile, secrets: result.secrets); env.reload(); busy = false; onSaved(result.profile) }
                catch { fail(tr("connection_verified_but_saving_failed_try_again")) }
            case .trustNeeded, .failed(.hostKeyMismatch): fail(tr("ssh_fingerprint_differs_from_the_pairing_key_connection_stopped"))
            case .failed(.tmuxUnavailable): fail(tr("ssh_is_connected_but_tmux_is_unavailable_you_can_skip_to_open_a_s"))
            case .failed(let f): fail(failureText(f))
            }
        } catch { fail(tr("pairing_failed_check_your_network_qr_code_expiry_and_computer_clo")) }
    }
    private func fail(_ text: String) { ticket = nil; busy = false; setStatus(text, error: true); scanning = true }
}

/// AVFoundation QR scanner. Reports camera availability through `onState`.
struct QRScannerView: UIViewRepresentable {
    let active: Bool
    let onCode: (String) -> Void
    let onState: ((String, Bool)) -> Void

    func makeUIView(context: Context) -> ScannerView { let v = ScannerView(); v.onCode = onCode; v.onState = onState; v.configure(); return v }
    func updateUIView(_ v: ScannerView, context: Context) { v.onCode = onCode; v.onState = onState; v.setActive(active) }

    final class ScannerView: UIView, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?; var onState: (((String, Bool)) -> Void)?
        private let session = AVCaptureSession(); private var preview: AVCaptureVideoPreviewLayer?
        private var configured = false, wantsActive = true
        override func layoutSubviews() { super.layoutSubviews(); preview?.frame = bounds }
        func configure() {
            onState?((tr("opening_camera"), false))
            // A simulator (or device without a camera) must not raise a system
            // permission dialog that blocks pairing from an imported QR image.
            guard AVCaptureDevice.default(for: .video) != nil else {
                onState?((tr("camera_unavailable_reopen_this_page_or_scan_from_an_image"), true)); return
            }
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: setup()
            case .notDetermined: AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { ok ? self.setup() : self.onState?((tr("camera_access_is_off_scan_from_an_image_or_allow_camera_access_in"), true)) } }
            default: onState?((tr("camera_access_is_off_scan_from_an_image_or_allow_camera_access_in"), true))
            }
        }
        private func setup() {
            guard !configured else { return }
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else {
                onState?((tr("camera_unavailable_reopen_this_page_or_scan_from_an_image"), true)); return
            }
            configured = true; session.addInput(input)
            let out = AVCaptureMetadataOutput(); if session.canAddOutput(out) { session.addOutput(out); out.setMetadataObjectsDelegate(self, queue: .main); out.metadataObjectTypes = [.qr] }
            let layer = AVCaptureVideoPreviewLayer(session: session); layer.videoGravity = .resizeAspectFill; layer.frame = bounds
            self.layer.addSublayer(layer); preview = layer
            if wantsActive { start() }
        }
        private func start() { DispatchQueue.global(qos: .userInitiated).async { [session] in if !session.isRunning { session.startRunning() } }; onState?((tr("point_at_a_qr_code_to_scan"), false)) }
        func setActive(_ a: Bool) {
            wantsActive = a; guard configured else { return }
            if a { start() } else { DispatchQueue.global().async { [session] in if session.isRunning { session.stopRunning() } } }
        }
        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objs: [AVMetadataObject], from: AVCaptureConnection) {
            if let s = (objs.first as? AVMetadataMachineReadableCodeObject)?.stringValue { onCode?(s) }
        }
        deinit { session.stopRunning() }
    }
}

/// Decodes a QR code from image data (photo library / saved screenshot).
enum QRDecoder {
    static func decode(_ data: Data) -> String? {
        guard let img = CIImage(data: data), let det = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) else { return nil }
        return (det.features(in: img).first as? CIQRCodeFeature)?.messageString
    }
    /// Renders a QR code (used by tests and the pairing preview).
    static func image(for text: String, scale: CGFloat = 8) -> UIImage? {
        let f = CIFilter(name: "CIQRCodeGenerator"); f?.setValue(Data(text.utf8), forKey: "inputMessage"); f?.setValue("M", forKey: "inputCorrectionLevel")
        guard let out = f?.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)) else { return nil }
        return UIImage(ciImage: out)
    }
}
