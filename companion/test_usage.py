"""Read-only quota adapters: disposable credentials and a local JSON-RPC stub only."""
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch,Mock
from urllib.error import HTTPError

spec=importlib.util.spec_from_file_location('usage_helper',Path(__file__).resolve().parents[1]/'public/agent/ghostty_hook.py')
h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)

class UsageTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();h.AGENT_HOME=Path(self.temp.name);h.STATE=h.AGENT_HOME/'state';h.private_dir(h.STATE)
    def tearDown(self):self.temp.cleanup()
    def credentials(self,token='FAKE_ACCESS_TOKEN'):
        folder=h.AGENT_HOME/'.claude';folder.mkdir(exist_ok=True)
        (folder/'.credentials.json').write_text(json.dumps({'claudeAiOauth':{'accessToken':token,'refreshToken':'DO_NOT_READ_REFRESH','subscriptionType':'pro'}}))
        (h.AGENT_HOME/'.claude.json').write_text(json.dumps({'oauthAccount':{'accountUuid':'fixture-account','emailAddress':'fixture@example.invalid'}}))
    def test_codex_uses_account_rpc_and_keeps_dynamic_windows(self):
        binary=h.AGENT_HOME/'codex-stub'
        binary.write_text('#!'+sys.executable+'\n'+'''import sys,json
for line in sys.stdin:
 m=json.loads(line)
 if 'id' not in m:continue
 if m['method']=='initialize':result={}
 elif m['method']=='account/read':result={'account':{'type':'chatgpt','email':'fixture@example.invalid'}}
 elif m['method']=='account/rateLimits/read':result={'rateLimitsByLimitId':{'codex':{'primary':{'usedPercent':31,'windowDurationMins':300,'resetsAt':2000000000},'secondary':{'usedPercent':72,'windowDurationMins':10080,'resetsAt':2000100000}}}}
 else:raise SystemExit('unexpected RPC')
 print(json.dumps({'id':m['id'],'result':result}),flush=True)
''');binary.chmod(0o700)
        with patch.object(h,'binary',return_value=str(binary)):result=h.codex_usage()
        self.assertEqual(result['status'],'ok');self.assertEqual([w['usedPercent'] for w in result['windows']],[31,72]);self.assertTrue(result['windows'][1]['label'].endswith('7d'))
    def test_claude_oauth_is_host_only_and_account_id_survives_token_rotation(self):
        self.credentials();payload={'five_hour':{'utilization':23.5,'resets_at':'2026-10-21T18:00:00Z'},'seven_day':{'utilization':26,'resets_at':None}}
        with patch.object(h,'sys') as platform,patch('urllib.request.urlopen',side_effect=lambda request,timeout:io.BytesIO(json.dumps(payload).encode())) as fetch:
            platform.platform='linux';first=h.claude_usage();self.credentials('ROTATED_FAKE_TOKEN');second=h.claude_usage()
        self.assertEqual(first['accountId'],second['accountId']);self.assertEqual(first['windows'][0]['usedPercent'],23.5)
        self.assertIsNone(first['windows'][1]['resetsAt']);self.assertNotIn('FAKE',json.dumps(first));self.assertNotIn('DO_NOT_READ_REFRESH',json.dumps(first))
        self.assertEqual(fetch.call_count,2)
    def test_auth_error_does_not_turn_into_zero_usage(self):
        self.credentials()
        with patch('urllib.request.urlopen',side_effect=HTTPError('https://api.anthropic.com/api/oauth/usage',401,'unauthorized',{},None)):result=h.claude_usage()
        self.assertEqual(result['status'],'unavailable');self.assertEqual(result['windows'],[]);self.assertNotIn('FAKE',json.dumps(result))
    def test_cache_limits_refreshes_and_contains_no_credentials(self):
        a={'provider':'codex','status':'ok','windows':[{'label':'5h','usedPercent':17}]}
        with patch.object(h,'codex_usage',return_value=a) as codex,patch.object(h,'claude_usage',return_value=h.usage_error('claude','not_signed_in','Sign in')) as claude:
            first=h.usage_direct();self.assertEqual(first,h.usage_direct(True));self.assertEqual(codex.call_count,1);self.assertEqual(claude.call_count,1)
        self.assertEqual(first['accounts'][0]['windows'][0]['usedPercent'],17)
        self.assertEqual(list(h.STATE.glob('*.json')),[])
    def test_missing_credential_is_an_explicit_state(self):
        with patch.object(h.subprocess,'run',return_value=Mock(returncode=1)):result=h.claude_usage()
        self.assertEqual(result['status'],'not_signed_in');self.assertEqual(result['windows'],[])

if __name__=='__main__':unittest.main()
