"""Standalone remote provisioner. Requests arrive over SSH stdin, never argv.

Only machine tasks run as root. Per-user tasks execute in a fork that permanently
relinquishes root, including all filesystem writes and downloaded installers.
"""
from __future__ import annotations

import base64
from contextlib import ExitStack
import fcntl
import json
import os
from pathlib import Path
import pwd
import re
import shlex
import selectors
import signal
import subprocess
import sys
import tempfile
import time

if 'build_diagnostic' not in globals():
    from pocketdeploy.diagnostics import build_diagnostic, validate_diagnostic

NIXPKGS = 'github:NixOS/nixpkgs/nixos-26.05'
ATUIN_REBUILD_STAMP = 'atuin-history-v2-rebuilt'
CREDENTIALS = {'.claude/.credentials.json', '.codex/auth.json', '.pi/agent/auth.json'}
AGENTS = {
    'pi': ('pi', 'https://pi.dev/install.sh', 'sh'),
    'codex': ('codex', 'https://chatgpt.com/codex/install.sh', 'sh'),
    'claude': ('claude', 'https://claude.ai/install.sh', 'bash'),
    'antigravity': ('agy', 'https://antigravity.google/cli/install.sh', 'bash'),
}


class ProvisionError(Exception):
    """Only fixed messages and validated structured evidence cross the boundary."""

    def __init__(self, message, diagnostic=None):
        super().__init__(message)
        self.diagnostic = validate_diagnostic(diagnostic, allowed_operations=OPERATIONS)



STAGES = frozenset(('validation', 'machine', 'users', 'prerequisites', 'go', 'docker', 'local-ssd', 'nix-install', 'nix-packages', 'asdf', 'shell-paths', 'agent-tools', 'credentials', 'github', 'dotfiles', 'atuin', 'emacs', 'complete'))
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


def task_progress(directory):
    return {path.stem.removeprefix('progress-'): value
            for path in sorted(directory.glob('progress-*.json'))
            if (value := read_progress(path)) is not None}


COMMAND_OPERATIONS = {
    ('atuin', 'sync'): 'atuin-sync', ('atuin', 'store'): 'atuin-store', ('nix', 'profile'): 'nix-profile',
    ('nix', 'build'): 'nix-build', ('nix', 'flake'): 'nix-metadata',
    ('asdf', 'plugin'): 'asdf-plugin', ('asdf', 'install'): 'asdf-install',
    ('asdf', 'latest'): 'asdf-resolve', ('asdf', 'set'): 'asdf-select',
    ('git', 'clone'): 'git-clone', ('git', 'config'): 'git-config',
    ('gh', 'api'): 'github-api', ('gh', 'auth'): 'github-auth',
    ('apt-get', 'update'): 'apt-update', ('apt-get', 'install'): 'apt-install',
}
OPERATIONS = STAGES | frozenset(COMMAND_OPERATIONS.values()) | {
    'command', 'atuin-login', 'download', 'installer', 'service-command', 'tool-version'}


def operation_for(args):
    name = Path(args[0]).name
    selected = COMMAND_OPERATIONS.get((name, args[1] if len(args) > 1 else ''))
    if selected:
        return selected
    if name == 'curl':
        return 'download'
    if name in ('bash', 'sh'):
        return 'installer'
    if name == 'systemctl':
        return 'service-command'
    if len(args) == 2 and args[1] == '--version':
        return 'tool-version'
    return STAGE if STAGE in STAGES else 'command'


def atuin_failure(stderr):
    """Inspect bounded diagnostics locally; return only fixed categories."""
    if not isinstance(stderr, str) or len(stderr) > 65536:
        return 'nonzero_exit', None
    text = stderr.lower()
    status = re.search(r'http status (?:client|server) error \(([1-5][0-9]{2})(?:[ )])', text)
    if status:
        code = int(status[1])
        return {401: 'authentication', 403: 'permission', 429: 'rate_limit'}.get(code, 'http'), code
    for markers, category in (
        (('invalid peer certificate', 'certificate verify failed'), 'tls'),
        (('dns error:', 'failed to lookup address information'), 'dns'),
        (('connection refused', 'network is unreachable'), 'connection'),
        (('operation timed out', 'connection timed out'), 'timeout'),
    ):
        if any(marker in text for marker in markers):
            return category, None
    return 'nonzero_exit', None


