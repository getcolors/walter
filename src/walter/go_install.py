"""Shared, checksum-pinned Go distribution; stdlib source is shipped over SSH."""
import hashlib
import os
import platform
import shutil
from pathlib import Path
import tempfile
import urllib.request

# https://go.dev/dl/?mode=json&include=all — verified 2026-10-11.
CHECKSUMS = {'1.27.2': {
    'amd64': 'ecbadb99091a3f46e31f5f934b068b1864eafa7995211b39eaddf76996045fe5',
    'arm64': '94f3e30b8e374bc285e7dadc11e0865726b9bc6e85b841ccceaabc0214c6b7c8',
}}
PREFIX = Path('/usr/local')


def architecture(machine=None):
    value = platform.machine() if machine is None else machine
    try:
        return {'aarch64': 'arm64', 'arm64': 'arm64',
                'x86_64': 'amd64', 'amd64': 'amd64'}[value]
    except KeyError:
        raise ValueError('Unsupported Go machine architecture') from None


def download(url, destination):
    with urllib.request.urlopen(url, timeout=120) as response, destination.open('wb') as output:
        shutil.copyfileobj(response, output)


def healthy(run, tree, expected):
    if tree.is_symlink() or not (tree / 'bin/go').is_file() or not (tree / 'bin/gofmt').is_file():
        return False
    try:
        result = run(['/usr/bin/env', 'GOTOOLCHAIN=local', str(tree / 'bin/go'), 'version'], check=False)
    except Exception:
        # An incompatible or damaged existing executable also needs replacement.
        return False
    return result.returncode == 0 and result.stdout.strip() == expected


def install(run, write, version):
    """Stage and verify before replacing; caller serializes the shared Go resource."""
    if os.geteuid() != 0:
        raise PermissionError('Shared Go installation requires root')
    arch = architecture()
    if version not in CHECKSUMS:
        raise ValueError('Go version has no pinned checksums')
    expected = 'go version go' + version + ' linux/' + arch
    tree = PREFIX / 'go'
    changed = not healthy(run, tree, expected)
    if changed:
        PREFIX.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.walter-go-', dir=PREFIX) as temporary:
            staging = Path(temporary)
            archive = staging / 'go.tar.gz'
            download('https://go.dev/dl/go' + version + '.linux-' + arch + '.tar.gz', archive)
            digest = hashlib.sha256()
            with archive.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            if digest.hexdigest() != CHECKSUMS[version][arch]:
                raise ValueError('Go archive checksum mismatch')
            run(['tar', '--no-same-owner', '-C', str(staging), '-xzf', str(archive)])
            fresh = staging / 'go'
            if not healthy(run, fresh, expected):
                raise ValueError('Staged Go verification failed')
            # Never extract over an existing tree. A failed/interrupted replacement
            # is retried on convergence; the verified staging tree is fresh.
            if tree.is_symlink() or tree.is_file():
                tree.unlink()
            elif tree.exists():
                shutil.rmtree(tree)
            fresh.rename(tree)
    run(['chown', '-R', 'root:root', str(tree)])
    run(['chmod', '-R', 'a+rX,go-w', str(tree)])
    (PREFIX / 'bin').mkdir(parents=True, exist_ok=True)
    for name in ('go', 'gofmt'):
        link = PREFIX / 'bin' / name
        target = tree / 'bin' / name
        if link.is_symlink() and link.readlink() == target:
            continue
        if link.exists() or link.is_symlink():
            link.unlink()
        link.symlink_to(target)
    write('/etc/profile.d/walter-go.sh',
          '# Managed by Walter.\nexport PATH="/usr/local/go/bin:$PATH:$HOME/go/bin"\n')
    write('/etc/fish/conf.d/walter-go.fish',
          '# Managed by Walter.\nfish_add_path --path --prepend /usr/local/go/bin\n'
          'fish_add_path --path --append "$HOME/go/bin"\n')
    if not healthy(run, tree, expected):
        raise ValueError('Installed Go verification failed')
    return changed
