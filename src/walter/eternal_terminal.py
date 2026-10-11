"""Root-owned Eternal Terminal server from the shared immutable Nix revision."""
from pathlib import Path
import re

PROFILE = '/nix/var/nix/profiles/walter-et'
CONFIG = '''; Managed by Walter.
[Networking]
port = 2022

[Debug]
verbose = 0
silent = 0
logsize = 20971520
telemetry = false
logdirectory = /tmp
'''


def install(run, write, nix_source, *, local_ssd=False):
    """Realize a shared store path with a root-owned GC root, then start ET.

    Unlike a user profile, this root cannot be redirected by a seat account.
    Using the same immutable source shares realization with user installations.
    """
    if not re.fullmatch(r'github:NixOS/nixpkgs/[0-9a-f]{40}', nix_source):
        raise ValueError('Eternal Terminal requires an immutable Nix revision')
    profile = Path(PROFILE)
    previous = profile.resolve() if profile.exists() else None
    run(['/nix/var/nix/profiles/default/bin/nix', 'build', '--impure',
         '--out-link', PROFILE, nix_source + '#eternal-terminal'])
    changed = previous != profile.resolve()
    changed = write('/etc/et.cfg', CONFIG) or changed
    unit = '[Unit]\nDescription=Eternal Terminal\nAfter=network.target ssh.service\n'
    if local_ssd:
        unit += 'Requires=walter-local-ssd.service\nAfter=walter-local-ssd.service\n'
    unit += ('\n[Service]\nType=simple\nRestart=on-failure\n'
             'ExecStart=' + PROFILE + '/bin/etserver --cfgfile=/etc/et.cfg\n'
             '\n[Install]\nWantedBy=multi-user.target\n')
    changed = write('/etc/systemd/system/et.service', unit) or changed
    run(['systemctl', 'daemon-reload'])
    run(['systemctl', 'enable', '--now', 'et.service'])
    if changed:
        run(['systemctl', 'restart', 'et.service'])
    run(['systemctl', 'is-active', '--quiet', 'et.service'])
