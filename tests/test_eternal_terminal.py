import unittest
from unittest.mock import patch

from walter import eternal_terminal as et

SOURCE = 'github:NixOS/nixpkgs/' + 'a' * 40


class EternalTerminalTest(unittest.TestCase):
    def test_shared_store_build_uses_root_owned_gc_root_and_ssd_dependency(self):
        commands, files = [], {}
        def write(path, content):
            files[path] = content
            return True
        with patch.object(et.Path, 'exists', return_value=False):
            et.install(commands.append, write, SOURCE, local_ssd=True)
        self.assertEqual(commands[0], ['/nix/var/nix/profiles/default/bin/nix', 'build', '--impure', '--out-link', et.PROFILE, SOURCE + '#eternal-terminal'])
        self.assertIn('port = 2022', files['/etc/et.cfg'])
        self.assertIn('telemetry = false', files['/etc/et.cfg'])
        unit = files['/etc/systemd/system/et.service']
        self.assertIn('Requires=walter-local-ssd.service', unit)
        self.assertIn('ExecStart=' + et.PROFILE + '/bin/etserver', unit)
        self.assertNotIn('/home/', unit)
        self.assertEqual(commands[-1], ['systemctl', 'is-active', '--quiet', 'et.service'])

    def test_unchanged_install_does_not_restart(self):
        commands, files = [], {}
        def write(path, content):
            files[path] = content
            return False
        with patch.object(et.Path, 'exists', return_value=True):
            et.install(commands.append, write, SOURCE)
        self.assertFalse(any('restart' in c for c in commands))
        self.assertNotIn('walter-local-ssd', files['/etc/systemd/system/et.service'])

    def test_mutable_source_rejected_before_execution(self):
        commands = []
        with self.assertRaises(ValueError):
            et.install(commands.append, lambda *args: True, 'github:NixOS/nixpkgs/nixpkgs-unstable')
        self.assertEqual(commands, [])

    def test_build_failure_propagates_before_enabling_service(self):
        commands = []
        def run(argv):
            commands.append(argv)
            raise RuntimeError('synthetic failure')
        with self.assertRaises(RuntimeError):
            et.install(run, lambda *args: True, SOURCE)
        self.assertEqual(len(commands), 1)