def run(args, *, data=None, check=True, timeout=3600):
    started = time.monotonic()
    operation = operation_for(args)
    try:
        result = subprocess.run(args, input=data, text=True, capture_output=True,
                                timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        raise ProvisionError('Command timed out.', build_diagnostic(
            operation, 'timeout', elapsed_seconds=time.monotonic() - started)) from None
    except OSError:
        raise ProvisionError('Command unavailable.', build_diagnostic(
            operation, 'unavailable', elapsed_seconds=time.monotonic() - started)) from None
    if check and result.returncode:
        category, status = atuin_failure(result.stderr) if operation == 'atuin-sync' else ('nonzero_exit', None)
        if result.returncode < 0:
            category, status = 'signal', None
        raise ProvisionError('Command failed; private output suppressed.', build_diagnostic(
            operation, category, returncode=result.returncode,
            elapsed_seconds=time.monotonic() - started, http_status=status))
    return result


ATUIN_CAPTURE_LIMIT = 65536


def atuin_sync(home, *, capture=False, timeout=3600):
    """Capture only sync, after privilege drop; never accept login arguments."""
    if not capture:
        return run(['atuin', 'sync'], timeout=timeout)
    started = time.monotonic()
    buffers = {'stdout': bytearray(), 'stderr': bytearray()}
    truncated = {'stdout': False, 'stderr': False}
    category, status, returncode = None, None, None
    try:
        with subprocess.Popen(['atuin', 'sync'], stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              start_new_session=True) as process:
            with selectors.DefaultSelector() as selector:
                for name in buffers:
                    selector.register(getattr(process, name), selectors.EVENT_READ, name)
                deadline = started + timeout
                while selector.get_map():
                    now = time.monotonic()
                    if now >= deadline:
                        if category == 'timeout':
                            break
                        category = 'timeout'
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                        deadline = now + 1
                    for key, _ in selector.select(min(0.1, max(0, deadline - now))):
                        chunk = os.read(key.fileobj.fileno(), 16384)
                        if not chunk:
                            selector.unregister(key.fileobj)
                            continue
                        name = key.data
                        buffers[name].extend(chunk)
                        truncated[name] |= len(buffers[name]) > ATUIN_CAPTURE_LIMIT
                        del buffers[name][:-ATUIN_CAPTURE_LIMIT]
                remaining = max(0, deadline - time.monotonic())
                try:
                    returncode = process.wait(timeout=remaining)
                except subprocess.TimeoutExpired:
                    category = 'timeout'
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    returncode = process.wait()
    except OSError:
        category = 'unavailable'
    elapsed = time.monotonic() - started
    output = {name: bytes(value).decode('utf-8', errors='replace')
              for name, value in buffers.items()}
    if category is None and returncode:
        category, status = atuin_failure(output['stderr'])
        if returncode < 0:
            category, status = 'signal', None
    record = {'operation': 'atuin-sync', 'category': category or 'success',
              'exit_code': returncode, 'elapsed_seconds': elapsed,
              **output, 'stdout_truncated': truncated['stdout'],
              'stderr_truncated': truncated['stderr']}
    # Atomic replacement keeps one bounded attempt, never a growing log.
    write(home / '.local/state/walter/atuin-sync.json', json.dumps(record) + '\n', 0o600)
    if category:
        raise ProvisionError('Atuin sync failed; output captured on the user seat.',
                             build_diagnostic('atuin-sync', category,
                                 returncode=returncode if category not in ('timeout', 'unavailable') else None,
                                 elapsed_seconds=elapsed, http_status=status))
    return subprocess.CompletedProcess(['atuin', 'sync'], returncode,
                                       output['stdout'], output['stderr'])


def install_atuin(home):
    """Use the official per-user installer before retiring the Nix package."""
    binary = home / '.atuin/bin/atuin'
    if not binary.is_file():
        script = run(['curl', '--proto', '=https', '--tlsv1.2', '-LsSf',
                      'https://setup.atuin.sh']).stdout
        run(['sh', '-s', '--', '--non-interactive'], data=script)
    version = run([str(binary), '--version']).stdout.strip()
    match = re.fullmatch(r'atuin (\d+)\.(\d+)\.(\d+)(?:[-+][^\s]+)?(?:\s.*)?', version)
    if not match or tuple(map(int, match.groups())) < (18, 18, 0):
        raise ProvisionError('The official Atuin installation must support history v2.')
    if (home / '.nix-profile').exists():
        current = profile_attributes(json.loads(run(['nix', 'profile', 'list', '--json']).stdout))
        if 'atuin' in current:
            run(['nix', 'profile', 'remove', current['atuin']])


def atuin_history(home, *, capture=False):
    """Sync with a compatible client and repair history once after migration."""
    version = run(['atuin', '--version']).stdout.strip()
    match = re.fullmatch(r'atuin (\d+)\.(\d+)\.(\d+)(?:[-+][^\s]+)?(?:\s.*)?', version)
    if not match or tuple(map(int, match.groups())) < (18, 18, 0):
        raise ProvisionError('Atuin 18.18.0 or newer must be first on the user PATH.')
    atuin_sync(home, capture=capture)
    stamp = home / '.local/state/walter' / ATUIN_REBUILD_STAMP
    if not stamp.exists():
        run(['atuin', 'store', 'rebuild', 'history'])
        write(stamp, 'History rebuilt with v2-compatible Atuin after successful sync.\n', 0o600)


def failure_result(error):
    result = {'ok': False, 'stage': STAGE}
    diagnostic = validate_diagnostic(getattr(error, 'diagnostic', None), allowed_operations=OPERATIONS)
    if diagnostic is not None:
        result['diagnostic'] = diagnostic
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
        if value.get('originalUrl', '').startswith('github:NixOS/nixpkgs/'):
            result['.'.join(attr)] = name
    return result


def nix_packages(packages, upgrade=False, source=NIXPKGS):
    if not packages:
        return
    current = profile_attributes(json.loads(run(['nix', 'profile', 'list', '--json']).stdout)) if (Path.home() / '.nix-profile').exists() else {}
    if upgrade:
        # `profile upgrade` deliberately skips immutable original URLs. Build
        # replacements first, preserving a usable profile if realization fails.
        installables = [source + '#' + p for p in packages]
        run(['nix', 'build', '--impure', '--no-link', *installables])
        names = [current[p] for p in packages if p in current]
        if names:
            run(['nix', 'profile', 'remove', *names])
        try:
            run(['nix', 'profile', 'add', '--impure', *installables])
        except BaseException:
            if names:
                run(['nix', 'profile', 'rollback'])
            raise
        return
    missing = [p for p in packages if p not in current]
    if missing:
        run(['nix', 'profile', 'add', '--impure', *[source + '#' + p for p in missing]])


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
    # Explicit entries win over discovery (including its archived/fork filters).
    # GitHub identities are case insensitive, including across both sources.
    repositories = {}

    def select(name, org=None):
        if (not isinstance(name, str) or
                not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+', name) or
                name.split('/')[1] in ('.', '..') or
                (org is not None and name.split('/')[0].lower() != org.lower())):
            raise ProvisionError('invalid repository identity')
        repositories.setdefault(name.lower(), name)

    for name in config.get('clone-repos', []):
        select(name)
    for org in config.get('clone-orgs', []):
        pages = json.loads(run(['gh', 'api', '--paginate', '--slurp',
                               f'orgs/{org}/repos?per_page=100&type=sources&sort=full_name']).stdout)
        for page in pages:
            for repo in page:
                if repo.get('archived') or repo.get('fork'):
                    continue
                select(repo['full_name'], org)
    # Workers only clone independent destinations, after global Git/auth writes.
    # Bound per-home network/disk work even when several users provision at once.
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(clone, 'https://github.com/' + name + '.git', home / 'code' / name)
                   for name in repositories.values()]
        for future in futures:
            future.result()


