import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class ReleaseTest(unittest.TestCase):
    def test_tag_must_match_android_version(self):
        env = dict(os.environ, GITHUB_EVENT_NAME='push', GITHUB_REF_TYPE='tag', GITHUB_REF_NAME='v999.0.0')
        result = subprocess.run(['python3', str(ROOT / 'scripts/release-metadata.py')], env=env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Tag must exactly match', result.stderr)

    def test_manual_metadata_does_not_require_a_tag(self):
        env = dict(os.environ, GITHUB_EVENT_NAME='workflow_dispatch')
        env.pop('GITHUB_OUTPUT', None)
        result = subprocess.run(['python3', str(ROOT / 'scripts/release-metadata.py')], env=env, capture_output=True, text=True, check=True)
        self.assertIn('tag=v', result.stdout)

    def publish(self, existing='', fail_upload=False):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            log = folder / 'calls'
            gh = folder / 'gh'
            gh.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with open(os.environ['CALL_LOG'], 'a') as out: out.write(json.dumps(sys.argv[1:])+'\\n')
if sys.argv[1] == 'api': print(os.environ.get('EXISTING',''))
if sys.argv[1:3] == ['release','upload'] and os.environ['FAIL_UPLOAD'] == '1': sys.exit(1)
''')
            gh.chmod(0o755)
            for name in ('Shello-1.2.3-alpha01-arm64-v8a.apk', 'shello-pair.sh', 'signature.txt'):
                (folder / name).write_text('test artifact')
            import hashlib
            (folder / 'SHA256SUMS').write_text(''.join(hashlib.sha256((folder / name).read_bytes()).hexdigest()+'  '+name+'\n' for name in ('Shello-1.2.3-alpha01-arm64-v8a.apk','shello-pair.sh')))
            env = dict(os.environ, PATH=temp+os.pathsep+os.environ['PATH'], CALL_LOG=str(log), EXISTING=existing,
                       FAIL_UPLOAD=str(int(fail_upload)), GH_REPO='example/Shello', RELEASE_TAG='v1.2.3-alpha01')
            result = subprocess.run(['bash', str(ROOT / 'scripts/publish-release.sh'), temp], env=env, capture_output=True, text=True)
            return result, [json.loads(line) for line in log.read_text().splitlines()]

    def test_new_release_publishes_only_after_upload(self):
        result, calls = self.publish()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['api','release','release','release'], [call[0] for call in calls])
        self.assertEqual('create', calls[1][1]); self.assertIn('--draft', calls[1]); self.assertIn('--prerelease', calls[1])
        self.assertEqual('upload', calls[2][1]); self.assertEqual('edit', calls[3][1]); self.assertIn('--draft=false', calls[3])

    def test_failed_upload_leaves_draft(self):
        result, calls = self.publish(fail_upload=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(call[:2] == ['release','edit'] for call in calls))

    def test_retry_preserves_published_assets(self):
        result, calls = self.publish(existing='v1.2.3-alpha01\tfalse')
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, len(calls))

    def test_existing_draft_can_resume(self):
        result, calls = self.publish(existing='v1.2.3-alpha01\ttrue')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any(call[:2] == ['release','create'] for call in calls))
        self.assertEqual('edit', calls[-1][1])

if __name__ == '__main__': unittest.main(verbosity=2)
