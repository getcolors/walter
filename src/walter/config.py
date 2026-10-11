"""Walter's small desired-state vocabulary mapped into PocketDeploy."""
import hashlib
import json
import os
import re
import shlex
from pathlib import Path
from urllib.parse import urlsplit

from blue.cli import load_yaml, read_pars
from pocketdeploy import config as deployment
from pocketdeploy.common import DeployError

GOOGLE = {
    'project': 'project', 'zone': 'zone', 'network': 'network', 'subnet': 'subnet',
    'machine-type': 'machine-type', 'image-id': 'image', 'image-project': 'image-project',
    'image-family': 'image-family', 'boot-disk-type': 'boot-disk-type',
    'boot-disk-size-gb': 'boot-disk-size-gb', 'nic-type': 'nic-type',
    'local-ssd-count': 'local-ssd-count', 'auth': 'auth', 'account': 'account',
}
DEFAULTS = {'workdir': '.colors', 'provider-compute': 'google', 'users': [],
            'compute-prevent-destroy': True, 'walter-ssh-sources': ['0.0.0.0/0'],
            'google-auth': 'application-default', 'google-network': 'default',
            'google-subnet': 'default', 'google-nic-type': 'GVNIC',
            'google-local-ssd-count': 0, 'local-ssd-scratch': False}
TOOLS = {'github-account', 'git-email', 'emacs-config-repo', 'emacs-config-dest',
         'nix-packages', 'agent-tools', 'login-shell', 'asdf-tools', 'corepack-packages',
         'dotfiles-checkout', 'clone-orgs', 'atuin-username', 'seed-agent-credentials'}
SHARED = {'state-file', 'ssh-user', 'ssh-private-key-file', 'ssh-public-key-file',
          'ssh-host-private-key-file', 'ssh-host-public-key-file', 'ssh-known-hosts-file',
          'vault-id', 'vault-state-document-id', 'vault-command', 'compute-require-existing-state'}
KEYS = set(DEFAULTS) | TOOLS | SHARED | {'profile', 'google-region'} | {'google-' + k for k in GOOGLE}
USER = re.compile(r'[a-z_][a-z0-9_-]{0,31}')


def _strings(c, key, pattern=None):
    values = c.get(key, [])
    if not isinstance(values, list) or any(not isinstance(v, str) or not v or
            any(ord(ch) < 32 for ch in v) or (pattern and not re.fullmatch(pattern, v)) for v in values):
        raise DeployError('Invalid ' + key + ' list.')
    if len(set(values)) != len(values):
        raise DeployError('Duplicate ' + key + ' entry.')
    return values


