#!/usr/bin/python3
"""Mount disposable Google NVMe Local SSDs, using RAID 0 for multiple disks."""
import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import tempfile

DEVICE = Path('/dev/disk/by-id/google-local-nvme-ssd-0')
MOUNT = Path('/scratch')
LABEL = 'walter-scratch'
ARRAY = Path('/dev/md/walter-scratch')
STATE = Path('/var/lib/walter/local-ssd-raid.json')
CACHES = ('npm', 'uv', 'ccache', 'tmp', 'build')


def run(*args, allowed=(0,)):
    result = subprocess.run(args, text=True, capture_output=True)
    if result.returncode not in allowed:
        raise RuntimeError(f'{args[0]} failed: {result.stderr.strip()}')
    return result.stdout


def filesystem_action(signatures):
    """Only an unsigned disk or our own labelled ext4 volume is acceptable."""
    if not signatures:
        return 'format'
    if all(s.get('type') == 'ext4' and s.get('label') == LABEL for s in signatures):
        return 'reuse'
    raise RuntimeError('Local SSD contains an unrecognized filesystem or partition table; refusing to format')


def directory(parent_fd, name, uid=0, gid=0, mode=0o755):
    """Operate through descriptors so a seat cannot redirect privileged chown."""
    if not re.fullmatch(r'[a-z_][a-z0-9_-]*', name):
        raise ValueError('invalid scratch directory name')
    try:
        os.mkdir(name, mode=mode, dir_fd=parent_fd)
    except FileExistsError:
        pass
    fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
    try:
        os.fchown(fd, uid, gid)
        os.fchmod(fd, mode)
    except BaseException:
        os.close(fd)
        raise
    return fd


def exports(text):
    return dict(line.split('=', 1) for line in text.splitlines() if '=' in line)


def read_state(path):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return None
    with os.fdopen(fd) as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
            raise RuntimeError('unsafe RAID ownership file')
        return json.load(source)


