# walter

A remote development machine, as a Package Skill.

Walter provisions one machine, writes it into `~/.ssh/config` so `ssh <profile>`
reaches it, confirms Ansible can talk to it, and powers it off and on so you are
not paying for it overnight.

```sh
./walter build              # render .colors/<profile>/ — contacts nothing
./walter create --dry-run   # print the graph — touches nothing
./walter create             # provision, and record the ssh alias
./walter stop               # power off
./walter start              # power on, and refresh the alias
./walter delete             # destroy, dropping the ssh block first
```

Desired state is `colors.yml`, found by walking up from wherever you run it.
Credentials never live there — they arrive as `COLORS_PAR_*` environment
variables. See `skills/package-walter-green/references/configuration.md`.

## Install it into a project

```sh
npx skills add getcolors/walter
cp .agents/skills/package-walter-green/walter walter
```

The root launcher is a **copy** of the skill payload, not a symlink, so
`npx skills update -p` leaves it behind — re-copy after every update or the
project keeps running the old pin while `skills-lock.json` claims the new one.

## What v1 does and does not do

It creates a machine and gets you onto it. The remote playbook is an
`ansible.builtin.ping` — it proves walter's own plumbing works and installs
nothing. Toolchains and dotfiles are a later playbook, and that stage is where
they land.

`stop` and `start` work on OCI. Everywhere else they report that the provider
has no power API walter can drive and exit 0. That is deliberate: no provider
should need a special case in the graph.

## Development

```sh
bb test                  # unit suite
bb golden                # every provider variant vs committed output
./scripts/launcher.sh    # the launcher, in environments this checkout is not
```

`bb golden` is the important one. Walter depends on ONCE's provider registry and
compute templates through a SHA pin, and nothing upstream promises that surface
will hold still — the golden diff is what turns a pin bump that changes output
into a loud failure instead of a silent one. Bump the pin deliberately, run
`bb golden`, and read the diff rather than accepting it.

`CLAUDE.md` covers the architecture and the invariants. `plans/0001-walter-v1.md`
covers why, including the designs that were rejected.