def validate(c):
    if c['provider-compute'] != 'google':
        raise DeployError('Walter supports Google Cloud only.')
    users = _strings(c, 'users', USER.pattern)
    primary = c.get('ssh-user', 'ubuntu')
    if primary in users or 'root' in users or primary == 'root':
        raise DeployError('Seats must differ from the non-root primary user.')
    for key in ('local-ssd-scratch', 'compute-prevent-destroy'):
        if type(c[key]) is not bool:
            raise DeployError(key + ' must be boolean.')
    if c['local-ssd-scratch'] and not c['google-local-ssd-count']:
        raise DeployError('Scratch requires Google Local SSD disks.')
    if c.get('google-region') and c['google-zone'].rsplit('-', 1)[0] != c['google-region']:
        raise DeployError('Google zone must belong to google-region.')
    _strings(c, 'nix-packages', r'[A-Za-z0-9_+.-]+')
    _strings(c, 'clone-orgs', r'[A-Za-z0-9][A-Za-z0-9-]*')
    agents = _strings(c, 'agent-tools')
    if set(agents) - {'pi', 'codex', 'claude', 'antigravity'}:
        raise DeployError('Unsupported agent tool.')
    if set(_strings(c, 'seed-agent-credentials')) - {'pi', 'codex', 'claude'}:
        raise DeployError('Unsupported agent credential.')
    _strings(c, 'corepack-packages', r'[a-z0-9][a-z0-9._-]*')
    if c.get('login-shell') and (not isinstance(c['login-shell'], str) or not re.fullmatch(r'[a-z][a-z0-9-]*', c['login-shell'])):
        raise DeployError('Invalid login-shell.')
    if c.get('github-account'):
        if not isinstance(c['github-account'], str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]*', c['github-account']) or not c.get('git-email'):
            raise DeployError('GitHub account requires a valid account and git-email.')
    elif any(c.get(k) for k in ('emacs-config-repo', 'clone-orgs', 'dotfiles-checkout')):
        raise DeployError('Repository setup requires github-account and git-email.')
    for key in ('git-email', 'atuin-username'):
        if key in c and (not isinstance(c[key], str) or not c[key] or any(ord(x) < 32 for x in c[key])):
            raise DeployError('Invalid ' + key + '.')
    if c.get('emacs-config-repo'):
        if not isinstance(c['emacs-config-repo'], str) or any(ord(x) < 32 for x in c['emacs-config-repo']):
            raise DeployError('Invalid Emacs repository URL.')
        url = urlsplit(c['emacs-config-repo'])
        if url.scheme != 'https' or not url.hostname or url.username or url.password:
            raise DeployError('Emacs repository must use credential-free HTTPS.')
    for key in ('emacs-config-dest', 'dotfiles-checkout'):
        if key in c and (not isinstance(c[key], str) or not c[key].startswith('~/')
                         or '..' in Path(c[key]).parts or any(ord(x) < 32 for x in c[key])):
            raise DeployError(key + ' must be a safe path under ~/.')
    tools = c.get('asdf-tools', [])
    if not isinstance(tools, list):
        raise DeployError('asdf-tools must be a list.')
    names = []
    for tool in tools:
        if not isinstance(tool, dict) or set(tool) != {'name', 'version', 'plugin'}:
            raise DeployError('Each asdf tool requires name, version and plugin.')
        if any(not isinstance(tool[k], str) or not tool[k] or any(ord(x) < 32 for x in tool[k]) for k in ('name', 'version', 'plugin')):
            raise DeployError('asdf tool fields must be nonempty strings without control characters.')
        if (not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', str(tool['name'])) or
                not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', str(tool['version']))):
            raise DeployError('Invalid asdf tool name/version.')
        url = urlsplit(tool['plugin'])
        if url.scheme != 'https' or not url.hostname or url.username or url.password:
            raise DeployError('asdf plugins must use credential-free HTTPS.')
        names.append(tool['name'])
    if len(names) != len(set(names)):
        raise DeployError('Duplicate asdf tools.')
    if ('pi' in agents or c.get('corepack-packages')) and 'nodejs' not in names:
        raise DeployError('Pi and Corepack require an asdf nodejs runtime.')


def validate_private_names(path):
    """Check assignment names without sourcing or exposing private file values."""
    try:
        if not path.exists():
            return
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
            raise ValueError()
        lexer = shlex.shlex(path.read_text(), posix=True, punctuation_chars=';&|()')
        lexer.whitespace_split = True
        for token in lexer:
            match = re.match(r'^([A-Za-z_][A-Za-z0-9_]*)(?:\+)?=', token)
            if match and not match[1].startswith('COLORS_PAR_'):
                raise ValueError()
    except Exception:
        raise DeployError('Private environment assignments must use COLORS_PAR_ variable names and valid shell quoting.') from None


def load(path, env=None):
    path = Path(path).absolute()
    env = os.environ if env is None else env
    validate_private_names(path.parent / '.envrc.private')
    if 'COLORS_PAR_PROFILE' in env:
        raise DeployError('COLORS_PAR_PROFILE is not allowed; select a configuration file.')
    try:
        if path.is_symlink() or path.stat().st_size > 1024 * 1024:
            raise ValueError()
        raw = load_yaml(path.read_text())
        if not isinstance(raw, dict) or set(raw) - KEYS:
            raise ValueError()
    except Exception:
        raise DeployError('Invalid Walter configuration; unknown or retired fields are not supported.') from None
    c = read_pars({**DEFAULTS, **raw}, env)
    # Credential environment variables must never enter desired state or hashing.
    c = {k: v for k, v in c.items() if k in KEYS}
    d = {**deployment.DEFAULTS, **deployment.PROVIDER_DEFAULTS['gcp'],
         **{k: c[k] for k in SHARED if k in c},
         'profile': c.get('profile'), 'provider-compute': 'gcp', 'workdir': c['workdir'],
         'compute-prevent-destroy': c['compute-prevent-destroy'],
         'compute-ssh-sources': c['walter-ssh-sources'], 'compute-http-sources': [],
         '_root': str(path.parent), '_file': str(path)}
    for old, new in GOOGLE.items():
        if 'google-' + old in c:
            d['gcp-' + new] = c['google-' + old]
    if isinstance(d.get('gcp-image'), str) and d['gcp-image'].startswith('projects/'):
        d['gcp-image'] = 'https://compute.googleapis.com/compute/v1/' + d['gcp-image']
    deployment.validate(d)
    validate(c)
    deployment.validate_local_paths(d, path.parent)
    d['_desired_hash'] = hashlib.sha256(json.dumps(c, sort_keys=True).encode()).hexdigest()
    return c, d
