"""Walter retains stable ownership markers through the shared config.d updater."""
import pytest

from walter.ssh_config import update


def payload(tmp_path, state='present'):
    return {'host_alias': 'probe', 'block_state': state,
            'ssh_hosts': [{'name': 'probe', 'ip': '192.0.2.2', 'user': 'ubuntu'}] if state == 'present' else [],
            'identity_file': str(tmp_path / 'key'), 'known_hosts_file': str(tmp_path / 'known')}


def test_walter_snippet_is_owned_in_config_d(tmp_path):
    directory = tmp_path / '.ssh'
    directory.mkdir()
    main = 'Host *\n    Include config.d/*\n'
    (directory / 'config').write_text(main)
    assert update(payload(tmp_path), tmp_path)
    snippet = directory / 'config.d/00-walter-probe.conf'
    assert snippet.read_text().startswith('# BEGIN probe WALTER MANAGED BLOCK\nHost probe\n')
    assert (directory / 'config').read_text() == main
    assert not update(payload(tmp_path), tmp_path)
    assert update(payload(tmp_path, 'absent'), tmp_path)
    assert not snippet.exists()


def test_legacy_walter_block_in_main_config_is_refused(tmp_path):
    directory = tmp_path / '.ssh'
    directory.mkdir()
    legacy = ('Host *\n    Include config.d/*\n# BEGIN probe WALTER MANAGED BLOCK\nHost probe\n'
              '    HostName 192.0.2.1\n# END probe WALTER MANAGED BLOCK\n')
    (directory / 'config').write_text(legacy)
    for state in ('present', 'absent'):
        with pytest.raises(ValueError, match='legacy owned SSH block'):
            update(payload(tmp_path, state), tmp_path)
    assert (directory / 'config').read_text() == legacy
    assert not (directory / 'config.d').exists()
