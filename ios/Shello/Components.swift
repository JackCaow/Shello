import SwiftUI
import UIKit

struct Card<Content: View>: View {
    @Environment(\.theme) var theme
    var content: () -> Content
    var body: some View {
        content().padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.border, lineWidth: 1))
    }
}

struct PrimaryButton: View {
    @Environment(\.theme) var theme
    let title: String; var busy = false; var enabled = true; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) { if busy { ProgressView().tint(.black) }; Text(title).appFont(size: 17, weight: .medium) }
                .padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 56)
                .background(enabled ? theme.vivid : theme.vivid.opacity(0.35), in: Capsule())
                .foregroundColor(theme.light ? theme.onAccent : .black)
        }.disabled(!enabled || busy)
    }
}

struct SecondaryButton: View {
    @Environment(\.theme) var theme
    let title: String; var destructive = false; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).appFont(size: 17).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 56)
                .background(theme.surface, in: Capsule()).foregroundColor(destructive ? theme.danger : theme.text)
        }
    }
}

/// Form field: 14pt secondary label above an 18pt-radius, 56pt-high input (Android form role).
struct Field: View {
    @Environment(\.theme) var theme
    let label: String; @Binding var text: String; var placeholder = ""; var secure = false; var keyboard: UIKeyboardType = .default; var id: String? = nil; var mono = false
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(label).appFont(size: 18, weight: .medium).foregroundColor(theme.text).padding(.leading, 2)
            Group {
                if secure { SecureField(placeholder, text: $text) }
                else { TextField(placeholder, text: $text).keyboardType(keyboard).textInputAutocapitalization(.never).autocorrectionDisabled() }
            }
            .appFont(size: 17, design: mono ? .monospaced : .default).padding(.horizontal, 16).frame(minHeight: 56)
            .background(theme.input, in: RoundedRectangle(cornerRadius: 20))
            .foregroundColor(theme.text)
            .accessibilityIdentifier(id ?? label)
        }
    }
}

/// Two- or three-way choice in the accent style used on the toolbar page (replaces the grey system segmented control).
struct SegmentedChoice<V: Hashable>: View {
    @Environment(\.theme) var theme
    @Environment(\.dynamicTypeSize) private var typeSize
    let options: [(label: String, value: V, id: String)]; @Binding var selection: V
    /// Hug the content (right-aligned in a row) instead of filling the width.
    var compact = false
    var body: some View {
        AdaptiveRow(spacing: 2) {
            ForEach(Array(options.enumerated()), id: \.offset) { _, o in
                Button { selection = o.value } label: {
                    Text(o.label).appFont(size: 16, weight: selection == o.value ? .medium : .regular).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                        .foregroundColor(selection == o.value ? theme.text : theme.secondary)
                        .padding(.horizontal, compact ? 16 : 0).padding(.vertical, 6).frame(maxWidth: compact && !typeSize.isAccessibilitySize ? nil : .infinity, minHeight: 44)
                        .background(selection == o.value ? Color(white: theme.light ? 1 : 0.07) : Color.clear, in: Capsule())
                }.accessibilityIdentifier(o.id)
            }
        }.padding(3).background(Color(white: theme.light ? 0.92 : 0.11), in: RoundedRectangle(cornerRadius: 25)).overlay(RoundedRectangle(cornerRadius: 25).stroke(theme.border.opacity(0.6), lineWidth: 1))
    }
}

/// 44pt round icon button with a hairline border, used in every header (close, back, confirm).
struct CircleButton: View {
    @Environment(\.theme) var theme
    let systemName: String; var tint: Color? = nil; var id: String; var label: String; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: systemName).font(.system(size: 18, weight: .medium)).foregroundColor(tint ?? theme.text)
                .frame(width: 44, height: 44).background(Color(white: theme.light ? 0.95 : 0.09), in: Circle()).overlay(Circle().stroke(theme.border, lineWidth: 1))
        }.frame(width: 52, height: 52).accessibilityLabel(label).accessibilityIdentifier(id)
    }
}

