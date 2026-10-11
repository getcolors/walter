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
        return {'architecture_matches': True, 'users': {'ubuntu': checks}}

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
