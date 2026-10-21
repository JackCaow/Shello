#!/usr/bin/env python3
"""Shello / Claude Code bridge. Private spool, accessed only over SSH.

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


def poll():
    events = []
    for path in STATE.glob('*.json'):
        try:
            event = read(path)
            if event['created'] < time.time() - 86400:
                path.unlink(missing_ok=True)
                path.with_suffix('.response').unlink(missing_ok=True)
                continue
            if event['status'] == 'pending' and event['expires'] <= time.time():
                event['status'] = 'expired'
            events.append(event)
        except (ValueError, KeyError, OSError):
            continue
    events.sort(key=lambda e: e['created'], reverse=True)
    # Keep a bounded disk history, including completion events while the app is away.
    for event in events[200:]:
        if event['status'] != 'pending':
            for path in paths(event['id']):
                path.unlink(missing_ok=True)
    selected, size = [], 0
    for event in events[:100]:
        length = len(json.dumps(event).encode())
        if size + length > 800000:
            break
        selected.append(event)
        size += length
    return {'version': 1, 'events': selected, 'now': time.time()}


def hook(value):
    kind = value.get('hook_event_name')
    question = kind == 'PreToolUse' and value.get('tool_name') == 'AskUserQuestion'
    if kind not in ('PermissionRequest', 'Notification', 'Stop', 'StopFailure') and not question:
        return {}
    event_id = str(uuid.uuid4())
    event = {key: value[key] for key in ('hook_event_name', 'session_id', 'cwd', 'tool_name', 'tool_input', 'message', 'notification_type', 'last_assistant_message') if key in value}
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
        return {}  # No phone decision: leave the normal Claude permission prompt intact.
    finally:
        with (STATE / '.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            current = read(event_path)
            if current['status'] == 'pending':
                current['status'] = 'expired'
                atomic(event_path, current)


def configure(remove=False):
    SETTINGS.parent.mkdir(parents=True, exist_ok=True)
    command = shlex.quote(sys.executable) + ' ' + shlex.quote(str(Path(__file__).resolve())) + ' hook'
    with (SETTINGS.parent / '.ghostty-settings.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = read(SETTINGS) if SETTINGS.exists() else {}
        hooks = current.setdefault('hooks', {})
        if not isinstance(hooks, dict):
            raise ValueError('invalid existing hooks')
        for kind in list(hooks):
            cleaned = []
            for group in hooks[kind]:
                handlers = [h for h in group.get('hooks', []) if h.get('command') != command]
                if handlers:
                    cleaned.append(dict(group, hooks=handlers))
            if cleaned:
                hooks[kind] = cleaned
            else:
                del hooks[kind]
        if not remove:
            for kind in ('PermissionRequest', 'PreToolUse', 'Notification', 'Stop', 'StopFailure'):
                hooks.setdefault(kind, []).append({'matcher': 'AskUserQuestion' if kind == 'PreToolUse' else '', 'hooks': [{'type': 'command', 'command': command, 'timeout': 180}]})
        if SETTINGS.exists():
            backup = SETTINGS.with_name('settings.ghostty-backup-' + str(time.time_ns()) + '.json')
            atomic(backup, read(SETTINGS))
        atomic(SETTINGS, current)
    return {'ok': True, 'installed': not remove}


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
        os.execv(tmux, [tmux, '-u', 'new-session', '-A', '-s', target, command])
    else:
        cwd, argv = resume_plan(provider, ident)
        os.chdir(cwd)
        os.execv(argv[0], argv)


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
        elif mode == 'poll':
            result = poll()
        elif mode == 'respond':
            result = respond(incoming())
        elif mode in ('install', 'uninstall'):
            result = configure(mode == 'uninstall')
        elif mode == 'hook':
            result = hook(incoming())
        else:
            raise ValueError('unknown mode')
        print(json.dumps(result, ensure_ascii=False))
    except Exception:
        # Never write tool input, credentials, or transcript data into error logs.
        if mode == 'hook':
            print('{}')
        else:
            if mode in ('resume', 'agent-run'):
                print('Unable to restore agent session. Check its history, working directory and CLI installation.', file=sys.stderr)
            print(json.dumps({'ok': False, 'error': 'Request failed or expired'}))
            sys.exit(1)


if __name__ == '__main__':
    main()
