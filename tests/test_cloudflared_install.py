import hashlib
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from walter import cloudflared_install as cf


@pytest.mark.parametrize('machine,expected', [('aarch64', 'arm64'), ('arm64', 'arm64'),
                                             ('x86_64', 'amd64'), ('amd64', 'amd64')])
def test_architecture(machine, expected):
    assert cf.architecture(machine) == expected


@pytest.fixture
def setup(tmp_path, monkeypatch):
    monkeypatch.setattr(cf, 'PREFIX', tmp_path)
    monkeypatch.setattr(cf.os, 'geteuid', lambda: 0)
    monkeypatch.setattr(cf.platform, 'machine', lambda: 'aarch64')
    monkeypatch.setattr(cf, 'CHECKSUMS', {'arm64': hashlib.sha256(b'binary').hexdigest()})
    download = Mock(side_effect=lambda url, path: path.write_bytes(b'binary'))
    monkeypatch.setattr(cf, 'download', download)
    run = Mock(return_value=SimpleNamespace(
        returncode=0, stdout=f'cloudflared version {cf.VERSION} (built 2026-10-01-1200 UTC)\n'))
    return tmp_path / 'bin/cloudflared', run, download


def test_install_pinned_binary_and_skip_matching_version(setup):
    binary, run, download = setup
    assert cf.install(run)
    assert binary.read_bytes() == b'binary'
    assert binary.stat().st_mode & 0o777 == 0o755
    assert download.call_args.args[0] == (
        'https://github.com/cloudflare/cloudflared/releases/download/'
        + cf.VERSION + '/cloudflared-linux-arm64')
    assert not cf.install(run)
    assert download.call_count == 1
    run.assert_any_call(['chown', 'root:root', str(binary)])


@pytest.mark.parametrize('failure', ['checksum', 'version', 'promotion'])
def test_failure_preserves_previous_binary(setup, monkeypatch, failure):
    binary, run, download = setup
    binary.parent.mkdir()
    binary.write_bytes(b'previous')
    def execute(args, **kwargs):
        version = 'old' if args[0] == str(binary) or failure == 'version' else cf.VERSION
        return SimpleNamespace(returncode=0, stdout=f'cloudflared version {version}')
    run.side_effect = execute
    if failure == 'checksum':
        monkeypatch.setattr(cf, 'CHECKSUMS', {'arm64': 'wrong'})
    if failure == 'promotion':
        monkeypatch.setattr(Path, 'replace', Mock(side_effect=OSError('promotion failed')))
    with pytest.raises((ValueError, OSError)):
        cf.install(run)
    assert binary.read_bytes() == b'previous'
    assert list(binary.parent.iterdir()) == [binary]


@pytest.mark.parametrize('output', ['cloudflared version 2026.10.01',
                                   'cloudflared version 2026.10.0-rc1', 'garbage'])
def test_version_requires_exact_token(tmp_path, output):
    binary = tmp_path / 'cloudflared'
    binary.touch()
    assert not cf.healthy(Mock(return_value=SimpleNamespace(returncode=0, stdout=output)), binary)


def test_damaged_binary_is_unhealthy(tmp_path):
    binary = tmp_path / 'cloudflared'
    binary.touch()
    assert not cf.healthy(Mock(side_effect=OSError('exec format')), binary)


@pytest.mark.parametrize('reason', ['privileges', 'architecture'])
def test_rejects_before_mutation(setup, monkeypatch, reason):
    binary, run, download = setup
    if reason == 'privileges':
        monkeypatch.setattr(cf.os, 'geteuid', lambda: 1000)
    else:
        monkeypatch.setattr(cf.platform, 'machine', lambda: 'armv7l')
    with pytest.raises((PermissionError, ValueError)):
        cf.install(run)
    download.assert_not_called()
    run.assert_not_called()
    assert not binary.parent.exists()


def test_replaces_symlink_without_touching_target(setup):
    binary, run, download = setup
    binary.parent.mkdir()
    target = binary.parent / 'other'
    target.write_bytes(b'untouched')
    binary.symlink_to(target)
    assert cf.install(run)
    assert not binary.is_symlink()
    assert target.read_bytes() == b'untouched'
