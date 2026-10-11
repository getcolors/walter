from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from pocketdeploy.common import DeployError
from walter import cli


class CLITest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.path = self.root / 'colors.yml'
        self.path.write_text('profile: test-gcp\ngoogle-project: synthetic-project\ngoogle-zone: europe-west4-b\ngoogle-machine-type: e2-small\n')
        self.reporter = Mock()

    def execute(self, *args):
        return cli.execute(cli.parser().parse_args([*args, '-f', str(self.path)]), self.reporter)

    def test_fresh_plan_and_dry_run_leave_no_local_artifacts(self):
        with patch.object(cli, 'GCP') as gcp, patch.object(cli, 'Host') as host:
            gcp.return_value.plan.return_value = []
            for arguments in [('plan',), ('converge', '--dry-run')]:
                self.execute(*arguments)
            gcp.return_value.converge.assert_not_called()
            host.return_value.prepare_keys.assert_not_called()
        self.assertEqual(['colors.yml'], [p.name for p in self.root.iterdir()])

    def test_missing_state_refuses_operational_commands(self):
        with patch.object(cli, 'GCP') as gcp:
            for command in ('delete', 'status', 'ssh', 'ssh-install', 'converge-nix', 'converge-asdf'):
                with self.subTest(command=command), self.assertRaisesRegex(DeployError, 'state is missing'):
                    self.execute(command)
            gcp.assert_not_called()

    def test_init_has_no_provider_calls(self):
        with patch.object(cli, 'GCP') as gcp, patch.object(cli, 'Host'), patch.object(cli, 'initialize', return_value={'initialized': True}) as initialize:
            self.assertEqual({'initialized': True}, self.execute('init'))
            self.assertEqual([], gcp.return_value.mock_calls)
            initialize.assert_called_once()

    def test_delete_protection_precedes_cloud_mutation(self):
        with patch.object(cli, 'GCP') as gcp, patch.object(cli, 'Host'), patch.object(cli, 'initialize'):
            self.execute('init')
            with self.assertRaisesRegex(DeployError, 'Deletion protection'):
                self.execute('delete')
            gcp.return_value.delete.assert_not_called()
            gcp.return_value.plan_delete.assert_not_called()

    def test_existing_compute_missing_identity_is_not_regenerated(self):
        from pocketdeploy.state import State
        from pocketdeploy.config import scope
        with patch.object(cli, 'GCP'):
            self.execute('init')
            config, deployment = cli.load(self.path)
            path = self.root / deployment['state-file']
            with State(path, config['profile'], scope(deployment)) as state:
                state.put_resource('compute', 'gcp', 'synthetic-instance', {})
            key = self.root / deployment.get('ssh-private-key-file', '.ssh/id_ed25519')
            original = key.read_bytes()
            key.unlink()
            with self.assertRaisesRegex(DeployError, 'identity is missing'):
                self.execute('init')
            self.assertFalse(key.exists())
            self.assertTrue(original)

    def test_uninstall_does_not_construct_provider(self):
        with patch.object(cli, 'GCP') as gcp, patch.object(cli, 'Host'), patch.object(cli.controller, 'aliases', return_value=False):
            self.execute('ssh-uninstall')
            gcp.assert_not_called()

    def test_unsupported_power_and_invalid_option_combinations(self):
        for command in ('start', 'stop'):
            with self.assertRaises(DeployError):
                cli.parser().parse_args([command])
        for args in [('init', '--dry-run'), ('plan', '--user', 'seat'), ('ssh', '--json')]:
            with self.subTest(args=args), self.assertRaises(DeployError):
                self.execute(*args)