/// Header of modal screens: round close button, centred 17pt title and an optional trailing action (Moshi layout).
struct SheetHeader<Trailing: View>: View {
    @Environment(\.theme) var theme
    let title: String; let closeID: String; var trailing: Trailing; let close: () -> Void
    var body: some View {
        ZStack {
            Text(title).appFont(size: 17, weight: .semibold).foregroundColor(theme.text).multilineTextAlignment(.center).lineLimit(2).padding(.horizontal, 76).padding(.vertical, 12)
            HStack(spacing: 0) {
                CircleButton(systemName: "xmark", id: closeID, label: tr("close"), action: close)
                Spacer(minLength: 8)
                trailing
            }
        }.padding(.horizontal, 12).frame(minHeight: 64)
    }
}

/// Switch with a saturated green track and a dark knob (off: dark track, light knob).
struct ShelloToggleStyle: ToggleStyle {
    @Environment(\.theme) var theme
    func makeBody(configuration: Configuration) -> some View {
        HStack {
            configuration.label
            Spacer(minLength: 12)
            Button { configuration.isOn.toggle() } label: {
                ZStack(alignment: configuration.isOn ? .trailing : .leading) {
                    Capsule().fill(configuration.isOn ? theme.vivid : Color(white: theme.light ? 0.82 : 0.17)).frame(width: 52, height: 32)
                    Circle().fill(configuration.isOn ? (theme.light ? .white : .black) : (theme.light ? .white : Color(white: 0.96))).frame(width: 26, height: 26).padding(3)
                }
            }.buttonStyle(.plain).accessibilityHidden(true)
        }
        .contentShape(Rectangle()).onTapGesture { configuration.isOn.toggle() }
        .accessibilityElement(children: .combine).accessibilityAddTraits(.isButton)
    }
}

/// "− 14pt +" stepper pill.
struct StepperPill: View {
    @Environment(\.theme) var theme
    let value: String; let minus: () -> Void; let plus: () -> Void; var idPrefix = "stepper"
    var body: some View {
        HStack(spacing: 0) {
            Button(action: minus) { Image(systemName: "minus").font(.system(size: 15, weight: .semibold)).frame(width: 40, height: 40) }.accessibilityIdentifier(idPrefix + ".minus").accessibilityLabel(tr("decrease_terminal_font_size"))
            Text(value).appFont(size: 17, weight: .medium).foregroundColor(theme.text).frame(minWidth: 52).accessibilityIdentifier(idPrefix + ".value")
            Button(action: plus) { Image(systemName: "plus").font(.system(size: 15, weight: .semibold)).frame(width: 40, height: 40) }.accessibilityIdentifier(idPrefix + ".plus").accessibilityLabel(tr("increase_terminal_font_size"))
        }.buttonStyle(.plain).foregroundColor(theme.text).padding(.horizontal, 4).background(Color(white: theme.light ? 0.92 : 0.11), in: Capsule())
    }
}

extension View {
    /// Wraps a modal screen's content under the shared header; callers hide the system navigation bar.
    func chrome<T: View>(title: String, closeID: String, @ViewBuilder trailing: () -> T = { EmptyView() }, close: @escaping () -> Void) -> some View {
        VStack(spacing: 0) { SheetHeader(title: title, closeID: closeID, trailing: trailing(), close: close); self }
    }
}

struct SectionTitle: View {
    @Environment(\.theme) var theme
    let key: String
    var body: some View { T(key).appFont(size: 13, weight: .medium).foregroundColor(theme.secondary).padding(.leading, 4).padding(.top, 6) }
}

/// Screen scaffold: themed canvas, title and a close/back control, shared by pushed pages.
struct Page<Content: View>: View {
    @Environment(\.theme) var theme
    let title: String; var content: () -> Content
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 12) { content() }.padding(16) }
            .background(theme.canvas.ignoresSafeArea())
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
    }
}

struct Toast: View {
    @Environment(\.theme) var theme
    let text: String
    var body: some View {
        Text(text).font(.callout).padding(.horizontal, 16).padding(.vertical, 10)
            .background(theme.surface, in: Capsule()).overlay(Capsule().stroke(theme.border))
            .foregroundColor(theme.text).accessibilityIdentifier("toast").padding(.bottom, 90)
    }
}

extension View {
    func themed(_ t: Theme) -> some View { environment(\.theme, t).preferredColorScheme(t.scheme).tint(t.accent) }
}

func haptic() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }

