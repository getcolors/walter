"""Standalone remote provisioner. Requests arrive over SSH stdin, never argv.

Only machine tasks run as root. Per-user tasks execute in a fork that permanently
relinquishes root, including all filesystem writes and downloaded installers.
"""
from __future__ import annotations

import base64
import fcntl
import json
import os
from pathlib import Path
import pwd
import re
import shlex
import subprocess
import sys
import tempfile

NIXPKGS = 'github:NixOS/nixpkgs/nixpkgs-unstable'
CREDENTIALS = {'.claude/.credentials.json', '.codex/auth.json', '.pi/agent/auth.json'}
AGENTS = {
    'pi': ('pi', 'https://pi.dev/install.sh', 'sh'),
    'codex': ('codex', 'https://chatgpt.com/codex/install.sh', 'sh'),
    'claude': ('claude', 'https://claude.ai/install.sh', 'bash'),
    'antigravity': ('agy', 'https://antigravity.google/cli/install.sh', 'bash'),
}


class ProvisionError(Exception):
    """Only fixed, credential-free operation names may appear in this error."""


STAGES = frozenset(('validation', 'machine', 'users', 'prerequisites', 'docker', 'local-ssd', 'nix-install', 'nix-packages', 'asdf', 'shell-paths', 'agent-tools', 'credentials', 'github', 'dotfiles', 'atuin', 'emacs', 'complete'))
STAGE = 'validation'
PROGRESS_PATH = None
PROGRESS_USER = None

def stage(value):
    global STAGE
    if value not in STAGES:
        raise ProvisionError('unknown provisioning stage')
    STAGE = value
    if PROGRESS_PATH is not None:
        write(PROGRESS_PATH, json.dumps({'stage': value, 'user': PROGRESS_USER}) + '\n', 0o600)


def read_progress(path):
    try:
        if path.stat().st_size > 1024:
            return None
        value = json.loads(path.read_text())
        user = value.get('user')
        if value.get('stage') not in STAGES or (user is not None and not re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}', user)):
            return None
        return {'stage': value['stage'], 'user': user}
    except (OSError, ValueError, TypeError, AttributeError):
        return None


