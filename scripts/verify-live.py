#!/usr/bin/env python3
"""Read-only configured deployment verification; emit only allowlisted evidence."""
import argparse
import importlib.metadata
import json
from pathlib import Path
import sys
import tempfile

REMOTE = r'''
import fcntl, grp, json, os, platform, pwd, re, stat, subprocess, sys
from pathlib import Path


def command(user, argv):
    record = pwd.getpwnam(user)
    home = record.pw_dir
    env = dict(HOME=home, USER=user, LOGNAME=user, LANG='C.UTF-8',
               PATH=home+'/.local/bin:'+home+'/.asdf/shims:'+home+'/.nix-profile/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin')
    def drop():
        os.initgroups(user, record.pw_gid)
        os.setgid(record.pw_gid)
        os.setuid(record.pw_uid)
    try:
        result = subprocess.run(argv, capture_output=True, text=True, env=env,
                                cwd=home, timeout=90, preexec_fn=drop)
        return result.returncode, result.stdout
    except Exception:
        return -1, ''


def emacs(home):
    result = dict(running=False, exit_code=None, succeeded=False)
    lock = home / '.local/state/walter/emacs-packages.lock'
    if lock.is_file() and not lock.is_symlink():
        with lock.open('rb') as stream:
            try:
                fcntl.flock(stream, fcntl.LOCK_SH | fcntl.LOCK_NB)
            except BlockingIOError:
                result['running'] = True
    log = home / '.local/state/walter/emacs-packages.log'
    if log.is_file() and not log.is_symlink():
        with log.open('rb') as stream:
            stream.seek(max(0, log.stat().st_size - 65536))
            endings = re.findall(rb'^walter: exit=([0-9]{1,3})$', stream.read(), re.M)
        if endings and int(endings[-1]) <= 255:
            result['exit_code'] = int(endings[-1])
    result['succeeded'] = result['exit_code'] == 0 and not result['running']
    return result


def verify(payload):
    config, primary = payload['config'], payload['primary']
    result = {'architecture_matches': platform.machine() == payload['architecture'], 'users': {}}
    for user in [primary, *config.get('users', [])]:
        try:
            record = pwd.getpwnam(user)
            home = Path(record.pw_dir)
            info = home.stat()
            groups = {grp.getgrgid(g).gr_name for g in os.getgrouplist(user, record.pw_gid)}
            checks = {'private_home': stat.S_IMODE(info.st_mode) == 0o700 and info.st_uid == record.pw_uid,
                      'unprivileged_seat': user == primary or not groups.intersection({'sudo', 'admin', 'wheel', 'docker'}),
                      'authorized_key': payload['public_key'] in (home / '.ssh/authorized_keys').read_text().splitlines()}
            code, text = command(user, ['nix', 'profile', 'list', '--json'])
            attrs = set()
            if code == 0:
                try:
                    for entry in json.loads(text).get('elements', {}).values():
                        attr = entry.get('attrPath', '').split('.')
                        if len(attr) > 2 and attr[0] in ('legacyPackages', 'packages'):
                            attr = attr[2:]
                        if entry.get('originalUrl') == 'github:NixOS/nixpkgs/nixpkgs-unstable':
                            attrs.add('.'.join(attr))
                except Exception:
                    pass
            checks['nix_packages'] = code == 0 and set(config.get('nix-packages', [])).issubset(attrs)
            selected = {}
            versions_file = home / '.tool-versions'
            if versions_file.is_file():
                for line in versions_file.read_text().splitlines():
                    fields = line.split()
                    if len(fields) == 2:
                        selected[fields[0]] = fields[1]
            checks['asdf_runtimes'] = True
            for tool in config.get('asdf-tools', []):
                version = selected.get(tool['name'], '')
                exact = bool(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', version)) and version not in ('latest', 'system')
                matches = tool['version'] == 'latest' or version == tool['version']
                installed = (home / '.asdf/installs' / tool['name'] / version).is_dir()
                checks['asdf_runtimes'] &= exact and matches and installed
            checks['agent_executables'] = all(os.access(home / '.local/bin' / ('agy' if name == 'antigravity' else name), os.X_OK) for name in config.get('agent-tools', []))
            checks['github_account'] = True
            if config.get('github-account'):
                code, text = command(user, ['gh', 'api', 'user', '--jq', '.login'])
                checks['github_account'] = code == 0 and text.strip().casefold() == config['github-account'].casefold()
            checks['dotfiles_stamp'] = not config.get('dotfiles-checkout') or (home / '.local/state/walter/dotfiles').is_file()
            checks['atuin_session_files'] = True
            if config.get('atuin-username'):
                directory = home / '.local/share/atuin'
                checks['atuin_session_files'] = (home / '.local/state/walter' / ('atuin-' + config['atuin-username'])).is_file() and any((directory / p).is_file() and (directory / p).stat().st_size > 0 for p in ('session', 'meta.db'))
            checks['emacs'] = emacs(home) if config.get('emacs-config-repo') else dict(running=False, exit_code=0, succeeded=True)
            result['users'][user] = checks
        except Exception:
            result['users'][user] = {}
    return result

try:
    print(json.dumps(verify(json.load(sys.stdin))))
except Exception:
    print(json.dumps({'architecture_matches': False, 'users': {}}))
'''

