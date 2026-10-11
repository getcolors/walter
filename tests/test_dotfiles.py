import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('dotfiles', Path(__file__).resolve().parents[1] / 'src/walter/dotfiles.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DotfilesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.home = self.root / 'home'
        self.home.mkdir()
        self.checkout = self.root / 'checkout'
        self.resources = self.checkout / 'src/resources/io/github/getcolors/dotfiles'
        self.common = self.resources / 'common'
        self.common.mkdir(parents=True)

    def test_render_profile_conditions(self):
        self.assertEqual('abc', MODULE.render('a{% if profile = "macos" %}X{% else %}b{% endif %}c'))
        for value in ['{% unknown %}', '{% else %}', '{% if profile = "ubuntu" %}', '{{ secret }}']:
            with self.assertRaises(ValueError):
                MODULE.render(value)

    def test_preserves_existing_and_later_edits(self):
        (self.common / '.gitconfig').write_text('checkout')
        (self.home / '.gitconfig').write_text('personal')
        (self.common / '.shellrc').write_text('seed')
        self.assertTrue(MODULE.install(self.checkout, self.home))
        self.assertEqual('personal', (self.home / '.gitconfig').read_text())
        self.assertEqual('seed', (self.home / '.shellrc').read_text())
        (self.home / '.shellrc').write_text('edited')
        self.assertFalse(MODULE.install(self.checkout, self.home))
        self.assertEqual('edited', (self.home / '.shellrc').read_text())

    def test_preflight_rejects_duplicates_before_writing(self):
        (self.common / '.config').write_text('common')
        profile = self.resources / 'profiles/ubuntu'
        profile.mkdir(parents=True)
        (profile / '.config').write_text('profile')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            MODULE.install(self.checkout, self.home)
        self.assertEqual([], list(self.home.iterdir()))

    def test_rejects_destination_symlink(self):
        (self.common / '.config').mkdir()
        (self.common / '.config/file').write_text('seed')
        outside = self.root / 'outside'
        outside.mkdir()
        (self.home / '.config').symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            MODULE.install(self.checkout, self.home)
        self.assertEqual([], list(outside.iterdir()))

    def test_bad_template_leaves_home_untouched(self):
        (self.common / 'a').write_text('valid')
        (self.common / 'z').write_text('{% unknown %}')
        with self.assertRaises(ValueError):
            MODULE.install(self.checkout, self.home)
        self.assertEqual([], list(self.home.iterdir()))
