import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest
from walter import memory

PAGE = os.sysconf('SC_PAGE_SIZE')
FSTAB = 'UUID=root / ext4 defaults 0 1\n'


class Host:
    """A synthetic root filesystem, swap table and command runner."""

    def __init__(self, tmp_path, monkeypatch, fallocate=0, rootfs='ext4', free=100, total=100):
        self.tmp = tmp_path
        self.commands, self.writes = [], []
        self.fallocate, self.free, self.total = fallocate, free, total
        for name, value in (('SWAPFILE', 'swapfile'), ('FSTAB', 'fstab'), ('FSTAB_BACKUP', 'fstab.walter-backup'),
                            ('STATE', 'state/swap.json'), ('PROC_SWAPS', 'swaps'), ('PROC_MOUNTS', 'mounts')):
            monkeypatch.setattr(memory, name, tmp_path / value)
        monkeypatch.setattr(memory, 'SYSCTL', str(tmp_path / 'sysctl.conf'))
        monkeypatch.setattr(memory, 'EARLYOOM', str(tmp_path / 'earlyoom'))
        monkeypatch.setattr(memory, 'GIB', 4 * PAGE)
        monkeypatch.setattr(memory, 'OWNER_UID', os.getuid())
        memory.FSTAB.write_text(FSTAB)
        memory.PROC_MOUNTS.write_text('/dev/sda1 / ' + rootfs + ' rw 0 0\n')
        self.swaps = []
        self.sync()

    def sync(self):
        memory.PROC_SWAPS.write_text('Filename Type Size Used Priority\n' +
                                     ''.join(f'{p} file 1 0 -2\n' for p in self.swaps))

    def write(self, path, text, mode=0o644):
        self.writes.append(str(path))
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists() and path.read_text() == text:
            return False
        path.write_text(text)
        os.chmod(path, mode)
        return True

    def run(self, args, check=True):
        self.commands.append(args)
        swapfile = str(memory.SWAPFILE)
        if args[0] == 'fallocate':
            if self.fallocate:
                return SimpleNamespace(returncode=self.fallocate)
            os.truncate(swapfile, int(args[2]))
        elif args[0] == 'dd':
            size = int(args[4].split('=')[1]) * (1 << 20)
            os.truncate(swapfile, size)
        elif args[0] == 'mkswap':
            with open(swapfile, 'r+b') as stream:
                stream.seek(PAGE - 10)
                stream.write(b'SWAPSPACE2')
        elif args[0] == 'swapon':
            self.swaps.append(args[1])
            self.sync()
        elif args[0] == 'swapoff':
            self.swaps.remove(args[1])
            self.sync()
        return SimpleNamespace(returncode=0)

    def statvfs(self, path):
        return SimpleNamespace(f_frsize=memory.GIB, f_bavail=self.free, f_blocks=self.total)

    def install(self, swap_gb=2):
        memory.install(self.run, self.write, swap_gb, self.statvfs)

    def names(self):
        return [c[0] if c[0] != 'systemctl' else 'systemctl ' + c[1] for c in self.commands]

    def preexisting(self, size, signature=True, fstab=True, active=True):
        memory.SWAPFILE.write_bytes(b'')
        os.truncate(memory.SWAPFILE, size)
        if signature:
            self.run(['mkswap', str(memory.SWAPFILE)])
        if fstab:
            memory.FSTAB.write_text(FSTAB + memory.FSTAB_LINE + '\n')
        if active:
            self.swaps.append(str(memory.SWAPFILE))
            self.sync()
        self.commands.clear()


@pytest.fixture
def host(tmp_path, monkeypatch):
    return Host(tmp_path, monkeypatch)


def test_fresh_install_creates_records_and_enables(host):
    host.install()
    assert host.names() == ['fallocate', 'mkswap', 'swapon', 'systemctl daemon-reload', 'findmnt', 'sysctl',
                            'systemctl enable', 'systemctl restart', 'systemctl is-active']
    assert memory.SWAPFILE.stat().st_size == 2 * memory.GIB
    assert memory.SWAPFILE.stat().st_mode & 0o777 == 0o600
    assert memory.FSTAB.read_text() == FSTAB + memory.FSTAB_LINE + '\n'
    assert memory.FSTAB_BACKUP.read_text() == FSTAB
    assert json.loads(memory.STATE.read_text())['bytes'] == 2 * memory.GIB
    assert Path(memory.SYSCTL).read_text() == 'vm.swappiness = 10\nvm.vfs_cache_pressure = 50\n'
    args = Path(memory.EARLYOOM).read_text()
    assert args.startswith('EARLYOOM_ARGS="-r 3600 -m 10 -s 10 --avoid ')
    assert '\\' not in args and 'etserver' in args and 'zellij' in args
    assert 'sshd.*' in args and 'python' not in args


def test_second_run_changes_nothing(host):
    host.install()
    host.commands.clear()
    fstab = memory.FSTAB.read_text()
    host.install()
    # earlyoom restarts every run so an interrupted run cannot leave package defaults.
    assert host.names() == ['sysctl', 'systemctl enable', 'systemctl restart', 'systemctl is-active']
    assert memory.FSTAB.read_text() == fstab


def test_adopts_matching_manual_swap_without_changes(host):
    host.preexisting(2 * memory.GIB)
    host.install()
    assert 'mkswap' not in host.names() and 'swapon' not in host.names()
    assert 'findmnt' not in host.names()
    assert str(memory.FSTAB) not in host.writes
    assert json.loads(memory.STATE.read_text()) == {'path': str(memory.SWAPFILE), 'bytes': 2 * memory.GIB}


