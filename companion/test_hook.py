"""Host-side protocol tests; all files/settings live in disposable directories."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'sample/src/main/assets/ghostty_hook.py'
spec = importlib.util.spec_from_file_location('hook', SCRIPT)
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)


class HookTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        hook.STATE = Path(self.temp.name) / 'events'
        hook.SETTINGS = Path(self.temp.name) / 'settings.json'
        hook.CODEX_SETTINGS = Path(self.temp.name) / 'codex/hooks.json'
        hook.private_dir(hook.STATE)
        hook.WAIT = 2
        self.bridge=patch.object(hook,"configure_usage_bridge",return_value="test");self.bridge.start()
        self.version=patch.object(hook,"claude_version",return_value=None);self.version.start()

    def tearDown(self):
        self.version.stop()
        self.bridge.stop()
        self.temp.cleanup()

    def waiting(self, value, provider='claude'):
        self.result = []
        thread = threading.Thread(target=lambda: self.result.append(hook.hook(value, provider)))
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

    def test_old_claude_stop_recovers_own_last_reply_without_retaining_transcript(self):
        transcript = Path(self.temp.name) / 'session.jsonl'
        rows = [
            {'type':'assistant','sessionId':'other','message':{'role':'assistant','content':[{'type':'text','text':'wrong session'}]}},
            {'type':'user','sessionId':'session-1','message':{'role':'user','content':'current prompt'}},
            {'type':'assistant','sessionId':'session-1','message':{'role':'assistant','content':[{'type':'thinking','thinking':'private'},{'type':'text','text':'完成 👩🏽‍💻'}]}},
            {'type':'assistant','sessionId':'session-1','isSidechain':True,'message':{'role':'assistant','content':'wrong subagent'}},
        ]
        transcript.write_text('\n'.join(json.dumps(row) for row in rows))
        hook.hook({'hook_event_name':'Stop','session_id':'session-1','transcript_path':str(transcript)})
        event = hook.poll()['events'][0]
        self.assertEqual(event['last_assistant_message'],'完成 👩🏽‍💻')
        self.assertNotIn('transcript_path',event)
        self.assertNotIn('private',json.dumps(event))

    def test_transcript_fallback_does_not_reuse_previous_turn_or_follow_symlink(self):
        path = Path(self.temp.name) / 'session.jsonl'
        rows = [{'type':'assistant','sessionId':'s','message':{'role':'assistant','content':'previous reply'}},
                {'type':'user','sessionId':'s','message':{'role':'user','content':'new turn'}}]
        path.write_text('\n'.join(json.dumps(row) for row in rows))
        self.assertEqual(hook.claude_transcript_reply({'session_id':'s','transcript_path':str(path)}),'')
        link = Path(self.temp.name) / 'linked.jsonl';link.symlink_to(path)
        self.assertEqual(hook.claude_transcript_reply({'session_id':'s','transcript_path':str(link)}),'')

    def test_claude_optional_hooks_use_oldest_of_parallel_cli_installs(self):
        from types import SimpleNamespace
        self.version.stop()
        with patch.object(hook,'claude_executables',return_value=['old','new']), patch.object(hook.subprocess,'run',side_effect=[SimpleNamespace(stdout='2.1.19 (Claude Code)'),SimpleNamespace(stdout='2.1.306 (Claude Code)')]):
            self.assertEqual(hook.claude_version(),(2,1,19))
        self.version.start()

    def test_hook_registration_matches_claude_version(self):
        for version, expected in [((2,1,19),False),((2,1,77),False),((2,1,78),True),((3,0,0),True),(None,False)]:
            with patch.object(hook,'claude_version',return_value=version):
                hook.configure()
            kinds=hook.read(hook.SETTINGS)['hooks']
            self.assertEqual('StopFailure' in kinds,expected)
            self.assertIn('Stop',kinds)

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

    def test_codex_lifecycle_keeps_bounded_prompt_but_never_transcript_path(self):
        for kind in ('SessionStart', 'UserPromptSubmit', 'Stop', 'Interrupt', 'SessionEnd'):
            self.assertEqual(hook.hook({'hook_event_name':kind, 'session_id':'session-1', 'turn_id':'turn-1', 'cwd':'/tmp/项目', 'model':'test-model', 'last_assistant_message':'完成', 'prompt':'本轮请求 👩🏽‍💻', 'transcript_path':'PRIVATE'}, 'codex'), {})
        events=hook.poll()['events'];self.assertEqual(len(events),5)
        for event in events:
            self.assertEqual(event['provider'],'codex');self.assertEqual(event['session_id'],'session-1')
            self.assertEqual(event['turn_id'],'turn-1');self.assertEqual(event['status'],'info')
            self.assertNotIn('PRIVATE',json.dumps(event))
            self.assertEqual(event.get('prompt_preview'), '本轮请求 👩🏽‍💻' if event['hook_event_name']=='UserPromptSubmit' else None)
        # Claude-specific question updates are not Codex's input contract.
        hook.hook({'hook_event_name':'PreToolUse','tool_name':'AskUserQuestion'},'codex')
        self.assertEqual(len(hook.poll()['events']),5)

    def test_both_providers_preserve_multiline_last_turn_and_bound_utf8(self):
        for provider in ('claude','codex'):
            hook.hook({'hook_event_name':'UserPromptSubmit','session_id':provider,'prompt':'第一行\n中文 👨‍👩‍👧‍👦\n'+'汉'*9000},provider)
            hook.hook({'hook_event_name':'Stop','session_id':provider,'last_assistant_message':'已完成\n👩🏽‍💻'+'字'*9000},provider)
        for event in hook.poll()['events']:
            key='prompt_preview' if event['hook_event_name']=='UserPromptSubmit' else 'last_assistant_message'
            self.assertLessEqual(len(event[key].encode()),8195);self.assertTrue(event[key].endswith('…'));self.assertIn('\n',event[key]);self.assertNotIn('\ufffd',event[key]);self.assertNotIn('prompt',event)

    def test_busy_session_retains_its_last_prompt_after_history_pruning(self):
        import uuid
        now=time.time()
        def save(kind,created,prompt=None):
            event={'id':str(uuid.uuid4()),'created':created,'expires':0,'status':'info','provider':'codex','session_id':'busy','turn_id':'current','hook_event_name':kind}
            if prompt:event['prompt_preview']=prompt
            hook.atomic(hook.STATE/(event['id']+'.json'),event)
        save('UserPromptSubmit',now-300,'Current prompt 中文')
        for i in range(220):save('Stop',now-220+i)
        for _ in range(2):
            rows=hook.poll()['events'];self.assertTrue(any(e.get('prompt_preview')=='Current prompt 中文' for e in rows))

    def test_codex_permission_allow_and_deny_do_not_update_input_or_permissions(self):
        for behavior in ('allow','deny'):
            thread,event=self.waiting({'hook_event_name':'PermissionRequest','tool_name':'apply_patch','tool_input':{'command':'test patch'},'turn_id':'t'},'codex')
            self.assertEqual(event['provider'],'codex')
            hook.respond({'id':event['id'],'behavior':behavior});thread.join()
            response=self.result[0]['hookSpecificOutput']
            self.assertEqual(response['hookEventName'],'PermissionRequest')
            self.assertEqual(response['decision']['behavior'],behavior)
            self.assertEqual(set(response),{'hookEventName','decision'})
            for file in hook.STATE.glob('*.json'):file.unlink()

    def test_codex_timeout_leaves_normal_approval_flow(self):
        hook.WAIT=.15
        thread,event=self.waiting({'hook_event_name':'PermissionRequest','tool_name':'Bash'},'codex')
        thread.join();self.assertEqual(self.result,[{}])
        self.assertEqual(hook.poll()['events'][0]['status'],'expired')
        with self.assertRaises(ValueError):hook.respond({'id':event['id'],'behavior':'allow'})

    def test_codex_install_preserves_moshi_and_uninstall_is_owned_only(self):
        hook.CODEX_SETTINGS.parent.mkdir()
        existing={'hooks':{'Stop':[{'hooks':[{'type':'command','command':'moshi-hook codex-hook'}]}]},'other':{'keep':True}}
        hook.atomic(hook.CODEX_SETTINGS,existing)
        config=hook.CODEX_SETTINGS.parent/'config.toml';config.write_text('# keep\n[features]\nhooks = false\n')
        hook.configure();first=hook.read(hook.CODEX_SETTINGS);hook.configure()
        self.assertEqual(first,hook.read(hook.CODEX_SETTINGS))
        self.assertEqual(len(first['hooks']['Stop']),2)
        self.assertEqual(config.read_text(),'# keep\n[features]\nhooks = false\n')
        hook.configure(remove=True);self.assertEqual(hook.read(hook.CODEX_SETTINGS),existing)

    def test_malformed_codex_configuration_does_not_partially_change_claude(self):
        hook.CODEX_SETTINGS.parent.mkdir();hook.CODEX_SETTINGS.write_text('{bad')
        with self.assertRaises(ValueError):hook.configure()
        self.assertFalse(hook.SETTINGS.exists())

    def test_reject_traversal_and_unknown_decisions(self):
        with self.assertRaises(ValueError):
            hook.respond({'id': '../../settings', 'behavior': 'allow'})
        with self.assertRaises(ValueError):
            hook.decision({'hook_event_name': 'PermissionRequest'}, {'behavior': 'maybe'})


if __name__ == '__main__':
    unittest.main()
