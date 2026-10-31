"""Seed rendered dotfiles into one home; sent to the remote stdlib program.

The controller renders getcolors/dotfiles' Ubuntu profile and sends
``{relative POSIX path: base64 content}``. Only missing files are seeded, and
only once: the stamp keeps existing homes and later edits untouched.
"""
import base64
import binascii
import os
from pathlib import Path, PurePosixPath
import tempfile

STAMP = '.local/state/walter/dotfiles'


def safe_path(path):
    for node in (path, *path.parents):
        if node.is_symlink():
            raise ValueError('dotfiles path contains a symlink')


def make_parents(home, target):
    """Create missing directories below home as 0700, including intermediate ones."""
    for parent in reversed(target.relative_to(home).parents[:-1]):
        try:
            os.mkdir(home / parent, 0o700)
        except FileExistsError:
            pass


def decode(files):
    """Validate the controller payload before any filesystem access."""
    if not isinstance(files, dict) or not files:
        raise ValueError('dotfiles payload is empty')
    decoded = {}
    for name, content in files.items():
        if not isinstance(name, str) or not isinstance(content, str):
            raise ValueError('invalid dotfiles payload')
        relative = PurePosixPath(name)
        if (not name or relative.is_absolute() or relative.as_posix() != name or '\\' in name
                or any(part in ('', '.', '..') for part in relative.parts)
                or any(ord(ch) < 32 for ch in name) or name == STAMP):
            raise ValueError('invalid dotfiles path')
        try:
            decoded[relative] = base64.b64decode(content, validate=True)
        except (binascii.Error, ValueError):
            raise ValueError('invalid dotfiles content') from None
    for relative in decoded:
        if any(parent in decoded for parent in relative.parents):
            raise ValueError('dotfiles path conflicts with another file')
    return decoded


def install(files, home=None):
    """Seed missing files once and return whether installation ran.

    Run as the destination user. Every destination is checked before any write.
    """
    files = decode(files)
    home = Path(home or Path.home()).absolute()
    safe_path(home)
    if not home.is_dir():
        raise ValueError('dotfiles home must exist')
    stamp = home / STAMP
    safe_path(stamp)
    if stamp.exists():
        if not stamp.is_file():
            raise ValueError('invalid dotfiles stamp')
        return False
    for relative in files:
        target = home / relative
        safe_path(target)
        for parent in target.parents:
            if parent == home:
                break
            if parent.exists() and not parent.is_dir():
                raise ValueError('dotfiles destination parent is not a directory')
        if target.exists() and not target.is_file():
            raise ValueError('dotfiles destination is not a regular file')
    # Exclusive link publication preserves files created by a concurrent process;
    # interrupted runs retry missing files without clobbering.
    for relative, content in files.items():
        target = home / relative
        safe_path(target)
        make_parents(home, target)
        if target.exists():
            continue
        fd, temporary = tempfile.mkstemp(prefix='.walter-dotfiles-', dir=target.parent)
        try:
            with os.fdopen(fd, 'wb') as stream:
                os.fchmod(stream.fileno(), 0o644)
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            try:
                os.link(temporary, target)
            except FileExistsError:
                pass
        finally:
            os.unlink(temporary)
    safe_path(stamp)
    make_parents(home, stamp)
    stamp.write_text('Ubuntu dotfiles seeded by Walter; existing files preserved.\n')
    return True
