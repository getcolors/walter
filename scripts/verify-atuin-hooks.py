#!/usr/bin/env python3
"""Verify fresh deployment agent-hook absence and interactive Fish integration."""
import argparse
import json
from pathlib import Path
import tempfile

CHECKS = ('claude_hooks_absent', 'codex_hooks_absent', 'pi_hook_absent',
          'opencode_hook_absent', 'fish_integration', 'fish_official_binary')

REMOTE = r'''
import json, os, pwd, re, subprocess, sys
from pathlib import Path

def absent_json_hook(home, relative):
    path = home / relative
    try:
        if any(part.is_symlink() for part in [path, *path.parents] if part != home.parent):
            return False
        if not path.exists():
            return True
        data = json.loads(path.read_text())
        if not isinstance(data, dict):
            return False
        # Inspect only hook configuration; never return configuration contents.
        hooks = json.dumps(data.get('hooks', {}))
        return re.search(r'\batuin\b.*?\bhook\b', hooks, re.S) is None
    except Exception:
        return False

def absent_file(home, relative):
    path = home / relative
    return not path.exists() and not path.is_symlink()

def verify_user(user):
    record = pwd.getpwnam(user)
    home = Path(record.pw_dir)
    checks = {
        'claude_hooks_absent': absent_json_hook(home, '.claude/settings.json'),
        'codex_hooks_absent': absent_json_hook(home, '.codex/hooks.json'),
        'pi_hook_absent': absent_file(home, '.pi/agent/extensions/atuin.ts'),
        'opencode_hook_absent': absent_file(home, '.config/opencode/plugins/atuin.ts'),
        'fish_integration': False, 'fish_official_binary': False,
    }
    env = dict(HOME=str(home), USER=user, LOGNAME=user, LANG='C.UTF-8', TERM='xterm',
               PATH=str(home / '.atuin/bin')+':'+str(home / '.local/bin')+':'+str(home / '.nix-profile/bin')+':/usr/local/bin:/usr/bin:/bin')
    def drop():
        os.initgroups(user, record.pw_gid)
        os.setgid(record.pw_gid)
        os.setuid(record.pw_uid)
    for name, probe in (
        ('fish_integration', 'functions -q _atuin_search'),
        ('fish_official_binary', 'test (command -s atuin) = "$HOME/.atuin/bin/atuin"'),
    ):
        try:
            result = subprocess.run([str(home / '.nix-profile/bin/fish'), '-ic', probe],
                env=env, cwd=home, preexec_fn=drop, capture_output=True, timeout=90)
            checks[name] = result.returncode == 0
        except Exception:
            pass
    return checks

def verify(users):
    result = {}
    for user in users:
        try:
            result[user] = verify_user(user)
        except Exception:
            result[user] = {}
    return result

try:
    print(json.dumps(verify(json.load(sys.stdin)['users'])))
except Exception:
    print('{}')
'''


def filter_result(raw, users):
    raw = raw if isinstance(raw, dict) else {}
    result = {}
    for user in users:
        entry = raw.get(user)
        entry = entry if isinstance(entry, dict) else {}
        result[user] = {name: entry.get(name) is True for name in CHECKS}
    return {'users': result, 'passed': bool(users) and all(
        all(checks.values()) for checks in result.values())}


def main(argv=None):
    from pocketdeploy.common import local_path
    from pocketdeploy.config import scope
    from pocketdeploy.gcp import GCP
    from pocketdeploy.host import Host
    from pocketdeploy.state import State
    from walter.config import load
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('-f', '--file', default='colors.yml')
    args = parser.parse_args(argv)
    try:
        config, deployment = load(args.file)
        root = Path(deployment['_root'])
        users = [deployment.get('ssh-user', 'ubuntu'), *config.get('users', [])]
        with State(local_path(root, deployment['state-file']), config['profile'],
                   scope(deployment), read_only=True) as state:
            cloud, host = GCP(deployment, state), Host(deployment, state, root)
            with tempfile.TemporaryDirectory(prefix='walter-atuin-verify-') as temporary:
                host.known = Path(temporary) / 'known_hosts'
                raw = host.run_python(cloud.connection(), REMOTE, {'users': users},
                                      timeout=max(600, len(users) * 190))
        result = filter_result(raw, users)
        print(json.dumps(result, sort_keys=True))
        return 0 if result['passed'] else 1
    except Exception:
        print(json.dumps({'passed': False, 'error': 'Atuin verification failed; private output suppressed.'}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
