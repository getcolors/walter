"""Test copied portable launcher selection without provider or credential access."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

launcher = Path(__file__).resolve().parents[1] / 'skills/walter/walter'
with tempfile.TemporaryDirectory() as directory:
    copied = Path(directory) / 'walter'
    shutil.copyfile(launcher, copied)
    copied.chmod(0o755)
    for arguments in ([], ['--help']):
        result = subprocess.run([str(copied), *arguments], cwd=directory, capture_output=True, text=True)
        assert result.returncode == 0, 'copied launcher help failed'
        assert 'converge' in result.stdout
    result = subprocess.run([str(copied), 'start'], cwd=directory, capture_output=True, text=True)
    assert result.returncode == 2, 'removed start command accepted'
    result = subprocess.run([str(copied), 'plan', '--json'], cwd=directory, capture_output=True, text=True)
    assert result.returncode == 1, 'missing configuration must fail before provider access'
print('Copied launcher checks passed')
