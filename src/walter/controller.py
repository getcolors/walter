"""Walter orchestration; cloud state, authority and recovery belong to PocketDeploy."""
import asyncio
import base64
import copy
import json
import inspect
import os
from pathlib import Path
import stat
import time
import uuid
from graphlib import TopologicalSorter

from blue.workflow import workflow, run as run_workflow
from pocketdeploy.common import DeployError, run
from pocketdeploy import diagnostics
from .remote import OPERATIONS
from pocketdeploy.runtime import initialize
from . import ssh_config

CREDENTIALS = {'claude': '.claude/.credentials.json', 'codex': '.codex/auth.json',
               'pi': '.pi/agent/auth.json'}


def source(name):
    return Path(__file__).with_name(name + '.py').read_text()


def remote(host, connection, config, action, **private):
    payload = {'config': config, 'primary_user': connection.get('user', 'ubuntu'),
               'action': action, **private}
    if action in ('converge', 'bootstrap', 'user-finish'):
        payload['dotfiles_source'] = source('dotfiles')
        if config.get('local-ssd-scratch'):
            payload['local_ssd_source'] = source('local_ssd')
    if action in ('install-et', 'converge-nix') and 'eternal-terminal' in config.get('nix-packages', []):
        payload['eternal_terminal_source'] = source('eternal_terminal')
    if action in ('converge', 'install-go'):
        payload['go_install_source'] = source('go_install')
    program = 'exec(' + repr(Path(diagnostics.__file__).read_text()) + ')\n' + 'exec(compile(' + repr(source('remote')) + ', "<walter>", "exec"))'
    try:
        result = host.run_python(connection, program, payload, timeout=14400)
    except DeployError as error:
        error.diagnostic = diagnostic_context(error.diagnostic, action, private, config, connection)
        raise
    if not isinstance(result, dict) or result.get('ok') is not True:
        # Remote text is untrusted, including exceptions from configured tools.
        stage = result.get('stage') if isinstance(result, dict) else None
        safe = stage if isinstance(stage, str) and stage in {'validation', 'local-ssd', 'machine', 'prerequisites', 'go', 'docker', 'nix-install', 'nix-packages', 'asdf', 'shell-paths', 'agent-tools', 'credentials', 'github', 'dotfiles', 'atuin', 'emacs'} else 'provisioning'
        diagnostic = diagnostics.validate_diagnostic(result.get('diagnostic'), allowed_operations=OPERATIONS) if isinstance(result, dict) else None
        diagnostic = diagnostic_context(diagnostic, action, private, config, connection)
        raise DeployError('Remote ' + safe + ' failed; private output suppressed.', stage=safe, diagnostic=diagnostic)
    return result


def diagnostic_context(value, action, private, config, connection):
    value = diagnostics.validate_diagnostic(value)
    if value is None:
        return None
    # Attribute from trusted caller context, never remote response strings.
    value.pop('task', None)
    value.pop('user', None)
    actions = {'converge', 'converge-nix', 'converge-asdf', 'bootstrap', 'install-go',
               'install-docker', 'install-nix', 'install-et', 'resolve-nix', 'resolve-asdf',
               'user-packages', 'user-runtime', 'user-github', 'user-finish', 'finalize', 'probe', 'status'}
    if action in actions:
        value['task'] = action
    user = private.get('target_user')
    if user in [connection.get('user', 'ubuntu'), *config.get('users', [])]:
        value['user'] = user
    return diagnostics.validate_diagnostic(value)


