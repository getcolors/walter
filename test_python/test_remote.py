import base64
import json
import os
import pwd
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

from walter import remote


class RemoteTests(unittest.TestCase):
    def test_seed_preserves_refreshed_credentials_and_onboarding_choice(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            token = {'.claude/.credentials.json': base64.b64encode(b'first-secret').decode()}
            remote.seed_credentials(home, token)
            credential = home / '.claude/.credentials.json'
            credential.write_text('refreshed-secret')
            (home / '.claude.json').write_text('{"hasCompletedOnboarding":false,"other":1}')
            remote.seed_credentials(home, token)
            self.assertEqual(credential.read_text(), 'refreshed-secret')
            self.assertFalse(json.loads((home / '.claude.json').read_text())['hasCompletedOnboarding'])
            self.assertEqual(credential.stat().st_mode & 0o777, 0o600)

    def test_credential_destinations_restricted(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(remote.ProvisionError):
                remote.seed_credentials(Path(directory), {'../../bad': 'YWJj'})

    def test_destination_cannot_escape_home_or_follow_external_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / 'escape').symlink_to('/tmp')
            for value in ('../outside', '/etc/passwd', '~/escape/anything'):
                with self.assertRaises(remote.ProvisionError):
                    remote.user_path(home, value)

    def test_latest_is_resolved_once_for_multiple_seats(self):
        calls = []
        def runner(args, **kwargs):
            calls.append(args)
            return types.SimpleNamespace(returncode=0, stdout='24.1.0\n', stderr='')
        config = {'asdf-tools': [{'name': 'nodejs', 'version': 'latest', 'plugin': 'https://example.com/plugin'}]}
        with tempfile.TemporaryDirectory() as directory, patch.object(remote, 'run', runner):
            resolved = remote.asdf_tools(config, {}, Path(directory))
            remote.asdf_tools(config, resolved, Path(directory))
        self.assertEqual(sum(args[:2] == ['asdf', 'latest'] for args in calls), 1)
        self.assertEqual(resolved, {'nodejs': '24.1.0'})

    def test_nix_upgrade_only_declared_original_flake(self):
        profile = {'elements': {
            'owned': {'attrPath': 'legacyPackages.aarch64-linux.fish', 'originalUrl': remote.NIXPKGS},
            'manual': {'attrPath': 'legacyPackages.aarch64-linux.ripgrep', 'originalUrl': 'github:other/repo'},
        }}
        calls = []
        def runner(args, **kwargs):
            calls.append(args)
            return types.SimpleNamespace(returncode=0, stdout=json.dumps(profile), stderr='')
        with patch.object(remote, 'run', runner):
            remote.nix_packages(['fish'], upgrade=True)
        self.assertEqual(calls[-1], ['nix', 'profile', 'upgrade', '--impure', 'owned'])
        self.assertFalse(any('manual' in args for args in calls))

    def test_command_failure_does_not_expose_output(self):
        with patch.object(remote.subprocess, 'run', return_value=types.SimpleNamespace(returncode=1, stdout='secret', stderr='secret')):
            with self.assertRaises(remote.ProvisionError) as error:
                remote.run(['some-command'], data='secret')
            self.assertNotIn('secret', str(error.exception))

    def test_existing_checkout_never_updated(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(remote, 'run') as runner:
            path = Path(directory)
            (path / '.git').mkdir()
            remote.clone('https://example.com/repo', path)
            runner.assert_not_called()

    def test_progress_contains_only_allowlisted_stage_and_login(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'progress.json'
            with patch.object(remote, 'PROGRESS_PATH', path), patch.object(remote, 'PROGRESS_USER', 'rose'):
                remote.stage('asdf')
            self.assertEqual(remote.read_progress(path), {'stage': 'asdf', 'user': 'rose'})
            path.write_text(json.dumps({'stage': 'asdf', 'user': 'rose', 'token': 'secret'}))
            self.assertEqual(remote.read_progress(path), {'stage': 'asdf', 'user': 'rose'})
            path.write_text(json.dumps({'stage': 'secret', 'user': 'rose'}))
            self.assertIsNone(remote.read_progress(path))
            path.write_text(json.dumps({'stage': 'asdf', 'user': 'secret/value'}))
            self.assertIsNone(remote.read_progress(path))

    def test_probe_without_gh_requires_login(self):
        with patch.object(remote, 'run', side_effect=remote.ProvisionError('unavailable')):
            self.assertFalse(remote.github_authenticated({'github-account': 'example'}))

    @unittest.skipUnless(os.geteuid() == 0, 'isolated privilege test requires root')
    def test_user_worker_cannot_read_private_root_files_or_regain_root(self):
        # Uses only an existing unprivileged identity and temporary synthetic data.
        try:
            account = pwd.getpwnam('nobody')
        except KeyError:
            self.skipTest('nobody identity unavailable')
        with tempfile.TemporaryDirectory() as directory:
            protected = Path(directory) / 'private'
            protected.write_text('synthetic private material')
            def worker(payload, user, resolved):
                try:
                    protected.read_text()
                    denied = False
                except PermissionError:
                    denied = True
                try:
                    os.setuid(0)
                    permanent = False
                except PermissionError:
                    permanent = True
                return {'denied': denied, 'permanent': permanent, 'uid': os.getuid()}
            with patch.object(remote, 'user_tasks', worker):
                result = remote.as_user({}, 'nobody', {})
        self.assertEqual(result, {'denied': True, 'permanent': True, 'uid': account.pw_uid})

    def test_invalid_user_refused_before_side_effects(self):
        with patch.object(remote, 'machine') as machine:
            with self.assertRaises(remote.ProvisionError):
                remote.main({'config': {'users': ['../root']}})
            machine.assert_not_called()


if __name__ == '__main__':
    unittest.main()
