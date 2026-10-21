#!/usr/bin/env python3
"""Verify foreground SSH ownership through Home, screen-off and Activity configuration recreation.

Requires an unlocked API 27 emulator, the sample already connected to the managed tmux fixture,
and an idle Bash pane. Temporarily changes emulator display size and stay-awake settings.
Restores those settings in finally. Writes screenshots and lifecycle/service evidence.
"""
import argparse,subprocess,sys,xml.etree.ElementTree as E,re,time,shlex,json,os,uuid
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--adb',default=str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
args=parser.parse_args();serial=args.serial
base=[args.adb,'-s',serial]
out=args.output;out.mkdir(parents=True,exist_ok=True)
root=Path.home()/'.cache/ghostty-android-ssh-test'
def adb(*args):return subprocess.check_output(base+list(args))
def shell(*args):return adb('shell',shlex.join(args))
def nodes():
 shell('uiautomator','dump','/sdcard/ghostty-auto.xml')
 return list(E.fromstring(shell('cat','/sdcard/ghostty-auto.xml')).iter('node'))
def tap(n):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
 shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
def desc(name,long=False):
 n=next(n for n in nodes() if n.get('content-desc')==name)
 if not long:tap(n);return
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));x=str((x1+x2)//2);y=str((y1+y2)//2)
 shell('input','swipe',x,y,x,y,'700')
def command(text,enter=True):
 # Android's input utility reserves %s for spaces, so avoid printf %s in test commands.
 assert '%s' not in text
 shell('input','text',text.replace(' ','%s'))
 desc('发送并回车；长按仅发送',not enter)
 nodes()
def status():
 return next(n.get('text','') for n in nodes() if n.get('text','').startswith('本机 ·'))
def capture(name):
 (out/name).write_bytes(adb('exec-out','screencap','-p'))
def wait_connected(timeout=80):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  s=status()
  if '已连接 · tmux' in s:return
 raise AssertionError('Never reconnected: '+s)
work=root/'workspace'/('BACKGROUND_'+uuid.uuid4().hex[:8]);token=work.name
pkg='dev.ghostty.android.sample'
old_size=shell('wm','size').decode()
old_stay=shell('settings','get','global','stay_on_while_plugged_in').decode().strip()

def client():return subprocess.check_output(['tmux','-S',str(root/'tmux.sock'),'list-clients','-F','#{client_pid}'],text=True).strip()
def service():return shell('dumpsys','activity','services',pkg).decode()
def power():return shell('dumpsys','power').decode()
def app_pid():return shell('pidof',pkg).decode().strip()
def resume():
 shell('input','keyevent','224');shell('wm','dismiss-keyguard')
 shell('am','start','-n',pkg+'/.MainActivity')
 wait_connected()
def evidence(label):
 text=service();assert 'isForeground=true' in text
 (out/(label+'-service.txt')).write_text(text)
 assert app_pid()==pid
 assert re.search(r'ServiceRecord\{(\w+)',text).group(1)==service_id
 assert 'ghostty:SSHSession' in power()

def cycle(label,lock=False,recreate=False):
 before=client();assert before
 if recreate:resume()
 if lock:
  shell('svc','power','stayon','false');shell('input','keyevent','223')
  assert 'mWakefulness=Asleep' in power()
  print(label+': screen off for 45 seconds',flush=True)
  time.sleep(45)
  assert 'mWakefulness=Asleep' in power()
 elif recreate:
  before_events=shell('logcat','-b','events','-d','-v','brief').decode(errors='replace')
  previous_relaunches=sum(pkg in line and 'am_relaunch_resume_activity' in line for line in before_events.splitlines())
  shell('wm','size','1000x1800');time.sleep(3)
  wait_connected()
  events=shell('logcat','-b','events','-d','-v','brief').decode(errors='replace')
  events='\n'.join(x for x in events.splitlines() if pkg in x and ('am_relaunch' in x or 'am_on_' in x))
  (out/(label+'-lifecycle.txt')).write_text(events+'\n')
  assert sum('am_relaunch_resume_activity' in line for line in events.splitlines())>previous_relaunches

 else:
  shell('input','keyevent','3');time.sleep(8)
 evidence(label)
 assert client()==before,'Background operation replaced SSH connection'
 resume();assert client()==before
 capture(label+'.png')
 if recreate:
  override=re.search(r'Override size: (\d+x\d+)',old_size)
  shell('wm','size',override.group(1) if override else 'reset');wait_connected()
 print(label+': same SSH client '+before,flush=True)
 return before

original_command=command
def command(text,enter=True):
 desc('本地文本草稿')
 original_command(text,enter)

assert shell('getprop','ro.kernel.qemu').strip()==b'1', 'Emulator only'
assert shell('getprop','ro.build.version.sdk').strip()==b'27', 'Targets API 27'
assert json.loads((root/'fixture.json').read_text())['mode']=='tmux'
assert subprocess.check_output(['tmux','-S',str(root/'tmux.sock'),'display-message','-p','#{pane_current_command}'],text=True).strip()=='bash', 'Requires idle Bash fixture'
try:
 resume();pid=app_pid();service_id=re.search(r'ServiceRecord\{(\w+)',service()).group(1)
 if not any(n.get('content-desc')=='关闭文本框，保留草稿' for n in nodes()):desc('编辑文本')
 desc('关闭文本框，保留草稿');desc('Ctrl：点击后组合下一个按键');shell('input','text','c');desc('编辑文本')
 command('mkdir -p '+shlex.quote(str(work))+'; cd '+shlex.quote(str(work))+'; export BG_TOKEN='+token+'; echo $$ > shell.pid')
 shell_pid=int((work/'shell.pid').read_text());baseline_client=client()
 command('for i in 1 2 3 4 5 6 7 8 9 10; do echo BACKGROUND_$i; sleep 1; done; echo DONE > bg.done')
 shell_bg=cycle('shell-background');assert (work/'bg.done').read_text()=='DONE\n'
 cycle('shell-lock',lock=True);cycle('shell-recreated',recreate=True)
 desc('编辑文本') if not any(n.get('content-desc')=='关闭文本框，保留草稿' for n in nodes()) else None
 command('echo $$ > restored.txt; echo $BG_TOKEN >> restored.txt; basename "$PWD" >> restored.txt')
 assert (work/'restored.txt').read_text().splitlines()==[str(shell_pid),token,token]
 command("vim -Nu NONE -n -c 'call writefile([string(getpid())], \"vim.pid\")' draft.txt")
 vim_pid=int((work/'vim.pid').read_text());command('iUNSAVED_'+token,False)
 cycle('vim-background');cycle('vim-lock',lock=True);cycle('vim-recreated',recreate=True)
 os.kill(vim_pid,0);assert not (work/'draft.txt').exists()
 # Exercise the moved retry owner while there is no bound Activity.
 shell('input','keyevent','3');time.sleep(2)
 previous=client();subprocess.run([sys.executable,'scripts/local-ssh.py','drop','--serial',serial],check=True)
 deadline=time.monotonic()+20
 while time.monotonic()<deadline:
  current=client()
  if current and current!=previous:break
  time.sleep(.25)
 else:raise AssertionError('No background reconnect')
 evidence('background-reconnected');resume();capture('vim-background-reconnected.png')
 os.kill(vim_pid,0);assert not (work/'draft.txt').exists()
 if any(n.get('content-desc')=='关闭文本框，保留草稿' for n in nodes()):desc('关闭文本框，保留草稿')
 desc('Escape');desc('编辑文本');command(':wq')
 assert (work/'draft.txt').read_text()=='UNSAVED_'+token+'\n'
 command('echo $$ > after-vim.txt; echo $BG_TOKEN >> after-vim.txt; basename "$PWD" >> after-vim.txt')
 assert (work/'after-vim.txt').read_text().splitlines()==[str(shell_pid),token,token]
 result={'device':'API 27 ARM64 emulator','app_pid':int(pid),'service_record':service_id,'ssh_client_pid_across_background_lock_recreate':int(baseline_client),'shell_pid_before_after':shell_pid,'vim_pid_before_after':vim_pid,'background_output_processed':True,'shell_and_vim_each_screen_off_seconds':45,'configuration_recreations':4,'background_reconnect_without_activity':True,'shell_environment_cwd_preserved':True,'vim_unsaved_saved_exactly':True,'fault_scope':'Home, normal screen-off, wm size configuration recreation and restoration, then abrupt SSH loss while backgrounded; no forced Doze'}
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
finally:
 override=re.search(r'Override size: (\d+x\d+)',old_size)
 shell('wm','size',override.group(1) if override else 'reset')
 for key,value in [('stay_on_while_plugged_in',old_stay)]:
  if value=='null':shell('settings','delete','global',key)
  else:shell('settings','put','global',key,value)
 shell('input','keyevent','224');shell('wm','dismiss-keyguard')
