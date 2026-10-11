import json
import threading
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from pocketdeploy.common import DeployError
from walter import remote
from walter.config import load


def test_explicit_configuration_validation(tmp_path):
    path = tmp_path / 'colors.yml'
    base = {'profile': 'test', 'google-project': 'synthetic', 'google-zone': 'europe-west4-b',
            'github-account': 'example', 'git-email': 'example@example.com'}
    for names in [['basecamp/once', 'pocketbase/pocketbase'], ['example/.emacs.d']]:
        path.write_text(json.dumps({**base, 'clone-repos': names}))
        assert load(path, {})[0]['clone-repos'] == names
    for names in [['../repo'], ['owner/..'], ['owner/.'], ['owner/repo/extra'],
                  ['https://github.com/owner/repo'], ['owner/\nrepo'], 'owner/repo']:
        path.write_text(json.dumps({**base, 'clone-repos': names}))
        with pytest.raises(DeployError):
            load(path, {})
    path.write_text(json.dumps({k: v for k, v in {**base, 'clone-repos': ['owner/repo']}.items()
                                if k != 'github-account'}))
    with pytest.raises(DeployError):
        load(path, {})


def setup_github(monkeypatch, pages=None):
    monkeypatch.setattr(remote, 'github_authenticated', lambda config: True)
    monkeypatch.setattr(remote, 'run', lambda *a, **kw: SimpleNamespace(stdout=json.dumps(pages or [])))
    return {'github-account': 'example', 'git-email': 'example@example.com'}


def test_selection_deduplicates_and_explicit_overrides_filters(tmp_path, monkeypatch):
    config = setup_github(monkeypatch, [[
        {'full_name': 'Org/Active'}, {'full_name': 'Org/Archived', 'archived': True},
        {'full_name': 'Org/Fork', 'fork': True}, {'full_name': 'Org/Skipped', 'archived': True}],
        [{'full_name': 'org/active'}]])
    config.update({'clone-orgs': ['Org'], 'clone-repos': ['org/active', 'Org/Archived', 'Org/Fork']})
    clone = Mock()
    monkeypatch.setattr(remote, 'clone', clone)
    remote.github(config, {}, tmp_path)
    assert sorted((a.args[0], a.args[1].relative_to(tmp_path).as_posix()) for a in clone.call_args_list) == [
        ('https://github.com/Org/Archived.git', 'code/Org/Archived'),
        ('https://github.com/Org/Fork.git', 'code/Org/Fork'),
        ('https://github.com/org/active.git', 'code/org/active')]


@pytest.mark.parametrize('name', ['other/repo', 'Org/..', '../escape', 'Org/a/b'])
def test_untrusted_discovery_identity_is_validated_before_cloning(tmp_path, monkeypatch, name):
    config = setup_github(monkeypatch, [[{'full_name': name}]])
    config['clone-orgs'] = ['Org']
    clone = Mock()
    monkeypatch.setattr(remote, 'clone', clone)
    with pytest.raises(remote.ProvisionError):
        remote.github(config, {}, tmp_path)
    clone.assert_not_called()


def test_clones_run_concurrently_and_propagate_failure(tmp_path, monkeypatch):
    config = setup_github(monkeypatch)
    config['clone-repos'] = ['owner/a', 'owner/b', 'owner/c', 'owner/d']
    barrier = threading.Barrier(2)
    lock = threading.Lock()
    active = peak = 0
    seen = []

    def clone(url, destination):
        nonlocal active, peak
        with lock:
            active += 1
            peak = max(peak, active)
            seen.append(destination.name)
        try:
            barrier.wait(timeout=5)
        finally:
            with lock:
                active -= 1
        if destination.name == 'a':
            raise remote.ProvisionError('command failed')

    monkeypatch.setattr(remote, 'clone', clone)
    with pytest.raises(remote.ProvisionError):
        remote.github(config, {}, tmp_path)
    assert peak == 2
    assert sorted(seen) == ['a', 'b', 'c', 'd']


def test_clone_preserves_checkout_and_cleans_failed_temporary_directory(tmp_path, monkeypatch):
    existing = tmp_path / 'existing'
    (existing / '.git').mkdir(parents=True)
    (existing / 'work').write_text('uncommitted work')
    run = Mock(side_effect=remote.ProvisionError('command failed'))
    monkeypatch.setattr(remote, 'run', run)
    remote.clone('https://github.com/owner/repo.git', existing)
    run.assert_not_called()
    assert (existing / 'work').read_text() == 'uncommitted work'
    with pytest.raises(remote.ProvisionError):
        remote.clone('https://github.com/owner/repo.git', tmp_path / 'new')
    assert sorted(p.name for p in tmp_path.iterdir()) == ['existing']
