"""Walter orchestration; cloud state, authority and recovery belong to PocketDeploy."""
import asyncio
import base64
import json
import os
from pathlib import Path
import stat

from blue.workflow import workflow, run as run_workflow
from pocketdeploy.common import DeployError, run
from pocketdeploy.cli import initialize
from . import ssh_config

CREDENTIALS = {'claude': '.claude/.credentials.json', 'codex': '.codex/auth.json',
               'pi': '.pi/agent/auth.json'}


def source(name):
    return Path(__file__).with_name(name + '.py').read_text()


def remote(host, connection, config, action, **private):
    payload = {'config': config, 'primary_user': connection.get('user', 'ubuntu'),
               'action': action, **private}
    if action == 'converge':
        payload['dotfiles_source'] = source('dotfiles')
        if config.get('local-ssd-scratch'):
            payload['local_ssd_source'] = source('local_ssd')
    result = host.run_python(connection, source('remote'), payload, timeout=14400)
    if not isinstance(result, dict) or result.get('ok') is not True:
        # Remote text is untrusted, including exceptions from configured tools.
        stage = result.get('stage') if isinstance(result, dict) else None
        safe = stage if stage in {'validation', 'local-ssd', 'machine', 'prerequisites', 'docker', 'nix-install', 'nix-packages', 'asdf', 'shell-paths', 'agent-tools', 'credentials', 'github', 'dotfiles', 'atuin', 'emacs'} else 'provisioning'
        raise DeployError('Remote ' + safe + ' failed; private output suppressed.', stage=safe)
    return result


def github_token(account):
    """Use the authenticated GitHub CLI identity; never print or persist its token."""
    if not account:
        return None
    try:
        actual = run(['gh', 'api', 'user', '--jq', '.login']).strip()
        if actual.casefold() != account.casefold():
            raise DeployError('GitHub CLI account differs from github-account; sign in with the intended account.')
        token = run(['gh', 'auth', 'token', '--hostname', 'github.com']).strip()
        if not token or '\n' in token:
            raise DeployError('GitHub CLI returned an invalid token.')
        return token
    except DeployError:
        raise DeployError('GitHub credentials unavailable or account mismatch; run gh auth login for github-account before convergence.') from None


def private_payload(config, *, include_token=True):
    result = {'credentials': {}}
    if include_token and config.get('github-account'):
        result['github_token'] = github_token(config['github-account'])
    for agent in config.get('seed-agent-credentials', []):
        relative = CREDENTIALS[agent]
        path = Path.home() / relative
        if not path.exists():
            continue
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1
                or info.st_size > 1024 * 1024 or any(p.is_symlink() for p in [path, *path.parents])):
            raise DeployError('Agent credential source must be a small owned regular file.')
        result['credentials'][relative] = base64.b64encode(path.read_bytes()).decode()
    if config.get('atuin-username'):
        for key in ('password', 'key'):
            value = os.environ.get('COLORS_PAR_ATUIN_' + key.upper())
            if not value:
                raise DeployError('Atuin requires COLORS_PAR_ATUIN_PASSWORD and COLORS_PAR_ATUIN_KEY.')
            result['atuin_' + key] = value
    return result


def aliases(config, deployment, host, connection=None, *, remove=False, check=False):
    names = [config['profile'], *[config['profile'] + '-' + u for u in config['users']]]
    users = [deployment.get('ssh-user', 'ubuntu'), *config['users']]
    payload = {'host_alias': config['profile'], 'block_state': 'absent' if remove else 'present',
               'ssh_hosts': [] if remove else [dict(name=n, ip=connection['ip'], user=u) for n, u in zip(names, users)],
               'identity_file': str(host.key), 'known_hosts_file': str(host.known), 'check_only': check}
    try:
        return ssh_config.update(payload)
    except (ValueError, OSError):
        raise DeployError('SSH alias update refused; check alias ownership and paths.') from None


def complete_remote(state, operation, config, host, connection, action, **private):
    evidence = {'instance_id': connection['instance_id'], 'desired_hash': config['_desired_hash']}
    step = state.intent(operation, action, evidence)
    result = remote(host, connection, config['_walter'], action, **private)
    # Record only safe summaries, never configuration credentials or remote output.
    summary = {'verified': True, 'users': [config.get('ssh-user', 'ubuntu'),
                                         *config['_walter'].get('users', [])]}
    state.complete(step, summary)
    for row in state.db.execute("SELECT id,payload FROM steps WHERE step=? AND status='pending'", (action,)).fetchall():
        if json.loads(row['payload']) == evidence:
            state.complete(row['id'], summary)
    return result


async def converge(config, deployment, state, cloud, host, operation, reporter):
    results, private = {}, {}
    failure = None

    def preflight():
        cloud.plan()  # Refuse immutable drift before provisioning or credentials.
        logged_in = False
        if state.get_resource('compute') and state.get_meta('ssh-host-trust', {}).get('verified'):
            connection = cloud.connection()
            probe = remote(host, connection, config, 'probe')
            logged_in = all(u.get('github_authenticated') for u in probe['users'].values())
        private.update(private_payload(config, include_token=not logged_in))

    def keys():
        initialize(deployment, state, host, Path(deployment['_root']))
        results['public'] = host.pub.read_text().strip()
        deployment['_cloud_init'] = host.cloud_init()

    def compute():
        results['connection'] = cloud.converge(results['public'], operation)

    def trust():
        host.rotate_key(results['connection'], operation)

    def provision():
        results['provisioning'] = complete_remote(state, operation, {**deployment, '_walter': config},
                                                host, results['connection'], 'converge', **private)

    def local():
        aliases(config, deployment, host, results['connection'])

    steps = {'preflight': [preflight, 'keys'], 'keys': [keys, 'compute'],
             'compute': [compute, 'trust'], 'trust': [trust, 'provision'],
             'provision': [provision, 'aliases'], 'aliases': [local]}

    def wire(name, _opts):
        fn, *next_steps = steps[name]
        def safe(opts):
            nonlocal failure
            try:
                with reporter.stage(name):
                    fn()
                return dict(opts)
            except DeployError as error:
                failure = error
            except Exception:
                failure = DeployError('Walter stage failed; private output suppressed.', stage=name)
            return {**opts, 'blue/exit': 1, 'blue/err': str(failure)}
        return [safe, *next_steps]
    try:
        outcome = await run_workflow(workflow(start='preflight', wire_fn=wire), {})
        if outcome.get('blue/exit'):
            raise failure or DeployError('Walter convergence failed.')
        return {'profile': config['profile'], 'connection': results['connection'],
                'provisioning': results['provisioning']}
    finally:
        private.clear()
        deployment.pop('_cloud_init', None)