def shell_paths(home, config):
    paths = '/usr/local/go/bin:$HOME/go/bin:$HOME/.atuin/bin:$HOME/.local/bin:$HOME/.asdf/shims:$HOME/.nix-profile/bin:/nix/var/nix/profiles/default/bin'
    fragment = '# BEGIN walter paths\nexport PATH="' + paths + ':$PATH"\n# END walter paths\n'
    profile = home / '.profile'
    text = profile.read_text() if profile.exists() else ''
    text = re.sub(r'# BEGIN walter paths\n.*?# END walter paths\n?', '', text, flags=re.S)
    write(profile, text.rstrip() + '\n' + fragment)
    write(home / '.config/fish/conf.d/walter-paths.fish',
          'fish_add_path --prepend /usr/local/go/bin ~/go/bin ~/.atuin/bin ~/.local/bin ~/.asdf/shims ~/.nix-profile/bin /nix/var/nix/profiles/default/bin\n')


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
    started = time.monotonic()
    deadline = started + timeout
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
                raise ProvisionError('Atuin rejected the supplied credentials', build_diagnostic(
                    'atuin-login', 'authentication', elapsed_seconds=time.monotonic() - started))
            if not eof and prompt is not None:
                # rpassword disables echo after writing the password prompt.
                if prompt == 'key' or not termios.tcgetattr(fd)[3] & termios.ECHO:
                    os.write(fd, pending.pop(prompt).encode() + b'\n')
                    captured = b''
            finished, status = os.waitpid(pid, os.WNOHANG)
            if finished:
                completed = True
                if status:
                    raise ProvisionError('Atuin login failed', build_diagnostic(
                        'atuin-login', 'nonzero_exit', returncode=os.waitstatus_to_exitcode(status),
                        elapsed_seconds=time.monotonic() - started))
                return
        raise ProvisionError('Atuin login timed out', build_diagnostic(
            'atuin-login', 'timeout', elapsed_seconds=time.monotonic() - started))
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
                      PATH=f'/usr/local/go/bin:{home}/go/bin:{home}/.atuin/bin:{home}/.local/bin:{home}/.asdf/shims:{home}/.nix-profile/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin',
                      NIXPKGS_ALLOW_UNFREE='1', DEBIAN_FRONTEND='noninteractive',
                      MAKEFLAGS='-j1', CMAKE_BUILD_PARALLEL_LEVEL='1', CARGO_BUILD_JOBS='1')
    os.chdir(home)
    config = payload['config']
    action = payload.get('action', 'converge')
    progress = home / '.local/state/walter' / ('progress-' + action + '.json')
    PROGRESS_PATH = None if action in ('probe', 'status') else progress
    PROGRESS_USER = user
    if action in ('probe', 'status'):
        return {'github_authenticated': github_authenticated(config), 'nix': Path('/nix/receipt.json').exists(), 'progress': read_progress(home / '.local/state/walter/progress-user-finish.json'), 'tasks': task_progress(home / '.local/state/walter')}
    if action in ('resolve', 'resolve-nix', 'resolve-asdf'):
        stage('nix-packages')
        nix_source = payload.get('nix_source', NIXPKGS)
        if action != 'resolve-asdf':
            metadata = json.loads(run(['nix', 'flake', 'metadata', '--json', NIXPKGS]).stdout)
            revision = metadata.get('locked', {}).get('rev', '')
            if not re.fullmatch(r'[0-9a-f]{40}', revision):
                raise ProvisionError('invalid Nix package revision')
            nix_source = 'github:NixOS/nixpkgs/' + revision
        if action == 'resolve-nix':
            stage('complete')
            return {'resolved_versions': {}, 'nix_source': nix_source}
        if action == 'resolve' and config.get('asdf-tools'):
            nix_packages(['asdf-vm'], source=nix_source)
        for tool in config.get('asdf-tools', []):
            name, version = tool['name'], tool['version']
            if version == 'latest':
                if not (home / '.asdf/plugins' / name).is_dir():
                    run(['asdf', 'plugin', 'add', name, *([tool['plugin']] if tool.get('plugin') else [])])
                run(['asdf', 'plugin', 'update', name])
                version = run(['asdf', 'latest', name]).stdout.strip()
            if len(version) > 128 or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', version) or version == 'latest':
                raise ProvisionError('invalid resolved runtime version')
            resolved[name] = version
        stage('complete')
        return {'resolved_versions': resolved, 'nix_source': nix_source}
    packages = list(config.get('nix-packages', []))
    if action in ('converge', 'user-packages'):
        packages += ['ghostty.terminfo']
        if config.get('github-account'):
            packages += ['gh']
        if config.get('emacs-config-repo'):
            packages += ['emacs', 'coreutils']
        if config.get('asdf-tools'):
            packages += ['asdf-vm']
        if config.get('login-shell'):
            packages += [config['login-shell']]
    if action in ('converge', 'converge-nix', 'user-packages'):
        stage('nix-packages')
        nix_packages(list(dict.fromkeys(packages)), upgrade=action == 'converge-nix', source=payload.get('nix_source', NIXPKGS))
    if action in ('converge', 'converge-asdf', 'user-runtime'):
        stage('asdf')
        asdf_tools(config, resolved, home)
    if action == 'user-github':
        stage('github')
        github(config, payload, home)
    if action not in ('converge', 'user-finish'):
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
    if action == 'converge':
        stage('github')
        github(config, payload, home)
    stage('dotfiles')
    if config.get('dotfiles-checkout'):
        namespace = {'__name__': 'walter_dotfiles'}
        exec(payload['dotfiles_source'], namespace)
        namespace['install'](user_path(home, config['dotfiles-checkout']), home)
    if config.get('atuin-username'):
        stage('atuin')
        install_atuin(home)
    # Emacs depends on packages/checkouts/dotfiles, not Atuin authentication.
    # Start its existing locked background job before the potentially long sync.
    stage('emacs')
    log = emacs_job(home, config)
    stage('atuin')
    if config.get('atuin-username'):
        username = config['atuin-username']
        stamp = home / '.local/state/walter' / ('atuin-' + username)
        if not stamp.exists():
            if not payload.get('atuin_password') or not payload.get('atuin_key'):
                raise ProvisionError('Atuin credentials are required')
            atuin_login(username, payload['atuin_password'], payload['atuin_key'])
            write(stamp, 'Authenticated by Walter\n', 0o600)
        atuin_history(home, capture=config.get('atuin-sync-capture', False))
    stage('complete')
    return {'resolved_versions': resolved, 'emacs_log': log}


