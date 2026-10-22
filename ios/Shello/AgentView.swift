import SwiftUI
import ShelloCore
import ShelloServices

struct AgentView: View {
    @Environment(\.theme) var theme
    @EnvironmentObject var env: AppEnvironment
    @ObservedObject var hub: AgentHub
    @State private var query = ""
    @State private var provider: String?
    @State private var host: String?
    @State private var selected: AgentSession?
    @State private var showArchived = false
    @State private var showUsage = false

    var sessions: [AgentSession] {
        hub.sessions.filter { AgentInbox.matches($0, query: query, provider: provider, host: host, project: nil) && ($0.state != .archived || showArchived) }
    }

    var body: some View {
        Group {
            List {
                if env.profiles.filter({ hub.enabled($0.id) }).isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        T("install_on_an_ssh_host_to_receive_agent_events").foregroundColor(theme.text)
                        Button(tr("set_up_hook")) { env.route = .settings("hook") }.accessibilityIdentifier("agent.setup")
                    }.listRowBackground(theme.surface)
                } else if sessions.isEmpty {
                    VStack(alignment: .leading) { T("no_agent_events_yet").foregroundColor(theme.text); T("approval_requests_questions_and_completed_tasks_will_appear_here").appFont(size: 14).foregroundColor(theme.secondary) }
                        .listRowBackground(theme.surface)
                }
                ForEach(sessions) { s in
                    Button { selected = s; hub.markSessionRead(s) } label: { row(s) }.listRowBackground(theme.surface).accessibilityIdentifier("agent.session.\(s.project)")
                }
                if !hub.usages.isEmpty { Section { Button { showUsage = true } label: { Label(tr("agent_updates"), systemImage: "gauge") }.accessibilityIdentifier("agent.usage") } .listRowBackground(theme.surface) }
            }
            .scrollContentBackground(.hidden).background(theme.canvas.ignoresSafeArea())
            .chrome(title: tr("agent_inbox"), closeID: "agent.close", trailing: {
                HStack(spacing: 0) {
                    Menu {
                        Picker(tr("agents"), selection: $provider) { Text("—").tag(String?.none); Text("Claude Code").tag(String?.some("claude")); Text("Codex").tag(String?.some("codex")) }
                        Picker(tr("ssh_hosts"), selection: $host) { Text("—").tag(String?.none); ForEach(env.profiles) { Text($0.name).tag(String?.some($0.id)) } }
                        Toggle("Archived", isOn: $showArchived)
                    } label: { Image(systemName: "line.3.horizontal.decrease").font(.system(size: 18)).foregroundColor(theme.text).frame(width: 48, height: 48) }.accessibilityIdentifier("agent.filter")
                    Button { Task { await hub.refresh(); await hub.refreshUsage(force: true) } } label: { Image(systemName: "arrow.clockwise").font(.system(size: 18)).foregroundColor(theme.text).frame(width: 48, height: 48) }
                        .accessibilityIdentifier("agent.refresh").accessibilityLabel(tr("refresh"))
                }
            }) { env.route = nil }
            .toolbar(.hidden, for: .navigationBar)
            .sheet(item: $selected) { s in AgentDetail(hub: hub, sessionKey: s.key) }
            .sheet(isPresented: $showUsage) { UsageView(hub: hub) }
            .onAppear { hub.attach() }.onDisappear { hub.detach() }
        }.themed(theme)
    }

    private func row(_ s: AgentSession) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: s.provider == "codex" ? "chevron.left.forwardslash.chevron.right" : "sparkle").foregroundColor(color(s.state)).frame(width: 24)
            VStack(alignment: .leading, spacing: 3) {
                HStack { Text(s.project).font(.headline).foregroundColor(theme.text); Spacer(); Text(label(s.state)).font(.caption.weight(.semibold)).foregroundColor(color(s.state)) }
                Text(s.summary).appFont(size: 15).lineSpacing(3).foregroundColor(theme.secondary).lineLimit(3).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                Text("\(s.agent) · \(s.host)").font(.caption2).foregroundColor(theme.secondary)
            }
        }
    }
    private func label(_ st: AgentSession.State) -> String {
        switch st {
        case .needsYou: return tr("awaiting_response"); case .working: return tr("working"); case .done: return tr("task_completed")
        case .interrupted: return tr("agent_task_interrupted"); case .offline: return tr("offline_waiting_to_sync"); case .archived: return "Archived"
        }
    }
    private func color(_ st: AgentSession.State) -> Color {
        switch st { case .needsYou: return theme.warning; case .working: return theme.accent; case .done: return theme.accent; case .interrupted: return theme.danger; default: return theme.secondary }
    }
}

