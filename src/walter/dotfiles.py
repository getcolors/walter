"""Install Ubuntu dotfiles from a checkout without invoking its launcher.

Only missing files are seeded. Existing home configuration and subsequent edits
are preserved. The supported template language is deliberately limited to the
profile conditionals used by getcolors/dotfiles; unknown syntax fails closed.
"""
import argparse
import os
from pathlib import Path
import re
import stat
import tempfile

TOKEN = re.compile(r'(\{%.*?%\}|\{\{.*?\}\})', re.DOTALL)
CONDITION = re.compile(r'if\s+profile\s*=\s*[\'"](ubuntu|macos)[\'"]')


def render(text):
    """Render the checkout's profile conditionals for Ubuntu."""
    output, stack = [], []
    active = True
    for part in TOKEN.split(text):
        if part.startswith(('{%', '{{')):
            tag = part[2:-2].strip()
            condition = CONDITION.fullmatch(tag)
            if condition and part.startswith('{%'):
                selected = condition[1] == 'ubuntu'
                stack.append((active, selected, False))
                active = active and selected
            elif tag == 'else' and stack and not stack[-1][2]:
                parent, selected, _ = stack[-1]
                stack[-1] = (parent, selected, True)
                active = parent and not selected
            elif tag == 'endif' and stack:
                active = stack.pop()[0]
            else:
                raise ValueError('unsupported dotfiles template syntax')
        elif active:
            output.append(part)
    if stack or any(token in ''.join(output) for token in ('{%', '{{')):
        raise ValueError('unterminated dotfiles template')
    return ''.join(output)


def safe_path(path):
    for node in (path, *path.parents):
        if node.is_symlink():
            raise ValueError('dotfiles path contains a symlink')


def install(checkout, home=None):
    """Seed Ubuntu resource files once and return whether installation ran.

    Run as the destination user. No YAML, package manager or template runtime is
    required. Checkout must contain getcolors/dotfiles' standard resource tree.
    """
    home = Path(home or Path.home()).absolute()
    checkout = Path(checkout).expanduser().absolute()
    safe_path(home)
    safe_path(checkout)
    if not home.is_dir():
        raise ValueError('dotfiles home must exist')
    stamp = home / '.local/state/walter/dotfiles'
    safe_path(stamp)
    if stamp.exists():
        if not stamp.is_file():
            raise ValueError('invalid dotfiles stamp')
        return False
    resource = checkout / 'src/resources/io/github/getcolors/dotfiles'
    common = resource / 'common'
    if not common.is_dir():
        raise ValueError('dotfiles checkout has no common resources')
    files = {}
    for directory in (common, resource / 'profiles/ubuntu'):
        safe_path(directory)
        if not directory.exists():
            continue
        for source in sorted(directory.rglob('*')):
            safe_path(source)
            if source.is_dir():
                continue
            if not stat.S_ISREG(source.stat().st_mode):
                raise ValueError('dotfiles source is not a regular file')
            relative = source.relative_to(directory)
            if relative in files:
                raise ValueError('duplicate dotfiles resource')
            target = home / relative
            safe_path(target)
            if target.exists() and not target.is_file():
                raise ValueError('dotfiles destination is not a regular file')
            files[relative] = render(source.read_text()).encode()
    # Preflight completes before mutation. Exclusive link publication preserves files created by a
    # concurrent process; interrupted runs retry missing files without clobbering.
    for relative, content in files.items():
        target = home / relative
        safe_path(target)
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
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
    stamp.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    stamp.write_text('Ubuntu dotfiles seeded by Walter; existing files preserved.\n')
    return True


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkout')
    parser.add_argument('--home')
    args = parser.parse_args()
    print('changed' if install(args.checkout, args.home) else 'unchanged')
