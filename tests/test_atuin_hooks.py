import json
from pathlib import Path
import tempfile
import unittest

from walter import remote


class AtuinHooksTests(unittest.TestCase):
    def test_cleanup_preserves_unrelated_settings_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            for relative, agent in (('.claude/settings.json', 'claude-code'),
                                    ('.codex/hooks.json', 'codex')):
                path = home / relative
                path.parent.mkdir(parents=True)
                other = {'type': 'command', 'command': 'echo atuin hook ' + agent}
                atuin = {'type': 'command', 'command': 'atuin hook ' + agent}
                groups = [{'matcher': '^Bash$', 'hooks': [atuin, other]},
                          {'matcher': '^Bash$', 'hooks': [atuin]}]
                settings = {'unrelated': {'enabled': True}, 'hooks': {
                    event: groups for event in ('PreToolUse', 'PostToolUse', 'PostToolUseFailure')}}
                path.write_text(json.dumps(settings))
                path.chmod(0o600)
            extension = home / '.pi/agent/extensions/atuin.ts'
            extension.parent.mkdir(parents=True)
            extension.write_text('synthetic extension')
            sibling = extension.with_name('other.ts')
            sibling.write_text('preserve')
            remote.remove_atuin_agent_hooks(home)
            self.assertFalse(extension.exists())
            self.assertEqual(sibling.read_text(), 'preserve')
            for relative in ('.claude/settings.json', '.codex/hooks.json'):
                path = home / relative
                result = json.loads(path.read_text())
                self.assertEqual(result['unrelated'], {'enabled': True})
                for groups in result['hooks'].values():
                    self.assertEqual(len(groups), 1)
                    self.assertEqual(len(groups[0]['hooks']), 1)
                    self.assertTrue(groups[0]['hooks'][0]['command'].startswith('echo '))
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
                before = (path.read_bytes(), path.stat().st_mtime_ns)
                remote.remove_atuin_agent_hooks(home)
                self.assertEqual(before, (path.read_bytes(), path.stat().st_mtime_ns))

    def test_missing_and_unrelated_files_are_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            remote.remove_atuin_agent_hooks(home)
            self.assertEqual(list(home.iterdir()), [])
            path = home / '.claude/settings.json'
            path.parent.mkdir()
            original = '{ "hooks": {"PreToolUse": []}, "other": 1 }'
            path.write_text(original)
            remote.remove_atuin_agent_hooks(home)
            self.assertEqual(path.read_text(), original)

    def test_invalid_json_is_preserved_without_leaking_content(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            path = home / '.claude/settings.json'
            path.parent.mkdir()
            path.write_text('private-invalid-json')
            with self.assertRaises(remote.ProvisionError) as error:
                remote.remove_atuin_agent_hooks(home)
            self.assertNotIn('private', str(error.exception))
            self.assertEqual(path.read_text(), 'private-invalid-json')

    def test_external_symlink_is_not_modified(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            home = Path(directory)
            (home / '.claude').symlink_to(outside)
            target = Path(outside) / 'settings.json'
            target.write_text('{}')
            with self.assertRaises(remote.ProvisionError):
                remote.remove_atuin_agent_hooks(home)
            self.assertEqual(target.read_text(), '{}')
