import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from walter import remote


class AtuinCaptureTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.capture = self.home / '.local/state/walter/atuin-sync.json'
        self.executable = self.home / 'atuin'
        self.environment = patch.dict(os.environ, PATH=str(self.home) + ':' + os.environ['PATH'])
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def program(self, source):
        self.executable.write_text('#!' + sys.executable + '\nimport os, sys, time\nassert sys.argv[1:] == ["sync"]\n' + source)
        self.executable.chmod(0o700)

    def test_large_streams_keep_bounded_tail_without_deadlock(self):
        self.program('for _ in range(256):\n os.write(1, b"a" * 8192); os.write(2, b"b" * 8192)\nos.write(1, b"final stdout"); os.write(2, b"final stderr")\nsys.exit(37)\n')
        with self.assertRaises(remote.ProvisionError) as raised:
            remote.atuin_sync(self.home, capture=True, timeout=10)
        self.assertEqual(37, raised.exception.diagnostic['exit_code'])
        record = json.loads(self.capture.read_text())
        for name in ('stdout', 'stderr'):
            self.assertEqual(65536, len(record[name]))
            self.assertTrue(record[name].endswith('final ' + name))
            self.assertTrue(record[name + '_truncated'])
        self.assertEqual(0o600, self.capture.stat().st_mode & 0o777)
        self.assertNotIn('final stderr', json.dumps(remote.failure_result(raised.exception)))

    def test_timeout_keeps_both_streams(self):
        self.program('os.write(1, b"before timeout"); os.write(2, b"error before timeout"); time.sleep(10)\n')
        with self.assertRaises(remote.ProvisionError) as raised:
            remote.atuin_sync(self.home, capture=True, timeout=0.2)
        self.assertEqual('timeout', raised.exception.diagnostic['category'])
        record = json.loads(self.capture.read_text())
        self.assertEqual('before timeout', record['stdout'])
        self.assertEqual('error before timeout', record['stderr'])
        self.assertLess(record['elapsed_seconds'], 3)

    def test_success_replaces_previous_attempt(self):
        self.program('os.write(2, b"failure"); sys.exit(1)\n')
        with self.assertRaises(remote.ProvisionError):
            remote.atuin_sync(self.home, capture=True)
        self.program('os.write(1, b"success")\n')
        remote.atuin_sync(self.home, capture=True)
        record = json.loads(self.capture.read_text())
        self.assertEqual('success', record['category'])
        self.assertEqual('', record['stderr'])
        self.assertEqual('success', record['stdout'])

    def test_opt_out_uses_existing_runner_without_capture(self):
        with patch.object(remote, 'run') as run:
            remote.atuin_sync(self.home)
        run.assert_called_once_with(['atuin', 'sync'], timeout=3600)
        self.assertFalse(self.capture.exists())

    def test_unavailable_records_only_fixed_metadata(self):
        with patch.object(remote.subprocess, 'Popen', side_effect=FileNotFoundError('private path')):
            with self.assertRaises(remote.ProvisionError) as raised:
                remote.atuin_sync(self.home, capture=True)
        self.assertEqual('unavailable', raised.exception.diagnostic['category'])
        self.assertNotIn('private path', self.capture.read_text())

    def test_existing_http_classification_preserved(self):
        self.program('os.write(2, b"HTTP status client error (429 Too Many Requests)"); sys.exit(1)\n')
        with self.assertRaises(remote.ProvisionError) as raised:
            remote.atuin_sync(self.home, capture=True)
        self.assertEqual(429, raised.exception.diagnostic['http_status'])
        self.assertEqual('rate_limit', raised.exception.diagnostic['category'])

    def test_login_never_creates_or_overwrites_sync_capture(self):
        self.program('print("existing sync")\n')
        remote.atuin_sync(self.home, capture=True)
        before = self.capture.read_bytes()
        self.executable.write_text('#!' + sys.executable + '\nprint("synthetic login secret"); raise SystemExit(1)\n')
        with self.assertRaises(remote.ProvisionError):
            remote.atuin_login('synthetic', 'password', 'key', timeout=3)
        self.assertEqual(before, self.capture.read_bytes())
