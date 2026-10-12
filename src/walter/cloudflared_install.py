"""Checksum-pinned cloudflared binary; stdlib source is shipped over SSH."""
import hashlib
import os
import platform
import shutil
from pathlib import Path
import tempfile
import urllib.request

# https://github.com/cloudflare/cloudflared/releases/tag/2026.10.0
# Release checksums and GitHub asset digests verified 2026-10-12.
VERSION = '2026.10.0'
CHECKSUMS = {
    'amd64': 'd33ff2d14475178d2012c2c56beba87389ac5ded27649519f198a7d3134a99db',
    'arm64': 'e6422b9d4f72d3194bc5a38676f13667c06666523217b842a877d72a80b5ac08',
}
PREFIX = Path('/usr/local')


def architecture(machine=None):
    value = platform.machine() if machine is None else machine
    try:
        return {'aarch64': 'arm64', 'arm64': 'arm64',
                'x86_64': 'amd64', 'amd64': 'amd64'}[value]
    except KeyError:
        raise ValueError('Unsupported cloudflared machine architecture') from None


def download(url, destination):
    with urllib.request.urlopen(url, timeout=120) as response, destination.open('wb') as output:
        shutil.copyfileobj(response, output)


def healthy(run, binary):
    if binary.is_symlink() or not binary.is_file():
        return False
    try:
        result = run([str(binary), '--version'], check=False)
    except Exception:
        return False
    # The release prints a build timestamp after its version. Compare the whole
    # version token so a prerelease or a similarly prefixed version is rejected.
    return result.returncode == 0 and result.stdout.split()[:3] == [
        'cloudflared', 'version', VERSION]


def install(run):
    """Verify a staged binary, then atomically replace the shared executable."""
    if os.geteuid() != 0:
        raise PermissionError('Shared cloudflared installation requires root')
    arch = architecture()
    directory = PREFIX / 'bin'
    binary = directory / 'cloudflared'
    changed = not healthy(run, binary)
    if changed:
        directory.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.walter-cloudflared-', dir=directory) as temporary:
            fresh = Path(temporary) / 'cloudflared'
            download('https://github.com/cloudflare/cloudflared/releases/download/'
                     + VERSION + '/cloudflared-linux-' + arch, fresh)
            digest = hashlib.sha256()
            with fresh.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            if digest.hexdigest() != CHECKSUMS[arch]:
                raise ValueError('Cloudflared binary checksum mismatch')
            fresh.chmod(0o755)
            if not healthy(run, fresh):
                raise ValueError('Staged cloudflared verification failed')
            run(['chown', 'root:root', str(fresh)])
            fresh.replace(binary)
    run(['chown', 'root:root', str(binary)])
    binary.chmod(0o755)
    if not healthy(run, binary):
        raise ValueError('Installed cloudflared verification failed')
    return changed