def run(args, *, data=None, check=True, timeout=3600):
    try:
        result = subprocess.run(args, input=data, text=True, capture_output=True,
                                timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ProvisionError('command unavailable or timed out') from None
    if check and result.returncode:
        raise ProvisionError('command failed: ' + Path(args[0]).name + ' (exit ' + str(result.returncode) + ')')
    return result


def write(path, text, mode=0o644, *, exclusive=False):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if exclusive:
        try:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
        except FileExistsError:
            return False
        with os.fdopen(fd, 'w') as stream:
            stream.write(text)
        return True
    if path.is_symlink():
        raise ProvisionError('refusing managed file symlink')
    if path.exists() and path.read_text() == text:
        os.chmod(path, mode)
        return False
    fd, name = tempfile.mkstemp(prefix='.walter-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(text)
            os.fchmod(stream.fileno(), mode)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)
    return True


def user_path(home, value):
    """Configured destinations stay inside the user's home."""
    raw = str(value)
    if raw == '~':
        return home
    if raw.startswith('~/'):
        raw = raw[2:]
    path = Path(raw)
    if path.is_absolute():
        result = path
    else:
        result = home / path
    if not result.resolve().is_relative_to(home.resolve()):
        raise ProvisionError('configured path escapes home')
    return result


def install_script(url, interpreter='sh', arguments=()):
    script = run(['curl', '-fsSL', '--retry', '3', url]).stdout
    run([interpreter, '-s', '--', *arguments], data=script)


def seed_credentials(home, credentials):
    for relative, encoded in credentials.items():
        if relative not in CREDENTIALS:
            raise ProvisionError('unsupported credential destination')
        target = home / relative
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(target.parent, 0o700)
        content = base64.b64decode(encoded, validate=True).decode()
        write(target, content, 0o600, exclusive=True)
        if relative == '.claude/.credentials.json':
            path = home / '.claude.json'
            value = json.loads(path.read_text()) if path.exists() else {}
            if not isinstance(value, dict):
                raise ProvisionError('invalid Claude onboarding configuration')
            if 'hasCompletedOnboarding' not in value:
                value['hasCompletedOnboarding'] = True
                write(path, json.dumps(value) + '\n', 0o600)


def profile_attributes(profile):
    result = {}
    for name, value in profile.get('elements', {}).items():
        attr = value.get('attrPath', '').split('.')
        if len(attr) > 2 and attr[0] in ('legacyPackages', 'packages'):
            attr = attr[2:]
        if value.get('originalUrl') == NIXPKGS:
            result['.'.join(attr)] = name
    return result


def nix_packages(packages, upgrade=False):
    if not packages:
        return
    current = profile_attributes(json.loads(run(['nix', 'profile', 'list', '--json']).stdout)) if (Path.home() / '.nix-profile').exists() else {}
    missing = [p for p in packages if p not in current]
    if missing:
        run(['nix', 'profile', 'add', '--impure', *[NIXPKGS + '#' + p for p in missing]])
    if upgrade:
        current = profile_attributes(json.loads(run(['nix', 'profile', 'list', '--json']).stdout))
        names = [current[p] for p in packages if p in current]
        if names:
            run(['nix', 'profile', 'upgrade', '--impure', *names])


def asdf_tools(config, resolved, home):
    for tool in config.get('asdf-tools', []):
        name = tool['name']
        if not (home / '.asdf/plugins' / name).is_dir():
            run(['asdf', 'plugin', 'add', name, *([tool['plugin']] if tool.get('plugin') else [])])
        version = tool['version']
        if version == 'latest':
            run(['asdf', 'plugin', 'update', name])
            if name not in resolved:
                resolved[name] = run(['asdf', 'latest', name]).stdout.strip()
            version = resolved[name]
        if len(version) > 128 or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', version) or version == 'latest':
            raise ProvisionError('invalid resolved runtime version')
        resolved[name] = version
        if not (home / '.asdf/installs' / name / version).is_dir():
            # Build with system compiler, not a possibly incompatible Nix gcc.
            previous = os.environ['PATH']
            try:
                os.environ['PATH'] = '/usr/bin:/bin:' + previous
                run(['asdf', 'install', name, version])
            finally:
                os.environ['PATH'] = previous
        run(['asdf', 'set', '--home', name, version])
    managers = config.get('corepack-packages', [])
    if managers:
        if run(['asdf', 'which', 'corepack'], check=False).returncode:
            run(['asdf', 'exec', 'npm', 'install', '--global', 'corepack'])
        run(['asdf', 'reshim', 'nodejs'])
        run(['corepack', 'enable', *managers])
        run(['asdf', 'reshim', 'nodejs'])
    return resolved


def clone(url, destination):
    if destination.exists():
        if not (destination / '.git').exists():
            raise ProvisionError('clone destination is not a repository')
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Clone to a temporary sibling so interruption never presents an incomplete
    # checkout as a successful previous clone.
    temporary = Path(tempfile.mkdtemp(prefix='.walter-clone-', dir=destination.parent))
    try:
        run(['git', 'clone', '--', url, str(temporary)])
        os.rename(temporary, destination)
    finally:
        if temporary.exists():
            import shutil
            shutil.rmtree(temporary)


def github_authenticated(config):
    if not config.get('github-account'):
        return True
    try:
        result = run(['gh', 'api', 'user', '--jq', '.login'], check=False, timeout=60)
    except ProvisionError:
        return False
    return result.returncode == 0 and result.stdout.strip().lower() == config['github-account'].lower()


def github(config, payload, home):
    if not config.get('github-account'):
        return
    if not github_authenticated(config):
        # Preserve an existing identity instead of silently replacing it.
        if run(['gh', 'auth', 'status', '--hostname', 'github.com'], check=False).returncode == 0:
            raise ProvisionError('existing GitHub account does not match configuration')
        if not payload.get('github_token'):
            raise ProvisionError('GitHub login requires a token')
        run(['gh', 'auth', 'login', '--hostname', 'github.com', '--with-token'], data=payload['github_token'])
        if not github_authenticated(config):
            raise ProvisionError('GitHub account does not match configuration')
    run(['gh', 'auth', 'setup-git', '--hostname', 'github.com'])
    run(['git', 'config', '--global', 'user.name', config['github-account']])
    run(['git', 'config', '--global', 'user.email', config['git-email']])
    for org in config.get('clone-orgs', []):
        pages = json.loads(run(['gh', 'api', '--paginate', '--slurp',
                               f'orgs/{org}/repos?per_page=100&type=sources&sort=full_name']).stdout)
        for page in pages:
            for repo in page:
                if repo.get('archived') or repo.get('fork'):
                    continue
                name = repo['full_name']
                if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', name) or name.split('/')[0].lower() != org.lower() or name.split('/')[-1] in ('.', '..'):
                    raise ProvisionError('invalid repository identity')
                clone('https://github.com/' + name + '.git', home / 'code' / name)


def shell_paths(home, config):
    paths = '$HOME/.local/bin:$HOME/.asdf/shims:$HOME/.nix-profile/bin:/nix/var/nix/profiles/default/bin'
    fragment = '# BEGIN walter paths\nexport PATH="' + paths + ':$PATH"\n# END walter paths\n'
    profile = home / '.profile'
    text = profile.read_text() if profile.exists() else ''
    text = re.sub(r'# BEGIN walter paths\n.*?# END walter paths\n?', '', text, flags=re.S)
    write(profile, text.rstrip() + '\n' + fragment)
    write(home / '.config/fish/conf.d/walter-paths.fish',
          'fish_add_path --prepend ~/.local/bin ~/.asdf/shims ~/.nix-profile/bin /nix/var/nix/profiles/default/bin\n')
    if config.get('local-ssd-scratch'):
        scratch = '/scratch/' + home.name
        values = {'NPM_CONFIG_CACHE': 'npm', 'UV_CACHE_DIR': 'uv', 'CCACHE_DIR': 'ccache', 'TMPDIR': 'tmp'}
        write(home / '.config/fish/conf.d/walter-local-ssd.fish', ''.join(f'set -gx {key} {scratch}/{value}\n' for key, value in values.items()))
        write(home / '.local/state/walter/scratch.sh', ''.join(f'export {key}={scratch}/{value}\n' for key, value in values.items()))
        source = '. "$HOME/.local/state/walter/scratch.sh"'
        text = profile.read_text()
        if source not in text:
            write(profile, text + source + '\n')


def emacs_job(home, config):
    if not config.get('emacs-config-repo'):
        return None
    destination = user_path(home, config.get('emacs-config-dest', '~/.config/emacs'))
    clone(config['emacs-config-repo'], destination)
    state = home / '.local/state/walter'
    state.mkdir(parents=True, exist_ok=True)
    log = state / 'emacs-packages.log'
    script = state / 'emacs-packages.sh'
    advice = '(progn (defvar walter-emacs-bootstrap-failed nil) (advice-add (quote display-warning) :before (lambda (_type _message &optional level &rest _) (when (memq level (quote (:error :emergency))) (setq walter-emacs-bootstrap-failed t)))))'
    command = [str(home / '.nix-profile/bin/emacs'), '--init-directory', str(destination), '--batch', '--eval', advice,
               '-l', str(destination / 'init.el'), '--eval', '(when walter-emacs-bootstrap-failed (kill-emacs 1))']
    write(script, '#!/bin/bash\nexec 9>' + shlex.quote(str(state / 'emacs-packages.lock')) + '\nflock -n 9 || exit 0\nexec >' + shlex.quote(str(log)) + ' 2>&1\ndate -Is\ntimeout 3600 ' + shlex.join(command) + '\nrc=$?\nprintf "walter: exit=%s\\n" "$rc"\ndate -Is\nexit "$rc"\n', 0o700)
    subprocess.Popen(['/bin/bash', str(script)], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True, close_fds=True)
    return str(log)


def atuin_login(username, password, key, *, timeout=120):
    """Answer Atuin's hidden password and key prompts in either order.

    Atuin 18.21 asks for the key first for Hub and password first for legacy
    servers. Secrets never enter argv, logs, or the returned failure message.
    """
    import pty
    import select
    import signal
    import termios
    import time
    if any('\n' in value or '\r' in value for value in (password, key)):
        raise ProvisionError('Atuin credentials must each occupy one line')
    pid, fd = pty.fork()
    if pid == 0:
        os.execvp('atuin', ['atuin', 'login', '-u', username])
    pending = {'password': password, 'key': key}
    captured = b''
    deadline = time.monotonic() + timeout
    completed = False
    eof = False
    try:
        while time.monotonic() < deadline:
            ready, _, _ = select.select([] if eof else [fd], [], [], 0.05)
            if ready:
                try:
                    chunk = os.read(fd, 4096)
                except OSError:
                    chunk = b''
                if not chunk:
                    eof = True
                captured = (captured + chunk)[-8192:]
            # Match the actual terminal prompt, never instructional prose that
            # happens to mention the encryption key or password.
            prompt = None
            if re.search(rb'please enter password:\s*$', captured, re.I):
                prompt = 'password'
            elif re.search(rb'please enter encryption key[^\r\n]*:\s*$', captured, re.I):
                prompt = 'key'
            if prompt is not None and prompt not in pending:
                raise ProvisionError('Atuin rejected the supplied credentials')
            if not eof and prompt is not None:
                # rpassword disables echo after writing the password prompt.
                if prompt == 'key' or not termios.tcgetattr(fd)[3] & termios.ECHO:
                    os.write(fd, pending.pop(prompt).encode() + b'\n')
                    captured = b''
            finished, status = os.waitpid(pid, os.WNOHANG)
            if finished:
                completed = True
                if status:
                    raise ProvisionError('Atuin login failed')
                return
        raise ProvisionError('Atuin login timed out')
    finally:
        os.close(fd)
        if not completed:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)


