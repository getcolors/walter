"""Storage placement and ordering checks on synthetic directories only."""
import os
from pathlib import Path
from unittest.mock import patch

import pytest
from walter import local_ssd, remote


def test_layout_modes_and_mount_targets(tmp_path):
    scratch = tmp_path / 'scratch'
    scratch.mkdir()
    layout = {key: (str(tmp_path / key), mode) for key, (_, mode) in local_ssd.LAYOUT.items()}
    bound = {}
    def execute(*args):
        assert args[:2] == ('mount', '--bind')
        bound[args[3]] = args[2]
    with patch.object(local_ssd, 'LAYOUT', layout), \
         patch.object(Path, 'is_mount', lambda p: p == scratch or str(p) in bound), \
         patch.object(os.path, 'samefile', lambda a, b: bound.get(str(b)) == str(a)), \
         patch.object(os, 'fchown'):
        local_ssd.bind_layout(scratch, execute)
        local_ssd.bind_layout(scratch, execute)
    assert len(bound) == 6
    for key, (_, mode) in layout.items():
        assert (scratch / key).stat().st_mode & 0o7777 == mode


def test_layout_refuses_foreign_mount(tmp_path):
    scratch = tmp_path / 'scratch'
    scratch.mkdir()
    target = tmp_path / 'home'
    target.mkdir()
    with patch.object(local_ssd, 'LAYOUT', {'home': (str(target), 0o755)}), \
         patch.object(Path, 'is_mount', return_value=True), patch.object(os, 'fchown'), \
         patch.object(os.path, 'samefile', return_value=False), pytest.raises(RuntimeError, match='occupied'):
        local_ssd.bind_layout(scratch, lambda *args: pytest.fail('must not mount'))


def test_layout_refuses_source_and_destination_symlinks(tmp_path):
    scratch = tmp_path / 'scratch'
    scratch.mkdir()
    target = tmp_path / 'home'
    target.symlink_to(tmp_path / 'elsewhere')
    with patch.object(local_ssd, 'LAYOUT', {'home': (str(target), 0o755)}), \
         patch.object(Path, 'is_mount', return_value=True), patch.object(os, 'fchown'), \
         pytest.raises(RuntimeError, match='symlink'):
        local_ssd.bind_layout(scratch)


def test_storage_boot_order_and_daemon_dependencies():
    files, commands = {}, []
    with patch.object(remote, 'write', lambda p, text, *args: files.update({p: text})), \
         patch.object(remote, 'run', lambda args: commands.append(args)):
        remote.setup_storage({'local_ssd_source': 'synthetic', 'config': {'google-local-ssd-count': 1}})
    unit = files['/etc/systemd/system/walter-local-ssd.service']
    assert 'DefaultDependencies=no' in unit
    assert 'Before=local-fs.target systemd-tmpfiles-setup.service' in unit
    for service in ('docker.service', 'containerd.service', 'ssh.service'):
        assert 'Requires=walter-local-ssd.service' in files[f'/etc/systemd/system/{service}.d/walter-storage.conf']
    assert commands.index(['/usr/local/sbin/walter-local-ssd', '--bootstrap']) < commands.index(['systemctl', 'enable', '--now', 'walter-local-ssd'])