struct AgentDetail: View {
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var env: AppEnvironment
    @ObservedObject var hub: AgentHub
    let sessionKey: String
    @State private var answers: [String: [String: String]] = [:]
    @State private var responseMessage: String?
    @State private var responseFailed = false
    @State private var busy = false
    var session: AgentSession? { hub.sessions.first { $0.key == sessionKey } }

    var body: some View {
        NavigationStack {
            ScrollView {
                if let s = session {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(s.project).font(.title2.weight(.bold)).foregroundColor(theme.text)
                        Text("\(s.agent) · \(s.host)\(s.model.isEmpty ? "" : " · " + s.model)").appFont(size: 14).foregroundColor(theme.secondary)
                        if !s.cwd.isEmpty { Text(s.cwd).font(.system(.footnote, design: .monospaced)).foregroundColor(theme.secondary) }
                        if let responseMessage { Text(responseMessage).appFont(size: 15).foregroundColor(responseFailed ? theme.danger : theme.accent).accessibilityIdentifier("agent.response.status") }
                        ForEach(s.requests) { r in request(r) }
                        if !s.lastPrompt.isEmpty { block(tr("send"), s.lastPrompt) }
                        if !s.lastAssistant.isEmpty { block(s.agent, s.lastAssistant) }
                        if let target = s.target, let profile = env.profile(s.profile) {
                            PrimaryButton(title: tr("resume_session_name", target.dropFirst().description)) { dismiss(); env.open(profile, target: target) }.accessibilityIdentifier("agent.resume")
                        }
                        if s.state != .archived && !s.unresolved { SecondaryButton(title: tr("dismiss")) { hub.archive(s, true); dismiss() }.accessibilityIdentifier("agent.archive") }
                    }.padding(16)
                }
            }.background(theme.canvas.ignoresSafeArea())
            .chrome(title: tr("agent_event"), closeID: "sheet.close") { dismiss() }
            .toolbar(.hidden, for: .navigationBar)
        }.themed(theme)
    }

    private func block(_ title: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption.weight(.semibold)).foregroundColor(theme.secondary)
            Text(text).appFont(size: 16).lineSpacing(4).foregroundColor(theme.text).multilineTextAlignment(.leading).textSelection(.enabled)
                .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(theme.surface, in: RoundedRectangle(cornerRadius: 12))
        }
    }
    @ViewBuilder private func request(_ r: AgentEvent) -> some View {
        let isQuestion = r.str("tool_name") == "AskUserQuestion"
        Card {
            VStack(alignment: .leading, spacing: 8) {
                Text(isQuestion ? tr("agent_question") : tr("approval_request_tool", r.str("tool_name"))).font(.headline).foregroundColor(theme.warning)
                if let input = r.data["tool_input"] { Text(pretty(input)).font(.system(.footnote, design: .monospaced)).foregroundColor(theme.text).lineLimit(12).textSelection(.enabled) }
                T("respond_to_this_request_only_if_it_expires_or_the_network_disconn").font(.caption).foregroundColor(theme.secondary)
                if isQuestion {
                    let titles = AgentQuestionnaire.titles(r.data["tool_input"] as? JSON ?? [:])
                    ForEach(Array(titles.enumerated()), id: \.offset) { index, title in
                        Text(title).appFont(size: 16, weight: .medium).foregroundColor(theme.text)
                        TextField(tr("enter_an_answer"), text: Binding(get: { answers[r.id]?[title] ?? "" }, set: { answers[r.id, default: [:]][title] = $0 }))
                            .appFont(size: 16).padding(12).background(theme.input, in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityIdentifier(index == 0 ? "agent.answer" : "agent.answer.\(index)")
                    }
                    PrimaryButton(title: tr("send_answer"), busy: busy, enabled: AgentQuestionnaire.response(titles: titles, answers: answers[r.id] ?? [:]) != nil) {
                        if let value = AgentQuestionnaire.response(titles: titles, answers: answers[r.id] ?? [:]) { respond(r, "allow", value) }
                    }.accessibilityIdentifier("agent.answer.send")
                    SecondaryButton(title: tr("deny"), destructive: true) { respond(r, "deny", nil) }.disabled(busy).accessibilityIdentifier("agent.deny")
                } else {
                    HStack {
                        PrimaryButton(title: tr("allow_once"), busy: busy) { respond(r, "allow", nil) }.accessibilityIdentifier("agent.allow")
                        SecondaryButton(title: tr("deny"), destructive: true) { respond(r, "deny", nil) }.accessibilityIdentifier("agent.deny")
                    }.disabled(busy)
                }
            }
        }
    }
    private func respond(_ r: AgentEvent, _ behavior: String, _ answers: JSON?) {
        guard !busy else { return }
        busy = true; responseMessage = nil
        Task {
            let ok = await hub.respond(r, behavior: behavior, answers: answers)
            busy = false; responseFailed = !ok
            responseMessage = ok ? tr("responded") : tr("not_delivered_or_expired_refresh_to_confirm")
        }
    }
    private func pretty(_ v: Any) -> String {
        if let d = try? JSONSerialization.data(withJSONObject: v, options: [.prettyPrinted, .sortedKeys]), let s = String(data: d, encoding: .utf8) { return s }
        return "\(v)"
    }
}