def start_user(payload, user, resolved):
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
            value = failure_result(error)
        with os.fdopen(write_fd, 'w') as stream:
            json.dump(value, stream)
        os._exit(0)
    os.close(write_fd)
    return pid, read_fd


def finish_user(worker):
    pid, read_fd = worker
    try:
        with os.fdopen(read_fd) as stream:
            result = json.load(stream)
    finally:
        os.waitpid(pid, 0)
    if not result['ok']:
        stage(result['stage'])
        raise ProvisionError('User provisioning failed.', result.get('diagnostic'))
    return result['result']


def setup_storage(payload):
    write('/usr/local/sbin/walter-local-ssd', payload['local_ssd_source'], 0o755)
    write('/etc/walter-scratch.json', json.dumps({'disk_count': payload['config']['google-local-ssd-count']}))
    # Local filesystems must be ready before tmpfiles, logins or package daemons.
    unit = ('[Unit]\nDescription=Walter Local SSD development directories\n'
            'DefaultDependencies=no\nAfter=systemd-remount-fs.service systemd-udev-trigger.service\n'
            'Wants=systemd-udev-trigger.service\n'
            'Before=local-fs.target systemd-tmpfiles-setup.service ssh.service ssh.socket '
            'docker.service containerd.service nix-daemon.service nix-daemon.socket\n'
            '[Service]\nType=oneshot\nExecStart=/usr/local/sbin/walter-local-ssd\n'
            'RemainAfterExit=yes\nTimeoutStartSec=120\nEnvironment=PATH=/usr/sbin:/usr/bin:/sbin:/bin\n'
            '[Install]\nWantedBy=local-fs.target\n')
    write('/etc/systemd/system/walter-local-ssd.service', unit)
    for service in ('ssh.service', 'ssh.socket', 'docker.service', 'containerd.service'):
        write('/etc/systemd/system/' + service + '.d/walter-storage.conf',
              '[Unit]\nRequires=walter-local-ssd.service\nAfter=walter-local-ssd.service\n')
    run(['systemctl', 'daemon-reload'])
    run(['/usr/local/sbin/walter-local-ssd', '--bootstrap'])
    run(['systemctl', 'enable', '--now', 'walter-local-ssd'])