def user_tasks(payload, user, resolved):
    global PROGRESS_PATH, PROGRESS_USER
    home = Path(pwd.getpwnam(user).pw_dir)
    os.environ.clear()
    os.environ.update(HOME=str(home), USER=user, LOGNAME=user, SHELL='/bin/bash', LANG='C.UTF-8',
                      PATH=f'{home}/.local/bin:{home}/.asdf/shims:{home}/.nix-profile/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin',
                      NIXPKGS_ALLOW_UNFREE='1', DEBIAN_FRONTEND='noninteractive')
    os.chdir(home)
    config = payload['config']
    action = payload.get('action', 'converge')
    progress = home / '.local/state/walter/progress.json'
    PROGRESS_PATH = None if action in ('probe', 'status') else progress
    PROGRESS_USER = user
    if action in ('probe', 'status'):
        return {'github_authenticated': github_authenticated(config), 'nix': Path('/nix/receipt.json').exists(), 'progress': read_progress(progress)}
    packages = list(config.get('nix-packages', []))
    if action == 'converge':
        packages += ['ghostty.terminfo']
        if config.get('github-account'):
            packages += ['gh']
        if config.get('emacs-config-repo'):
            packages += ['emacs', 'coreutils']
        if config.get('asdf-tools'):
            packages += ['asdf-vm']
        if config.get('login-shell'):
            packages += [config['login-shell']]
    if action in ('converge', 'converge-nix'):
        stage('nix-packages')
        nix_packages(list(dict.fromkeys(packages)), upgrade=action == 'converge-nix')
    if action in ('converge', 'converge-asdf'):
        stage('asdf')
        asdf_tools(config, resolved, home)
    if action != 'converge':
        stage('complete')
        return {'resolved_versions': resolved}
    stage('shell-paths')
    shell_paths(home, config)
    for directory, name in [('x', 'xterm-ghostty'), ('g', 'ghostty')]:
        target = home / '.terminfo' / directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        source = home / '.nix-profile/share/terminfo' / directory / name
        if target.is_symlink():
            if target.readlink() != source:
                target.unlink()
        if not target.exists() and not target.is_symlink():
            target.symlink_to(source)
    stage('agent-tools')
    for name in config.get('agent-tools', []):
        binary, url, interpreter = AGENTS[name]
        if not (home / '.local/bin' / binary).exists():
            install_script(url, interpreter)
        run([str(home / '.local/bin' / binary), '--version'])
    stage('credentials')
    seed_credentials(home, payload.get('credentials', {}))
    stage('github')
    github(config, payload, home)
    stage('dotfiles')
    if config.get('dotfiles-checkout'):
        namespace = {'__name__': 'walter_dotfiles'}
        exec(payload['dotfiles_source'], namespace)
        namespace['install'](user_path(home, config['dotfiles-checkout']), home)
    stage('atuin')
    if config.get('atuin-username'):
        username = config['atuin-username']
        stamp = home / '.local/state/walter' / ('atuin-' + username)
        if not stamp.exists():
            if not payload.get('atuin_password') or not payload.get('atuin_key'):
                raise ProvisionError('Atuin credentials are required')
            atuin_login(username, payload['atuin_password'], payload['atuin_key'])
            write(stamp, 'Authenticated by Walter\n', 0o600)
        run(['atuin', 'sync'])
    stage('emacs')
    log = emacs_job(home, config)
    stage('complete')
    return {'resolved_versions': resolved, 'emacs_log': log}


