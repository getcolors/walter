import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('verify_live', Path(__file__).resolve().parents[1] / 'scripts/verify-live.py')
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class VerifyLiveTest(unittest.TestCase):
    def fixture(self):
        checks = {key: True for key in VERIFY.CHECKS}
        checks['emacs'] = {'running': False, 'exit_code': 0, 'succeeded': True}
        return {'architecture_matches': True, 'ssd_layout': True, 'eternal_terminal': True, 'shared_asdf_versions': True,
                'memory': {key: True for key in VERIFY.MEMORY_CHECKS}, 'users': {'ubuntu': checks}}

    def test_filters_unknown_content_and_requires_true_booleans(self):
        raw = self.fixture()
        raw['PRIVATE'] = 'TOKEN'
        raw['users']['SECRET'] = {'credentials': 'PRIVATE'}
        raw['users']['ubuntu']['stdout'] = 'PRIVATE'
        self.assertTrue(VERIFY.filter_result(raw, ['ubuntu'])['passed'])
        result = VERIFY.filter_result(raw, ['ubuntu', 'seat'])
        self.assertFalse(result['passed'])
        self.assertNotIn('PRIVATE', str(result))
        self.assertNotIn('SECRET', str(result))
        raw['users']['ubuntu']['private_home'] = 'true'
        self.assertFalse(VERIFY.filter_result(raw, ['ubuntu'])['passed'])

    def test_pending_is_opt_in_and_nonzero_exit_never_passes(self):
        raw = self.fixture()
        raw['users']['ubuntu']['emacs'] = {'running': True, 'exit_code': None}
        self.assertFalse(VERIFY.filter_result(raw, ['ubuntu'])['passed'])
        self.assertTrue(VERIFY.filter_result(raw, ['ubuntu'], True)['passed'])
        raw['users']['ubuntu']['emacs'] = {'running': False, 'exit_code': 1, 'succeeded': True}
        self.assertFalse(VERIFY.filter_result(raw, ['ubuntu'], True)['passed'])

    def test_invalid_exit_and_response_are_safe(self):
        raw = self.fixture()
        raw['users']['ubuntu']['emacs']['exit_code'] = 'TOKEN'
        result = VERIFY.filter_result(raw, ['ubuntu'])
        self.assertFalse(result['passed'])
        self.assertNotIn('TOKEN', str(result))
        self.assertFalse(VERIFY.filter_result(None, ['ubuntu'])['passed'])

    def test_remote_program_compiles_without_execution(self):
        compile(VERIFY.REMOTE, '<remote verifier>', 'exec')

    def test_storage_and_shared_versions_are_required(self):
        for field in ('ssd_layout', 'shared_asdf_versions', 'eternal_terminal'):
            raw = self.fixture()
            raw[field] = False
            self.assertFalse(VERIFY.filter_result(raw, ['ubuntu'])['passed'])

    def test_storage_rejects_missing_and_boot_disk_mounts(self):
        namespace = {'__name__': 'verification_test'}
        exec(VERIFY.REMOTE.split('try:\n    print(json.dumps(verify')[0], namespace)
        storage = namespace['storage']
        self.assertTrue(storage({}, ''))
        self.assertFalse(storage({'local-ssd-scratch': True}, ''))
        mounts = '1 0 8:1 / / rw - ext4 /dev/root rw\n2 1 8:1 / /scratch rw - ext4 /dev/root rw'
        self.assertFalse(storage({'local-ssd-scratch': True}, mounts))

    def test_storage_requires_all_six_writable_ssd_bind_mounts(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        namespace = {'__name__': 'verification_test'}
        exec(VERIFY.REMOTE.split('try:\n    print(json.dumps(verify')[0], namespace)
        storage = namespace['storage']
        mounts = ['1 0 8:1 / / rw - ext4 /dev/root rw',
                  '2 1 259:1 / /scratch rw - ext4 /dev/nvme0n1 rw']
        for number, target in enumerate(('/home', '/nix', '/tmp', '/var/tmp', '/var/lib/docker', '/var/lib/containerd'), 3):
            mounts.append(f'{number} 1 259:1 {target} {target} rw - ext4 /dev/nvme0n1 rw')
        with patch.object(namespace['Path'], 'stat', return_value=SimpleNamespace(st_mode=0o41777, st_uid=0, st_gid=0)):
            self.assertTrue(storage({'local-ssd-scratch': True}, '\n'.join(mounts)))
            self.assertFalse(storage({'local-ssd-scratch': True}, '\n'.join(mounts[:-1])))
            self.assertFalse(storage({'local-ssd-scratch': True}, '\n'.join(mounts).replace('/home rw', '/home ro')))


FISH = {'go-version': '1.27.2', 'nix-packages': ['fish']}


def test_go_verification_requires_each_shell_and_compiles_native_probe():
    from unittest.mock import patch
    from types import SimpleNamespace
    namespace = {'__name__': 'verification_test'}
    exec(VERIFY.REMOTE.split('try:\n    print(json.dumps(verify')[0], namespace)
    commands = []
    def command(user, argv):
        commands.append(argv)
        if argv[0] == '/usr/bin/python3':
            compile(argv[2], '<native go probe>', 'exec')
            return 0, ''
        return 0, 'go version go1.27.2 linux/arm64\n'
    namespace['command'] = command
    with patch.object(namespace['platform'], 'machine', return_value='aarch64'), patch.object(namespace['Path'], 'stat', return_value=SimpleNamespace(st_uid=0, st_mode=0o100755)):
        checks = namespace['go_checks']('seat', Path('/home/seat'), FISH)
    assert all(checks.values())
    assert len(commands) == 4
    commands.clear()
    with patch.object(namespace['platform'], 'machine', return_value='aarch64'), patch.object(namespace['Path'], 'stat', return_value=SimpleNamespace(st_uid=0, st_mode=0o100755)):
        checks = namespace['go_checks']('seat', Path('/home/seat'), {'go-version': '1.27.2'})
    assert checks['go_fish'] and len(commands) == 3  # Fish not configured: skipped, not failed.
    namespace['command'] = lambda *args: (0, 'go version go1.26.0 linux/arm64')
    with patch.object(namespace['platform'], 'machine', return_value='aarch64'):
        checks = namespace['go_checks']('seat', Path('/home/seat'), FISH)
    assert not checks['go_direct'] and not checks['go_bash'] and not checks['go_fish']


def test_atuin_requires_effective_compatible_version_and_completed_rebuild(tmp_path):
    namespace = {'__name__': 'verification_test'}
    exec(VERIFY.REMOTE.split('try:\n    print(json.dumps(verify')[0], namespace)
    marker = tmp_path / '.local/state/walter/atuin-history-v2-rebuilt'
    marker.parent.mkdir(parents=True)
    config = {'atuin-username': 'synthetic'}
    binary = tmp_path / '.atuin/bin/atuin'
    binary.parent.mkdir(parents=True)
    binary.write_text('synthetic')
    namespace['command'] = lambda user, args: (0, str(binary) if args[0] == '/bin/sh' else 'atuin 18.23.0 (revision extra)\n')
    checks = namespace['atuin_checks']('seat', tmp_path, config)
    assert checks['atuin_v2_compatible'] and not checks['atuin_history_rebuilt']
    marker.write_text('History rebuilt with v2-compatible Atuin after successful sync.\n')
    marker.chmod(0o600)
    assert all(namespace['atuin_checks']('seat', tmp_path, config).values())
    namespace['command'] = lambda *args: (0, 'atuin 18.15.2 (extras)\n')
    assert not namespace['atuin_checks']('seat', tmp_path, config)['atuin_v2_compatible']
    namespace['command'] = lambda *args: (1, 'atuin 18.21.0')
    assert not namespace['atuin_checks']('seat', tmp_path, config)['atuin_v2_compatible']
    marker.chmod(0o644)
    assert not namespace['atuin_checks']('seat', tmp_path, config)['atuin_history_rebuilt']
    marker.unlink()
    marker.symlink_to(tmp_path / 'missing')
    assert not namespace['atuin_checks']('seat', tmp_path, config)['atuin_history_rebuilt']
    assert all(namespace['atuin_checks']('seat', tmp_path, {}).values())


def test_atuin_checks_are_required_even_with_existing_session():
    fixture = VerifyLiveTest().fixture()
    for name in ('atuin_v2_compatible', 'atuin_history_rebuilt', 'atuin_official_install'):
        raw = dict(fixture, users={'ubuntu': dict(fixture['users']['ubuntu'])})
        raw['users']['ubuntu'][name] = False
        assert not VERIFY.filter_result(raw, ['ubuntu'])['passed']


def test_architecture_follows_each_provider_instance_type():
    cases = [('google', 'gcp-machine-type', 'c4a-standard-8', 'aarch64'),
             ('google', 'gcp-machine-type', 'n4a-highmem-4', 'aarch64'),
             ('google', 'gcp-machine-type', 'n2-standard-8', 'x86_64'),
             ('oci', 'oci-shape', 'VM.Standard.A1.Flex', 'aarch64'),
             ('oci', 'oci-shape', 'VM.Standard.A2.Flex', 'aarch64'),
             ('oci', 'oci-shape', 'VM.Standard.E5.Flex', 'x86_64'),
             ('hcloud', 'hcloud-server-type', 'cax31', 'aarch64'),
             ('hcloud', 'hcloud-server-type', 'cpx31', 'x86_64'),
             ('aws', 'aws-instance-type', 'm8i.xlarge', 'x86_64'),
             ('aws', 'aws-instance-type', 't3.small', 'x86_64'),
             ('aws', 'aws-instance-type', 'g5.xlarge', 'x86_64'),
             ('aws', 'aws-instance-type', 'm7g.xlarge', 'aarch64'),
             ('aws', 'aws-instance-type', 'c8gd.2xlarge', 'aarch64'),
             ('aws', 'aws-instance-type', 'g5g.xlarge', 'aarch64'),
             ('aws', 'aws-instance-type', 'a1.large', 'aarch64')]
    for provider, key, value, expected in cases:
        assert VERIFY.architecture({'provider-compute': provider}, {key: value}) == expected
    assert VERIFY.architecture({'provider-compute': 'oci'}, {'gcp-machine-type': 'c4a-standard-8'}) == 'x86_64'


def test_memory_results_are_filtered_and_required():
    fixture = VerifyLiveTest().fixture()
    for name in VERIFY.MEMORY_CHECKS:
        raw = dict(fixture, memory=dict(fixture['memory'], PRIVATE='TOKEN'))
        assert VERIFY.filter_result(raw, ['ubuntu'])['passed']
        raw['memory'][name] = 'true'
        result = VERIFY.filter_result(raw, ['ubuntu'])
        assert not result['passed'] and result['memory'][name] is False
        assert 'TOKEN' not in str(result)
    assert not VERIFY.filter_result(dict(fixture, memory=None), ['ubuntu'])['passed']


def memory_host(tmp_path, swaps, fstab, swappiness='10\n', pressure='50\n'):
    namespace = {'__name__': 'verification_test'}
    exec(VERIFY.REMOTE.split('try:\n    print(json.dumps(verify')[0], namespace)
    for name, text in (('SWAPS', swaps), ('FSTAB', fstab), ('SWAPPINESS', swappiness), ('CACHE_PRESSURE', pressure)):
        path = tmp_path / name
        if text is not None:
            path.write_text(text)
        namespace[name] = str(path)
    return namespace


SWAPS_HEADER = 'Filename\t\t\t\tType\t\tSize\t\tUsed\t\tPriority\n'
FSTAB = 'LABEL=cloudimg-rootfs / ext4 defaults 0 1\n  /swapfile none swap sw 0 0  \n'


def test_memory_checks_require_sized_swap_fstab_sysctl_and_earlyoom(tmp_path):
    from types import SimpleNamespace
    from unittest.mock import patch
    swaps = SWAPS_HEADER + '/swapfile                               file\t\t8388604\t\t0\t\t-2\n'
    namespace = memory_host(tmp_path, swaps, FSTAB)
    calls = []
    def run(argv, **kwargs):
        calls.append(argv)
        return SimpleNamespace(returncode=0, stdout='PRIVATE')
    with patch.object(namespace['subprocess'], 'run', side_effect=run):
        checks = namespace['memory']({})
        assert checks == {'swap': True, 'fstab': True, 'sysctl': True, 'earlyoom': True}
        assert {tuple(c[:2]) for c in calls} == {('systemctl', 'is-enabled'), ('systemctl', 'is-active')}
        assert not namespace['memory']({'swap-gb': 4})['swap']
        assert namespace['memory']({'swap-gb': 0}) == {'swap': False, 'fstab': False, 'sysctl': True, 'earlyoom': True}
    with patch.object(namespace['subprocess'], 'run', side_effect=lambda argv, **kw: SimpleNamespace(returncode=int(argv[1] == 'is-active'))):
        assert not namespace['memory']({})['earlyoom']
    with patch.object(namespace['subprocess'], 'run', side_effect=OSError):
        assert not namespace['memory']({})['earlyoom']


def test_memory_checks_reject_duplicate_fstab_bad_sysctl_and_missing_files(tmp_path):
    from types import SimpleNamespace
    from unittest.mock import patch
    swaps = SWAPS_HEADER + '/swapfile file 8388604 0 -2\n'
    ok = lambda *a, **kw: SimpleNamespace(returncode=0)
    with patch('subprocess.run', side_effect=ok):
        namespace = memory_host(tmp_path, swaps, FSTAB + '/swapfile none swap sw 0 0\n', '60\n')
        checks = namespace['memory']({'swap-gb': 8})
        assert not checks['fstab'] and not checks['sysctl'] and checks['swap']
        namespace = memory_host(tmp_path, swaps, FSTAB, '10\n', '100\n')
        assert not namespace['memory']({})['sysctl']
        missing = tmp_path / 'missing'
        missing.mkdir()
        namespace = memory_host(missing, None, None, None, None)
        assert namespace['memory']({}) == {'swap': False, 'fstab': False, 'sysctl': False, 'earlyoom': True}
        namespace = memory_host(tmp_path, SWAPS_HEADER, 'LABEL=root / ext4 defaults 0 1\n')
        assert namespace['memory']({'swap-gb': 0}) == {'swap': True, 'fstab': True, 'sysctl': True, 'earlyoom': True}