def as_user(payload, user, resolved):
    return finish_user(start_user(payload, user, resolved))


def bootstrap(payload, users):
    config = payload['config']
    stage('prerequisites')
    prerequisites(config)
    if config.get('local-ssd-scratch'):
        stage('local-ssd')
        setup_storage(payload)
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


def install_docker(primary):
    stage('docker')
    if not Path('/usr/bin/dockerd').exists():
        install_script('https://get.docker.com')
    run(['systemctl', 'enable', '--now', 'docker'])
    run(['usermod', '-aG', 'docker', primary])


def install_go(payload):
    stage('go')
    namespace = {'__name__': 'walter_go_install'}
    exec(payload['go_install_source'], namespace)
    return namespace['install'](run, write, payload['config']['go-version'])


def machine(payload, users):
    bootstrap(payload, users)
    install_docker(users[0])
    stage('nix-install')
    install_nix(payload['config'])
    install_go(payload)


def install_nix(config, receipt=Path('/nix/receipt.json')):
    def completed():
        if not receipt.exists():
            return False
        actions = json.loads(receipt.read_text()).get('actions', [])
        return bool(actions) and all(item.get('state') in ('Completed', 'Skipped') for item in actions)

    if not receipt.exists():
        install_script('https://install.determinate.systems/nix', arguments=('install', '--no-confirm'))
    elif not completed():
        # Plain install treats every existing receipt as success, even Progress.
        # The explicit positional plan resumes unfinished actions in that receipt.
        run(['/nix/nix-installer', 'install', '--no-confirm', str(receipt)])
    if not completed():
        raise ProvisionError('Nix installer did not complete')
    if config.get('local-ssd-scratch'):
        # The installer explicitly refuses preexisting service override folders.
        # Add storage dependencies only after every installer action completes.
        for service in ('nix-daemon.service', 'nix-daemon.socket', 'determinate-nixd.socket'):
            write('/etc/systemd/system/' + service + '.d/walter-storage.conf',
                  '[Unit]\nRequires=walter-local-ssd.service\nAfter=walter-local-ssd.service\n')
        run(['systemctl', 'daemon-reload'])


