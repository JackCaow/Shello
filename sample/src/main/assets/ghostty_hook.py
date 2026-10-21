#!/usr/bin/env python3
"""Shello / Claude Code and Codex bridge. Private spool, accessed only over SSH.

No listening port, account, push relay, or automatic permission grants. Install
explicitly with `install`; `uninstall` removes only this script's hook handlers.
"""
import fcntl
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

STATE = Path(os.environ.get('GHOSTTY_HOOK_STATE', str(Path.home() / '.local/state/ghostty-android')))
SETTINGS = Path(os.environ.get('GHOSTTY_CLAUDE_SETTINGS', str(Path.home() / '.claude/settings.json')))
CODEX_SETTINGS = Path(os.environ.get('GHOSTTY_CODEX_SETTINGS', str(Path(os.environ.get('CODEX_HOME', str(Path.home() / '.codex'))) / 'hooks.json')))
LIMIT = 262144
WAIT = 170


def private_dir(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or path.stat().st_uid != os.getuid():
        raise ValueError('unsafe state directory')
    path.chmod(0o700)


def atomic(path, value):
    data = json.dumps(value, ensure_ascii=False).encode()
    fd, tmp = tempfile.mkstemp(prefix='.write-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def read(path):
    if path.is_symlink() or path.stat().st_size > LIMIT:
        raise ValueError('invalid event')
    return json.loads(path.read_text())


def incoming():
    raw = sys.stdin.buffer.read(LIMIT + 1)
    if len(raw) > LIMIT:
        raise ValueError('input too large')
    value = json.loads(raw or '{}')
    if not isinstance(value, dict):
        raise ValueError('object required')
    return value


def paths(event_id):
    if str(uuid.UUID(event_id)) != event_id:
        raise ValueError('invalid id')
    return STATE / (event_id + '.json'), STATE / (event_id + '.response')


def decision(event, response):
    behavior = response.get('behavior')
    if behavior not in ('allow', 'deny'):
        raise ValueError('invalid decision')
    hook = event['hook_event_name']
    result = {'hookEventName': hook}
    if hook == 'PreToolUse' and event.get('tool_name') == 'AskUserQuestion':
        result['permissionDecision'] = behavior
        if behavior == 'allow':
            questions = event['tool_input']['questions']
            answers = response.get('answers', {})
            if not isinstance(answers, dict) or any(not isinstance(answers.get(q['question']), str) or not answers[q['question']].strip() or len(answers[q['question']]) > 8192 for q in questions):
                raise ValueError('answer every question')
            result['updatedInput'] = dict(event['tool_input'], answers={q['question']: answers[q['question']] for q in questions})
        else:
            result['permissionDecisionReason'] = 'Declined on Shello'
    elif hook == 'PermissionRequest':
        result['decision'] = {'behavior': behavior}
        if behavior == 'deny':
            result['decision']['message'] = 'Declined on Shello'
    else:
        raise ValueError('event does not accept decisions')
    return {'hookSpecificOutput': result}


def respond(value):
    event_path, response_path = paths(value['id'])
    # Serialize competing devices and reject expired/duplicate replies.
    with (STATE / '.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        event = read(event_path)
        if event.get('status') != 'pending' or event['expires'] <= time.time():
            raise ValueError('request expired')
        output = decision(event, value)
        fd = os.open(response_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(output, stream)
        event['status'] = 'answered'
        event['answer'] = value['behavior']
        atomic(event_path, event)
    return {'ok': True}


def agent_process():
    pid=os.getppid()
    for _ in range(8):
        try:
            row=subprocess.run(['ps','-p',str(pid),'-o','ppid=','-o','lstart=','-o','comm='],capture_output=True,text=True,timeout=.3).stdout.strip().split(None,6)
            if len(row)<7:break
            if any(name in Path(row[6]).name.lower() for name in ('codex','claude')):
                return {'agent_pid':pid,'agent_start':' '.join(row[1:6])}
            parent=int(row[0])
            if parent<=1 or parent==pid:break
            pid=parent
        except (ValueError,OSError,subprocess.SubprocessError):break
    return {}


def process_snapshot():
    try:
        rows=subprocess.run(['ps','-axo','pid=,lstart=,comm='],capture_output=True,text=True,timeout=1).stdout.splitlines()
        return {parts[0]:' '.join(parts[1:6]) for row in rows if len(parts:=row.split(None,6))>=7}
    except (OSError,subprocess.SubprocessError):return {}


def poll():
    events = []
    processes = process_snapshot()
    for path in STATE.glob('*.json'):
        try:
            event = read(path)
            if event['created'] < time.time() - 86400:
                path.unlink(missing_ok=True)
                path.with_suffix('.response').unlink(missing_ok=True)
                continue
            if event['status'] == 'pending' and event['expires'] <= time.time():
                event['status'] = 'expired'
            if event.get('agent_pid') and event.get('agent_start'):
                event['process_alive'] = processes.get(str(event['agent_pid'])) == event['agent_start']
            events.append(event)
        except (ValueError, KeyError, OSError):
            continue
    events.sort(key=lambda e: e['created'], reverse=True)
    latest, prompts = {}, {}
    for event in events:
        key = (event.get('provider', 'claude'), event.get('session_id', event['id']))
        latest.setdefault(key, event)
        if event.get('hook_event_name') == 'UserPromptSubmit':
            prompts.setdefault(key, event)
    # Retain the latest prompt with each of the 100 most recent sessions. A
    # busy turn must not lose its user message to the ordinary history cap.
    context = [prompts[key] for key in list(latest)[:100] if key in prompts]
    protected = {event['id'] for event in context}
    # Keep a bounded disk history, including completion events while the app is away.
    for event in events[200:]:
        if event['status'] != 'pending' and event['id'] not in protected:
            for path in paths(event['id']):
                path.unlink(missing_ok=True)
    # Pending decisions take priority over noisy completions. Include each
    # session's newest event before filling the remaining bounded history.
    pending = [e for e in events if e['status'] == 'pending']
    candidates = pending + list(latest.values()) + context + events
    selected, size, seen = [], 0, set()
    for event in candidates:
        if event['id'] in seen:continue
        seen.add(event['id'])
        length = len(json.dumps(event).encode())
        if size + length > 800000 or len(selected)>=400:continue
        selected.append(event)
        size += length
    return {'version': 1, 'events': selected, 'now': time.time()}


def message_preview(value):
    if not isinstance(value, str):
        return ''
    # Keep UTF-8 valid and bound message content independently of tool payloads.
    text = value.strip().encode('utf-8')
    return text[:8192].decode('utf-8', errors='ignore') + ('…' if len(text) > 8192 else '')


def hook(value, provider='claude'):
    if provider not in ('claude', 'codex'):
        raise ValueError('unsupported provider')
    kind = value.get('hook_event_name')
    question = provider == 'claude' and kind == 'PreToolUse' and value.get('tool_name') == 'AskUserQuestion'
    supported = ('PermissionRequest', 'SessionStart', 'UserPromptSubmit', 'Stop', 'Interrupt', 'SessionEnd') if provider == 'codex' else ('PermissionRequest', 'Notification', 'Stop', 'StopFailure', 'SessionStart', 'UserPromptSubmit', 'SessionEnd')
    if kind not in supported and not question:
        return {}
    event_id = str(uuid.uuid4())
    event = {key: value[key] for key in ('hook_event_name', 'session_id', 'turn_id', 'cwd', 'model', 'tool_name', 'tool_input', 'message', 'notification_type') if key in value}
    if kind == 'UserPromptSubmit':
        event['prompt_preview'] = message_preview(value.get('prompt'))
    if kind == 'Stop':
        event['last_assistant_message'] = message_preview(value.get('last_assistant_message'))
    event['provider'] = provider
    process = agent_process()
    if process:event.update(process)
    pane = os.environ.get('TMUX_PANE', '')
    if pane.startswith('%') and pane[1:].isdigit():
        binary = shutil.which('tmux') or next((str(p) for p in (Path('/opt/homebrew/bin/tmux'), Path('/usr/local/bin/tmux')) if p.is_file()), None)
        if binary:
            try:
                name = subprocess.run([binary, 'display-message', '-p', '-t', pane, '#{session_name}'], capture_output=True, text=True, timeout=1, check=True).stdout.strip()
                if name and len(name) <= 179 and not any(ord(c) < 32 for c in name):
                    event['tmux_session'] = name
            except (OSError, subprocess.SubprocessError):
                pass
    waiting = kind == 'PermissionRequest' or question
    event.update(id=event_id, created=time.time(), expires=time.time() + WAIT if waiting else 0, status='pending' if waiting else 'info')
    event_path, response_path = paths(event_id)
    atomic(event_path, event)
    poll()
    if not waiting:
        return {}
    try:
        while time.time() < event['expires']:
            if response_path.exists():
                # The writer serializes the complete response while holding this lock.
                with (STATE / '.lock').open('a') as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX)
                    return read(response_path)
            time.sleep(.2)
        return {}  # No phone decision: leave the agent's normal permission prompt intact.
    finally:
        with (STATE / '.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            current = read(event_path)
            if current['status'] == 'pending':
                current['status'] = 'expired'
                atomic(event_path, current)


def configure_file(settings, provider, remove):
    # Removing an integration must not create an agent configuration directory.
    if remove and not settings.exists():
        return
    settings.parent.mkdir(parents=True, exist_ok=True)
    command = shlex.quote(sys.executable) + ' ' + shlex.quote(str(Path(__file__).resolve())) + (' codex-hook' if provider == 'codex' else ' hook')
    def owned(handler):
        try:
            parts = shlex.split(handler.get('command', ''))
            return len(parts) == 3 and parts[1] == str(Path(__file__).resolve()) and parts[2] == ('codex-hook' if provider == 'codex' else 'hook')
        except (ValueError, TypeError):
            return False
    with (settings.parent / '.ghostty-settings.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = read(settings) if settings.exists() else {}
        hooks = current.setdefault('hooks', {})
        if not isinstance(hooks, dict):
            raise ValueError('invalid existing hooks')
        for kind in list(hooks):
            cleaned = []
            for group in hooks[kind]:
                handlers = [h for h in group.get('hooks', []) if not owned(h)]
                if handlers:
                    cleaned.append(dict(group, hooks=handlers))
            if cleaned:
                hooks[kind] = cleaned
            else:
                del hooks[kind]
        if not remove:
            kinds = ('PermissionRequest', 'SessionStart', 'UserPromptSubmit', 'Stop', 'Interrupt', 'SessionEnd') if provider == 'codex' else ('PermissionRequest', 'PreToolUse', 'Notification', 'Stop', 'StopFailure', 'SessionStart', 'UserPromptSubmit', 'SessionEnd')
            for kind in kinds:
                timeout = 180 if kind in ('PermissionRequest', 'PreToolUse') else 3 if kind in ('Interrupt', 'SessionEnd') else 10
                group = {'hooks': [{'type': 'command', 'command': command, 'timeout': timeout}]}
                if provider == 'claude':
                    group['matcher'] = 'AskUserQuestion' if kind == 'PreToolUse' else ''
                hooks.setdefault(kind, []).append(group)
        if settings.exists():
            backup = settings.with_name(settings.stem + '.ghostty-backup-' + str(time.time_ns()) + '.json')
            atomic(backup, read(settings))
        atomic(settings, current)


def configure(remove=False):
    # Validate both sources before touching either. Do not rewrite config.toml,
    # enable daemon flags or grant trust; Codex owns /hooks review and settings.
    for settings in (SETTINGS, CODEX_SETTINGS):
        if settings.exists():
            current = read(settings)
            if not isinstance(current, dict) or not isinstance(current.get('hooks', {}), dict):
                raise ValueError('invalid existing hooks')
            for groups in current.get('hooks', {}).values():
                if not isinstance(groups, list) or any(not isinstance(g, dict) or not isinstance(g.get('hooks', []), list) or any(not isinstance(h, dict) for h in g.get('hooks', [])) for g in groups):
                    raise ValueError('invalid hook groups')
    configure_file(SETTINGS, 'claude', remove)
    configure_file(CODEX_SETTINGS, 'codex', remove)
    try:bridge=configure_usage_bridge(remove)
    except Exception:bridge='unavailable'
    return {'ok': True, 'installed': not remove, 'usageBridge':bridge}


# Agent history metadata only; no transcript text is returned.
AGENT_HOME = Path(os.environ.get('GHOSTTY_AGENT_HOME', str(Path.home())))


def session_id(value):
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError):
        return None


def epoch(value, fallback=0):
    try:
        if isinstance(value, (int, float)):
            return value / 1000 if value > 1e12 else value
        from datetime import datetime
        return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()
    except (ValueError, TypeError, AttributeError, OverflowError):
        return fallback


def modified(path):
    try:
        return path.stat().st_mtime
    except OSError:
        return 0


def recent_agents():
    records = {}
    def add(provider, ident, cwd, updated):
        ident = session_id(ident)
        if not ident or not isinstance(cwd, str) or not cwd.startswith('/') or len(cwd) > 4096 or any(ord(c) < 32 for c in cwd):
            return
        value = {'provider': provider, 'id': ident, 'cwd': cwd, 'project': Path(cwd).name or cwd, 'updated': epoch(updated)}
        key = provider + ':' + ident
        if key not in records or value['updated'] > records[key]['updated']:
            records[key] = value
    # Claude's older project index plus bounded reads of recent JSONL headers for newer releases.
    projects = AGENT_HOME / '.claude/projects'
    candidates = []
    if projects.is_dir():
        for folder in projects.iterdir():
            if not folder.is_dir() or folder.is_symlink():
                continue
            index = folder / 'sessions-index.json'
            try:
                if index.is_file() and index.stat().st_size <= 4 * 1024 * 1024:
                    for entry in json.loads(index.read_text()).get('entries', []):
                        if not entry.get('isSidechain'):
                            add('claude', entry.get('sessionId'), entry.get('projectPath'), entry.get('modified'))
            except (ValueError, OSError, AttributeError):
                pass
            for path in folder.glob('*.jsonl'):
                if not path.is_symlink():
                    candidates.append(path)
    candidates.sort(key=modified, reverse=True)
    for path in candidates[:150]:
        try:
            with path.open('rb') as stream:
                head = stream.read(131072)
            for raw in head.splitlines():
                try:
                    entry = json.loads(raw)
                    if entry.get('cwd') and not entry.get('isSidechain'):
                        add('claude', entry.get('sessionId', path.stem), entry['cwd'], path.stat().st_mtime)
                        break
                except (ValueError, AttributeError):
                    pass
        except OSError:
            pass
    # Codex's local state index is queried read-only. Never read prompt/message columns.
    import sqlite3
    codex_home = AGENT_HOME / '.codex'
    databases = sorted(codex_home.glob('state_*.sqlite'), key=modified, reverse=True)
    found = False
    for path in databases:
        if path.is_symlink():
            continue
        try:
            con = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=1)
            try:
                columns = {row[1] for row in con.execute('pragma table_info(threads)')}
                if not {'id', 'cwd', 'updated_at'}.issubset(columns):
                    continue
                where = ' where archived=0' if 'archived' in columns else ''
                for ident, cwd, updated in con.execute('select id,cwd,updated_at from threads' + where + ' order by updated_at desc limit 150'):
                    add('codex', ident, cwd, updated)
                found = True
                break
            finally:
                con.close()
        except (sqlite3.Error, OSError):
            pass
    if not found:
        # Legacy Codex session_meta headers; bound reads, ignore all conversation entries.
        files = list((codex_home / 'sessions').glob('*/*/*/*.jsonl'))
        files.sort(key=modified, reverse=True)
        for path in files[:150]:
            try:
                if path.is_symlink():
                    continue
                with path.open('rb') as stream:
                    entry = json.loads(stream.readline(131072))
                if entry.get('type') == 'session_meta':
                    meta = entry.get('payload', {})
                    add('codex', meta.get('id'), meta.get('cwd'), path.stat().st_mtime)
            except (ValueError, OSError, AttributeError):
                pass
    return {'version': 1, 'home': str(AGENT_HOME), 'sessions': sorted(records.values(), key=lambda e: e['updated'], reverse=True)[:200], 'now': time.time()}


def binary(name):
    # SSH login PATH often misses per-user Node and Homebrew installations.
    choices = [AGENT_HOME / folder / name for folder in ('.local/bin', '.npm-global/bin', '.bun/bin')]
    choices += [Path('/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex')] if name == 'codex' else []
    choices += [Path('/opt/homebrew/bin') / name, Path('/usr/local/bin') / name]
    discovered = shutil.which(name)
    if discovered:
        choices.append(Path(discovered))
    for candidate in choices:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    raise ValueError(name + ' executable missing')


def resume_plan(provider, ident):
    ident = session_id(ident)
    if provider not in ('claude', 'codex') or ident is None:
        raise ValueError('invalid agent session')
    match = next((e for e in recent_agents()['sessions'] if e['provider'] == provider and e['id'] == ident), None)
    if match is None or not Path(match['cwd']).is_dir():
        raise ValueError('session or working directory no longer exists')
    argv = [binary(provider), '--resume' if provider == 'claude' else 'resume', ident]
    return match['cwd'], argv


def resume_agent(provider, ident, attach=True):
    ident = session_id(ident)
    if provider not in ('claude', 'codex') or ident is None:
        raise ValueError('invalid agent session')
    if attach:
        target = 'ghostty_agent_' + provider + '_' + ident.replace('-', '')
        tmux = binary('tmux')
        # Existing process remains attachable even after its history falls outside the recent window.
        if subprocess.run([tmux, 'has-session', '-t', '=' + target], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5).returncode == 0:
            os.execv(tmux, [tmux, '-u', 'attach-session', '-t', '=' + target])
        resume_plan(provider, ident)
        command = shlex.join([sys.executable, str(Path(__file__).resolve()), 'agent-run', provider, ident])
        # Do not inherit a background launcher's NO_COLOR from the tmux server.
        # These session-local values apply only at creation; attach preserves it.
        os.execv(tmux, [tmux, '-u', 'new-session', '-A', '-s', target,
                       '-e', 'NO_COLOR=', '-e', 'COLORTERM=truecolor', command])
    else:
        cwd, argv = resume_plan(provider, ident)
        os.chdir(cwd)
        os.execv(argv[0], argv)


def usage_error(provider, status, message):
    return {'provider': provider, 'accountId': provider + ':unknown', 'accountLabel': provider, 'status': status, 'error': message, 'windows': [], 'capturedAt': time.time()}


def codex_usage():
    import selectors
    import hashlib
    environment=os.environ.copy()
    environment['PATH']=':'.join(str(AGENT_HOME/p) for p in ('.local/bin','.npm-global/bin','.bun/bin'))+':/opt/homebrew/bin:/usr/local/bin:'+environment.get('PATH','/usr/bin:/bin')
    proc = subprocess.Popen([binary('codex'), 'app-server', '--listen', 'stdio://'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=environment, bufsize=0)
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ)
    deadline = time.monotonic() + 10
    def request(ident, method, params):
        proc.stdin.write((json.dumps({'id': ident, 'method': method, 'params': params}) + '\n').encode());proc.stdin.flush()
        while time.monotonic() < deadline:
            if not selector.select(max(0, deadline-time.monotonic())):
                break
            line = proc.stdout.readline(1048576)
            if not line:
                break
            message = json.loads(line)
            if message.get('id') == ident:
                if 'error' in message:
                    raise ValueError('Codex account API unavailable')
                return message.get('result', {})
        raise TimeoutError('Codex account API timed out')
    try:
        request(1, 'initialize', {'clientInfo': {'name': 'shello_usage', 'version': '1.0.0'}, 'capabilities': {}})
        proc.stdin.write(b'{"method":"initialized","params":{}}\n');proc.stdin.flush()
        account = request(2, 'account/read', {'refreshToken': False}).get('account')
        if not account or account.get('type') != 'chatgpt':
            return usage_error('codex', 'not_signed_in', 'Sign into Codex with a ChatGPT account on this host.')
        limits = request(3, 'account/rateLimits/read', {})
        email = account.get('email') or 'ChatGPT'
        result = {'provider': 'codex', 'accountId': 'codex:'+hashlib.sha256(email.encode()).hexdigest()[:24], 'accountLabel': email, 'status': 'ok', 'capturedAt': time.time(), 'windows': []}
        buckets = limits.get('rateLimitsByLimitId') or {'codex': limits.get('rateLimits')}
        for bucket_id, bucket in buckets.items():
            if not isinstance(bucket, dict):
                continue
            for name in ('primary', 'secondary'):
                window = bucket.get(name)
                if not isinstance(window, dict) or not isinstance(window.get('usedPercent'), (int, float)):
                    continue
                minutes = window.get('windowDurationMins')
                label = ((str(minutes//1440)+'d' if minutes%1440 == 0 else str(minutes//60)+'h') if isinstance(minutes,int) and minutes%60 == 0 else str(minutes)+'m') if minutes else name
                result['windows'].append({'label': str(bucket.get('limitName') or bucket_id)+' · '+label, 'usedPercent': window['usedPercent'], 'durationMins': minutes, 'resetsAt': window.get('resetsAt')})
        if not result['windows']:
            result.update(status='unavailable', error='This account returned no quota windows.')
        return result
    finally:
        selector.close()
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill();proc.wait(timeout=2)
        for stream in (proc.stdin, proc.stdout):
            if stream: stream.close()


def claude_usage():
    import hashlib
    import urllib.request
    import urllib.error
    from datetime import datetime
    credentials = None
    file = AGENT_HOME / '.claude/.credentials.json'
    if file.is_file() and not file.is_symlink() and file.stat().st_size <= LIMIT:
        credentials = json.loads(file.read_text())
    elif sys.platform == 'darwin':
        response = subprocess.run(['security', 'find-generic-password', '-s', 'Claude Code-credentials', '-w', str(AGENT_HOME/'Library/Keychains/login.keychain-db')], capture_output=True, text=True, timeout=3)
        if response.returncode == 0 and len(response.stdout) <= LIMIT:
            credentials = json.loads(response.stdout)
    oauth = (credentials or {}).get('claudeAiOauth', {})
    token = oauth.get('accessToken')
    if not isinstance(token, str) or not token:
        return usage_error('claude', 'not_signed_in', 'Sign into Claude Code on this host; unlock the credential store if needed.')
    # This is Claude Code's OAuth quota surface, not the Console API usage report.
    # Treat schema/auth failures as unavailable; never infer quota from terminal text.
    request = urllib.request.Request('https://api.anthropic.com/api/oauth/usage', headers={'Authorization': 'Bearer '+token, 'anthropic-beta': 'oauth-2025-04-20', 'User-Agent': 'Shello/1.0'})
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            raw = response.read(LIMIT+1)
            if len(raw)>LIMIT:raise ValueError('Quota response too large')
            value = json.loads(raw)
    except urllib.error.HTTPError as error:
        code=error.code
        error.close()
        return usage_error('claude', 'unavailable', 'Claude quota API returned HTTP '+str(code)+'. Retry later or check Claude Code login.')
    account = {}
    try:
        config = AGENT_HOME / '.claude.json'
        if config.stat().st_size <= 1048576:account=json.loads(config.read_text()).get('oauthAccount', {})
    except (OSError,ValueError):pass
    import socket
    identity = account.get('accountUuid') or account.get('emailAddress') or socket.gethostname()+':claude-local'
    result = {'provider': 'claude', 'accountId': 'claude:'+hashlib.sha256(identity.encode()).hexdigest()[:24], 'accountLabel': account.get('emailAddress') or oauth.get('subscriptionType') or 'Claude Code', 'status': 'ok', 'capturedAt': time.time(), 'windows': []}
    for key,label in (('five_hour','5h'),('seven_day','7d'),('seven_day_sonnet','Sonnet · 7d'),('seven_day_opus','Opus · 7d')):
        window = value.get(key)
        if isinstance(window, dict) and isinstance(window.get('utilization'), (int,float)):
            reset = window.get('resets_at');timestamp = None
            if isinstance(reset,str):
                try:timestamp=datetime.fromisoformat(reset.replace('Z','+00:00')).timestamp()
                except ValueError:pass
            result['windows'].append({'label':label,'usedPercent':window['utilization'],'resetsAt':timestamp})
    if not result['windows']:result.update(status='unavailable', error='Claude returned no supported quota windows.')
    return result


def usage_direct(refresh=False):
    # Only explicit phone requests collect usage; no background auth polling on the host.
    cache = STATE / 'usage.cache'
    with (STATE / '.usage-lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if cache.exists():
            previous = read(cache)
            if time.time()-previous.get('capturedAt',0)<60:
                return previous
        from concurrent.futures import ThreadPoolExecutor
        accounts = []
        with ThreadPoolExecutor(max_workers=2) as executor:
            collectors=[('codex',executor.submit(codex_usage)),('claude',executor.submit(claude_usage))]
            for provider,future in collectors:
                try:accounts.append(future.result(timeout=14))
                except Exception:accounts.append(usage_error(provider,'unavailable','Unable to read account usage. Check the installed CLI, login, and network.'))
        result={'version':2,'status':'ok','capturedAt':time.time(),'accounts':accounts}
        atomic(cache,result)
        return result


def usage_bridge_path():
    return Path.home()/'Library/LaunchAgents/dev.shello.usage.plist'


def configure_usage_bridge(remove=False):
    if sys.platform!='darwin':return 'not_required'
    if any(os.environ.get(k) for k in ('GHOSTTY_HOOK_STATE','GHOSTTY_CLAUDE_SETTINGS','GHOSTTY_CODEX_SETTINGS')):return 'isolated'
    import plistlib
    path=usage_bridge_path();label='dev.shello.usage';domain='gui/'+str(os.getuid())
    if remove:
        if path.exists():
            try:
                value=plistlib.loads(path.read_bytes())
                if value.get('ProgramArguments',[None,None])[1]!=str(Path(__file__).resolve()):return 'not_owned'
            except (ValueError,IndexError):return 'not_owned'
            subprocess.run(['launchctl','bootout',domain+'/'+label],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=3)
            path.unlink()
        return 'removed'
    path.parent.mkdir(parents=True,exist_ok=True)
    request=STATE/'usage.request';request.touch(exist_ok=True);request.chmod(0o600)
    value={'Label':label,'ProgramArguments':[sys.executable,str(Path(__file__).resolve()),'usage-worker'],
           'WatchPaths':[str(request)],'ThrottleInterval':10,'ProcessType':'Background',
           'StandardOutPath':'/dev/null','StandardErrorPath':'/dev/null'}
    encoded=plistlib.dumps(value)
    if path.exists() and path.read_bytes()==encoded:
        status=subprocess.run(['launchctl','print',domain+'/'+label],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=3)
        if status.returncode==0:return 'installed'
    temp=path.with_suffix('.plist.new');temp.write_bytes(encoded);temp.chmod(0o600);os.replace(temp,path)
    subprocess.run(['launchctl','bootout',domain+'/'+label],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=3)
    status=subprocess.run(['launchctl','bootstrap',domain,str(path)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=3)
    if status.returncode==0:
        (STATE/'usage.cache').unlink(missing_ok=True)
        return 'installed'
    return 'unavailable'


def usage(refresh=False):
    if sys.platform!='darwin' or os.environ.get('GHOSTTY_HOOK_STATE') or not usage_bridge_path().is_file():return usage_direct(refresh)
    cache=STATE/'usage.cache'
    if cache.exists():
        try:
            saved=read(cache)
            if time.time()-saved.get('capturedAt',0)<60:return saved
        except (ValueError,OSError):pass
    requested=time.time();(STATE/'usage.request').touch(exist_ok=True)
    deadline=time.monotonic()+16
    while time.monotonic()<deadline:
        try:
            value=read(cache)
            if value.get('capturedAt',0)>=requested:return value
        except (ValueError,OSError):pass
        time.sleep(.1)
    return {'version':2,'status':'unavailable','accounts':[usage_error(p,'unavailable','Host quota worker did not respond. Reinstall Hook and check the macOS login session/keychain.') for p in ('claude','codex')]}


def diagnostics():
    installed = {}
    for provider,settings in (('claude',SETTINGS),('codex',CODEX_SETTINGS)):
        try:
            data=read(settings);handlers=[h.get('command','') for groups in data.get('hooks',{}).values() for group in groups for h in group.get('hooks',[])]
            installed[provider]='installed' if any(str(Path(__file__).resolve()) in command for command in handlers) else 'not_installed'
        except Exception:installed[provider]='not_installed'
    return {'version':2,'capabilities':['sessions','approvals','usage','cache'],'hooks':installed,'usageBridge':('installed' if sys.platform=='darwin' and usage_bridge_path().is_file() else 'not_required'),'codexReview':'Review Shello in Codex /hooks; host registration does not grant hook trust.'}


def main():
    os.umask(0o077)
    private_dir(STATE)
    mode = sys.argv[1] if len(sys.argv) > 1 else 'hook'
    try:
        if mode == 'recent':
            result = recent_agents()
        elif mode in ('resume', 'agent-run'):
            resume_agent(sys.argv[2], sys.argv[3], mode == 'resume')
            return
        elif mode == 'usage-worker':
            result = usage_direct()
        elif mode == 'usage':
            result = usage('--refresh' in sys.argv)
        elif mode == 'doctor':
            result = diagnostics()
        elif mode == 'poll':
            result = poll()
            result['diagnostics'] = diagnostics()
        elif mode == 'respond':
            result = respond(incoming())
        elif mode in ('install', 'uninstall'):
            result = configure(mode == 'uninstall')
        elif mode in ('hook', 'codex-hook'):
            result = hook(incoming(), 'codex' if mode == 'codex-hook' else 'claude')
        else:
            raise ValueError('unknown mode')
        print(json.dumps(result, ensure_ascii=False))
    except Exception:
        # Never write tool input, credentials, or transcript data into error logs.
        if mode in ('hook', 'codex-hook'):
            print('{}')
        else:
            if mode in ('resume', 'agent-run'):
                print('Unable to restore agent session. Check its history, working directory and CLI installation.', file=sys.stderr)
            print(json.dumps({'ok': False, 'error': 'Request failed or expired'}))
            sys.exit(1)


if __name__ == '__main__':
    main()
