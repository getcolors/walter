"""Swap file and earlyoom, so memory pressure kills a process instead of the host.

Without swap the kernel OOM killer acts only after the machine has thrashed into
unresponsiveness. A recorded swap file plus earlyoom keeps SSH sessions usable.
Walter manages only a /swapfile it recorded, or adopts one that already matches.
"""
import json
import os
from pathlib import Path

GIB = 1 << 30
SWAPFILE = Path('/swapfile')
FSTAB = Path('/etc/fstab')
FSTAB_BACKUP = Path('/etc/fstab.walter-backup')
STATE = Path('/var/lib/walter/swap.json')
PROC_SWAPS = Path('/proc/swaps')
PROC_MOUNTS = Path('/proc/self/mounts')
SYSCTL = '/etc/sysctl.d/90-walter-memory.conf'
EARLYOOM = '/etc/default/earlyoom'
FSTAB_LINE = '/swapfile none swap sw 0 0'
SYSCTL_TEXT = 'vm.swappiness = 10\nvm.vfs_cache_pressure = 50\n'
# systemd splits $EARLYOOM_ARGS at whitespace, strips the quotes and drops
# backslashes, so these regular expressions contain neither.
AVOID = '^(sshd.*|systemd|systemd-.*|dockerd|containerd|tailscaled|etserver|zellij|tmux.*|nix-daemon)$'
PREFER = '^(node|bun|java|go|gopls|clangd|cc1plus|rustc)$'
EARLYOOM_TEXT = ('EARLYOOM_ARGS="-r 3600 -m 10 -s 10 '
                 "--avoid '" + AVOID + "' --prefer '" + PREFER + "'\"\n")
FILESYSTEMS = ('ext4', 'xfs')
MIN_FREE = 0.10
OWNER_UID = 0


def swap_active():
    return any(line.split()[0] == str(SWAPFILE)
               for line in PROC_SWAPS.read_text().splitlines()[1:] if line.split())


def has_signature():
    page = os.sysconf('SC_PAGE_SIZE')
    with open(SWAPFILE, 'rb') as stream:
        header = stream.read(page)
    return len(header) == page and header[-10:] == b'SWAPSPACE2'


def matches(size):
    info = SWAPFILE.lstat()
    return (SWAPFILE.is_file() and not SWAPFILE.is_symlink() and info.st_uid == OWNER_UID
            and info.st_size == size and has_signature())


def root_filesystem():
    selected = None
    for line in PROC_MOUNTS.read_text().splitlines():
        fields = line.split()
        if len(fields) > 2 and fields[1] == '/':
            selected = fields[2]
    return selected


def fstab_lines():
    return FSTAB.read_text().splitlines()


def swap_entries(lines):
    return [line for line in lines if line.split()[:1] == FSTAB_LINE.split()[:1]]


def check_fstab():
    """Refuse any /swapfile entry other than Walter's, compared field by field."""
    entries = swap_entries(fstab_lines())
    if any(line.split() != FSTAB_LINE.split() for line in entries):
        raise RuntimeError('refusing an unrecognized /swapfile fstab entry')
    return entries


def set_fstab(run, write, present):
    entries = check_fstab()
    if len(entries) == (1 if present else 0):
        return
    lines = fstab_lines()
    write(str(FSTAB_BACKUP), '\n'.join(lines) + '\n', 0o644)
    kept = [line for line in lines if line not in entries]
    write(str(FSTAB), '\n'.join([*kept, *([FSTAB_LINE] if present else [])]) + '\n', 0o644)
    run(['systemctl', 'daemon-reload'])
    run(['findmnt', '--verify'])


def remove(run, write):
    if swap_active():
        # Fails rather than forcing pages back when memory cannot hold them.
        run(['swapoff', str(SWAPFILE)])
    set_fstab(run, write, False)
    SWAPFILE.unlink(missing_ok=True)
    STATE.unlink(missing_ok=True)


def check_space(size, statvfs, reclaimed=0):
    if root_filesystem() not in FILESYSTEMS:
        raise RuntimeError('swap file requires an ext4 or xfs root filesystem')
    disk = statvfs('/')
    if disk.f_bavail * disk.f_frsize + reclaimed - size < MIN_FREE * disk.f_blocks * disk.f_frsize:
        raise RuntimeError('insufficient free disk space for the swap file')


def record(write, size):
    write(str(STATE), json.dumps({'path': str(SWAPFILE), 'bytes': size}) + '\n', 0o600)


def create(run, write, size, statvfs=os.statvfs):
    check_space(size, statvfs)
    # Persist intent first so an interrupted creation is resumed, not refused.
    record(write, size)
    os.close(os.open(SWAPFILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600))
    if run(['fallocate', '-l', str(size), str(SWAPFILE)], check=False).returncode:
        run(['dd', 'if=/dev/zero', 'of=' + str(SWAPFILE), 'bs=1M', 'count=' + str(size // (1 << 20))])
    run(['mkswap', str(SWAPFILE)])


def swap(run, write, swap_gb, statvfs=os.statvfs):
    size = swap_gb * GIB
    owned = STATE.exists()
    if not owned and not swap_gb:
        return  # Swap Walter did not record is never changed.
    present = SWAPFILE.exists() or SWAPFILE.is_symlink()
    if present and (SWAPFILE.is_symlink() or not SWAPFILE.is_file()):
        raise RuntimeError('refusing an unrecognized /swapfile')
    # Refuse before recording ownership or turning any swap off.
    check_fstab()
    if not owned and present:
        # Adopt only a root-owned swap file of exactly the desired size.
        if not matches(size):
            raise RuntimeError('refusing to adopt an unrecognized /swapfile')
        record(write, size)
    if not swap_gb:
        remove(run, write)
        return
    if present and SWAPFILE.lstat().st_size != size:
        check_space(size, statvfs, reclaimed=SWAPFILE.lstat().st_size)
        remove(run, write)
        present = False
    if not present:
        create(run, write, size, statvfs)
    if SWAPFILE.lstat().st_mode & 0o7777 != 0o600:
        os.chmod(SWAPFILE, 0o600)  # Unconditional chmod would change ctime every run.
    if not swap_active():
        if not has_signature():
            run(['mkswap', str(SWAPFILE)])
        run(['swapon', str(SWAPFILE)])
    set_fstab(run, write, True)


def install(run, write, swap_gb, statvfs=os.statvfs):
    swap(run, write, swap_gb, statvfs)
    write(SYSCTL, SYSCTL_TEXT)
    run(['sysctl', '--load', SYSCTL])
    write(EARLYOOM, EARLYOOM_TEXT)
    run(['systemctl', 'enable', 'earlyoom'])
    # Restart unconditionally: a run interrupted after writing the settings
    # would otherwise leave earlyoom on the package defaults.
    run(['systemctl', 'restart', 'earlyoom'])
    run(['systemctl', 'is-active', '--quiet', 'earlyoom'])