def write_state(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.parent.is_symlink() or path.parent.stat().st_uid != os.geteuid() or path.parent.stat().st_mode & 0o022:
        raise RuntimeError('unsafe RAID ownership directory')
    fd, temp = tempfile.mkstemp(prefix='.raid-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as target:
            json.dump(value, target)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def raid_identity(info, count, uuid=None):
    if (info.get('MD_LEVEL') != 'raid0' or info.get('MD_DEVICES') != str(count)
            or not info.get('MD_UUID') or (uuid is not None and info['MD_UUID'] != uuid)):
        raise RuntimeError('RAID identity, level or member count does not match Walter ownership')
    return info['MD_UUID']


def raid_device(disks, layouts, execute, array, state):
    ownership = read_state(state)
    if ownership is not None and (ownership.get('count') != len(disks) or not ownership.get('uuid')):
        raise RuntimeError('RAID ownership does not match configured disk count')
    signatures = [json.loads(execute('wipefs', '--no-act', '--json', str(d)))['signatures'] for d in disks]
    children = [child for layout in layouts for child in layout.get('children', [])]
    if any(c.get('type') != 'raid0' or c.get('children') for c in children):
        raise RuntimeError('Local SSD has unexpected partitions or holders')
    active = {Path(c['path']).resolve() for c in children}
    if len(active) > 1:
        raise RuntimeError('Local SSDs belong to different active arrays')
    if all(not sig for sig in signatures):
        if children or array.exists():
            raise RuntimeError('refusing to create RAID over an active or occupied array')
        # Fresh disks, or all members lost together. Never wipe partial survivors.
        execute('mdadm', '--create', str(array), '--run', '--metadata=1.2',
                '--homehost=any', '--name=walter-scratch', '--level=0',
                '--raid-devices=' + str(len(disks)), *map(str, disks))
        execute('udevadm', 'settle', '--timeout=30')
        info = exports(execute('mdadm', '--detail', '--export', str(array)))
        uuid = raid_identity(info, len(disks))
        write_state(state, {'uuid': uuid, 'count': len(disks)})
    else:
        if not ownership or any(not sig or any(s.get('type') != 'linux_raid_member' for s in sig) for sig in signatures):
            raise RuntimeError('unknown or partially lost RAID members; refusing to overwrite')
        uuid = ownership['uuid']
        for disk in disks:
            raid_identity(exports(execute('mdadm', '--examine', '--export', str(disk))), len(disks), uuid)
        if active:
            # udev may already have assembled our UUID as /dev/md127.
            array = next(iter(active))
        else:
            if array.exists():
                raise RuntimeError('RAID target is occupied by another device')
            execute('mdadm', '--assemble', str(array), '--uuid=' + uuid, *map(str, disks))
            execute('udevadm', 'settle', '--timeout=30')
    info = exports(execute('mdadm', '--detail', '--export', str(array)))
    raid_identity(info, len(disks), uuid)
    members = {Path(v).resolve() for k, v in info.items() if k.startswith('MD_DEVICE_') and k.endswith('_DEV')}
    if members != set(disks):
        raise RuntimeError('active RAID members do not match the configured Local SSDs')
    return array


def prepare(device=DEVICE, mount=MOUNT, execute=run, count=1, array=ARRAY, state=STATE):
    if type(count) is not int or count not in (1, 2, 4, 6, 10, 14, 16):
        raise ValueError('invalid configured Local SSD count')
    execute('udevadm', 'settle', '--timeout=30')
    expected = [device.parent / ('google-local-nvme-ssd-' + str(i)) for i in range(count)]
    if set(device.parent.glob('google-local-nvme-ssd-*')) != set(expected):
        raise RuntimeError('expected exactly ' + str(count) + ' Google NVMe Local SSDs')
    if not all(stat.S_ISBLK(d.stat().st_mode) for d in expected):
        raise RuntimeError('Local SSD aliases must point to block devices')
    disks = [d.resolve(strict=True) for d in expected]
    if len(set(disks)) != count:
        raise RuntimeError('Local SSD aliases must identify distinct devices')
    if mount.is_symlink():
        raise RuntimeError('scratch mountpoint must not be a symlink')
    if not mount.is_mount() and mount.exists() and any(mount.iterdir()):
        raise RuntimeError('scratch mountpoint is not empty; refusing to hide existing files')
    layouts = []
    for disk in disks:
        layout = json.loads(execute('lsblk', '--json', '--paths', '--output', 'PATH,TYPE,MOUNTPOINTS', str(disk)))['blockdevices']
        if len(layout) != 1 or layout[0]['type'] != 'disk':
            raise RuntimeError('refusing a non-disk scratch device')
        layouts.append(layout[0])
        children = layout[0].get('children', [])
        if count == 1 and children:
            raise RuntimeError('refusing a partitioned scratch device')
        for entry in [layout[0], *children]:
            points = [p for p in entry.get('mountpoints', []) if p]
            if any(p != str(mount) for p in points) or (count > 1 and entry is layout[0] and points):
                raise RuntimeError('Local SSD is mounted elsewhere; refusing to modify it')
    # Refuse an occupied mount before creating/assembling/formatting anything.
    mounted_sources = [Path(c['path']) for layout in layouts for c in layout.get('children', [])] if count > 1 else disks
    if mount.is_mount() and not any(os.stat(mount).st_dev == os.stat(d).st_rdev for d in mounted_sources):
        raise RuntimeError('scratch mountpoint is occupied by another filesystem')
    resolved = disks[0] if count == 1 else raid_device(disks, layouts, execute, array, state)
    signatures = json.loads(execute('wipefs', '--no-act', '--json', str(resolved)))['signatures']
    action = filesystem_action(signatures)
    if mount.is_mount():
        if action != 'reuse' or os.stat(mount).st_dev != os.stat(resolved).st_rdev:
            raise RuntimeError('scratch mountpoint is occupied by another filesystem')
    else:
        if action == 'format':
            execute('mkfs.ext4', '-L', LABEL, str(resolved))
        mount.mkdir(mode=0o755, exist_ok=True)
        execute('mount', '-o', 'noatime,nodev,nosuid', str(resolved), str(mount))
    actual_label = execute('blkid', '-s', 'LABEL', '-o', 'value', str(resolved)).strip()
    actual_type = execute('blkid', '-s', 'TYPE', '-o', 'value', str(resolved)).strip()
    if actual_label != LABEL or actual_type != 'ext4':
        raise RuntimeError('scratch filesystem does not belong to Walter')


def initialize_users(users, mount=MOUNT):
    if not isinstance(users, list) or not users or any(
            not isinstance(u, str) or not re.fullmatch(r'[a-z_][a-z0-9_-]*', u) for u in users):
        raise ValueError('scratch users must be explicit login names')
    accounts = [pwd.getpwnam(u) for u in dict.fromkeys(users)]
    if any(a.pw_uid == 0 for a in accounts):
        raise ValueError('root cannot be a scratch seat')
    root_fd = os.open(mount, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fchown(root_fd, 0, 0)
        os.fchmod(root_fd, 0o755)
        for account in accounts:
            user_fd = directory(root_fd, account.pw_name, account.pw_uid, account.pw_gid, 0o700)
            try:
                for name in CACHES:
                    fd = directory(user_fd, name, account.pw_uid, account.pw_gid, 0o700)
                    os.close(fd)
            finally:
                os.close(user_fd)
    finally:
        os.close(root_fd)


def main():
    with open('/etc/walter-scratch.json') as source:
        config = json.load(source)
    users = config['users']
    prepare(count=config['disk_count'])
    initialize_users(users)


if __name__ == '__main__':
    main()
