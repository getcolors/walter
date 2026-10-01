#!/usr/bin/env python3
"""Exercise rendered asdf/agent tasks with local fake downloads, never real installs."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import yaml

ROOT = Path(__file__).resolve().parents[1]

with tempfile.TemporaryDirectory(prefix='walter-tooling-') as temporary:
    root = Path(temporary)
    config = yaml.safe_load((ROOT / 'colors.yml').read_text())
    config['workdir'] = str(root / 'render')
    state = root / 'colors.yml'
    state.write_text(yaml.safe_dump(config))
    subprocess.run([str(ROOT / 'green'), 'build', '-f', str(state)], cwd=ROOT,
                   check=True, stdout=subprocess.DEVNULL)
    stage = root / 'render/build' / config['profile'] / 'walter-ansible-remote'
    home = root / 'home'
    bin_dir = home / '.nix-profile/bin'
    bin_dir.mkdir(parents=True)
    (home / '.config/fish/conf.d').mkdir(parents=True)
    (home / '.asdf/shims').mkdir(parents=True)
    asdf = bin_dir / 'asdf'
    asdf.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
home = pathlib.Path(os.environ['HOME'])
a = sys.argv[1:]
with (home / 'calls.jsonl').open('a') as f: f.write(json.dumps(a) + '\\n')
if a[0] == 'latest':
 print({'nodejs': '25.1.0', 'bun': '1.4.0', 'uv': '0.12.7', 'python': '3.14.0'}[a[1]])
elif a[:2] == ['plugin', 'add']: print('already added')
elif a[:2] == ['which', 'corepack']:
 sys.exit(0 if (home / '.asdf/shims/corepack').exists() else 1)
elif a[:2] == ['exec', 'npm']:
 p = home / '.asdf/shims/corepack'
 p.write_text('#!/bin/sh\\nexit 0\\n'); p.chmod(0o755)
elif a[0] in ('install', 'set'):
 assert a[-1] != 'latest', 'latest must resolve before install and selection'
''')
    asdf.chmod(0o755)
    curl = bin_dir / 'curl'
    curl.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
home = pathlib.Path(os.environ['HOME'])
if os.environ.get('FAIL_DOWNLOAD'): sys.exit(22)
url = sys.argv[-1]
name = 'pi' if 'pi.dev/' in url else 'codex' if 'chatgpt.com/' in url else 'claude'
with (home / 'downloads').open('a') as f: f.write(name + '\\n')
print('mkdir -p "$HOME/.local/bin"')
print("printf '#!/bin/sh\\\\necho test-version\\\\n' > \\\"$HOME/.local/bin/" + name + "\\\"")
print('chmod +x "$HOME/.local/bin/' + name + '"')
''')
    curl.chmod(0o755)
    tasks = []
    for filename in ('asdf.yml', 'agents.yml'):
        # Package-manager prerequisites are machine-scoped and intentionally
        # excluded: this probe must never sudo or change the controller.
        tasks.extend(t for t in yaml.safe_load((stage / filename).read_text())
                     if 'ansible.builtin.apt' not in t)
    play = root / 'test.yml'
    play.write_text(yaml.safe_dump([{'hosts': 'all', 'gather_facts': False,
        'vars': {'ansible_env': {'HOME': str(home), 'PATH': str(bin_dir) + ':' + os.environ['PATH']}},
        'tasks': tasks}]))
    env = dict(os.environ, HOME=str(home))
    command = ['ansible-playbook', '-i', 'localhost,', '-c', 'local', str(play)]
    for _ in range(2):
        result = subprocess.run(command, env=env, text=True, capture_output=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
    calls = [json.loads(line) for line in (home / 'calls.jsonl').read_text().splitlines()]
    for name, version in [('nodejs', '25.1.0'), ('bun', '1.4.0'), ('uv', '0.12.7'), ('python', '3.14.0')]:
        assert calls.count(['latest', name]) == 2
        assert calls.count(['install', name, version]) == 2
        assert calls.count(['set', '--home', name, version]) == 2
    assert (home / 'downloads').read_text().splitlines() == ['pi', 'codex', 'claude']
    assert calls.count(['exec', 'npm', 'install', '--global', 'corepack']) == 1
    (home / '.local/bin/pi').unlink()
    failed = subprocess.run(command, env=dict(env, FAIL_DOWNLOAD='1'), text=True, capture_output=True)
    assert failed.returncode != 0, 'a failed curl must fail the play'
    assert not (home / '.local/bin/pi').exists()
    print('Tooling smoke: exact latest resolution, Corepack, installer guards and download failure passed')
