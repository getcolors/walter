import importlib.util
import json
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('verify_atuin_hooks',
    Path(__file__).resolve().parents[1] / 'scripts/verify-atuin-hooks.py')
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


def namespace():
    result = {}
    exec(VERIFY.REMOTE.split("try:\n    print(json.dumps(verify")[0], result)
    return result


def test_json_hook_detection_and_fail_closed(tmp_path):
    check = namespace()['absent_json_hook']
    relative = '.claude/settings.json'
    path = tmp_path / relative
    assert check(tmp_path, relative)
    path.parent.mkdir()
    path.write_text(json.dumps({'hooks': {'PreToolUse': [{'hooks': [
        {'command': '/home/synthetic/.atuin/bin/atuin hook claude-code'}]}]}}))
    assert not check(tmp_path, relative)
    path.write_text(json.dumps({'hooks': {'PreToolUse': [{'hooks': [
        {'command': 'synthetic-other-hook'}]}]}, 'private': 'synthetic-secret'}))
    assert check(tmp_path, relative)
    path.write_text('{')
    assert not check(tmp_path, relative)
    path.unlink()
    path.symlink_to(tmp_path / 'missing')
    assert not check(tmp_path, relative)


def test_extensions_detect_files_and_dangling_symlinks(tmp_path):
    check = namespace()['absent_file']
    for relative in ('.pi/agent/extensions/atuin.ts', '.config/opencode/plugins/atuin.ts'):
        path = tmp_path / relative
        assert check(tmp_path, relative)
        path.parent.mkdir(parents=True)
        path.write_text('synthetic')
        assert not check(tmp_path, relative)
        path.unlink()
        path.symlink_to(tmp_path / 'missing')
        assert not check(tmp_path, relative)


def test_result_only_exports_expected_true_booleans():
    raw = {'seat': dict.fromkeys(VERIFY.CHECKS, True), 'unexpected': 'synthetic-secret'}
    raw['seat']['private'] = 'synthetic-secret'
    assert VERIFY.filter_result(raw, ['seat'])['passed']
    assert 'synthetic-secret' not in str(VERIFY.filter_result(raw, ['seat']))
    assert not VERIFY.filter_result(raw, ['seat', 'missing'])['passed']
    raw['seat']['fish_integration'] = 'true'
    assert not VERIFY.filter_result(raw, ['seat'])['passed']
    assert not VERIFY.filter_result(None, ['seat'])['passed']
    assert not VERIFY.filter_result({}, [])['passed']
