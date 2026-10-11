"""Walter ownership marker over the shared SSH config implementation."""
from pocketdeploy.ssh_config import update as _update


def update(payload, home=None):
    return _update(payload, home, owner='WALTER')