async def remote_task(*args, **kwargs):
    """Cancellation stops scheduling, never abandons an SSH mutation worker."""
    worker = asyncio.create_task(asyncio.to_thread(remote, *args, **kwargs))
    try:
        return await asyncio.shield(worker)
    except asyncio.CancelledError:
        while not worker.done():
            try:
                await asyncio.shield(worker)
            except asyncio.CancelledError:
                continue
            except Exception:
                break
        # Retrieve a failed worker's exception without replacing cancellation.
        if not worker.cancelled():
            worker.exception()
        raise


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
    except DeployError as error:
        raise DeployError('GitHub credentials unavailable or account mismatch; run gh auth login for github-account before convergence.', diagnostic=error.diagnostic) from None


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

    users = [deployment.get('ssh-user', 'ubuntu'), *config['users']]

    async def provision():
        results['worker_host'] = host.prepare_transport(results['connection'])
        evidence = {'instance_id': results['connection']['instance_id'], 'desired_hash': deployment['_desired_hash']}
        results['intent'] = state.intent(operation, 'converge', evidence)
        results['run_token'] = uuid.uuid4().hex
        results['users'], results['timings'] = {}, {}
        results['resolved'] = {'resolved_versions': {}}
        # Blue handles the outer lifecycle. Its wave-based fork scheduler waits
        # for all siblings, so use a dependency sorter for remote readiness:
        # Docker must not hold back Nix consumers after Nix has completed.
        graph = {'bootstrap': set(), 'install-go': {'bootstrap'},
                 'install-docker': {'bootstrap'}, 'install-nix': {'bootstrap'},
                 'resolve-nix': {'install-nix'}, 'install-et': {'resolve-nix'},
                 'resolve-asdf': {'user-packages-' + users[0]}}
        actions = {name: (name, None) for name in graph}
        for user in users:
            packages = 'user-packages-' + user
            runtime = 'user-runtime-' + user
            github = 'user-github-' + user
            finish = 'user-finish-' + user
            graph[packages] = {'resolve-nix'}
            graph[runtime] = {packages, 'resolve-asdf'}
            graph[github] = {packages}
            graph[finish] = {runtime, github, 'install-go'}
            for name, action in ((packages, 'user-packages'), (runtime, 'user-runtime'),
                                 (github, 'user-github'), (finish, 'user-finish')):
                actions[name] = (action, user)
        actions['resolve-nix'] = ('resolve-nix', users[0])
        actions['resolve-asdf'] = ('resolve-asdf', users[0])
        sorter = TopologicalSorter(graph)
        sorter.prepare()
        active, ready = {}, []
        error = None
        origin = time.monotonic()

        async def execute(name):
            action, user = actions[name]
            arguments = {'run_token': results['run_token']}
            if user:
                arguments['target_user'] = user
            if action not in ('bootstrap', 'install-go', 'install-docker', 'install-nix', 'resolve-nix'):
                arguments['nix_source'] = results['resolved']['nix_source']
            if action in ('user-runtime', 'user-finish'):
                arguments['resolved_versions'] = results['resolved']['resolved_versions']
            if action in ('user-github', 'user-finish'):
                arguments.update(private)
            branch_reporter = copy.copy(reporter)
            branch_reporter._active_spans = []
            branch_reporter.current_stage = None
            branch_reporter.failed_stage = None
            start = time.monotonic() - origin
            try:
                with branch_reporter.stage(name):
                    value = await remote_task(results['worker_host'], results['connection'], config, action, **arguments)
                if action == 'resolve-nix':
                    results['resolved']['nix_source'] = value['nix_source']
                elif action == 'resolve-asdf':
                    results['resolved']['resolved_versions'] = value['resolved_versions']
                elif action == 'user-finish':
                    results['users'][user] = value['users'][user]
                return value
            finally:
                results['timings'][name] = {'started_seconds': round(start, 3),
                                           'finished_seconds': round(time.monotonic() - origin, 3)}

        try:
            while sorter.is_active():
                ready.extend(sorter.get_ready())
                while ready and len(active) < 6 and error is None:
                    name = ready.pop(0)
                    active[asyncio.create_task(execute(name))] = name
                if not active:
                    break
                done, _ = await asyncio.wait(active, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    name = active.pop(task)
                    try:
                        task.result()
                    except Exception as failure:
                        error = error or failure
                    else:
                        sorter.done(name)
                if error is not None:
                    # Never cancel an SSH worker while its process still mutates
                    # the host. Drain every started task; schedule no new work.
                    await asyncio.gather(*active, return_exceptions=True)
                    active.clear()
                    raise error
        finally:
            if active:
                drain = asyncio.gather(*active, return_exceptions=True)
                while not drain.done():
                    try:
                        await asyncio.shield(drain)
                    except asyncio.CancelledError:
                        continue

    async def finalize():
        await remote_task(results['worker_host'], results['connection'], config, 'finalize', run_token=results['run_token'])
        summary = {'verified': True, 'users': users}
        state.complete(results['intent'], summary)
        evidence = {'instance_id': results['connection']['instance_id'], 'desired_hash': deployment['_desired_hash']}
        for row in state.db.execute("SELECT id,payload FROM steps WHERE step=? AND status='pending'", ('converge',)).fetchall():
            if json.loads(row['payload']) == evidence:
                state.complete(row['id'], summary)
        results['provisioning'] = {'ok': True, 'users': results['users'],
                                   'resolved_versions': results['resolved']['resolved_versions'],
                                   'task_timings': results['timings']}

    def local():
        aliases(config, deployment, host, results['connection'])

    steps = {'preflight': [preflight, 'keys'], 'keys': [keys, 'compute'],
             'compute': [compute, 'trust'], 'trust': [trust, 'provision'],
             'provision': [provision, 'finalize'],
             'finalize': [finalize, 'aliases'], 'aliases': [local]}

    def wire(name, _opts):
        fn, *next_steps = steps[name]
        async def safe(opts):
            nonlocal failure
            try:
                with reporter.stage(name):
                    if inspect.iscoroutinefunction(fn):
                        await fn()
                    else:
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
