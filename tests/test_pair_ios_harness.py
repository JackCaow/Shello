import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/ios-test-pair.sh'


class PairingHarnessTest(unittest.TestCase):
    def run_fixture(self, mode, timeout=3):
        folder = Path(self.temp.name)
        executable = folder / 'python-fixture'
        executable.write_text('#!' + sys.executable + '\n' + textwrap.dedent('''
            import os, sys, time
            from pathlib import Path
            destination = Path(sys.argv[sys.argv.index('--qr-file') + 1])
            (destination.parent / 'child.pid').write_text(str(os.getpid()))
            mode = os.environ['FIXTURE_MODE']
            if mode == 'exit':
                print('fixture startup error', flush=True)
                raise SystemExit(17)
            if mode == 'slow':
                time.sleep(.5)
                destination.write_text('isolated fixture ready')
            time.sleep(30)
        '''))
        executable.chmod(0o755)
        env = dict(os.environ, PAIR_PYTHON=str(executable), FIXTURE_MODE=mode,
                   SHELLO_PAIR_START_TIMEOUT=str(timeout))
        return subprocess.run(['bash', str(SCRIPT), 'start', str(folder)], env=env,
                              capture_output=True, text=True, timeout=10)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()

    def tearDown(self):
        subprocess.run(['bash', str(SCRIPT), 'stop', self.temp.name], capture_output=True)
        self.temp.cleanup()

    def test_slow_start_waits_for_ready_file(self):
        result = self.run_fixture('slow')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((Path(self.temp.name) / 'qr.txt').is_file())

    def test_early_exit_reports_error_and_removes_pid(self):
        result = self.run_fixture('exit')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('fixture startup error', result.stderr)
        self.assertFalse((Path(self.temp.name) / 'pair.pid').exists())

    def test_timeout_stops_only_the_owned_child(self):
        result = self.run_fixture('never', timeout=1)
        self.assertNotEqual(result.returncode, 0)
        pid = int((Path(self.temp.name) / 'child.pid').read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
        self.assertFalse((Path(self.temp.name) / 'pair.pid').exists())


if __name__ == '__main__':
    unittest.main()
