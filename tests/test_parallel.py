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
    host._argv.side_effect = lambda *a: (pytest.fail('worker trust access') if threading.get_ident() != main_thread else ['ssh'])
    cloud.converge.return_value = {'instance_id': '123'}
    reporter.stage.side_effect = lambda name: nullcontext()

    def fake_remote(_host, connection, conf, action, **private):
        actions.append(action)
        if action == 'prepare':
            return {'resolved_versions': {'nodejs': '24.0.0'}, 'nix_source': 'pinned'}
        if action == 'provision-user':
            assert private['nix_source'] == 'pinned'
            assert private['resolved_versions'] == {'nodejs': '24.0.0'}
            barrier.wait()
            user = private['target_user']
            completed.add(user)
            if user == failed_user:
                raise DeployError('synthetic failure')
            return {'users': {user: {'resolved_versions': private['resolved_versions']}}}
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
        remote.main({'action': 'provision-user', 'config': {'users': ['jack']}, 'target_user': 'root'})


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
