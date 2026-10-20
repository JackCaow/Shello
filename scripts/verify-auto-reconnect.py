#!/usr/bin/env python3
"""Verify sample auto-reconnect through two silent SSH outages on a rooted API 27 emulator.

Requires the managed tmux fixture already attached to the sample and an idle Bash pane.
The temporary firewall rule is removed on failure/interrupt. No remote session is recreated.
"""
import subprocess,sys,xml.etree.ElementTree as E,re,time,shlex,json,os,uuid
from pathlib import Path
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--adb',default=str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
args=parser.parse_args()
serial=args.serial
base=[args.adb,'-s',serial]
out=args.output;out.mkdir(parents=True,exist_ok=True)
root=Path.home()/'.cache/ghostty-android-ssh-test'
meta=json.loads((root/'fixture.json').read_text())
assert meta['mode']=='tmux', 'Requires --tmux fixture'
port=str(meta['port'])
pane=subprocess.check_output(['tmux','-S',str(root/'tmux.sock'),'display-message','-p','#{pane_current_command}'],text=True).strip()
assert pane=='bash', 'Finish the current fixture program before running this test'
work=root/'workspace'/('AUTO_'+uuid.uuid4().hex[:8]);token=work.name

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
rule=['OUTPUT','-d','127.0.0.1','-p','tcp','--dport',port,'-m','comment','--comment','ghostty-auto-test','-j','DROP']
def outage(label):
 states=[];start=time.monotonic()
 shell('iptables','-I',*rule)
 try:
  print(label+': SSH packets dropped',flush=True)
  while time.monotonic()-start<65:
   s=status()
   if s not in states:
    states.append(s);print(label+': '+s,flush=True)
   if '秒后重连' in s:capture(label+'-retry.png')
  counters=shell('iptables','-L','OUTPUT','-n','-v','-x').decode()
  assert any('ghostty-auto-test' in line and int(line.split()[0])>0 for line in counters.splitlines()),counters
  assert any('秒后重连' in s for s in states),states
 finally:
  shell('iptables','-D',*rule)
  print(label+': SSH packets restored',flush=True)
 restored=time.monotonic();wait_connected();elapsed=time.monotonic()-restored
 capture(label+'-restored.png')
 print(label+': auto reattached after %.1fs'%elapsed,flush=True)
 return {'blackhole_seconds':round(restored-start,1),'recovery_seconds':round(elapsed,1),'observed_states':states,'packet_counters':counters}

assert shell('getprop','ro.kernel.qemu').strip()==b'1', 'Emulator only'
assert shell('getprop','ro.build.version.sdk').strip()==b'27', 'This UI test targets API 27'
assert b'uid=0' in shell('id'), 'Run adb root first'
wait_connected()
if not any(n.get('content-desc')=='关闭文本框，保留草稿' for n in nodes()):desc('编辑文本')
desc('关闭文本框，保留草稿')
desc('Ctrl：点击后组合下一个按键')
shell('input','text','c')
desc('编辑文本')
command('mkdir -p '+shlex.quote(str(work))+'; cd '+shlex.quote(str(work))+'; export AUTO_TOKEN='+token+'; echo $$ > shell.pid')
shell_pid=int((work/'shell.pid').read_text())
print('Shell PID: '+str(shell_pid),flush=True)
first=outage('shell')
command('echo $$ > restored.txt; echo $AUTO_TOKEN >> restored.txt; basename "$PWD" >> restored.txt')
assert (work/'restored.txt').read_text().splitlines()==[str(shell_pid),token,token]
command("vim -Nu NONE -n -c 'call writefile([string(getpid())], \"vim.pid\")' draft.txt")
vim_pid=int((work/'vim.pid').read_text())
command('iUNSAVED_'+token,False)
assert not (work/'draft.txt').exists()
capture('vim-before.png')
second=outage('vim')
os.kill(vim_pid,0);assert not (work/'draft.txt').exists()
# Close draft to use the terminal Esc control, then reopen for :wq.
desc('关闭文本框，保留草稿');desc('Escape');desc('编辑文本')
command(':wq')
assert (work/'draft.txt').read_text()=='UNSAVED_'+token+'\n'
command('echo $$ > after-vim.txt; echo $AUTO_TOKEN >> after-vim.txt; basename "$PWD" >> after-vim.txt')
assert (work/'after-vim.txt').read_text().splitlines()==[str(shell_pid),token,token]
result={'device':'Android 8.1 API 27 ARM64 emulator','fault_model':'Emulator OUTPUT iptables DROP for loopback SSH TCP port '+port+'; no reconnect UI action','shell_pid_before_after':shell_pid,'vim_pid_before_after':vim_pid,'shell_environment_cwd_preserved':True,'vim_unsaved_text_saved_exactly':True,'shell_outage':first,'vim_outage':second}
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result),flush=True)
