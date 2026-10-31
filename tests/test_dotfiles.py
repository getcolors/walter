import base64
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import Mock

from dotfiles.render import render_profile
from walter import controller


def installer(source=None):
    """Load the installer exactly as the remote program does: from sent source."""
    namespace = {'__name__': 'walter_dotfiles'}
    exec(source or controller.source('dotfiles_install'), namespace)
    return namespace['install']


def encoded(files):
    return {path: base64.b64encode(content).decode() for path, content in files.items()}


class DotfilesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.home = self.root / 'home'
        self.home.mkdir()
        self.install = installer()
        self.stamp = self.home / '.local/state/walter/dotfiles'

    def payload(self, config, action='user-finish'):
        host = Mock()
        host.run_python.return_value = {'ok': True}
        controller.remote(host, {'user': 'ubuntu'}, config, action)
        return host.run_python.call_args.args[2]

    def test_controller_sends_rendered_ubuntu_profile(self):
        rendered = render_profile('ubuntu')
        for action in ('converge', 'user-finish'):
            payload = self.payload({'dotfiles': True}, action)
            self.assertNotIn('dotfiles_source', payload)
            self.assertEqual(rendered, {path: base64.b64decode(value) for path, value in payload['dotfiles'].items()})
            self.assertEqual(controller.source('dotfiles_install'), payload['dotfiles_install_source'])
        self.assertTrue(self.installer_from(payload)(payload['dotfiles'], self.home))
        for path, content in rendered.items():
            self.assertEqual(content, (self.home / path).read_bytes())
            self.assertEqual(0o644, stat.S_IMODE((self.home / path).stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE((self.home / '.config').stat().st_mode))

    def installer_from(self, payload):
        return installer(payload['dotfiles_install_source'])

    def test_disabled_or_other_actions_send_no_dotfiles(self):
        for config, action in (({}, 'user-finish'), ({'dotfiles': False}, 'converge'),
                               ({'dotfiles': True}, 'bootstrap'), ({'dotfiles': True}, 'user-packages')):
            payload = self.payload(config, action)
            self.assertNotIn('dotfiles', payload)
            self.assertNotIn('dotfiles_install_source', payload)

    def test_seeds_missing_files_once_and_preserves_existing_and_later_edits(self):
        (self.home / '.gitconfig').write_text('personal')
        files = encoded({'.gitconfig': b'packaged', '.config/fish/config.fish': b'seed'})
        self.assertTrue(self.install(files, self.home))
        self.assertEqual('personal', (self.home / '.gitconfig').read_text())
        self.assertEqual('seed', (self.home / '.config/fish/config.fish').read_text())
        self.assertTrue(self.stamp.is_file())
        (self.home / '.config/fish/config.fish').write_text('edited')
        (self.home / '.gitconfig').unlink()
        self.assertFalse(self.install(files, self.home))
        self.assertEqual('edited', (self.home / '.config/fish/config.fish').read_text())
        self.assertFalse((self.home / '.gitconfig').exists())

    def test_existing_stamp_leaves_home_unchanged(self):
        self.stamp.parent.mkdir(parents=True)
        self.stamp.write_text('seeded by an earlier Walter\n')
        self.assertFalse(self.install(encoded({'.gitconfig': b'new'}), self.home))
        self.assertEqual(['.local'], [p.name for p in self.home.iterdir()])
        self.stamp.unlink()
        self.stamp.mkdir()
        with self.assertRaisesRegex(ValueError, 'stamp'):
            self.install(encoded({'.gitconfig': b'new'}), self.home)

    def test_refuses_symlinks_before_writing(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (self.home / '.config').symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.install(encoded({'a': b'first', '.config/file': b'seed'}), self.home)
        self.assertEqual([], list(outside.iterdir()))
        self.assertFalse((self.home / 'a').exists())
        (self.home / '.config').unlink()
        (self.home / '.local').symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.install(encoded({'a': b'first'}), self.home)
        self.assertEqual([], list(outside.iterdir()))
        linked_home = self.root / 'linked-home'
        linked_home.symlink_to(self.home)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.install(encoded({'a': b'first'}), linked_home)
        self.assertFalse((self.home / 'a').exists())

    def test_preflight_rejects_conflicting_destinations_before_writing(self):
        (self.home / '.config').write_text('not a directory')
        with self.assertRaisesRegex(ValueError, 'not a directory'):
            self.install(encoded({'a': b'first', '.config/fish/config.fish': b'seed'}), self.home)
        (self.home / '.config').unlink()
        (self.home / 'z').mkdir()
        with self.assertRaisesRegex(ValueError, 'not a regular file'):
            self.install(encoded({'a': b'first', 'z': b'seed'}), self.home)
        self.assertEqual(['z'], [p.name for p in self.home.iterdir()])

    def test_rejects_invalid_payloads_before_touching_home(self):
        for files in ({}, {'../outside': 'eA=='}, {'/etc/passwd': 'eA=='}, {'a/./b': 'eA=='},
                      {'a\\b': 'eA=='}, {'a\nb': 'eA=='}, {'a': 'not base64!'}, {'a': b'eA=='},
                      {'a': 'eA==', 'a/b': 'eA=='}, {'.local/state/walter/dotfiles': 'eA=='}):
            with self.subTest(files=files), self.assertRaises(ValueError):
                self.install(files, self.home)
        self.assertEqual([], list(self.home.iterdir()))