def prerequisites(config):
    packages = ['curl', 'git', 'unzip', 'xz-utils', 'ca-certificates', 'gnupg', 'tar', 'gzip', 'util-linux']
    if any(tool['name'] == 'nodejs' for tool in config.get('asdf-tools', [])):
        packages += ['libatomic1']
    if any(tool['name'] == 'python' for tool in config.get('asdf-tools', [])):
        packages += ['build-essential', 'libssl-dev', 'zlib1g-dev', 'libbz2-dev', 'libreadline-dev', 'libsqlite3-dev', 'libffi-dev', 'liblzma-dev', 'libncurses-dev', 'tk-dev', 'uuid-dev']
    if config.get('local-ssd-scratch'):
        packages += ['e2fsprogs', 'mdadm']
    os.environ['DEBIAN_FRONTEND'] = 'noninteractive'
    run(['apt-get', 'update'])
    run(['apt-get', 'install', '-y', '--no-install-recommends', *packages])


def finalize_shells(config, users):
    if not config.get('login-shell'):
        return
    for user in users:
        shell = str(Path(pwd.getpwnam(user).pw_dir) / '.nix-profile/bin' / config['login-shell'])
        shells = Path('/etc/shells').read_text().splitlines()
        if shell not in shells:
            write('/etc/shells', '\n'.join([*shells, shell]) + '\n')
        run(['chsh', '-s', shell, user])