CHECKS = ('private_home', 'unprivileged_seat', 'authorized_key', 'nix_packages',
          'asdf_runtimes', 'agent_executables', 'github_account', 'dotfiles_stamp', 'atuin_session_files')


def filter_result(raw, users, allow_emacs_pending=False):
    """Treat the host response as untrusted: copy only expected bools and exit code."""
    raw = raw if isinstance(raw, dict) else {}
    result = {'architecture_matches': raw.get('architecture_matches') is True, 'users': {}}
    passed = result['architecture_matches']
    remote_users = raw.get('users') if isinstance(raw.get('users'), dict) else {}
    for user in users:
        entry = remote_users.get(user)
        entry = entry if isinstance(entry, dict) else {}
        checks = {name: entry.get(name) is True for name in CHECKS}
        emacs = entry.get('emacs') if isinstance(entry.get('emacs'), dict) else {}
        code = emacs.get('exit_code')
        code = code if type(code) is int and 0 <= code <= 255 else None
        running = emacs.get('running') is True
        succeeded = emacs.get('succeeded') is True and code == 0 and not running
        checks['emacs'] = {'running': running, 'exit_code': code, 'succeeded': succeeded}
        passed &= all(checks[k] for k in CHECKS) and (succeeded or (allow_emacs_pending and running))
        result['users'][user] = checks
    result['passed'] = bool(passed)
    return result


def main(argv=None):
    from pocketdeploy.common import local_path
    from pocketdeploy.config import scope
    from pocketdeploy.gcp import GCP
    from pocketdeploy.host import Host
    from pocketdeploy.state import State
    from walter.config import load
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('-f', '--file', default='colors.yml')
    parser.add_argument('--allow-emacs-pending', action='store_true')
    args = parser.parse_args(argv)
    try:
        config, deployment = load(args.file)
        root = Path(deployment['_root'])
        primary = deployment.get('ssh-user', 'ubuntu')
        machine = deployment.get('gcp-machine-type', '')
        architecture = 'aarch64' if machine.startswith(('n4a-', 'c4a-', 't2a-')) else 'x86_64'
        with State(local_path(root, deployment['state-file']), config['profile'], scope(deployment), read_only=True) as state:
            cloud, host = GCP(deployment, state), Host(deployment, state, root)
            payload = {'config': config, 'primary': primary, 'architecture': architecture,
                       'public_key': host.pub.read_text().strip()}
            # PocketDeploy refreshes its connection-specific known-hosts file.
            # Keep that ephemeral so verification never rewrites deployment files.
            with tempfile.TemporaryDirectory(prefix='walter-verify-') as temporary:
                host.known = Path(temporary) / 'known_hosts'
                raw = host.run_python(cloud.connection(), REMOTE, payload, timeout=600)
        result = filter_result(raw, [primary, *config['users']], args.allow_emacs_pending)
        result['local_packages_installed'] = all(bool(importlib.metadata.version(name)) for name in ('walter', 'pocketdeploy'))
        result['passed'] &= result['local_packages_installed']
        print(json.dumps(result, sort_keys=True))
        return 0 if result['passed'] else 1
    except Exception:
        print(json.dumps({'passed': False, 'error': 'Live verification failed; private output suppressed.'}))
        return 1


if __name__ == '__main__':
    sys.exit(main())
