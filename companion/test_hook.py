"""Host-side protocol tests; all files/settings live in disposable directories."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'sample/src/main/assets/ghostty_hook.py'
spec = importlib.util.spec_from_file_location('hook', SCRIPT)
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)


class HookTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        hook.STATE = Path(self.temp.name) / 'events'
        hook.SETTINGS = Path(self.temp.name) / 'settings.json'
        hook.private_dir(hook.STATE)
        hook.WAIT = 2

    def tearDown(self):
        self.temp.cleanup()

    def waiting(self, value):
        self.result = []
        thread = threading.Thread(target=lambda: self.result.append(hook.hook(value)))
        thread.start()
        for _ in range(100):
            entries = hook.poll()['events']
            if entries:
                return thread, entries[0]
            time.sleep(.01)
        self.fail('hook did not publish')

    def test_permission_response_exactly_once(self):
        thread, event = self.waiting({'hook_event_name': 'PermissionRequest', 'tool_name': 'Bash', 'tool_input': {'command': 'echo example'}})
        hook.respond({'id': event['id'], 'behavior': 'allow'})
        with self.assertRaises(ValueError):
            hook.respond({'id': event['id'], 'behavior': 'deny'})
        thread.join()
        self.assertEqual(self.result[0], {'hookSpecificOutput': {'hookEventName': 'PermissionRequest', 'decision': {'behavior': 'allow'}}})
        self.assertEqual(hook.poll()['events'][0]['status'], 'answered')

    def test_expiry_never_grants_permission(self):
        hook.WAIT = .15
        thread, event = self.waiting({'hook_event_name': 'PermissionRequest', 'tool_name': 'Bash', 'tool_input': {}})
        thread.join()
        self.assertEqual(self.result, [{}])
        with self.assertRaises(ValueError):
            hook.respond({'id': event['id'], 'behavior': 'allow'})

    def test_question_answers_preserve_original_input(self):
        original = {'questions': [{'question': '选择语言？', 'options': [{'label': '中文'}]}]}
        thread, event = self.waiting({'hook_event_name': 'PreToolUse', 'tool_name': 'AskUserQuestion', 'tool_input': original})
        with self.assertRaises(ValueError):
            hook.respond({'id': event['id'], 'behavior': 'allow', 'answers': {}})
        hook.respond({'id': event['id'], 'behavior': 'allow', 'answers': {'选择语言？': '中文'}})
        thread.join()
        response = self.result[0]['hookSpecificOutput']
        self.assertEqual(response['permissionDecision'], 'allow')
        self.assertEqual(response['updatedInput']['questions'], original['questions'])
        self.assertEqual(response['updatedInput']['answers'], {'选择语言？': '中文'})

    def test_completion_has_no_approval(self):
        hook.hook({'hook_event_name': 'Stop', 'last_assistant_message': '完成 👩🏽‍💻'})
        event = hook.poll()['events'][0]
        self.assertEqual(event['last_assistant_message'], '完成 👩🏽‍💻')
        with self.assertRaises(ValueError):
            hook.respond({'id': event['id'], 'behavior': 'allow'})

    def test_install_idempotent_uninstall_preserves_other_hooks(self):
        existing = {'model': 'keep-this', 'hooks': {'Stop': [{'hooks': [{'type': 'command', 'command': 'echo keep'}]}]}}
        hook.atomic(hook.SETTINGS, existing)
        hook.configure()
        first = hook.read(hook.SETTINGS)
        hook.configure()
        self.assertEqual(first, hook.read(hook.SETTINGS))
        hook.configure(remove=True)
        self.assertEqual(hook.read(hook.SETTINGS), existing)
        self.assertTrue(list(hook.SETTINGS.parent.glob('settings.ghostty-backup-*.json')))

    def test_invalid_settings_not_overwritten(self):
        hook.SETTINGS.write_text('{bad json')
        with self.assertRaises(ValueError):
            hook.configure()
        self.assertEqual(hook.SETTINGS.read_text(), '{bad json')

    def test_reject_traversal_and_unknown_decisions(self):
        with self.assertRaises(ValueError):
            hook.respond({'id': '../../settings', 'behavior': 'allow'})
        with self.assertRaises(ValueError):
            hook.decision({'hook_event_name': 'PermissionRequest'}, {'behavior': 'maybe'})


if __name__ == '__main__':
    unittest.main()
