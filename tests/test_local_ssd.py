"""Scratch recovery must never overwrite data or follow seat-controlled links."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from walter import local_ssd as MODULE


class LocalSSDTest(unittest.TestCase):
    def exercise_prepare(self, signatures, mounted_elsewhere=False, extra_disk=False):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            device = root / 'google-local-nvme-ssd-0'
            device.touch()
            if extra_disk:
                (root / 'google-local-nvme-ssd-1').touch()
            calls = []

            def execute(*args):
                calls.append(args)
                if args[0] == 'lsblk':
                    return json.dumps({'blockdevices': [{'type': 'disk', 'mountpoints': ['/other'] if mounted_elsewhere else [None]}]})
                if args[0] == 'wipefs':
                    return json.dumps({'signatures': signatures})
                if args[0] == 'blkid':
                    return MODULE.LABEL if args[2] == 'LABEL' else 'ext4'
                return ''

            with patch.object(MODULE.stat, 'S_ISBLK', return_value=True):
                MODULE.prepare(device, root / 'scratch', execute)
            return calls

    def test_blank_replacement_is_formatted_and_mounted(self):
        calls = self.exercise_prepare([])
        self.assertEqual(1, sum(c[0] == 'mkfs.ext4' for c in calls))
        self.assertTrue(any(c[:3] == ('mount', '-o', 'noatime,nodev,nosuid') for c in calls))

    def test_reboot_reuses_existing_label_without_formatting(self):
        calls = self.exercise_prepare([{'type': 'ext4', 'label': MODULE.LABEL}])
        self.assertFalse(any(c[0] == 'mkfs.ext4' for c in calls))
        self.assertTrue(any(c[0] == 'mount' for c in calls))

    def test_conflicting_mount_and_extra_device_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'mounted elsewhere'):
            self.exercise_prepare([], mounted_elsewhere=True)
        with self.assertRaisesRegex(RuntimeError, 'exactly 1'):
            self.exercise_prepare([], extra_disk=True)

    def test_blank_disk_can_be_initialized(self):
        self.assertEqual('format', MODULE.filesystem_action([]))

    def test_owned_filesystem_survives_reruns(self):
        self.assertEqual('reuse', MODULE.filesystem_action([{'type': 'ext4', 'label': MODULE.LABEL}]))

    def test_unknown_filesystems_and_partition_tables_are_never_formatted(self):
        for signatures in ([{'type': 'ext4', 'label': 'ubuntu-root'}],
                           [{'type': 'gpt'}], [{'type': 'xfs', 'label': MODULE.LABEL}],
                           [{'type': 'ext4', 'label': MODULE.LABEL}, {'type': 'gpt'}]):
            with self.subTest(signatures=signatures), self.assertRaises(RuntimeError):
                MODULE.filesystem_action(signatures)

    def test_seat_symlink_cannot_redirect_privileged_operations(self):
        with tempfile.TemporaryDirectory() as temp:
            parent = Path(temp)
            (parent / 'elsewhere').mkdir()
            (parent / 'npm').symlink_to(parent / 'elsewhere')
            fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                with patch.object(MODULE.os, 'fchown') as chown, self.assertRaises(OSError):
                    MODULE.directory(fd, 'npm')
                chown.assert_not_called()
            finally:
                os.close(fd)

    def test_directory_is_reusable_and_private(self):
        with tempfile.TemporaryDirectory() as temp:
            fd = os.open(temp, os.O_RDONLY | os.O_DIRECTORY)
            try:
                for _ in range(2):
                    with patch.object(MODULE.os, 'fchown'):
                        child = MODULE.directory(fd, 'alice', mode=0o700)
                    os.close(child)
                self.assertEqual(0o700, (Path(temp) / 'alice').stat().st_mode & 0o777)
                for name in ('../alice', 'alice/tmp', ''):
                    with self.assertRaises(ValueError):
                        MODULE.directory(fd, name)
            finally:
                os.close(fd)


class RAIDTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.disks = [self.root / ('google-local-nvme-ssd-' + str(i)) for i in range(2)]
        for disk in self.disks:
            disk.touch()
        self.array = self.root / 'md-walter-scratch'
        self.mount = self.root / 'scratch'
        self.state = self.root / 'state' / 'raid.json'
        self.calls = []
        self.uuid = '12345678:12345678:12345678:12345678'
        self.signatures = {d: [] for d in self.disks}
        self.array_signatures = []
        self.member_uuids = {d: self.uuid for d in self.disks}
        self.active = None
        self.member_override = None
        self.level = 'raid0'

    def existing(self, active=False):
        MODULE.write_state(self.state, {'uuid': self.uuid, 'count': 2})
        self.signatures = {d: [{'type': 'linux_raid_member'}] for d in self.disks}
        self.array_signatures = [{'type': 'ext4', 'label': MODULE.LABEL}]
        if active:
            self.active = self.root / 'md127'
            self.active.touch()

    def execute(self, *args):
        self.calls.append(args)
        if args[0] == 'lsblk':
            disk = Path(args[-1])
            entry = {'path': str(disk), 'type': 'disk', 'mountpoints': [None]}
            if self.active:
                entry['children'] = [{'path': str(self.active), 'type': 'raid0', 'mountpoints': [None]}]
            return json.dumps({'blockdevices': [entry]})
        if args[0] == 'wipefs':
            return json.dumps({'signatures': self.signatures.get(Path(args[-1]), self.array_signatures)})
        if args[0] == 'mdadm':
            if args[1] in ('--create', '--assemble'):
                self.array.touch()
                return ''
            uuid = self.member_uuids[Path(args[-1])] if args[1] == '--examine' else self.uuid
            fields = [f'MD_LEVEL={self.level}', 'MD_DEVICES=2', f'MD_UUID={uuid}']
            if args[1] == '--detail':
                for i, disk in enumerate(self.member_override or self.disks):
                    fields.extend([f'MD_DEVICE_disk{i}_DEV={disk}', f'MD_DEVICE_disk{i}_ROLE={i}'])
            return '\n'.join(fields)
        if args[0] == 'blkid':
            return MODULE.LABEL if args[2] == 'LABEL' else 'ext4'
        return ''

    def prepare(self):
        with patch.object(MODULE.stat, 'S_ISBLK', return_value=True):
            MODULE.prepare(self.disks[0], self.mount, self.execute, count=2,
                           array=self.array, state=self.state)

    def assert_no_destructive_commands(self):
        self.assertFalse(any(c[0] == 'mkfs.ext4' or c[:2] == ('mdadm', '--create') for c in self.calls))

    def test_blank_members_create_one_array_and_one_filesystem(self):
        self.prepare()
        creates = [c for c in self.calls if c[:2] == ('mdadm', '--create')]
        self.assertEqual(1, len(creates))
        self.assertIn('--level=0', creates[0])
        self.assertIn('--raid-devices=2', creates[0])
        self.assertEqual(tuple(map(str, self.disks)), creates[0][-2:])
        formats = [c for c in self.calls if c[0] == 'mkfs.ext4']
        self.assertEqual([('mkfs.ext4', '-L', MODULE.LABEL, str(self.array))], formats)
        self.assertEqual({'uuid': self.uuid, 'count': 2}, MODULE.read_state(self.state))

    def test_reboot_assembles_by_owned_uuid_without_formatting(self):
        self.existing()
        self.prepare()
        self.assert_no_destructive_commands()
        self.assertIn(('mdadm', '--assemble', str(self.array), '--uuid=' + self.uuid, *map(str, self.disks)), self.calls)

    def test_udev_autoassembled_array_is_reused_under_actual_device_name(self):
        self.existing(active=True)
        self.prepare()
        self.assert_no_destructive_commands()
        self.assertFalse(any(c[:2] == ('mdadm', '--assemble') for c in self.calls))
        self.assertIn(('mount', '-o', 'noatime,nodev,nosuid', str(self.active), str(self.mount)), self.calls)

    def test_all_blank_replacement_members_recreate_scratch(self):
        MODULE.write_state(self.state, {'uuid': 'old-array', 'count': 2})
        self.prepare()
        self.assertEqual(self.uuid, MODULE.read_state(self.state)['uuid'])

    def test_already_mounted_owned_array_is_not_remounted_or_formatted(self):
        self.existing(active=True)
        self.mount.mkdir()
        actual_stat = os.stat

        def mounted_stat(path, *args, **kwargs):
            info = actual_stat(path, *args, **kwargs)
            if isinstance(path, (str, Path)) and Path(path) == self.mount:
                values = list(info)
                values[2] = 0  # Match the fake block device's st_rdev.
                return os.stat_result(values)
            return info

        with patch.object(Path, 'is_mount', lambda path: path == self.mount), patch.object(MODULE.os, 'stat', mounted_stat):
            self.prepare()
        self.assert_no_destructive_commands()
        self.assertFalse(any(c[0] == 'mount' for c in self.calls))

    def test_partial_loss_is_refused_without_wiping_survivors(self):
        self.existing()
        self.signatures[self.disks[1]] = []
        with self.assertRaisesRegex(RuntimeError, 'partially lost'):
            self.prepare()
        self.assert_no_destructive_commands()
        self.assertFalse(any(c[:2] == ('mdadm', '--assemble') for c in self.calls))

    def test_foreign_uuid_is_refused(self):
        self.existing()
        self.member_uuids[self.disks[1]] = 'foreign-array'
        with self.assertRaisesRegex(RuntimeError, 'identity'):
            self.prepare()
        self.assert_no_destructive_commands()

    def test_missing_ownership_does_not_adopt_an_array(self):
        self.existing()
        self.state.unlink()
        with self.assertRaisesRegex(RuntimeError, 'unknown'):
            self.prepare()
        self.assert_no_destructive_commands()

    def test_foreign_active_member_is_refused_before_filesystem_operations(self):
        self.existing(active=True)
        self.member_override = [self.disks[0], self.root / 'persistent-root']
        with self.assertRaisesRegex(RuntimeError, 'active RAID members'):
            self.prepare()
        self.assert_no_destructive_commands()
        self.assertFalse(any(c[0] == 'mount' for c in self.calls))

    def test_wrong_raid_level_is_refused(self):
        self.existing()
        self.level = 'raid1'
        with self.assertRaisesRegex(RuntimeError, 'level'):
            self.prepare()
        self.assert_no_destructive_commands()

    def test_existing_files_at_mountpoint_are_not_hidden(self):
        self.mount.mkdir()
        (self.mount / 'important.txt').write_text('preserve')
        with self.assertRaisesRegex(RuntimeError, 'not empty'):
            self.prepare()
        self.assert_no_destructive_commands()

    def test_unknown_array_filesystem_is_not_formatted(self):
        self.existing()
        self.array_signatures = [{'type': 'ext4', 'label': 'other-data'}]
        with self.assertRaisesRegex(RuntimeError, 'unrecognized filesystem'):
            self.prepare()
        self.assert_no_destructive_commands()

    def test_configured_disk_count_does_not_silently_shrink(self):
        self.disks[1].unlink()
        with self.assertRaisesRegex(RuntimeError, 'exactly 2'):
            self.prepare()
        self.assert_no_destructive_commands()
