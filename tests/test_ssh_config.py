"""Walter retains stable ownership markers through the shared updater."""
from walter.ssh_config import update


def test_existing_walter_block_is_updated_without_duplicate(tmp_path):
    directory = tmp_path / '.ssh'
    directory.mkdir()
    path = directory / 'config'
    path.write_text('# BEGIN probe WALTER MANAGED BLOCK\nHost probe\n    HostName 192.0.2.1\n# END probe WALTER MANAGED BLOCK\n')
    payload = {'host_alias': 'probe', 'block_state': 'present',
               'ssh_hosts': [{'name': 'probe', 'ip': '192.0.2.2', 'user': 'ubuntu'}],
               'identity_file': str(tmp_path / 'key'), 'known_hosts_file': str(tmp_path / 'known')}
    assert update(payload, tmp_path)
    assert path.read_text().count('# BEGIN probe WALTER MANAGED BLOCK') == 1
    assert '192.0.2.1' not in path.read_text()
    assert not update(payload, tmp_path)
