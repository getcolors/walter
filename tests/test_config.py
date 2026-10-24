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

    def test_atuin_capture_requires_explicit_boolean_opt_in(self):
        c, _ = self.load()
        self.assertIs(False, c['atuin-sync-capture'])
        c, _ = self.load(env={'COLORS_PAR_ATUIN_SYNC_CAPTURE': 'true'})
        self.assertIs(True, c['atuin-sync-capture'])
        for value in ('yes', 1, None, []):
            with self.subTest(value=value), self.assertRaises(DeployError):
                self.load({'atuin-sync-capture': value})

    def test_atuin_cannot_be_reintroduced_through_nix(self):
        with self.assertRaises(DeployError):
            self.load({'nix-packages': ['atuin']})

    def test_gws_requires_boolean_and_node_runtime(self):
        c, _ = self.load()
        self.assertIs(c['gws'], False)
        for value in (True, 'yes', 1, None):
            with self.subTest(value=value), self.assertRaises(DeployError):
                self.load({'gws': value})
        c, _ = self.load({'gws': True, 'asdf-tools': [
            {'name': 'nodejs', 'version': 'latest', 'plugin': 'https://github.com/asdf-vm/asdf-nodejs.git'}]})
        self.assertIs(c['gws'], True)

    def test_go_version_requires_pinned_release(self):
        c, _ = self.load()
        self.assertEqual('1.27.2', c['go-version'])
        for value in ['latest', '1.27.3', 1272, None, ['1.27.2']]:
            with self.subTest(value=value), self.assertRaises(DeployError):
                self.load({'go-version': value})

    def test_rejects_retired_configuration(self):
        for values, env in [({'compute-api-version': 2}, {}), ({'provider-backend': 'r2'}, {})]:
            with self.assertRaises(DeployError):
                self.load(values, env)

    def test_shared_environment_override_maps_to_state_identity(self):
        c, d = self.load(env={'COLORS_PAR_PROFILE': 'other-profile'})
        self.assertEqual('other-profile', c['profile'])
        self.assertEqual('other-profile', d['profile'])

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


OCI_BASE = {'profile': 'test-oci', 'provider-compute': 'oci', 'oci-config-file-profile': 'DEFAULT',
            'oci-compartment-id': 'ocid1.compartment.oc1..synthetic',
            'oci-subnet-id': 'ocid1.subnet.oc1.synthetic', 'oci-availability-domain': 'AD-1',
            'oci-ocpus': 2, 'oci-memory-in-gbs': 12, 'oci-boot-volume-size-in-gbs': 100,
            'oci-boot-volume-vpus-per-gb': 30}


def test_oci_fields_pass_through_without_google_defaults(tmp_path):
    config = tmp_path / 'colors.yml'
    config.write_text(json.dumps({**OCI_BASE, 'nix-packages': ['eternal-terminal']}))
    c, d = load(config, env={})
    assert d['provider-compute'] == 'oci'
    assert (d['oci-ocpus'], d['oci-memory-in-gbs'], d['oci-boot-volume-vpus-per-gb']) == (2, 12, 30)
    assert d['compute-ssh-ports'] == ['22', '2022']
    assert not any(k.startswith(('google-', 'gcp-')) for k in [*c, *d])


def test_oci_rejects_google_fields_and_scratch(tmp_path):
    config = tmp_path / 'colors.yml'
    for extra in ({'google-zone': 'europe-west4-b'}, {'local-ssd-scratch': True}):
        config.write_text(json.dumps({**OCI_BASE, **extra}))
        try:
            load(config, env={})
        except DeployError:
            continue
        raise AssertionError(extra)


def test_google_rejects_oci_fields(tmp_path):
    config = tmp_path / 'colors.yml'
    config.write_text('profile: example\ngoogle-project: example\ngoogle-zone: europe-west4-b\noci-ocpus: 2\n')
    try:
        load(config, env={})
    except DeployError:
        return
    raise AssertionError('oci field accepted')
