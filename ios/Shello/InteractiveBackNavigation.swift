import SwiftUI
import UIKit

/// Keep UIKit's interactive edge transition with Shello's custom headers.
/// Scoped to this navigation stack; no global UINavigationController overrides or synthetic swipes.
struct InteractiveBackNavigation: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> Probe {
        let probe = Probe()
        probe.isUserInteractionEnabled = false
        probe.attach = { [weak coordinator = context.coordinator] in coordinator?.install(on: $0) }
        return probe
    }
    func updateUIView(_ probe: Probe, context: Context) { probe.connect() }
    static func dismantleUIView(_ probe: Probe, coordinator: Coordinator) { coordinator.uninstall() }

    final class Probe: UIView {
        var attach: ((UINavigationController) -> Void)?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            connect()
            DispatchQueue.main.async { [weak self] in self?.connect() }
        }
        override func layoutSubviews() { super.layoutSubviews(); connect() }
        func connect() {
            guard window != nil else { return }
            var ancestor = superview
            while let view = ancestor {
                if let controller = view.next as? UIViewController,
                   let navigation = (controller as? UINavigationController) ?? controller.navigationController {
                    if let transition = navigation.transitionCoordinator {
                        transition.animate(alongsideTransition: nil) { [weak self, weak navigation] _ in
                            DispatchQueue.main.async {
                                guard let self, self.window != nil, let navigation else { return }
                                self.attach?(navigation)
                            }
                        }
                    } else { attach?(navigation) }
                    return
                }
                ancestor = view.superview
            }
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private weak var navigation: UINavigationController?
        private weak var previous: UIGestureRecognizerDelegate?
        private var wasEnabled = true

        func install(on navigation: UINavigationController) {
            guard let gesture = navigation.interactivePopGestureRecognizer else { return }
            if self.navigation === navigation, gesture.delegate === self {
                gesture.isEnabled = navigation.viewControllers.count > 1
                return
            }
            uninstall()
            self.navigation = navigation
            // Multiple visible SwiftUI wrappers may share one UINavigationController.
            // Retain UIKit's original delegate for teardown, rather than chaining page bridges.
            if let bridge = gesture.delegate as? Coordinator {
                previous = bridge.previous; wasEnabled = bridge.wasEnabled
            } else { previous = gesture.delegate; wasEnabled = gesture.isEnabled }
            gesture.delegate = self; gesture.isEnabled = navigation.viewControllers.count > 1

        }
        func uninstall() {
            // Never swap or disable a recognizer while it owns an interactive transition.
            if let transition = navigation?.transitionCoordinator {
                transition.animate(alongsideTransition: nil) { [self] _ in
                    DispatchQueue.main.async { self.uninstall() }
                }
                return
            }
            if let gesture = navigation?.interactivePopGestureRecognizer, gesture.delegate === self {
                gesture.delegate = previous; gesture.isEnabled = wasEnabled
            }
            navigation = nil; previous = nil
        }
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let navigation, navigation.viewControllers.count > 1,
                  navigation.transitionCoordinator == nil, navigation.presentedViewController == nil else { return false }
            return true
        }

    }
}

extension View {
    func interactiveBackNavigation() -> some View { background(InteractiveBackNavigation().frame(width: 0, height: 0)) }
}
