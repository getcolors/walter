import hashlib
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from walter import go_install as go


@pytest.mark.parametrize('machine,expected', [('aarch64', 'arm64'), ('arm64', 'arm64'),
                                             ('amd64', 'amd64'), ('x86_64', 'amd64')])
def test_architecture(machine, expected):
    assert go.architecture(machine) == expected


def test_unsupported_architecture():
    with pytest.raises(ValueError, match='architecture'):
        go.architecture('riscv64')


@pytest.fixture
def setup(tmp_path, monkeypatch):
    monkeypatch.setattr(go, 'PREFIX', tmp_path)
    monkeypatch.setattr(go.os, 'geteuid', lambda: 0)
    monkeypatch.setattr(go.platform, 'machine', lambda: 'aarch64')
    monkeypatch.setattr(go, 'CHECKSUMS', {'1.27.2': {'arm64': hashlib.sha256(b'archive').hexdigest()}})
    download = Mock(side_effect=lambda url, path: path.write_bytes(b'archive'))
    monkeypatch.setattr(go, 'download', download)
    def run(args, **kwargs):
        if args[0] == 'tar':
            tree = Path(args[3]) / 'go/bin'
            tree.mkdir(parents=True)
            (tree / 'go').write_text('compiler')
            (tree / 'gofmt').write_text('formatter')
        return SimpleNamespace(returncode=0, stdout='go version go1.27.2 linux/arm64\n')
    return tmp_path, Mock(side_effect=run), Mock(), download


def test_fresh_tree_shared_paths_and_idempotence(setup):
    prefix, run, write, download = setup
    (prefix / 'go').mkdir()
    (prefix / 'go/obsolete').write_text('old tree')
    assert go.install(run, write, '1.27.2')
    assert not (prefix / 'go/obsolete').exists()
    assert (prefix / 'bin/go').resolve() == prefix / 'go/bin/go'
    assert (prefix / 'bin/gofmt').resolve() == prefix / 'go/bin/gofmt'
    run.assert_any_call(['chown', '-R', 'root:root', str(prefix / 'go')])
    run.assert_any_call(['chmod', '-R', 'a+rX,go-w', str(prefix / 'go')])
    assert {call.args[0] for call in write.call_args_list} == {
        '/etc/profile.d/walter-go.sh', '/etc/fish/conf.d/walter-go.fish'}
    assert not go.install(run, write, '1.27.2')
    assert download.call_count == 1
    run.assert_any_call(['/usr/bin/env', 'GOTOOLCHAIN=local', str(prefix / 'go/bin/go'), 'version'], check=False)


def test_checksum_failure_preserves_previous_tree(setup, monkeypatch):
    prefix, run, write, download = setup
    (prefix / 'go').mkdir()
    old = prefix / 'go/important'
    old.write_text('previous installation')
    monkeypatch.setattr(go, 'CHECKSUMS', {'1.27.2': {'arm64': 'wrong'}})
    with pytest.raises(ValueError, match='checksum'):
        go.install(run, write, '1.27.2')
    assert old.read_text() == 'previous installation'
    run.assert_not_called()
    write.assert_not_called()


def test_staged_wrong_architecture_preserves_previous_tree(setup):
    prefix, run, write, download = setup
    (prefix / 'go').mkdir()
    old = prefix / 'go/important'
    old.touch()
    actual = run.side_effect
    def wrong(args, **kwargs):
        result = actual(args, **kwargs)
        result.stdout = 'go version go1.27.2 linux/amd64'
        return result
    run.side_effect = wrong
    with pytest.raises(ValueError, match='Staged'):
        go.install(run, write, '1.27.2')
    assert old.exists()
    write.assert_not_called()


def test_requires_root_before_mutation(setup, monkeypatch):
    prefix, run, write, download = setup
    monkeypatch.setattr(go.os, 'geteuid', lambda: 1000)
    with pytest.raises(PermissionError):
        go.install(run, write, '1.27.2')
    download.assert_not_called()
    run.assert_not_called()


def test_damaged_executable_is_unhealthy(tmp_path):
    (tmp_path / 'bin').mkdir()
    (tmp_path / 'bin/go').touch()
    (tmp_path / 'bin/gofmt').touch()
    assert not go.healthy(Mock(side_effect=OSError('exec format')), tmp_path,
                          'go version go1.27.2 linux/arm64')


def test_failed_promotion_recovers_with_fresh_tree(setup, monkeypatch):
    prefix, run, write, download = setup
    tree = prefix / 'go'
    tree.mkdir()
    (tree / 'obsolete').write_text('old installation')
    (prefix / 'bin').mkdir()
    (prefix / 'bin/go').symlink_to(tree / 'bin/go')
    original_rename = Path.rename

    def fail_promotion(source, destination):
        if destination == tree:
            # The failure window is after removal but before promoting the
            # verified replacement; the next run must tolerate absent Go.
            assert not tree.exists()
            assert (source / 'bin/go').is_file()
            raise OSError('simulated interrupted promotion')
        return original_rename(source, destination)

    with monkeypatch.context() as patch:
        patch.setattr(Path, 'rename', fail_promotion)
        with pytest.raises(OSError, match='interrupted promotion'):
            go.install(run, write, '1.27.2')
    assert not tree.exists()
    assert (prefix / 'bin/go').is_symlink()
    write.assert_not_called()

    assert go.install(run, write, '1.27.2')
    assert (tree / 'bin/go').read_text() == 'compiler'
    assert (tree / 'bin/gofmt').read_text() == 'formatter'
    assert not (tree / 'obsolete').exists()
    assert (prefix / 'bin/go').resolve(strict=True) == tree / 'bin/go'
    assert download.call_count == 2
    assert not go.install(run, write, '1.27.2')
    assert download.call_count == 2
