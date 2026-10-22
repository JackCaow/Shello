import XCTest
import ShelloServices
@testable import Shello

final class AgentBoxTests: XCTestCase {
    func testQuestionAnswersMatchHostProtocolAndAreComplete() {
        let titles = AgentQuestionnaire.titles(["questions": [["question": "选择语言？"], ["question": "运行方式？"]]])
        XCTAssertNil(AgentQuestionnaire.response(titles: titles, answers: ["选择语言？": "中文"]))
        let reply = AgentQuestionnaire.response(titles: titles, answers: ["选择语言？": "中文 👩🏽‍💻", "运行方式？": "只读", "stale question": "discard"])
        XCTAssertEqual(reply as? [String: String], ["选择语言？": "中文 👩🏽‍💻", "运行方式？": "只读"])
        XCTAssertNil(reply?["answer"])
    }
    func testMalformedOrEmptyQuestionCannotSubmit() {
        let inputs: [JSON] = [[:], ["questions": []], ["questions": [["question": "duplicate"], ["question": "duplicate"]]], ["questions": [["question": "  "]]]]
        for input in inputs {
            XCTAssertTrue(AgentQuestionnaire.titles(input).isEmpty)
        }
        XCTAssertNil(AgentQuestionnaire.response(titles: ["question"], answers: ["question": " \n"]))
    }
    func testSameSessionIDIsSeparatedByProviderAndOnlyShowsCurrentTurnReply() {
        let now = Date().timeIntervalSince1970
        func event(_ id: String, _ provider: String, _ kind: String, _ seconds: Double, _ extra: JSON) -> AgentEvent {
            var data: JSON = ["id": id, "provider": provider, "session_id": "same-session", "hook_event_name": kind, "created": now + seconds]
            data.merge(extra) { _, latest in latest }
            return AgentEvent(profile: "fixture", host: "Mac", data: data)
        }
        let events = [event("1", "claude", "Stop", -5, ["last_assistant_message": "old reply"]),
                      event("2", "claude", "UserPromptSubmit", -3, ["turn_id": "new", "prompt_preview": "new prompt"]),
                      event("3", "codex", "Stop", -2, ["last_assistant_message": "Codex reply"])]
        let sessions = AgentInbox.sessions(events, defaults: UserDefaults(suiteName: UUID().uuidString)!)
        XCTAssertEqual(sessions.count, 2)
        XCTAssertEqual(sessions.first { $0.provider == "claude" }?.summary, "new prompt")
        XCTAssertEqual(sessions.first { $0.provider == "codex" }?.summary, "Codex reply")
    }
}
