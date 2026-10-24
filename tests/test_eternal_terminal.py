import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from walter import eternal_terminal as et

SOURCE = 'github:NixOS/nixpkgs/' + 'a' * 40


OCI_RULES = '''*filter
:INPUT ACCEPT [0:0]
-A INPUT -p tcp -m state --state NEW -m tcp --dport 22 -j ACCEPT
-A INPUT -j REJECT --reject-with icmp-host-prohibited
COMMIT
'''


class EternalTerminalTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.rules = Path(tmp.name) / 'rules.v4'
        patcher = patch.object(et, 'RULES', self.rules)
        patcher.start()
        self.addCleanup(patcher.stop)

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

    def test_oci_persisted_reject_gains_et_rule_once(self):
        self.rules.write_text(OCI_RULES)
        commands, missing = [], [1]
        def run(argv, check=True):
            commands.append(argv)
            return SimpleNamespace(returncode=missing.pop() if missing else 0)
        def write(path, content, mode):
            Path(path).write_text(content)
            return True
        for _ in range(2):
            et.open_host_firewall(run, write)
        lines = self.rules.read_text().splitlines()
        accept = lines.index('-A ' + ' '.join(et.RULE))
        self.assertLess(accept, next(i for i, l in enumerate(lines) if l.startswith('-A INPUT -j REJECT')))
        self.assertEqual(1, sum('walter-et' in l for l in lines))
        self.assertEqual(1, sum(c[1] == '-I' for c in commands))

    def test_hosts_without_persisted_reject_are_unchanged(self):
        commands = []
        et.open_host_firewall(commands.append, lambda *args: True)
        self.rules.write_text('*filter\nCOMMIT\n')
        et.open_host_firewall(commands.append, lambda *args: True)
        self.assertEqual([], commands)
