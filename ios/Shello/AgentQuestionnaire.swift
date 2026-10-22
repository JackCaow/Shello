import Foundation
import ShelloServices

/// AskUserQuestion requires answers keyed by each exact question, not a generic "answer" field.
enum AgentQuestionnaire {
    static func titles(_ input: JSON) -> [String] {
        guard let questions = input["questions"] as? [JSON], !questions.isEmpty, questions.count <= 16 else { return [] }
        let titles = questions.compactMap { $0["question"] as? String }
        guard titles.count == questions.count, Set(titles).count == titles.count, titles.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { return [] }
        return titles
    }
    static func response(titles: [String], answers: [String: String]) -> JSON? {
        guard !titles.isEmpty, titles.allSatisfy({ title in
            guard let answer = answers[title] else { return false }
            return !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && answer.count <= 8192
        }) else { return nil }
        return Dictionary(uniqueKeysWithValues: titles.map { ($0, answers[$0]!) })
    }
}