extension AgentEvent { func str(_ k: String) -> String { data[k] as? String ?? "" } }

struct UsageView: View {
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    @ObservedObject var hub: AgentHub
    @EnvironmentObject var env: AppEnvironment
    var body: some View {
        NavigationStack {
            List {
                ForEach(env.profiles.filter { hub.usages[$0.id] != nil }) { p in
                    Section(p.name) {
                        let accounts = hub.usages[p.id]?["accounts"] as? [JSON] ?? []
                        if accounts.isEmpty { Text((hub.usages[p.id]?["status"] as? String) ?? "—").foregroundColor(theme.secondary) }
                        ForEach(Array(accounts.enumerated()), id: \.offset) { _, a in
                            VStack(alignment: .leading, spacing: 4) {
                                Text("\(a["provider"] as? String ?? "") · \(a["accountLabel"] as? String ?? "")").font(.subheadline).foregroundColor(theme.text)
                                ForEach(Array((a["windows"] as? [JSON] ?? []).enumerated()), id: \.offset) { _, w in
                                    let used = (w["usedPercent"] as? Double) ?? Double((w["usedPercent"] as? Int) ?? 0)
                                    HStack { Text(w["label"] as? String ?? "").font(.caption); Spacer(); Text("\(Int(used))%").font(.caption) }.foregroundColor(theme.secondary)
                                    ProgressView(value: min(100, used), total: 100).tint(used > 85 ? theme.danger : theme.accent)
                                }
                            }
                        }
                    }.listRowBackground(theme.surface)
                }
            }.scrollContentBackground(.hidden).background(theme.canvas)
            .chrome(title: tr("agent_updates"), closeID: "sheet.close") { dismiss() }
            .toolbar(.hidden, for: .navigationBar)
        }.themed(theme)
    }
}