@pytest.mark.parametrize('size,signature', [(3 * PAGE, True), (8 * PAGE, False)])
def test_refuses_to_adopt_unrecognized_swapfile(host, size, signature):
    host.preexisting(size, signature=signature, active=False)
    with pytest.raises(RuntimeError, match='adopt'):
        host.install()
    assert not memory.STATE.exists() and host.commands == []
    assert memory.SWAPFILE.stat().st_size == size


def test_refuses_symlink_swapfile(host):
    memory.SWAPFILE.symlink_to(host.tmp / 'elsewhere')
    with pytest.raises(RuntimeError, match='unrecognized'):
        host.install()


def test_refuses_foreign_fstab_entry(host):
    memory.FSTAB.write_text(FSTAB + '/swapfile none swap sw,pri=5 0 0\n')
    with pytest.raises(RuntimeError, match='fstab'):
        host.install()


def test_disabled_leaves_unowned_swap_untouched(host):
    host.preexisting(2 * memory.GIB)
    host.install(0)
    assert memory.SWAPFILE.exists() and memory.swap_active()
    assert memory.FSTAB_LINE in memory.FSTAB.read_text()
    assert not memory.STATE.exists()
    assert 'swapoff' not in host.names()


def test_disabled_removes_owned_swap(host):
    host.install()
    host.commands.clear()
    host.install(0)
    assert host.names()[0] == 'swapoff'
    assert not memory.SWAPFILE.exists() and not memory.STATE.exists()
    assert memory.FSTAB.read_text() == FSTAB
    assert 'systemctl is-active' in host.names()


def test_resize_recreates_owned_swap(host):
    host.install(2)
    host.commands.clear()
    host.install(1)
    assert host.names()[:4] == ['swapoff', 'systemctl daemon-reload', 'findmnt', 'fallocate']
    assert memory.SWAPFILE.stat().st_size == memory.GIB
    assert memory.swap_active()
    assert memory.FSTAB.read_text().count(memory.FSTAB_LINE) == 1


def test_resumes_interrupted_creation(host):
    memory.STATE.parent.mkdir()
    memory.STATE.write_text('{}')
    memory.SWAPFILE.write_bytes(b'')
    os.truncate(memory.SWAPFILE, 2 * memory.GIB)
    host.install()
    assert host.names()[:2] == ['mkswap', 'swapon']


def test_fallocate_failure_falls_back_to_dd(tmp_path, monkeypatch):
    host = Host(tmp_path, monkeypatch, fallocate=1)
    monkeypatch.setattr(memory, 'GIB', 1 << 20)
    host.install(2)
    assert host.names()[:3] == ['fallocate', 'dd', 'mkswap']
    assert memory.SWAPFILE.stat().st_size == 2 << 20


@pytest.mark.parametrize('options,message', [({'free': 11}, 'disk space'), ({'rootfs': 'btrfs'}, 'ext4 or xfs')])
def test_refuses_unsafe_creation(tmp_path, monkeypatch, options, message):
    host = Host(tmp_path, monkeypatch, **options)
    with pytest.raises(RuntimeError, match=message):
        host.install(2)
    assert not memory.SWAPFILE.exists() and not memory.STATE.exists()


def test_refused_adoption_records_nothing(host):
    host.preexisting(2 * memory.GIB, fstab=False)
    memory.FSTAB.write_text(FSTAB + '/swapfile none swap defaults 0 0\n')
    with pytest.raises(RuntimeError, match='fstab'):
        host.install()
    assert not memory.STATE.exists() and memory.swap_active()


def test_adoption_accepts_equivalent_fstab_spacing_without_rewrite(host):
    host.preexisting(2 * memory.GIB, fstab=False)
    text = FSTAB + '/swapfile\tnone  swap sw 0 0\n'
    memory.FSTAB.write_text(text)
    host.install()
    assert memory.FSTAB.read_text() == text and memory.STATE.exists()


def test_disabled_ignores_unowned_symlink(host):
    memory.SWAPFILE.symlink_to(host.tmp / 'elsewhere')
    host.install(0)
    assert memory.SWAPFILE.is_symlink()


def test_resize_without_space_keeps_existing_swap(tmp_path, monkeypatch):
    host = Host(tmp_path, monkeypatch, free=14, total=100)
    host.install(2)
    host.commands.clear()
    with pytest.raises(RuntimeError, match='disk space'):
        host.install(8)
    assert memory.swap_active() and memory.SWAPFILE.stat().st_size == 2 * memory.GIB


def test_removal_without_fstab_entry_does_not_rewrite_fstab(host):
    host.install()
    memory.FSTAB.write_text(FSTAB)
    host.commands.clear()
    host.install(0)
    assert 'findmnt' not in host.names() and not memory.SWAPFILE.exists()


def test_second_run_keeps_swapfile_ctime(host):
    host.install()
    before = memory.SWAPFILE.stat().st_ctime_ns
    host.install()
    assert memory.SWAPFILE.stat().st_ctime_ns == before


def test_wrong_swapfile_mode_is_corrected(host):
    host.install()
    os.chmod(memory.SWAPFILE, 0o644)
    host.install()
    assert memory.SWAPFILE.stat().st_mode & 0o777 == 0o600
