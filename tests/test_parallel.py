"""Real DAG fan-out with synthetic SSH, including failure joins and thread safety."""
import asyncio
from contextlib import nullcontext
from pathlib import Path
import threading
from unittest.mock import Mock, patch

import pytest
from pocketdeploy.common import DeployError
from walter import controller, remote


@pytest.mark.parametrize('failed_user', [None, 'jack'])
def test_user_branches_overlap_and_join_without_worker_journal_access(failed_user):
    main_thread = threading.get_ident()
    barrier = threading.Barrier(3, timeout=5)
    completed = set()
    actions = []
    config = {'profile': 'test', 'users': ['jack', 'rose']}
    deployment = {'_root': '.', '_desired_hash': 'hash'}
    state, host, cloud, reporter = Mock(), Mock(), Mock(), Mock()
    state.get_resource.return_value = None
    state.db.execute.return_value.fetchall.return_value = []
    state.intent.side_effect = lambda *a: (pytest.fail('worker journal') if threading.get_ident() != main_thread else 1)
    state.complete.side_effect = lambda *a: (pytest.fail('worker journal') if threading.get_ident() != main_thread else None)
    host.pub = Mock(spec=Path)
    host.pub.read_text.return_value = 'public'
    host.prepare_transport.side_effect = lambda *a: (pytest.fail('worker trust access') if threading.get_ident() != main_thread else ['ssh'])
    cloud.converge.return_value = {'instance_id': '123'}
    reporter.stage.side_effect = lambda name: nullcontext()

    docker_release = threading.Event()
    et_release = threading.Event()
    packages = set()
    runtimes = set()
    git_done = set()

    def fake_remote(_host, connection, conf, action, **private):
        actions.append(action)
        assert len(private['run_token']) == 32
        if action == 'bootstrap':
            return {'ok': True}
        if action == 'install-docker':
            # Fast Nix consumers MUST progress before slow Docker completes.
            assert docker_release.wait(5)
        if action == 'install-et':
            # ET must likewise not hold back per-user work.
            assert et_release.wait(5)
        if action == 'resolve-nix':
            assert 'install-nix' in actions
            return {'nix_source': 'pinned'}
        if action == 'resolve-asdf':
            assert 'ubuntu' in packages
            return {'resolved_versions': {'nodejs': '24.0.0'}}
        if action == 'user-packages':
            assert private['nix_source'] == 'pinned'
            packages.add(private['target_user'])
        if action == 'user-github':
            user = private['target_user']
            assert user in packages
            git_done.add(user)
        if action == 'user-runtime':
            assert private['resolved_versions'] == {'nodejs': '24.0.0'}
            user = private['target_user']
            assert user in packages
            docker_release.set()
            et_release.set()
            barrier.wait()
            runtimes.add(user)
            completed.add(user)
            if user == failed_user:
                raise DeployError('synthetic failure')
        if action == 'user-finish':
            user = private['target_user']
            assert user in runtimes and user in git_done
            assert 'install-go' in actions
            return {'users': {user: {'resolved_versions': private['resolved_versions']}}}
        if action == 'finalize':
            assert completed == {'ubuntu', 'jack', 'rose'}
        return {'ok': True}

    with patch.object(controller, 'remote', side_effect=fake_remote), patch.object(controller, 'initialize'), patch.object(controller, 'private_payload', return_value={'credentials': {}}), patch.object(controller, 'aliases') as aliases:
        task = controller.converge(config, deployment, state, cloud, host, 'operation', reporter)
        if failed_user:
            with pytest.raises(DeployError):
                asyncio.run(task)
            assert 'finalize' not in actions
            state.complete.assert_not_called()
            aliases.assert_not_called()
        else:
            result = asyncio.run(task)
            assert set(result['provisioning']['users']) == completed
            state.complete.assert_called_once()
            aliases.assert_called_once()
    assert completed == {'ubuntu', 'jack', 'rose'}


def test_user_action_rejects_unconfigured_identity():
    with pytest.raises(remote.ProvisionError, match='invalid target user'):
        remote.main({'action': 'user-packages', 'config': {'users': ['jack']}, 'target_user': 'root'})


def test_probe_starts_every_user_before_waiting_and_drains_failures():
    events = []
    def start(payload, user, resolved):
        events.append(('start', user))
        return user
    def finish(user):
        assert [e for e in events if e[0] == 'start'] == [('start', 'ubuntu'), ('start', 'jack'), ('start', 'rose')]
        events.append(('finish', user))
        if user == 'jack':
            raise remote.ProvisionError('synthetic failure')
        return {'github_authenticated': True}
    with patch.object(remote.pwd, 'getpwnam'), patch.object(remote, 'start_user', side_effect=start), patch.object(remote, 'finish_user', side_effect=finish):
        with pytest.raises(remote.ProvisionError):
            remote.main({'action': 'probe', 'config': {'users': ['jack', 'rose']}})
    assert events[-1] == ('finish', 'rose')


