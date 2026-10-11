import base64
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from pocketdeploy.common import DeployError
from walter import controller


class ControllerTest(unittest.TestCase):
    def test_remote_exception_text_is_not_reported(self):
        host = Mock()
        host.run_python.return_value = {'ok': False, 'stage': 'secret-token', 'error': 'PRIVATE'}
        with self.assertRaises(DeployError) as raised:
            controller.remote(host, {'user': 'ubuntu'}, {}, 'status')
        self.assertNotIn('PRIVATE', str(raised.exception))
        self.assertNotIn('secret-token', str(raised.exception))

    def test_safe_remote_stage_is_reported(self):
        host = Mock()
        host.run_python.return_value = {'ok': False, 'stage': 'agent-tools'}
        with self.assertRaisesRegex(DeployError, 'agent-tools'):
            controller.remote(host, {}, {}, 'status')

    def test_journal_uses_configured_users_not_remote_content(self):
        state = Mock()
        state.db.execute.return_value.fetchall.return_value = []
        config = {'_desired_hash': 'hash', '_walter': {'users': ['seat']}}
        host = Mock()
        host.run_python.return_value = {'ok': True, 'users': {'PRIVATE': {}}, 'resolved_versions': {'PRIVATE': 'SECRET'}}
        controller.complete_remote(state, 'operation', config, host, {'instance_id': '123'}, 'converge-nix', github_token='TOKEN')
        self.assertEqual({'verified': True, 'users': ['ubuntu', 'seat']}, state.complete.call_args.args[1])
        self.assertNotIn('TOKEN', str(state.mock_calls))

    def test_github_mismatch_never_requests_token(self):
        with patch.object(controller, 'run', return_value='other') as run:
            with self.assertRaises(DeployError):
                controller.github_token('expected')
        self.assertEqual(1, run.call_count)

    def test_private_credentials_skip_missing_and_reject_symlink(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(Path, 'home', return_value=Path(temporary)):
            config = {'seed-agent-credentials': ['codex']}
            self.assertEqual({'credentials': {}}, controller.private_payload(config))
            directory = Path(temporary) / '.codex'
            directory.mkdir()
            target = Path(temporary) / 'secret'
            target.write_text('PRIVATE')
            (directory / 'auth.json').symlink_to(target)
            with self.assertRaises(DeployError):
                controller.private_payload(config)
