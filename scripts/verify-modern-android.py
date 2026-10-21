#!/usr/bin/env python3
"""API 35 emulator: notification allow/deny, FGS ownership, process death and background-start rejection.

Requires the debug APK (including the debug-only restriction probe) and an idle managed tmux fixture.
Exercises actual permission dialogs, UI input, 30-second screen-off and ordinary launcher recovery.
Uses run-as for SIGKILL, not root. Leaves notification permission granted and the shell connected.
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


pkg='dev.ghostty.android.sample';permission='android.permission.POST_NOTIFICATIONS'
work=root/'workspace'/('MODERN_'+uuid.uuid4().hex[:8]);token=work.name
old_stay=shell('settings','get','global','stay_on_while_plugged_in').decode().strip()
old_stylus=shell('settings','get','secure','stylus_handwriting_enabled').decode().strip()
results=[]
def tmux(*args):return subprocess.check_output(['tmux','-S',str(root/'tmux.sock'),*args],text=True).strip()
def pid():return subprocess.run(base+['shell','pidof',pkg],capture_output=True,text=True).stdout.strip()
def clients():return tmux('list-clients','-F','#{client_pid}')
def service():return shell('dumpsys','activity','services',pkg).decode()
def save_text(name,value): (out/name).write_text('\n'.join(line.rstrip() for line in value.splitlines()).rstrip()+'\n')
def launch():
 shell('input','keyevent','224');shell('wm','dismiss-keyguard')
 shell('am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',pkg+'/.MainActivity')
 wait_connected()
 assert not any(n.get('resource-id','').endswith('/permission_deny_button') for n in nodes())
def composer():
 if not any(n.get('content-desc')=='关闭文本框，保留草稿' for n in nodes()):desc('编辑文本')
 desc('本地文本草稿')
def command(text,enter=True):
 assert '%s' not in text
 composer()
 assert next(n.get('text','') for n in nodes() if n.get('content-desc')=='本地文本草稿') in ('','对终端说…')
 # Android timestamps a whole `input text` invocation at once; long batches can expire on slow emulators.
 for offset in range(0,len(text),12):shell('input','text',text[offset:offset+12].replace(' ','%s'))
 actual=next(n.get('text','') for n in nodes() if n.get('content-desc')=='本地文本草稿')
 assert actual==text,('Draft injection incomplete; will not send',actual,text)
 desc('发送并回车；长按仅发送',not enter);nodes()
def foreground(label):
 value=service();assert 'isForeground=true' in value
 assert 'types=0x40000000' in value,value
 save_text(label+'-service.txt',value)
 return value

def setup_permission(allowed):
 shell('am','force-stop',pkg)
 shell('pm','revoke',pkg,permission)
 shell('pm','clear-permission-flags',pkg,permission,'user-set','user-fixed')
 subprocess.run([sys.executable,'scripts/local-ssh.py','connect','--serial',serial,'--adb',args.adb],check=True)
 button='/permission_allow_button' if allowed else '/permission_deny_button'
 deadline=time.monotonic()+20
 while time.monotonic()<deadline:
  matches=[n for n in nodes() if n.get('resource-id','').endswith(button)]
  if matches:break
 else:raise AssertionError('Missing notification permission dialog')
 capture(('allowed' if allowed else 'denied')+'-permission-dialog.png');tap(matches[0]);wait_connected()
 package=shell('dumpsys','package',pkg).decode()
 assert re.search(r'POST_NOTIFICATIONS: granted='+str(allowed).lower(),package)
 save_text(('allowed' if allowed else 'denied')+'-permission.txt','\n'.join(x for x in package.splitlines() if 'POST_NOTIFICATIONS:' in x))
 if not allowed:assert '通知已关闭' in status()


def notification(label,allowed):
 before=clients();shell('input','keyevent','3');shell('cmd','statusbar','expand-notifications')
 visible=any(n.get('text')=='SSH 终端会话' for n in nodes())
 assert visible==allowed,(label,visible)
 capture(label+'-notification.png')
 if allowed:
  tap(next(n for n in nodes() if n.get('text')=='SSH 终端会话'));wait_connected();assert clients()==before
 else:shell('cmd','statusbar','collapse');launch()

def cycle(label,lock=False):
 before=clients();app=pid();assert before
 shell('input','keyevent','3')
 if lock:
  shell('svc','power','stayon','false');shell('input','keyevent','223')
  deadline=time.monotonic()+10
  while 'mWakefulness=Asleep' not in shell('dumpsys','power').decode() and time.monotonic()<deadline:time.sleep(.25)
  assert 'mWakefulness=Asleep' in shell('dumpsys','power').decode()
  print(label+': screen off for 30 seconds',flush=True);time.sleep(30)
 else:time.sleep(5)
 value=foreground(label)
 assert not re.search(r'ConnectionRecord\{',value),'Activity binding leaked in background'
 assert clients()==before and pid()==app
 if lock:assert 'mWakefulness=Asleep' in shell('dumpsys','power').decode()
 launch();assert clients()==before and pid()==app
 results.append(dict(case=label,app_pid=int(app),ssh_client_pid=int(before),same_connection=True,screen_off_seconds=30 if lock else 0))

def kill_cycle(label,stop_app=False):
 before=pid();client=clients();shell('input','keyevent','3')
 if stop_app:shell('am','stop-app',pkg)
 else:shell('run-as',pkg,'kill','-9',before)
 time.sleep(3);assert not pid();assert not clients()
 started=time.monotonic();launch();elapsed=time.monotonic()-started
 assert pid()!=before and clients()!=client
 foreground(label);capture(label+'.png')
 results.append(dict(case=label,kill='am stop-app (Task Manager equivalent)' if stop_app else 'SIGKILL via run-as',old_app_pid=int(before),new_app_pid=int(pid()),ordinary_launcher=True,no_permission_prompt=True,reopen_seconds=round(elapsed,2)))

def check_shell(name):
 command('echo $$ > '+name+'; echo $MODERN_TOKEN >> '+name+'; basename "$PWD" >> '+name)
 assert (work/name).read_text().splitlines()==[str(shell_pid),token,token]

def stop_explicit(allowed):
 if allowed:
  shell('input','keyevent','3');shell('cmd','statusbar','expand-notifications')
  # Expand the notification if the system initially collapses its actions.
  found=[n for n in nodes() if n.get('text')=='断开' or n.get('content-desc')=='断开']
  if not found:
   expand=[n for n in nodes() if n.get('resource-id','').endswith('/expand_button')]
   if expand:tap(expand[0])
   found=[n for n in nodes() if n.get('text')=='断开' or n.get('content-desc')=='断开']
  assert found,'No notification Disconnect action';tap(found[0]);shell('cmd','statusbar','collapse')
 else:
  desc('会话工具');tap(next(n for n in nodes() if n.get('text')=='断开 SSH'))
 time.sleep(2);assert not clients()
 shell('am','force-stop',pkg);shell('am','start','-n',pkg+'/.MainActivity');time.sleep(2)
 assert not clients();assert 'isForeground=true' not in service()

def verify_notification_settings():
 before=pid();shell('pm','revoke',pkg,permission);time.sleep(2)
 launch();assert '通知已关闭' in status()
 revoked_pid=pid();client=clients()
 desc('会话工具');tap(next(n for n in nodes() if n.get('text')=='通知设置'))
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  values=nodes()
  if any(n.get('package')=='com.android.settings' for n in values):break
 else:raise AssertionError('Notification settings did not open')
 capture('notification-settings-disabled.png')
 shell('pm','grant',pkg,permission)
 shell('input','keyevent','4');wait_connected()
 assert '通知已关闭' not in status();assert clients()==client and pid()==revoked_pid
 notification('notification-settings-restored',True)
 result=dict(notification_revoked_while_connected=True,app_pid_before=int(before),app_pid_after_reopen=int(revoked_pid),ordinary_reopen_without_permission_prompt=True,app_settings_entry_verified=True,grant_while_settings_open=True,return_refreshed_notification=True,same_connection_after_settings_return=True)
 (out/'settings-result.json').write_text(json.dumps(result,indent=2)+'\n')
 print('Notification settings grant/return: same SSH connection and visible notification',flush=True)

assert shell('getprop','ro.kernel.qemu').strip()==b'1','Emulator only'
assert shell('getprop','ro.build.version.sdk').strip()==b'35','Tested against API 35'
assert json.loads((root/'fixture.json').read_text())['mode']=='tmux'
assert tmux('display-message','-p','#{pane_current_command}')=='bash','Requires idle Bash'
try:
 # Fresh Gboard images may show a handwriting tutorial over the test composer.
 shell('settings','put','secure','stylus_handwriting_enabled','0')
 shell('input','keyevent','224');shell('wm','dismiss-keyguard')
 for allowed in [False,True]:
  label='allowed' if allowed else 'denied'
  setup_permission(allowed);foreground(label+'-connected');notification(label,allowed)
  command('mkdir -p '+shlex.quote(str(work))+'; cd '+shlex.quote(str(work))+'; export MODERN_TOKEN='+token+'; echo $$ > shell.pid')
  shell_pid=int((work/'shell.pid').read_text())
  cycle(label+'-shell-home');cycle(label+'-shell-lock',lock=True)
  kill_cycle(label+'-shell-killed');check_shell(label+'-shell.txt')
  filename=label+'-draft.txt'
  command("vim -Nu NONE -n -c 'call writefile([string(getpid())], \"vim.pid\")' "+filename)
  vim_pid=int((work/'vim.pid').read_text());command('iUNSAVED_'+token+'_'+label,False)
  cycle(label+'-vim-home');cycle(label+'-vim-lock',lock=True)
  kill_cycle(label+'-vim-killed');os.kill(vim_pid,0)
  if allowed:kill_cycle(label+'-vim-task-manager-stop',True);os.kill(vim_pid,0)
  shell('input','keyevent','3');before=clients()
  subprocess.run([sys.executable,'scripts/local-ssh.py','drop','--serial',serial],check=True)
  deadline=time.monotonic()+25
  while time.monotonic()<deadline:
   if clients() and clients()!=before:break
   time.sleep(.25)
  else:raise AssertionError('No background transport recovery')
  launch();os.kill(vim_pid,0);assert not (work/filename).exists();capture(label+'-vim-recovered.png')
  if any(n.get('content-desc')=='关闭文本框，保留草稿' for n in nodes()):desc('关闭文本框，保留草稿')
  desc('Escape');command(':wq')
  assert (work/filename).read_text()=='UNSAVED_'+token+'_'+label+'\n'
  check_shell(label+'-after-vim.txt')
  results.append(dict(case=label+'-remote-state',shell_pid=shell_pid,vim_pid=vim_pid,environment_cwd_preserved=True,unsaved_text_exact=True,background_reconnect=True))
  stop_explicit(allowed)
 verify_notification_settings()
 # Preserve a resume marker, then verify the real background-start restriction without instrumentation.
 subprocess.run([sys.executable,'scripts/local-ssh.py','connect','--serial',serial],check=True);wait_connected()
 shell('am','force-stop',pkg)
 shell('am','broadcast','-n',pkg+'/.BackgroundStartProbeReceiver','-a',pkg+'.PROBE','-f','32')
 answer=shell('run-as',pkg,'cat','files/background-start-probe.txt').decode()
 assert answer=='ForegroundServiceStartNotAllowedException',answer
 assert pid() and not clients();assert 'isForeground=true' not in service()
 save_text('background-start-probe.txt',answer)
 launch();check_shell('after-rejected-start.txt')
 results.append(dict(case='background-start-rejected',exception=answer,app_survived=True,resume_intent_preserved=True,visible_reopen_restored=True))
 result=dict(device='Android 15 / API 35 ARM64 Play emulator',target_sdk=35,service_type='specialUse (0x40000000)',root_required=False,cases=results,explicit_disconnect_cancels_resume_in_both_permission_states=True,scope='Emulator OS restrictions, notification allowed/denied, Home, normal lock, process death and Task Manager equivalent; no OEM or Play distribution approval')
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
finally:
 if old_stylus=='null':shell('settings','delete','secure','stylus_handwriting_enabled')
 else:shell('settings','put','secure','stylus_handwriting_enabled',old_stylus)
 if old_stay=='null':shell('settings','delete','global','stay_on_while_plugged_in')
 else:shell('settings','put','global','stay_on_while_plugged_in',old_stay)
 shell('input','keyevent','224');shell('wm','dismiss-keyguard');shell('cmd','statusbar','collapse')
