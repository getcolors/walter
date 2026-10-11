import json
from pathlib import Path
import tempfile
import unittest
from pocketdeploy.common import DeployError
from walter.config import load


class ConfigTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'colors.yml'
        self.base = {'profile': 'test-gcp', 'google-project': 'synthetic-project',
                     'google-zone': 'europe-west4-b', 'google-machine-type': 'e2-small'}

    def load(self, overrides=None, env=None):
        self.path.write_text(json.dumps({**self.base, **(overrides or {})}))
        return load(self.path, {} if env is None else env)

    def test_minimal_google_mapping_and_no_credentials(self):
        c, d = self.load({'users': ['seat']}, {'COLORS_PAR_ATUIN_PASSWORD': 'PRIVATE', 'COLORS_PAR_GITHUB_TOKEN': 'PRIVATE'})
        self.assertEqual('gcp', d['provider-compute'])
        self.assertEqual('synthetic-project', d['gcp-project'])
        self.assertEqual([], d['compute-http-sources'])
        self.assertEqual(['seat'], c['users'])
        self.assertNotIn('PRIVATE', json.dumps([c, d]))
        _, ordinary = self.load({'users': ['seat']})
        self.assertEqual(ordinary['_desired_hash'], d['_desired_hash'])

    def test_rejects_legacy_and_profile_override(self):
        for values, env in [({'compute-api-version': 2}, {}), ({}, {'COLORS_PAR_PROFILE': 'other'})]:
            with self.assertRaises(DeployError):
                self.load(values, env)

    def test_rejects_unsafe_users(self):
        for values in [{'users': ['root']}, {'users': ['ubuntu']}, {'users': ['seat', 'seat']}, {'users': ['bad\nuser']}, {'ssh-user': '-option'}]:
            with self.subTest(values=values), self.assertRaises(DeployError):
                self.load(values)

    def test_rejects_invalid_tool_types_and_urls(self):
        for tool in [{'name': 'nodejs', 'version': 22, 'plugin': 'https://example.com/plugin'},
                     {'name': 'nodejs', 'version': 'latest', 'plugin': 'https://secret@example.com/plugin'},
                     {'name': 'nodejs', 'version': 'latest', 'plugin': 'https://example.com/\nplugin'}]:
            with self.subTest(tool=tool), self.assertRaises(DeployError):
                self.load({'asdf-tools': [tool]})

    def test_requires_github_and_safe_home_paths(self):
        with self.assertRaises(DeployError):
            self.load({'dotfiles-checkout': '~/code/dotfiles'})
        with self.assertRaises(DeployError):
            self.load({'github-account': 'example', 'git-email': 'a@example.com', 'dotfiles-checkout': '~/../outside'})

    def test_private_assignment_names_without_evaluation_or_secret_errors(self):
        private = self.path.parent / '.envrc.private'
        for text in ['export COLORS_PAR_TOKEN="secret ; @ $ value"\n',
                     '# IGNORED=comment\nCOLORS_PAR_TOKEN=abc\n',
                     'export COLORS_PAR_TOKEN="value OTHER_NAME=inside-value"\n']:
            private.write_text(text)
            self.load()
        for text in ['BAD_NAME=PRIVATE\n', 'export BAD_NAME="PRIVATE"\n',
                     'export COLORS_PAR_OK=abc; BAD_NAME=PRIVATE\n', 'BAD_NAME+=PRIVATE\n']:
            private.write_text(text)
            with self.assertRaises(DeployError) as raised:
                self.load()
            self.assertNotIn('PRIVATE', str(raised.exception))
            self.assertNotIn('BAD_NAME', str(raised.exception))
        marker = self.path.parent / 'must-not-exist'
        private.write_text('COLORS_PAR_TOKEN=$(touch ' + str(marker) + ')\n')
        self.load()
        self.assertFalse(marker.exists())


def test_relative_google_image_normalizes_without_editing_yaml(tmp_path):
    from walter.config import load
    config = tmp_path / 'colors.yml'
    config.write_text('profile: example\ngoogle-project: example\ngoogle-zone: europe-west4-b\ngoogle-image-id: projects/ubuntu-os-cloud/global/images/example\n')
    _, deployment = load(config, env={})
    assert deployment['gcp-image'] == 'https://compute.googleapis.com/compute/v1/projects/ubuntu-os-cloud/global/images/example'