/// Home backdrop: black canvas with two soft green radial glows (top centre and bottom right), as on Android.
struct HomeBackdrop: View {
    @Environment(\.theme) var theme
    var body: some View {
        GeometryReader { g in
            ZStack {
                theme.canvas
                if !theme.light {
                    RadialGradient(colors: [Theme.rgb(0x041104), .clear], center: UnitPoint(x: 0.5, y: 0), startRadius: 0, endRadius: g.size.width * 0.8)
                    RadialGradient(colors: [Theme.rgb(0x032003), .clear], center: .bottomTrailing, startRadius: 0, endRadius: g.size.width * 0.65)
                }
            }
        }.ignoresSafeArea()
    }
}

/// Grouped settings-style list: one rounded surface, inset hairline separators.
struct GroupCard<Content: View>: View {
    @Environment(\.theme) var theme
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(spacing: 0) { content() }
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}
struct Hairline: View { @Environment(\.theme) var theme; var inset: CGFloat = 56
    var body: some View { Rectangle().fill(theme.separator).frame(height: 1 / UIScreen.main.scale).padding(.leading, inset) } }

/// Round 48pt-target icon button with a dark fill (home navigation, terminal header).
struct RoundIconButton: View {
    @Environment(\.theme) var theme
    let systemName: String; var fill: Color? = nil; var tint: Color? = nil; var size: CGFloat = 44; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: systemName).font(.system(size: 19, weight: .regular))
                .frame(width: size, height: size).background(fill ?? theme.surface, in: Circle()).foregroundColor(tint ?? theme.text)
        }.frame(minWidth: 48, minHeight: 48)
    }
}

/// Uppercase-free section label (Android "Section" role: 13 / medium, secondary).
struct SectionLabel: View {
    @Environment(\.theme) var theme
    let text: String
    var body: some View { Text(text).appFont(size: 13, weight: .medium, relativeTo: .caption).foregroundColor(theme.secondary).padding(.leading, 4) }
}

struct DashedCard<Content: View>: View {
    @Environment(\.theme) var theme
    @ViewBuilder var content: () -> Content
    var body: some View {
        content().frame(maxWidth: .infinity, alignment: .leading).padding(16)
            .background(theme.surface.opacity(0.55), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(theme.accent.opacity(0.28), style: StrokeStyle(lineWidth: 1, dash: [4, 4])))
    }
}

struct IconTile: View {
    @Environment(\.theme) var theme
    let systemName: String; var size: CGFloat = 48
    var body: some View {
        Image(systemName: systemName).font(.system(size: size * 0.42)).foregroundColor(theme.vivid)
            .frame(width: size, height: size).background(theme.vivid.opacity(0.13), in: RoundedRectangle(cornerRadius: size * 0.3))
    }
}

extension View {
    /// Sheets use the app canvas with large (28pt) corners and a visible grabber, like the Android bottom sheets.
    @ViewBuilder func sheetStyle(_ theme: Theme) -> some View {
        if #available(iOS 16.4, *) { self.presentationBackground(theme.canvas).presentationCornerRadius(28).presentationDragIndicator(.visible) }
        else { self.presentationDragIndicator(.visible) }
    }
}

extension View {
    /// Fades the trailing 24pt so horizontally scrolling rows never end in a hard-cut half icon.
    func fadingTrailingEdge(_ width: CGFloat = 24) -> some View {
        mask(HStack(spacing: 0) { Rectangle(); LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing).frame(width: width) })
    }
}

/// Preserve the app's type hierarchy at the default size while respecting Dynamic Type.
private struct AppFontModifier: ViewModifier {
    @ScaledMetric private var size: CGFloat
    let weight: Font.Weight
    let design: Font.Design
    init(size: CGFloat, weight: Font.Weight, design: Font.Design, relativeTo: Font.TextStyle) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: relativeTo)
        self.weight = weight; self.design = design
    }
    func body(content: Content) -> some View { content.font(.system(size: size, weight: weight, design: design)) }
}
extension View {
    func appFont(size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default, relativeTo: Font.TextStyle = .body) -> some View {
        modifier(AppFontModifier(size: size, weight: weight, design: design, relativeTo: relativeTo))
    }
}

/// Stacks dense controls vertically at accessibility sizes rather than shrinking their labels.
struct AdaptiveRow<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    var spacing: CGFloat = 12
    @ViewBuilder var content: () -> Content
    var body: some View {
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: spacing)) : AnyLayout(HStackLayout(alignment: .center, spacing: spacing))
        layout { content() }
    }
}