def as_user(payload, user, resolved):
    """Fork rather than temporarily changing euid: shells cannot regain root."""
    read_fd, write_fd = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(read_fd)
        try:
            account = pwd.getpwnam(user)
            os.initgroups(user, account.pw_gid)
            os.setgid(account.pw_gid)
            os.setuid(account.pw_uid)
            os.umask(0o077)
            value = {'ok': True, 'result': user_tasks(payload, user, dict(resolved))}
        except Exception as error:
            value = {'ok': False, 'stage': STAGE, 'error': str(error) if isinstance(error, ProvisionError) else 'user provisioning failed'}
        with os.fdopen(write_fd, 'w') as stream:
            json.dump(value, stream)
        os._exit(0)
    os.close(write_fd)
    with os.fdopen(read_fd) as stream:
        result = json.load(stream)
    os.waitpid(pid, 0)
    if not result['ok']:
        stage(result['stage'])
        raise ProvisionError(result['error'])
    return result['result']


def machine(payload, users):
    config = payload['config']
    primary = users[0]
    primary_home = Path(pwd.getpwnam(primary).pw_dir)
    keys = (primary_home / '.ssh/authorized_keys').read_text()
    seat_state = Path('/var/lib/walter/seats.json')
    owned = json.loads(seat_state.read_text()) if seat_state.exists() else {}
    for user in users[1:]:
        try:
            account = pwd.getpwnam(user)
        except KeyError:
            # Persist intent before useradd so an interrupted creation can resume.
            owned[user] = None
            write(seat_state, json.dumps(owned) + '\n', 0o600)
            run(['useradd', '--create-home', '--shell', '/bin/bash', user])
            account = pwd.getpwnam(user)
        if user not in owned or owned[user] not in (None, account.pw_uid):
            raise ProvisionError('seat conflicts with an unmanaged account')
        if account.pw_uid < 1000:
            raise ProvisionError('seat conflicts with system account')
        owned[user] = account.pw_uid
        write(seat_state, json.dumps(owned) + '\n', 0o600)
        run(['usermod', '-G', '', user])
        home = Path(account.pw_dir)
        if home != Path('/home') / user or home.is_symlink() or home.stat().st_uid != account.pw_uid:
            raise ProvisionError('unsafe seat home')
        os.chmod(home, 0o700)
        # Run seat writes with seat privilege; never follow a seat-owned symlink as root.
        child = os.fork()
        if child == 0:
            try:
                os.initgroups(user, account.pw_gid)
                os.setgid(account.pw_gid)
                os.setuid(account.pw_uid)
                write(home / '.ssh/authorized_keys', keys, 0o600)
                os.chmod(home / '.ssh', 0o700)
                os._exit(0)
            except Exception:
                os._exit(1)
        if os.waitpid(child, 0)[1]:
            raise ProvisionError('seat SSH setup failed')
    os.chmod(primary_home, 0o700)
    gids = [pwd.getpwnam(user).pw_gid for user in users]
    write('/etc/sysctl.d/90-walter.conf', f'net.ipv4.ping_group_range = {min(gids)} {max(gids)}\nnet.core.rmem_max = 7500000\nnet.core.wmem_max = 7500000\n')
    run(['sysctl', '--load', '/etc/sysctl.d/90-walter.conf'])
    write('/etc/ssh/sshd_config.d/00-walter-forwarding.conf', 'ClientAliveInterval 15\nClientAliveCountMax 3\nAllowTcpForwarding yes\nGatewayPorts clientspecified\nStreamLocalBindUnlink yes\n')
    run(['/usr/sbin/sshd', '-t'])
    run(['systemctl', 'reload', 'ssh'])
    stage('prerequisites')
    prerequisites(config)
    stage('docker')
    if not Path('/usr/bin/dockerd').exists():
        install_script('https://get.docker.com')
    run(['systemctl', 'enable', '--now', 'docker'])
    run(['usermod', '-aG', 'docker', primary])
    if config.get('local-ssd-scratch'):
        stage('local-ssd')
        write('/usr/local/sbin/walter-local-ssd', payload['local_ssd_source'], 0o755)
        write('/etc/walter-scratch.json', json.dumps({'disk_count': config['google-local-ssd-count'], 'users': users}))
        write('/etc/systemd/system/walter-local-ssd.service', '[Unit]\nDescription=Walter disposable Local SSD\nAfter=local-fs.target systemd-udev-trigger.service\nBefore=ssh.service ssh.socket nix-daemon.service nix-daemon.socket\n[Service]\nType=oneshot\nExecStart=/usr/local/sbin/walter-local-ssd\nRemainAfterExit=yes\nTimeoutStartSec=120\nEnvironment=PATH=/usr/sbin:/usr/bin:/sbin:/bin\n[Install]\nWantedBy=multi-user.target\n')
        run(['systemctl', 'daemon-reload'])
        run(['systemctl', 'enable', 'walter-local-ssd'])
        run(['systemctl', 'restart', 'walter-local-ssd'])
    stage('nix-install')
    if not Path('/nix/receipt.json').exists():
        install_script('https://install.determinate.systems/nix', arguments=('install', '--no-confirm'))