def install_et(payload, nix_source):
    if 'eternal-terminal' not in payload['config'].get('nix-packages', []):
        return
    namespace = {'__name__': 'walter_eternal_terminal'}
    exec(payload['eternal_terminal_source'], namespace)
    namespace['install'](run, write, nix_source,
                         local_ssd=payload['config'].get('local-ssd-scratch', False))


USER_ACTIONS = frozenset(('user-packages', 'user-runtime', 'user-github', 'user-finish', 'resolve-nix', 'resolve-asdf'))
MACHINE_ACTIONS = frozenset(('bootstrap', 'install-go', 'install-docker', 'install-nix', 'install-et'))
DAG_ACTIONS = USER_ACTIONS | MACHINE_ACTIONS | {'finalize'}


def main(payload):
    global PROGRESS_PATH, PROGRESS_USER
    users = [payload.get('primary_user', 'ubuntu'), *payload['config'].get('users', [])]
    if len(set(users)) != len(users) or any(not re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}', user) for user in users):
        raise ProvisionError('invalid login names')
    action = payload.get('action', 'converge')
    if action not in DAG_ACTIONS and action not in ('converge', 'converge-nix', 'converge-asdf', 'probe', 'status', 'finalize'):
        raise ProvisionError('unsupported remote action')
    if action in DAG_ACTIONS:
        PROGRESS_PATH = Path('/var/lib/walter') / ('progress-' + action + '.json')
        PROGRESS_USER = None
    if action in USER_ACTIONS:
        user = payload.get('target_user')
        if user not in users:
            raise ProvisionError('invalid target user')
        PROGRESS_PATH = Path('/var/lib/walter') / ('progress-' + action + '-' + user + '.json')
        PROGRESS_USER = user
        stage('users')
        result = as_user(payload, user, payload.get('resolved_versions', {}))
        stage('complete')
        return {'ok': True, 'users': {user: result}, **result}
    if action in MACHINE_ACTIONS:
        if action == 'bootstrap':
            bootstrap(payload, users)
        elif action == 'install-go':
            install_go(payload)
        elif action == 'install-docker':
            install_docker(users[0])
        elif action == 'install-nix':
            stage('nix-install')
            install_nix(payload['config'])
        elif action == 'install-et':
            install_et(payload, payload['nix_source'])
        stage('complete')
        return {'ok': True}
    if action == 'finalize':
        finalize_shells(payload['config'], users)
        PROGRESS_PATH = Path('/var/lib/walter/progress.json')
        PROGRESS_USER = None
        stage('complete')
        return {'ok': True}
    PROGRESS_PATH = None if action in ('probe', 'status') else Path('/var/lib/walter/progress.json')
    PROGRESS_USER = None
    if action == 'converge':
        stage('machine')
        machine(payload, users)
    elif action == 'converge-asdf':
        prerequisites(payload['config'])
    if action in ('probe', 'status'):
        results, workers = {}, {}
        for user in users:
            try:
                pwd.getpwnam(user)
            except KeyError:
                results[user] = {'github_authenticated': False, 'exists': False}
                continue
            workers[user] = start_user(payload, user, {})
        failure = None
        for user, worker in workers.items():
            try:
                results[user] = finish_user(worker)
            except Exception as error:
                failure = error
        if failure is not None:
            raise failure
        return {'ok': True, 'users': results, 'resolved_versions': {},
                'progress': read_progress(Path('/var/lib/walter/progress.json')), 'tasks': task_progress(Path('/var/lib/walter'))}
    if action == 'converge-nix':
        resolution = as_user({**payload, 'action': 'resolve-nix'}, users[0], {})
        payload = {**payload, 'nix_source': resolution['nix_source']}
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
    if action == 'converge-nix':
        install_et(payload, payload['nix_source'])
    if action == 'converge':
        finalize_shells(payload['config'], users)
    if action not in ('probe', 'status'):
        PROGRESS_USER = None
        stage('complete')
    return {'ok': True, 'users': results, 'resolved_versions': resolved,
            'progress': read_progress(Path('/var/lib/walter/progress.json')), 'tasks': task_progress(Path('/var/lib/walter'))}


