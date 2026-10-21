#!/usr/bin/env python3
"""Physical Android + existing SSH/tmux: lock, Wi-Fi fallback/return and process-death recovery.

Requires an unlocked debug app already connected to an idle dedicated Bash fixture.
No provisioning or new tmux session is performed during recovery. USB is observation only.
Use --ssh-alias/--fixture-dir for a second Mac; its tmux path can be overridden.
Restores Wi-Fi, simulated battery state and stay-awake in finally. Do not touch the phone
while locked. Output contains test process IDs/state only, not profiles or network addresses.
"""
import argparse
import json
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as E
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--case',choices=['shell','vim'],required=True)
parser.add_argument('--lock-seconds',type=int,default=720)
parser.add_argument('--wifi-outage-seconds',type=int,default=90)
parser.add_argument('--ssh-alias')
parser.add_argument('--fixture-dir',default=str(Path.home()/'.cache/ghostty-android-ssh-test'))
parser.add_argument('--tmux',default='/opt/homebrew/bin/tmux')
parser.add_argument('--adb',default=str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
args=parser.parse_args()
if args.lock_seconds<120 or not 1<=args.wifi_outage_seconds<args.lock_seconds//2:
 parser.error('lock must be >=120s; outage must be positive and shorter than half the lock')
if args.ssh_alias and args.ssh_alias.startswith('-'):parser.error('invalid SSH alias')
out=args.output;out.mkdir(parents=True,exist_ok=True)
base=[args.adb,'-s',args.serial];pkg='dev.ghostty.android.sample'
root=Path(args.fixture_dir);work=root/'workspace'/('OEM_'+uuid.uuid4().hex[:8]);token=work.name

def adb(*a):return subprocess.check_output(base+list(a),timeout=30)
def shell(*a):return adb('shell',shlex.join(a))
def backend(*a):
 command=list(a)
 if args.ssh_alias:command=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=8',args.ssh_alias,shlex.join(command)]
 return subprocess.check_output(command,text=True,timeout=20).strip()
def tmux(*a):return backend(args.tmux,'-S',str(root/'tmux.sock'),*a)
def read(name):return backend('cat',str(work/name))
def clients():return tmux('list-clients','-F','#{client_pid}')
def pid():
 result=subprocess.run(base+['shell','pidof',pkg],capture_output=True,text=True,timeout=15)
 return result.stdout.strip()
def nodes():
 shell('rm','-f','/sdcard/ghostty-device-test.xml')
 shell('uiautomator','dump','/sdcard/ghostty-device-test.xml')
 return list(E.fromstring(shell('cat','/sdcard/ghostty-device-test.xml')).iter('node'))
def tap(n,long=False):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));x=str((x1+x2)//2);y=str((y1+y2)//2)
 shell('input',*(['swipe',x,y,x,y,'700'] if long else ['tap',x,y]))
def desc(s,long=False):tap(next(n for n in nodes() if n.get('package')==pkg and n.get('content-desc')==s),long)
def command(s,enter=True):
 assert any(n.get('package')==pkg and '已连接 · tmux' in n.get('text','') for n in nodes()),'Target app is not connected; refusing input'
 if not any(n.get('content-desc')=='关闭文本框，保留草稿' for n in nodes()):desc('编辑文本')
 desc('本地文本草稿')
 actual=next(n.get('text','') for n in nodes() if n.get('content-desc')=='本地文本草稿')
 assert actual in ('','对终端说…'), 'Unexpected draft; refusing to send'
 assert '%s' not in s
 for offset in range(0,len(s),24):shell('input','text',s[offset:offset+24].replace(' ','%s'))
 actual=next(n.get('text') for n in nodes() if n.get('content-desc')=='本地文本草稿')
 assert actual==s,'Incomplete input injection; refusing to send'
 desc('发送并回车；长按仅发送',not enter)
def connected():
 deadline=time.monotonic()+90
 while time.monotonic()<deadline:
  if any('已连接 · tmux' in n.get('text','') for n in nodes()) and clients():return
  time.sleep(1)
 raise AssertionError('Connection did not recover')
def launch():
 shell('input','keyevent','224');shell('wm','dismiss-keyguard')
 shell('am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',pkg+'/.MainActivity')
 # A real secure lock cannot be dismissed by shell; allow the operator to authenticate.
 print('Launcher requested; unlock the phone with its normal authentication if needed.',flush=True)
 deadline=time.monotonic()+600
 while time.monotonic()<deadline:
  if any(n.get('package')==pkg for n in nodes()):break
  time.sleep(2)
 else:raise AssertionError('Phone was not unlocked within 10 minutes')
 connected()
def sample(elapsed):
 power=shell('dumpsys','power').decode();service=shell('dumpsys','activity','services',pkg).decode()
 idle=shell('dumpsys','deviceidle').decode()
 routes=shell('ip','route').decode()
 return dict(elapsed_seconds=round(elapsed,1),app_pid=pid(),ssh_clients=clients(),
  wakefulness=re.search(r'mWakefulness=(\w+)',power).group(1),foreground='isForeground=true' in service,
  activity_bound='ConnectionRecord{' in service,
  wake_lock_disabled=bool(re.search(r"'ghostty:SSHSession'.*DISABLED",power)),
  device_idle=re.search(r'mState=(\w+)',idle).group(1),
  wifi_route='dev wlan0' in routes,cellular_route='dev rmnet' in routes)

assert shell('getprop','ro.kernel.qemu').strip()!=b'1','Physical devices only'
assert shell('settings','get','global','wifi_on').strip() in (b'1',b'2'),'Requires initially enabled Wi-Fi'
assert tmux('display-message','-p','#{pane_current_command}')=='bash','Requires dedicated idle Bash'
assert b'Updates stopped' not in shell('dumpsys','battery'),'Reset existing battery simulation first'
# Direct profile route is required: loopback via adb reverse would invalidate network acceptance.
profile=shell('run-as',pkg,'cat','files/ssh/connection.properties').decode()
assert not re.search(r'^host=(127\.|localhost|::1)',profile,re.M),'Direct SSH network profile required'
old_stay=shell('settings','get','global','stay_on_while_plugged_in').decode().strip()
results=dict(case=args.case,device_sdk=shell('getprop','ro.build.version.sdk').decode().strip(),
 battery_simulated_unplug=True,usb_observation=True,samples=[])
try:
 connected()
 command('mkdir -p '+shlex.quote(str(work))+'; cd '+shlex.quote(str(work))+'; export OEM_TOKEN='+token+'; echo $$ > shell.pid')
 shell_pid=read('shell.pid');results['shell_pid']=shell_pid
 if args.case=='vim':
  command("vim -Nu NONE -n -c 'call writefile([string(getpid())], \"vim.pid\")' draft.txt")
  vim_pid=read('vim.pid');results['vim_pid']=vim_pid
  command('iUNSAVED_'+token,False)
 before=sample(0);results['before']=before
 shell('settings','put','global','stay_on_while_plugged_in','0');shell('dumpsys','battery','unplug')
 shell('input','keyevent','3');shell('input','keyevent','223')
 time.sleep(3);started=time.monotonic();off=False;on=False
 print('Lock started; keep phone locked for '+str(args.lock_seconds)+' seconds',flush=True)
 while time.monotonic()-started<args.lock_seconds:
  elapsed=time.monotonic()-started
  if elapsed>=args.lock_seconds//4 and not off:
   shell('svc','wifi','disable');off=True;results['wifi_off_seconds']=round(elapsed,1)
  if elapsed>=args.lock_seconds//4+args.wifi_outage_seconds and not on:
   shell('svc','wifi','enable');on=True;results['wifi_on_seconds']=round(elapsed,1)
  state=sample(elapsed);results['samples'].append(state)
  (out/'progress.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(state),flush=True)
  assert state['wakefulness'] in ('Dozing','Asleep'),'Phone woke; continuous lock test invalid'
  time.sleep(min(30,max(0,args.lock_seconds-(time.monotonic()-started))))
 results['lock_seconds']=round(time.monotonic()-started,1)
 started=time.monotonic();launch();results['reopen_seconds']=round(time.monotonic()-started,2)
 # Prove the original shell or unsaved Vim survives both a network outage and app death.
 results['after_lock']=sample(0)
 if args.case=='vim':backend('kill','-0',vim_pid)
 before_pid=pid();shell('input','keyevent','3');shell('run-as',pkg,'kill','-9',before_pid)
 time.sleep(3);assert not pid();launch();assert pid()!=before_pid
 results['process_kill']={'old_pid':before_pid,'new_pid':pid(),'ordinary_launcher':True}
 if args.case=='vim':
  backend('kill','-0',vim_pid);desc('关闭文本框，保留草稿');desc('Escape');command(':wq')
  assert read('draft.txt')=='UNSAVED_'+token,'Unsaved Vim contents lost'
 command('echo $$ > result.txt; echo $OEM_TOKEN >> result.txt; basename "$PWD" >> result.txt')
 assert read('result.txt').splitlines()==[shell_pid,token,token],'Shell identity/state changed'
 results['passed']=True
 (out/'result.json').write_text(json.dumps(results,indent=2)+'\n');print('PASS original remote state preserved',flush=True)
finally:
 shell('svc','wifi','enable');shell('dumpsys','battery','reset')
 if old_stay=='null':shell('settings','delete','global','stay_on_while_plugged_in')
 else:shell('settings','put','global','stay_on_while_plugged_in',old_stay)
 shell('input','keyevent','224');shell('wm','dismiss-keyguard')