def test_pinned_nix_upgrade_prebuilds_and_rolls_back_failed_replacement():
    import json
    from types import SimpleNamespace
    profile = {'elements': {
        'fish': {'attrPath': 'legacyPackages.aarch64-linux.fish',
                 'originalUrl': 'github:NixOS/nixpkgs/' + 'a' * 40},
        'manual': {'attrPath': 'legacyPackages.aarch64-linux.fd',
                   'originalUrl': 'github:other/packages'},
    }}
    calls = []
    def run(args, **kwargs):
        calls.append(args)
        if args[:3] == ['nix', 'profile', 'add']:
            raise remote.ProvisionError('synthetic add failure')
        return SimpleNamespace(stdout=json.dumps(profile))
    with patch.object(remote, 'run', side_effect=run), patch.object(Path, 'exists', return_value=True):
        with pytest.raises(remote.ProvisionError):
            remote.nix_packages(['fish'], upgrade=True, source='github:NixOS/nixpkgs/' + 'b' * 40)
    assert calls[1][:2] == ['nix', 'build']
    assert calls[2] == ['nix', 'profile', 'remove', 'fish']
    assert calls[-1] == ['nix', 'profile', 'rollback']
    assert all('manual' not in call for call in calls)


def test_focused_nix_resolves_once_and_updates_et_after_users():
    actions = []
    def as_user(payload, user, resolved):
        actions.append((payload['action'], user, payload.get('nix_source')))
        if payload['action'] == 'resolve-nix':
            return {'nix_source': 'pinned'}
        return {'resolved_versions': {}}
    with patch.object(remote, 'as_user', side_effect=as_user), patch.object(remote.pwd, 'getpwnam'), patch.object(remote, 'stage'), patch.object(remote, 'read_progress'), patch.object(remote, 'install_et') as install:
        payload = {'config': {'users': ['jack', 'rose']}, 'action': 'converge-nix'}
        remote.main(payload)
    assert actions == [('resolve-nix', 'ubuntu', None), ('converge-nix', 'ubuntu', 'pinned'), ('converge-nix', 'jack', 'pinned'), ('converge-nix', 'rose', 'pinned')]
    assert install.call_args.args[1] == 'pinned'


def test_failed_nix_realization_does_not_remove_installed_packages():
    import json
    from types import SimpleNamespace
    calls = []
    def run(args, **kwargs):
        calls.append(args)
        if args[:2] == ['nix', 'build']:
            raise remote.ProvisionError('synthetic build failure')
        return SimpleNamespace(stdout=json.dumps({'elements': {'fish': {
            'attrPath': 'legacyPackages.aarch64-linux.fish',
            'originalUrl': remote.NIXPKGS}}}))
    with patch.object(remote, 'run', side_effect=run), patch.object(Path, 'exists', return_value=True):
        with pytest.raises(remote.ProvisionError):
            remote.nix_packages(['fish'], upgrade=True)
    assert not any(call[:3] == ['nix', 'profile', 'remove'] for call in calls)


