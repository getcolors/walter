import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

from walter import remote


class AtuinMigrationTest(unittest.TestCase):
    def test_official_install_verified_before_nix_removal(self):
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / '.nix-profile').mkdir()
            def runner(args, **kwargs):
                calls.append(args)
                if args[0] == 'sh':
                    binary = home / '.atuin/bin/atuin'
                    binary.parent.mkdir(parents=True)
                    binary.write_text('synthetic')
                output = 'atuin 18.23.0' if args[-1] == '--version' else json.dumps({'elements': {
                    'atuin-old': {'attrPath': 'legacyPackages.aarch64-linux.atuin', 'originalUrl': remote.NIXPKGS}}})
                return types.SimpleNamespace(stdout=output)
            with patch.object(remote, 'run', runner):
                remote.install_atuin(home)
            self.assertEqual(calls[0], ['curl', '--proto', '=https', '--tlsv1.2', '-LsSf', 'https://setup.atuin.sh'])
            self.assertEqual(calls[1], ['sh', '-s', '--', '--non-interactive'])
            self.assertEqual(calls[2], [str(home / '.atuin/bin/atuin'), '--version'])
            self.assertEqual(calls[-1], ['nix', 'profile', 'remove', 'atuin-old'])
            calls.clear()
            with patch.object(remote, 'run', runner):
                remote.install_atuin(home)
            self.assertFalse(any(c[0] in ('curl', 'sh') for c in calls))

    def test_invalid_official_version_preserves_nix_package(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            binary = home / '.atuin/bin/atuin'
            binary.parent.mkdir(parents=True)
            binary.write_text('synthetic')
            with patch.object(remote, 'run', return_value=types.SimpleNamespace(stdout='atuin 18.15.2')) as run:
                with self.assertRaises(remote.ProvisionError):
                    remote.install_atuin(home)
            run.assert_called_once_with([str(binary), '--version'])

    def test_rebuild_only_after_successful_sync_once(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            with patch.object(remote, 'run', return_value=types.SimpleNamespace(stdout='atuin 18.21.0 (revision synthetic)\n')) as run, patch.object(remote, 'atuin_sync') as sync:
                remote.atuin_history(home, capture=True)
                remote.atuin_history(home, capture=True)
            self.assertEqual(sync.call_count, 2)
            self.assertEqual(sum(call.args[0] == ['atuin', 'store', 'rebuild', 'history'] for call in run.call_args_list), 1)
            self.assertTrue((home / '.local/state/walter' / remote.ATUIN_REBUILD_STAMP).exists())

    def test_failure_never_marks_rebuild_complete(self):
        for phase in ('sync', 'rebuild'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory:
                home = Path(directory)
                def run(args):
                    if phase == 'rebuild' and args[1] == 'store':
                        raise remote.ProvisionError('Synthetic rebuild failure')
                    return types.SimpleNamespace(stdout='atuin 18.21.0 (revision synthetic)\n')
                with patch.object(remote, 'run', run), patch.object(remote, 'atuin_sync', side_effect=remote.ProvisionError('Synthetic sync failure') if phase == 'sync' else None):
                    with self.assertRaises(remote.ProvisionError):
                        remote.atuin_history(home)
                self.assertFalse((home / '.local/state/walter' / remote.ATUIN_REBUILD_STAMP).exists())

    def test_effective_old_client_blocks_sync_and_rebuild(self):
        with patch.object(remote, 'run', return_value=types.SimpleNamespace(stdout='atuin 18.15.2\n')) as run, patch.object(remote, 'atuin_sync') as sync:
            with self.assertRaises(remote.ProvisionError):
                remote.atuin_history(Path('/unused'))
        run.assert_called_once_with(['atuin', '--version'])
        sync.assert_not_called()
