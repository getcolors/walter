import json
from unittest.mock import patch

import pytest
from walter import remote


def test_resume_uses_explicit_receipt_before_installing_dropins(tmp_path):
    receipt = tmp_path / 'receipt.json'
    receipt.write_text(json.dumps({'actions': [{'state': 'Progress'}]}))
    events = []
    def run(args):
        events.append(('run', args))
        if args[0] == '/nix/nix-installer':
            assert args == ['/nix/nix-installer', 'install', '--no-confirm', str(receipt)]
            receipt.write_text(json.dumps({'actions': [{'state': 'Completed'}]}))
    with patch.object(remote, 'run', side_effect=run), patch.object(remote, 'write', side_effect=lambda *args: events.append(('write', args))), patch.object(remote, 'install_script') as installer:
        remote.install_nix({'local-ssd-scratch': True}, receipt)
    installer.assert_not_called()
    assert events[0][0] == 'run'
    assert len([e for e in events if e[0] == 'write']) == 3
    assert events[-1] == ('run', ['systemctl', 'daemon-reload'])


def test_incomplete_receipt_does_not_get_dropins_or_success(tmp_path):
    receipt = tmp_path / 'receipt.json'
    receipt.write_text(json.dumps({'actions': [{'state': 'Progress'}]}))
    with patch.object(remote, 'run'), patch.object(remote, 'write') as write:
        with pytest.raises(remote.ProvisionError, match='did not complete'):
            remote.install_nix({'local-ssd-scratch': True}, receipt)
    write.assert_not_called()


def test_storage_setup_leaves_nix_override_directories_absent():
    with patch.object(remote, 'run'), patch.object(remote, 'write') as write:
        remote.setup_storage({'local_ssd_source': 'synthetic', 'config': {'google-local-ssd-count': 1}})
    assert not any('/nix-daemon.' in call.args[0] for call in write.call_args_list)