def test_remote_tokens_resource_exclusion_and_finalization(tmp_path):
    import fcntl
    lock_root, state_root = tmp_path / 'locks', tmp_path / 'state'
    lock_root.mkdir()
    state_root.mkdir()
    token = 'a' * 32
    request = {'run_token': token, 'config': {'users': []}}
    with patch.object(remote, 'main', return_value={'ok': True}) as main:
        remote.dispatch({**request, 'action': 'bootstrap'}, lock_root, state_root)
        with pytest.raises(remote.ProvisionError, match='stale'):
            remote.dispatch({**request, 'action': 'install-go', 'run_token': 'b' * 32}, lock_root, state_root)
        with (lock_root / 'walter-install-go.lock').open('a') as resource:
            fcntl.flock(resource, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with pytest.raises(BlockingIOError):
                remote.dispatch({**request, 'action': 'install-go'}, lock_root, state_root)
            # An unrelated resource remains eligible under the shared lock.
            remote.dispatch({**request, 'action': 'install-nix'}, lock_root, state_root)
        with (lock_root / 'walter-provision.lock').open('a') as machine:
            fcntl.flock(machine, fcntl.LOCK_SH | fcntl.LOCK_NB)
            for action in ('bootstrap', 'finalize', 'converge-nix'):
                with pytest.raises(BlockingIOError):
                    remote.dispatch({**request, 'action': action}, lock_root, state_root)
        remote.dispatch({**request, 'action': 'finalize'}, lock_root, state_root)
        with pytest.raises(remote.ProvisionError, match='stale'):
            remote.dispatch({**request, 'action': 'install-go'}, lock_root, state_root)
        assert main.call_count == 3


def test_new_bootstrap_supersedes_idle_run_and_asdf_owns_one_resource(tmp_path):
    import fcntl
    locks, state = tmp_path / 'locks', tmp_path / 'state'
    locks.mkdir()
    state.mkdir()
    request = {'run_token': 'a' * 32, 'config': {'users': []}, 'target_user': 'ubuntu'}
    with patch.object(remote, 'main', return_value={'ok': True}):
        remote.dispatch({**request, 'action': 'bootstrap'}, locks, state)
        request['run_token'] = 'b' * 32
        remote.dispatch({**request, 'action': 'bootstrap'}, locks, state)
        with pytest.raises(remote.ProvisionError, match='stale'):
            remote.dispatch({**request, 'run_token': 'a' * 32, 'action': 'user-packages'}, locks, state)
        with (locks / 'walter-user-ubuntu-asdf.lock').open('a') as resource:
            fcntl.flock(resource, fcntl.LOCK_EX | fcntl.LOCK_NB)
            for action in ('resolve-asdf', 'user-runtime'):
                with pytest.raises(BlockingIOError):
                    remote.dispatch({**request, 'action': action}, locks, state)
            remote.dispatch({**request, 'action': 'user-github'}, locks, state)
        remote.dispatch({**request, 'action': 'converge-nix'}, locks, state)
        with pytest.raises(remote.ProvisionError, match='stale'):
            remote.dispatch({**request, 'action': 'user-packages'}, locks, state)


@pytest.mark.parametrize('action,expected', [('user-packages', 'packages'), ('user-runtime', 'runtime'), ('user-github', 'github')])
def test_user_branch_does_not_run_finish_writes(tmp_path, action, expected):
    from types import SimpleNamespace
    calls = []
    with patch.object(remote.pwd, 'getpwnam', return_value=SimpleNamespace(pw_dir=str(tmp_path))), patch.dict(remote.os.environ, {}, clear=True), patch.object(remote.os, 'chdir'), patch.object(remote, 'stage'), patch.object(remote, 'nix_packages', side_effect=lambda *a, **k: calls.append('packages')), patch.object(remote, 'asdf_tools', side_effect=lambda *a: calls.append('runtime')), patch.object(remote, 'github', side_effect=lambda *a: calls.append('github')), patch.object(remote, 'shell_paths', side_effect=AssertionError('concurrent shell writes')):
        remote.user_tasks({'action': action, 'config': {}}, 'ubuntu', {})
    assert calls == [expected]


def test_remote_dispatch_is_bounded_across_many_users():
    import time
    barrier = threading.Barrier(6, timeout=5)
    mutex = threading.Lock()
    active = maximum = 0
    config = {'profile': 'test', 'users': ['seat' + str(i) for i in range(11)]}
    deployment = {'_root': '.', '_desired_hash': 'hash'}
    state, host, cloud, reporter = Mock(), Mock(), Mock(), Mock()
    state.get_resource.return_value = None
    state.db.execute.return_value.fetchall.return_value = []
    host.pub.read_text.return_value = 'public'
    cloud.converge.return_value = {'instance_id': '123'}
    reporter.stage.side_effect = lambda name: nullcontext()

    def fake_remote(_host, connection, conf, action, **private):
        nonlocal active, maximum
        with mutex:
            active += 1
            maximum = max(maximum, active)
        try:
            if action == 'resolve-nix':
                return {'nix_source': 'pinned'}
            if action == 'resolve-asdf':
                return {'resolved_versions': {}}
            if action == 'user-packages':
                barrier.wait()
            if action == 'user-finish':
                return {'users': {private['target_user']: {}}}
            time.sleep(0.005)
            return {'ok': True}
        finally:
            with mutex:
                active -= 1

    with patch.object(controller, 'remote', side_effect=fake_remote), patch.object(controller, 'initialize'), patch.object(controller, 'private_payload', return_value={'credentials': {}}), patch.object(controller, 'aliases'):
        result = asyncio.run(controller.converge(config, deployment, state, cloud, host, 'operation', reporter))
    assert maximum == 6
    assert len(result['provisioning']['users']) == 12
    assert all(t['finished_seconds'] >= t['started_seconds'] for t in result['provisioning']['task_timings'].values())


@pytest.mark.parametrize('worker_fails', [False, True])
def test_cancelled_remote_task_drains_actual_ssh_thread(worker_fails):
    started, release, finished = threading.Event(), threading.Event(), threading.Event()

    def blocking_remote(*args, **kwargs):
        started.set()
        assert release.wait(5)
        finished.set()
        if worker_fails:
            raise DeployError('synthetic remote failure')
        return {'ok': True}

    async def exercise():
        task = asyncio.create_task(controller.remote_task(None, None, {}, 'install-go'))
        assert await asyncio.to_thread(started.wait, 5)
        task.cancel()
        await asyncio.sleep(0.01)
        assert not task.done()
        # Cancellation during the first drain must not abandon the SSH thread.
        task.cancel()
        await asyncio.sleep(0.01)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert finished.is_set()

    with patch.object(controller, 'remote', side_effect=blocking_remote):
        asyncio.run(exercise())
