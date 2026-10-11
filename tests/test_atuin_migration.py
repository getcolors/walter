import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

from walter import remote


class AtuinMigrationTest(unittest.TestCase):
    def profile_run(self, atuin_source, *, fail_add=False):
        profile = {'elements': {
            'atuin-old': {'attrPath': 'legacyPackages.aarch64-linux.atuin', 'originalUrl': atuin_source},
            'fish-existing': {'attrPath': 'legacyPackages.aarch64-linux.fish', 'originalUrl': remote.NIXPKGS},
        }}
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            if fail_add and args[:3] == ['nix', 'profile', 'add']:
                raise remote.ProvisionError('Synthetic add failure')
            return types.SimpleNamespace(stdout=json.dumps(profile))
        return calls, run

    def test_existing_old_atuin_replaced_but_other_packages_preserved(self):
        calls, run = self.profile_run(remote.NIXPKGS)
        with patch.object(remote, 'run', run), patch.object(Path, 'exists', return_value=True):
            remote.nix_packages(['atuin', 'fish'])
        self.assertEqual(calls[1], ['nix', 'build', '--impure', '--no-link', remote.ATUIN_NIXPKGS + '#atuin'])
        self.assertEqual(calls[2], ['nix', 'profile', 'remove', 'atuin-old'])
        self.assertEqual(calls[3], ['nix', 'profile', 'add', '--impure', remote.ATUIN_NIXPKGS + '#atuin'])

    def test_correct_source_not_replaced(self):
        calls, run = self.profile_run(remote.ATUIN_NIXPKGS)
        with patch.object(remote, 'run', run), patch.object(Path, 'exists', return_value=True):
            remote.nix_packages(['atuin', 'fish'])
        self.assertEqual(calls, [['nix', 'profile', 'list', '--json']])

    def test_fresh_install_sources_only_atuin_from_new_pin(self):
        with patch.object(remote, 'run') as run, patch.object(Path, 'exists', return_value=False):
            remote.nix_packages(['atuin', 'fish'], source='github:NixOS/nixpkgs/' + 'a' * 40)
        run.assert_called_once_with(['nix', 'profile', 'add', '--impure', remote.ATUIN_NIXPKGS + '#atuin', 'github:NixOS/nixpkgs/' + 'a' * 40 + '#fish'])

    def test_failed_replacement_rolls_back(self):
        calls, run = self.profile_run(remote.NIXPKGS, fail_add=True)
        with patch.object(remote, 'run', run), patch.object(Path, 'exists', return_value=True):
            with self.assertRaises(remote.ProvisionError):
                remote.nix_packages(['atuin', 'fish'])
        self.assertEqual(calls[-1], ['nix', 'profile', 'rollback'])

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
