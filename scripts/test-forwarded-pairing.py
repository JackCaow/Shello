"""Opt-in macOS / adb fixture for PairingForwardedDeviceTest; no Windows networking claim."""
import argparse,getpass,json,os,signal,socket,subprocess,tempfile,time
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--adb',default=str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
args=parser.parse_args()
root=Path(__file__).resolve().parents[1];adb=args.adb;serial=args.serial
artifacts=root/'build/windows-pairing';artifacts.mkdir(parents=True,exist_ok=True)
existing=subprocess.check_output([adb,'-s',serial,'reverse','--list'],text=True)
if any('tcp:'+str(port) in existing for port in (33442,33443)):
 raise SystemExit('Fixture forwarding ports already mapped; existing rules were preserved.')
with tempfile.TemporaryDirectory(prefix='shello-wsl-device-',dir=str(root/'build/windows-pairing')) as tmp:
 p=Path(tmp).resolve();host=p/'host';subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(host)],check=True)
 authorized=p/'authorized_keys';authorized.write_text('# keep fixture entry\n');authorized.chmod(0o600)
 def free():
  with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
 sshport,https=free(),free()
 config=p/'sshd_config';config.write_text(f'''ListenAddress 127.0.0.1
Port {sshport}
HostKey "{host}"
PidFile "{p/'sshd.pid'}"
AuthorizedKeysFile "{authorized}"
AllowUsers {getpass.getuser()}
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes yes
Subsystem sftp internal-sftp
''')
 with (p/'ssh.log').open('wb') as log,(p/'pair.log').open('wb') as plog:
  server=subprocess.Popen(['/usr/sbin/sshd','-D','-e','-f',str(config)],stdout=log,stderr=log,start_new_session=True);pair=None
  try:
   time.sleep(.3);assert server.poll() is None,'Private SSH fixture failed'
   qr=p/'ticket';env=dict(os.environ,SHELLO_PAIR_HOME=str(root/'build/pair-launcher-env'))
   pair=subprocess.Popen(['bash',str(root/'companion/pair.sh'),'--host','127.0.0.1','--port',str(https),'--ssh-port','33442','--ssh-check-port',str(sshport),'--advertise-port','33443','--name','Forwarded WSL contract fixture','--host-key',str(host.with_suffix('.pub')),'--authorized-keys',str(authorized),'--ttl','120','--qr-file',str(qr)],stdout=plog,stderr=plog,env=env,start_new_session=True)
   deadline=time.monotonic()+90
   while not qr.exists() and time.monotonic()<deadline and pair.poll() is None:time.sleep(.1)
   assert qr.exists(),'Private pairing fixture failed'
   for external,internal in [(33442,sshport),(33443,https)]:subprocess.run([adb,'-s',serial,'reverse',f'tcp:{external}',f'tcp:{internal}'],check=True,stdout=subprocess.DEVNULL)
   subprocess.run([adb,'-s',serial,'shell','-T','run-as dev.ghostty.android.sample tee cache/forwarded-pair-ticket'],input=qr.read_bytes(),stdout=subprocess.DEVNULL,check=True)
   result=subprocess.run([adb,'-s',serial,'shell','am','instrument','-w','-e','class','dev.ghostty.android.sample.PairingForwardedDeviceTest','-e','forwardedPairing','true','dev.ghostty.android.sample.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True)
   (root/'build/windows-pairing/forwarded-device.log').write_text(result.stdout+result.stderr)
   (root/'build/windows-pairing/fixture-ssh.log').write_bytes((p/'ssh.log').read_bytes())
   assert 'OK (1 test)' in result.stdout,'Android forwarded pairing test failed; inspect local sanitized test result'
   assert authorized.read_text().startswith('# keep fixture entry\n')
   print('PASS: API 27 TLS pin, public-key install, non-22 SSH, encrypted save and tmux reattachment.')
  finally:
   for port in (33442,33443):subprocess.run([adb,'-s',serial,'reverse','--remove',f'tcp:{port}'],capture_output=True)
   for proc in (pair,server):
    if proc is not None and proc.poll() is None:
     os.killpg(proc.pid,signal.SIGTERM)
     try:proc.wait(timeout=5)
     except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGKILL);proc.wait()
