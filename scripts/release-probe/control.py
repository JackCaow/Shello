"""Local client for disposable-emulator release acceptance; never points at a physical phone."""
import socket,json,subprocess,time,sys,os,shutil
from pathlib import Path
sdk=Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
adb_binary=shutil.which('adb') or str(sdk/'platform-tools/adb')
adb=[adb_binary,'-s',os.environ.get('SHELLO_TEST_SERIAL','emulator-5582')]
if not adb[-1].startswith('emulator-'):
 raise SystemExit('Release probe client requires a disposable emulator')
def probe(op,**kwargs):
 with socket.create_connection(('127.0.0.1',int(os.environ.get('SHELLO_PROBE_PORT','38765'))),timeout=30) as s:
  s.sendall((json.dumps(dict(op=op,**kwargs),ensure_ascii=False)+'\n').encode());f=s.makefile();r=json.loads(f.readline());assert r.get('ok'),r;return r

def nodes():return probe('tree')['nodes']
def show():
 for n in nodes():
  if n['text'] or n['desc']:print(n['text'] or n['desc'],n['bounds'],n['class'].split('.')[-1])
def tap(match):
 for n in nodes():
  if match in [n['text'],n['desc']]:
   l,t,r,b=n['bounds'];subprocess.run(adb+['shell','input','tap',str((l+r)//2),str((t+b)//2)],check=True);time.sleep(.5);return
 raise AssertionError('UI node not found: '+match)
def key(code):subprocess.run(adb+['shell','input','keyevent',str(code)],check=True)
def settext(match,value):probe('text',match=match,value=value)
def shot(name):
 with (Path('build/stable-release')/(Path(name).name+'.png')).open('wb') as f:subprocess.run(adb+['exec-out','screencap','-p'],stdout=f,check=True)
def wait(match,timeout=30):
 until=time.monotonic()+timeout
 while time.monotonic()<until:
  if any(match in [n['text'],n['desc']] for n in nodes()):return
  time.sleep(.5)
 raise AssertionError('Timed out: '+match)
if __name__=='__main__':
 if len(sys.argv)>1 and sys.argv[1]=='launch':probe('launch');time.sleep(1)
 show()
