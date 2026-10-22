import SwiftUI
import ShelloCore
import ShelloServices

@main
struct ShelloApp: App {
    @StateObject private var env = AppEnvironment()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup { RootView().environmentObject(env).environmentObject(env.sessions).environmentObject(env.settings)
            .onChange(of: phase) { p in
                switch p {
                case .active: env.sessions.appDidBecomeActive()
                case .background: env.sessions.appDidEnterBackground()
                default: break
                }
            }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var env: AppEnvironment
    @EnvironmentObject var settings: AppSettings
    var body: some View {
        let theme = settings.current
        NavigationStack(path: $env.navigationPath) {
            HomeView()
                .interactiveBackNavigation()
                .navigationDestination(for: AppEnvironment.Route.self) { route in
                    Group {
                        switch route {
                        case .terminal: TerminalScreen(env: env)
                        case .agent: AgentView(hub: env.hub)
                        case .settings(let page):
                            if let page { SettingsDestination(page: page) }
                            else { SettingsView() }
                        }
                    }
                    .navigationBarBackButtonHidden(true)
                    .toolbar(.hidden, for: .navigationBar)
                    .interactiveBackNavigation()
                    .themed(theme)
                }
        }
        .overlay(alignment: .bottom) { if let toast = env.toast { Toast(text: toast).transition(.opacity) } }
        .themed(theme)
        .id(AppLanguage.choice)
        .onAppear {
            if env.testMode, ProcessInfo.processInfo.arguments.contains("-SHELLO_OPEN_PAIR") { DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { NotificationCenter.default.post(name: .shelloOpenPair, object: nil) } }
        }
    }
}
