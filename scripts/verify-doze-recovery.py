#!/usr/bin/env python3
"""Rooted API 27 emulator: forced deep Doze + silent TCP loss, process death and normal launcher recovery.

Requires the sample connected to the managed, idle Bash tmux fixture. Never creates a new remote
session. Temporarily changes battery/stay-awake/idle state and one port-scoped firewall rule;
restores them in finally. No debug connect extra or fixture connect is used during recovery.
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

pkg='dev.ghostty.android.sample'
work=root/'workspace'/('DOZE_'+uuid.uuid4().hex[:8]);token=work.name
old_stay=shell('settings','get','global','stay_on_while_plugged_in').decode().strip()
rule=['OUTPUT','-p','tcp','-d','127.0.0.1','--dport','22222','-j','DROP']
blocked=False
results=[]
def tmux(*args):return subprocess.check_output(['tmux','-S',str(root/'tmux.sock'),*args],text=True).strip()
def pid():return subprocess.run(base+['shell','pidof',pkg],capture_output=True,text=True).stdout.strip()
def clients():return tmux('list-clients','-F','#{client_pid}')
def launch():
 shell('input','keyevent','224');shell('wm','dismiss-keyguard')
 shell('am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',pkg+'/.MainActivity')
 wait_connected()
def composer():
 if not any(n.get('content-desc')=='关闭文本框，保留草稿' for n in nodes()):desc('编辑文本')
 desc('本地文本草稿')
raw_command=command
def command(text,enter=True):
 composer();raw_command(text,enter)
def deep_cycle(label):
 global blocked
 old_pid=pid();before=clients();assert before
 shell('input','keyevent','3');shell('svc','power','stayon','false')
 shell('dumpsys','battery','unplug');shell('input','keyevent','223')
 shell('dumpsys','deviceidle','force-idle','deep')
 assert shell('dumpsys','deviceidle','get','deep').strip()==b'IDLE'
 (out/(label+'-idle.txt')).write_bytes(shell('dumpsys','deviceidle'))
 # ADB reverse uses loopback; force packet loss as well, so passing cannot rely on its exemption.
 shell('iptables','-I',*rule);blocked=True
 print(label+': verified deep IDLE; silent TCP drop for 70 seconds',flush=True)
 time.sleep(70)
 assert shell('dumpsys','deviceidle','get','deep').strip()==b'IDLE'
 assert pid()==old_pid
 (out/(label+'-service.txt')).write_bytes(shell('dumpsys','activity','services',pkg))
 (out/(label+'-firewall.txt')).write_bytes(shell('iptables','-L','OUTPUT','-n','-v'))
 shell('iptables','-D',*rule);blocked=False
 shell('dumpsys','deviceidle','unforce');shell('dumpsys','battery','reset')
 started=time.monotonic();launch();elapsed=time.monotonic()-started
 assert pid()==old_pid
 after=clients();assert after and after!=before,'Outage did not replace SSH client'
 capture(label+'.png')
 results.append(dict(case=label,deep_state='IDLE',idle_seconds=70,additional_fault='port-scoped silent TCP DROP over adb reverse',app_pid=int(old_pid),client_before=before,client_after=after,reopen_to_connected_seconds=round(elapsed,2)))
 print(label+': recovered automatically',flush=True)
def kill_cycle(label,force_stop=False):
 before=pid();old_client=clients();assert before and old_client
 shell('input','keyevent','3')
 if force_stop:shell('am','force-stop',pkg)
 else:shell('kill','-9',before)
 time.sleep(3)
 assert not pid(),'START_NOT_STICKY service unexpectedly resurrected'
 assert not clients(),'Killed process left SSH client attached'
 started=time.monotonic();launch();elapsed=time.monotonic()-started
 after=pid();assert after and after!=before
 new_client=clients();assert new_client and new_client!=old_client
 capture(label+'.png')
 results.append(dict(case=label,kill='force-stop' if force_stop else 'SIGKILL',old_app_pid=int(before),new_app_pid=int(after),client_before=old_client,client_after=new_client,ordinary_launcher=True,reopen_to_connected_seconds=round(elapsed,2)))
 print(label+': new App process, original remote session',flush=True)
def check_shell(name):
 command('echo $$ > '+name+'; echo $DOZE_TOKEN >> '+name+'; basename "$PWD" >> '+name)
 assert (work/name).read_text().splitlines()==[str(shell_pid),token,token]
def check_vim():
 os.kill(vim_pid,0);assert tmux('display-message','-p','#{pane_current_command}')=='vim'
 assert not (work/'draft.txt').exists()

assert shell('getprop','ro.kernel.qemu').strip()==b'1','Emulator only'
assert shell('getprop','ro.build.version.sdk').strip()==b'27'
assert shell('id','-u').strip()==b'0','Requires rooted adbd'
assert shell('dumpsys','deviceidle','enabled','deep').strip()==b'1'
assert pkg not in shell('dumpsys','deviceidle','whitelist').decode(),'App must not be Doze exempt'
assert json.loads((root/'fixture.json').read_text())['mode']=='tmux'
assert tmux('display-message','-p','#{pane_current_command}')=='bash','Requires idle Bash'
try:
 launch()
 composer();desc('关闭文本框，保留草稿');desc('Ctrl：点击后组合下一个按键');shell('input','text','c')
 command('mkdir -p '+shlex.quote(str(work))+'; cd '+shlex.quote(str(work))+'; export DOZE_TOKEN='+token+'; echo $$ > shell.pid')
 shell_pid=int((work/'shell.pid').read_text())
 deep_cycle('shell-doze');check_shell('after-doze.txt')
 kill_cycle('shell-killed');check_shell('after-kill.txt')
 command("vim -Nu NONE -n -c 'call writefile([string(getpid())], \"vim.pid\")' draft.txt")
 vim_pid=int((work/'vim.pid').read_text());command('iUNSAVED_'+token,False)
 deep_cycle('vim-doze');check_vim()
 kill_cycle('vim-killed');check_vim()
 kill_cycle('vim-force-stopped',force_stop=True);check_vim()
 if any(n.get('content-desc')=='关闭文本框，保留草稿' for n in nodes()):desc('关闭文本框，保留草稿')
 desc('Escape');command(':wq')
 assert (work/'draft.txt').read_text()=='UNSAVED_'+token+'\n'
 check_shell('after-vim.txt')
 # Explicit user stop must cancel persisted resume, including a subsequent process death.
 shell('input','keyevent','3');shell('cmd','statusbar','expand-notifications')
 desc('断开');shell('cmd','statusbar','collapse');time.sleep(2)
 assert not clients()
 shell('am','force-stop',pkg)
 shell('am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',pkg+'/.MainActivity')
 time.sleep(3)
 assert not clients(),'Explicit disconnect was ignored on reopen'
 assert 'isForeground=true' not in shell('dumpsys','activity','services',pkg).decode()
 capture('explicit-stop-reopened.png')
 result=dict(device='API 27 ARM64 rooted emulator',shell_pid_before_after=shell_pid,vim_pid_before_after=vim_pid,shell_environment_cwd_preserved=True,vim_unsaved_saved_exactly=True,explicit_disconnect_cancels_resume=True,cases=results,scope='Forced deep Doze with extra TCP loss, SIGKILL and force-stop; no OEM or physical suspend acceptance')
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
finally:
 if blocked:shell('iptables','-D',*rule)
 shell('dumpsys','deviceidle','unforce');shell('dumpsys','battery','reset')
 if old_stay=='null':shell('settings','delete','global','stay_on_while_plugged_in')
 else:shell('settings','put','global','stay_on_while_plugged_in',old_stay)
 shell('input','keyevent','224');shell('wm','dismiss-keyguard')
