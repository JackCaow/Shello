"""Agent history and resume contract; never touches real histories or invokes an agent."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location('helper', Path(__file__).resolve().parents[1] / 'sample/src/main/assets/ghostty_hook.py')
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
C = '11111111-2222-3333-4444-555555555555'
X = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

class RecentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        h.AGENT_HOME = Path(self.temp.name)
        self.cwd = h.AGENT_HOME / "项目 with 'quotes'"
        self.cwd.mkdir()
        self.project = h.AGENT_HOME / '.claude/projects/test'
        self.project.mkdir(parents=True)
        self.index = self.project / 'sessions-index.json'
        self.index.write_text(json.dumps({'entries': [{'sessionId': C, 'projectPath': str(self.cwd), 'modified': '2026-10-21T00:00:00Z', 'firstPrompt': 'PRIVATE_PROMPT'}]}))
        (h.AGENT_HOME / '.codex').mkdir()
    def tearDown(self):
        self.temp.cleanup()
    def test_metadata_deduplication_and_readonly_database(self):
        (self.project / (C+'.jsonl')).write_text(json.dumps({'sessionId': C, 'cwd': str(self.cwd), 'message': 'PRIVATE_MESSAGE'})+'\n')
        db = h.AGENT_HOME / '.codex/state_5.sqlite'
        con = sqlite3.connect(db)
        con.execute('create table threads(id text,cwd text,updated_at integer,archived integer,title text)')
        con.executemany('insert into threads values(?,?,?,?,?)', [(X,str(self.cwd),123,0,'PRIVATE_TITLE'),(C,str(self.cwd),124,1,'ARCHIVED')])
        con.commit();con.close()
        before = db.read_bytes()
        result = h.recent_agents()
        self.assertEqual(len(result['sessions']),2)
        self.assertEqual({e['provider'] for e in result['sessions']},{'claude','codex'})
        self.assertNotIn('PRIVATE',json.dumps(result))
        self.assertEqual(before,db.read_bytes())
    def test_resume_argv_and_cwd_no_shell_interpolation(self):
        with patch.object(h,'binary',return_value='/test/claude'):
            cwd,args=h.resume_plan('claude',C)
        self.assertEqual(cwd,str(self.cwd));self.assertEqual(args,['/test/claude','--resume',C])
        for provider,ident in [('claude',C+';id'),('shell',C),('codex',C)]:
            with self.assertRaises(ValueError):h.resume_plan(provider,ident)
    def test_legacy_codex_and_malformed_files(self):
        self.index.write_text('{broken')
        folder=h.AGENT_HOME/'.codex/sessions/2026/10/21';folder.mkdir(parents=True)
        (folder/'rollout.jsonl').write_text(json.dumps({'type':'session_meta','payload':{'id':X,'cwd':str(self.cwd)}})+'\nPRIVATE_TRANSCRIPT')
        result=h.recent_agents()['sessions'];self.assertEqual(len(result),1);self.assertEqual(result[0]['provider'],'codex')
    def test_existing_tmux_attach_does_not_need_history(self):
        with patch.object(h,'binary',return_value='/test/tmux'),patch.object(h.subprocess,'run',return_value=Mock(returncode=0)),patch.object(h,'resume_plan',side_effect=AssertionError('must not read history')),patch.object(h.os,'execv',side_effect=SystemExit) as execute:
            with self.assertRaises(SystemExit):h.resume_agent('claude',C)
        self.assertEqual(execute.call_args.args[1],['/test/tmux','attach-session','-t','=ghostty_agent_claude_'+C.replace('-','')])

if __name__=='__main__':unittest.main()