def dispatch(request, lock_root=Path('/run/lock'), state_root=Path('/var/lib/walter')):
    """A bootstrap may supersede an idle run; stale tasks then fail closed.

    The controller holds its deployment lock for the entire DAG. Remote token
    checks additionally prevent delayed SSH requests from an earlier controller
    run from mutating the replacement run. Every check holds the machine lock.
    """
    action = request.get('action')
    if action in ('probe', 'status'):
        return main(request)
    target = request.get('target_user')
    user_action = action in USER_ACTIONS
    if user_action and (not isinstance(target, str) or not re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}', target)):
        raise ProvisionError('invalid target user')
    with ExitStack() as locks:
        machine_lock = locks.enter_context(open(lock_root / 'walter-provision.lock', 'a'))
        shared = action in DAG_ACTIONS - {'bootstrap', 'finalize'}
        fcntl.flock(machine_lock, (fcntl.LOCK_SH if shared else fcntl.LOCK_EX) | fcntl.LOCK_NB)
        if action in DAG_ACTIONS:
            token = request.get('run_token')
            if not isinstance(token, str) or not re.fullmatch(r'[0-9a-f]{32}', token):
                raise ProvisionError('invalid convergence token')
            marker = state_root / 'active-run'
            if action == 'bootstrap':
                write(marker, token + '\n', 0o600)
            elif not marker.exists() or marker.read_text().strip() != token:
                raise ProvisionError('stale convergence task')
        elif not shared:
            (state_root / 'active-run').unlink(missing_ok=True)
        # Each Nix profile/asdf tree/Git configuration belongs to one worker.
        # Finish is ordered after the concurrent runtime and Git workers.
        resource = ('user-' + target + '-' + ('asdf' if action in ('resolve-asdf', 'user-runtime') else action)) if user_action else action
        if shared:
            resource_lock = locks.enter_context(open(lock_root / ('walter-' + resource + '.lock'), 'a'))
            fcntl.flock(resource_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        result = main(request)
        if action == 'finalize':
            marker.unlink(missing_ok=True)
        return result


if __name__ == '__main__':
    try:
        request = json.load(sys.stdin)
        if os.geteuid() != 0:
            raise ProvisionError('remote provisioner requires sudo')
        response = dispatch(request)
    except Exception as error:
        response = failure_result(error)
    print(json.dumps(response))
    # Host.run_python transports structured failure without exposing SSH stderr.
    sys.exit(0)
