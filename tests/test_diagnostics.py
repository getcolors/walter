"""Failure evidence crosses boundaries without command text or output."""
import json
import subprocess
import sys
import unittest
from unittest.mock import Mock, patch

from pocketdeploy.common import DeployError
from walter import controller, remote

SENTINEL = 'PRIVATE_SENTINEL_DO_NOT_EMIT'


class DiagnosticTests(unittest.TestCase):
    def failure(self, args, **kwargs):
        with self.assertRaises(remote.ProvisionError) as caught:
            remote.run(args, **kwargs)
        result = remote.failure_result(caught.exception)
        self.assertNotIn(SENTINEL, json.dumps(result))
        self.assertNotIn(SENTINEL, str(caught.exception))
        return result['diagnostic']

    def test_actual_nonzero_command_does_not_echo_arguments_or_output(self):
        script = 'import sys; print(sys.argv[1]); print(sys.argv[1], file=sys.stderr); sys.exit(37)'
        value = self.failure([sys.executable, '-c', script, SENTINEL])
        self.assertEqual(value['exit_code'], 37)
        self.assertEqual(value['category'], 'nonzero_exit')
        self.assertGreaterEqual(value['elapsed_seconds'], 0)

    def test_signal_is_distinguished_from_exit_code(self):
        with patch.object(remote.subprocess, 'run', return_value=subprocess.CompletedProcess([], -15, SENTINEL, SENTINEL)):
            value = self.failure(['atuin', 'sync'])
        self.assertEqual(value['category'], 'signal')
        self.assertEqual(value['signal'], 15)
        self.assertNotIn('exit_code', value)

    def test_timeout_and_unavailable_do_not_echo_exception_details(self):
        for error, category in [(subprocess.TimeoutExpired(SENTINEL, 1, output=SENTINEL, stderr=SENTINEL), 'timeout'), (OSError(SENTINEL), 'unavailable')]:
            with self.subTest(category=category), patch.object(remote.subprocess, 'run', side_effect=error):
                self.assertEqual(self.failure(['python', SENTINEL])['category'], category)

    def test_atuin_classifies_only_recognized_evidence(self):
        cases = [
            ('HTTP status client error (401 Unauthorized)', ('authentication', 401)),
            ('HTTP status client error (403 Forbidden)', ('permission', 403)),
            ('HTTP status client error (429 Too Many Requests)', ('rate_limit', 429)),
            ('HTTP status server error (503 Service Unavailable)', ('http', 503)),
            ('certificate verify failed', ('tls', None)),
            ('dns error: resolution failed', ('dns', None)),
            ('connection refused', ('connection', None)),
            ('connection timed out', ('timeout', None)),
            ('my command printed 401 or authentication', ('nonzero_exit', None)),
            (SENTINEL, ('nonzero_exit', None)),
            ('connection refused' + 'x' * 65536, ('nonzero_exit', None)),
        ]
        for text, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(remote.atuin_failure(text), expected)

    def test_atuin_classifier_is_not_used_for_unrelated_commands(self):
        completed = subprocess.CompletedProcess([], 1, SENTINEL, 'HTTP status client error (401 Unauthorized)')
        with patch.object(remote.subprocess, 'run', return_value=completed):
            self.assertEqual(self.failure(['git', 'clone', SENTINEL])['category'], 'nonzero_exit')
            self.assertEqual(self.failure(['atuin', 'sync'])['category'], 'authentication')

    def test_unknown_or_malformed_diagnostic_is_omitted(self):
        cases = [None, {'operation': SENTINEL, 'category': 'unknown'},
                 {'operation': 'atuin-sync', 'category': 'unknown', 'stderr': SENTINEL},
                 {'operation': 'atuin-sync', 'category': 'unknown', 'user': SENTINEL}]
        for value in cases:
            error = RuntimeError(SENTINEL)
            error.diagnostic = value
            result = remote.failure_result(error)
            self.assertNotIn('diagnostic', result)
            self.assertNotIn(SENTINEL, json.dumps(result))

    def test_controller_preserves_evidence_and_adds_trusted_context(self):
        host = Mock()
        host.run_python.return_value = {'ok': False, 'stage': 'atuin', 'error': SENTINEL,
            'diagnostic': {'operation': 'atuin-sync', 'category': 'rate_limit', 'exit_code': 1, 'http_status': 429}}
        with self.assertRaises(DeployError) as caught:
            controller.remote(host, {'user': 'ubuntu'}, {'users': ['rose']}, 'user-finish', target_user='rose')
        self.assertEqual(caught.exception.diagnostic['task'], 'user-finish')
        self.assertEqual(caught.exception.diagnostic['user'], 'rose')
        self.assertEqual(caught.exception.diagnostic['http_status'], 429)
        self.assertNotIn(SENTINEL, str(caught.exception))

    def test_controller_rejects_remote_attribution_and_extra_fields(self):
        for extra in ({'user': SENTINEL}, {'task': SENTINEL}, {'stdout': SENTINEL}):
            host = Mock()
            host.run_python.return_value = {'ok': False, 'stage': SENTINEL,
                'diagnostic': {'operation': 'atuin-sync', 'category': 'nonzero_exit', **extra}}
            with self.assertRaises(DeployError) as caught:
                controller.remote(host, {}, {}, 'status')
            self.assertIsNone(caught.exception.diagnostic)
            self.assertNotIn(SENTINEL, str(caught.exception))
