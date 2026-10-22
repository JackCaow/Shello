"""Opt-in Linux CI fixture for the actual WSL helper/sshd/tmux, not Windows networking."""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest
import uuid
ROOT=Path(__file__).resolve().parents[1]

@unittest.skipUnless(os.environ.get('SHELLO_WSL_ROOT_TEST')=='1','isolated Linux CI fixture only')
class WslHostTest(unittest.TestCase):
    def test_owned_sshd_keeps_tmux_and_preserves_regular_service(self):
        owner=uuid.uuid4().hex[:16];user='shello_'+owner[:8];folder=Path('/var/lib/shello-wsl')/owner
        normal=Path('/etc/ssh/sshd_config').read_bytes()
        def root(*args,**kw):return subprocess.run(['sudo','-n',*map(str,args)],check=True,capture_output=True,text=True,**kw)
        daemon=None
        root('useradd','-m','-s','/bin/bash',user)
        try:
            with tempfile.TemporaryDirectory(prefix='shello-wsl-host-') as temp:
                key=Path(temp)/'client';subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(key)],check=True)
                home=Path('/home')/user
                root('install','-d','-m','700','-o',user,'-g',user,home/'.ssh')
                root('install','-m','600','-o',user,'-g',user,key.with_suffix('.pub'),home/'.ssh/authorized_keys')
                original=root('cat',home/'.ssh/authorized_keys').stdout
                with socket.socket() as reserve:reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1]
                def prepare():return json.loads(root('bash',ROOT/'companion/windows/wsl-host.sh',user,port,owner).stdout.splitlines()[-1])
                ready=prepare();self.assertFalse(ready['running']);self.assertEqual(str(folder/'sshd_config'),ready['config'])
                daemon=subprocess.Popen(['sudo','-n','/usr/sbin/sshd','-D','-e','-f',ready['config']],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                for _ in range(50):
                    try:
                        with socket.create_connection(('127.0.0.1',port),timeout=.2):break
                    except OSError:time.sleep(.1)
                self.assertTrue(prepare()['running'])
                ssh=['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=accept-new','-o','UserKnownHostsFile='+str(Path(temp)/'known'),'-i',str(key),'-p',str(port),user+'@127.0.0.1']
                def remote(command):return subprocess.check_output(ssh+[command],text=True).strip()
                remote('tmux -L shello-check new-session -d -s preserved "sleep 120"')
                before=remote("tmux -L shello-check display-message -p -t preserved '#{pane_pid}'")
                after=remote("tmux -L shello-check display-message -p -t preserved '#{pane_pid}'")
                self.assertTrue(before.isdigit());self.assertEqual(before,after)
                remote('tmux -L shello-check kill-server')
                self.assertEqual(original,root('cat',home/'.ssh/authorized_keys').stdout)
                self.assertEqual(normal,Path('/etc/ssh/sshd_config').read_bytes())
                # A foreign process on the requested port must not be accepted as our SSH daemon.
                with socket.socket() as foreign:
                    foreign.bind(('127.0.0.1',0));foreign.listen()
                    other_owner=uuid.uuid4().hex[:16]
                    other=subprocess.run(['sudo','-n','bash',str(ROOT/'companion/windows/wsl-host.sh'),user,str(foreign.getsockname()[1]),other_owner],capture_output=True,text=True)
                    self.assertNotEqual(0,other.returncode);self.assertIn('occupied',other.stderr)
                    # Remove only the fixture's second config, identified by its generated owner.
                    root('rm','-rf',Path('/var/lib/shello-wsl')/other_owner)
        finally:
            if daemon is not None:
                root('kill','-TERM',root('cat',folder/'sshd.pid').stdout.strip())
                daemon.wait(timeout=5)
            root('rm','-rf',folder)
            # PAM can leave systemd --user alive after SSH disconnects. This is a
            # newly created, fixture-only account; never target the runner's user.
            subprocess.run(['sudo','-n','pkill','-KILL','-u',user],capture_output=True)
            deadline=time.monotonic()+10
            while time.monotonic()<deadline:
                result=subprocess.run(['sudo','-n','userdel','--remove',user],capture_output=True,text=True)
                if result.returncode==0:break
                if result.returncode!=8:raise RuntimeError(result.stderr)
                time.sleep(.25)
            else:raise RuntimeError('Fixture account cleanup timed out')

if __name__=='__main__':unittest.main()