def prerequisites(config):
    packages = ['curl', 'git', 'unzip', 'xz-utils', 'ca-certificates', 'gnupg', 'tar', 'gzip', 'util-linux']
    if any(tool['name'] == 'python' for tool in config.get('asdf-tools', [])):
        packages += ['build-essential', 'libssl-dev', 'zlib1g-dev', 'libbz2-dev', 'libreadline-dev', 'libsqlite3-dev', 'libffi-dev', 'liblzma-dev', 'libncurses-dev', 'tk-dev', 'uuid-dev']
    if config.get('local-ssd-scratch'):
        packages += ['e2fsprogs', 'mdadm']
    os.environ['DEBIAN_FRONTEND'] = 'noninteractive'
    run(['apt-get', 'update'])
    run(['apt-get', 'install', '-y', '--no-install-recommends', *packages])


def main(payload):
    global PROGRESS_PATH, PROGRESS_USER
    users = [payload.get('primary_user', 'ubuntu'), *payload['config'].get('users', [])]
    if len(set(users)) != len(users) or any(not re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}', user) for user in users):
        raise ProvisionError('invalid login names')
    action = payload.get('action', 'converge')
    if action not in ('converge', 'converge-nix', 'converge-asdf', 'probe', 'status'):
        raise ProvisionError('unsupported remote action')
    PROGRESS_PATH = None if action in ('probe', 'status') else Path('/var/lib/walter/progress.json')
    PROGRESS_USER = None
    if action == 'converge':
        stage('machine')
        machine(payload, users)
    elif action == 'converge-asdf':
        prerequisites(payload['config'])
    results, resolved = {}, {}
    for user in users:
        try:
            pwd.getpwnam(user)
        except KeyError:
            if action in ('probe', 'status'):
                results[user] = {'github_authenticated': False, 'exists': False}
                continue
            raise ProvisionError('configured login missing') from None
        if action not in ('probe', 'status'):
            PROGRESS_USER = user
            stage('users')
        result = as_user(payload, user, resolved)
        results[user] = result
        resolved.update(result.get('resolved_versions', {}))
        if action == 'converge' and payload['config'].get('login-shell'):
            shell = str(Path(pwd.getpwnam(user).pw_dir) / '.nix-profile/bin' / payload['config']['login-shell'])
            shells = Path('/etc/shells').read_text().splitlines()
            if shell not in shells:
                write('/etc/shells', '\n'.join([*shells, shell]) + '\n')
            run(['chsh', '-s', shell, user])
    if action not in ('probe', 'status'):
        PROGRESS_USER = None
        stage('complete')
    return {'ok': True, 'users': results, 'resolved_versions': resolved,
            'progress': read_progress(Path('/var/lib/walter/progress.json'))}


if __name__ == '__main__':
    try:
        request = json.load(sys.stdin)
        if os.geteuid() != 0:
            raise ProvisionError('remote provisioner requires sudo')
        if request.get('action') in ('probe', 'status'):
            response = main(request)
        else:
            with open('/run/lock/walter-provision.lock', 'w') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                response = main(request)
    except Exception as error:
        response = {'ok': False, 'stage': STAGE, 'error': str(error) if isinstance(error, ProvisionError) else 'remote provisioning failed'}
    print(json.dumps(response))
    # Host.run_python transports structured failure without exposing SSH stderr.
    sys.exit(0)
