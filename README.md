# walter

A remote development machine, as a Package Skill.

Walter provisions one machine, writes it into `~/.ssh/config` so `ssh <profile>`
reaches it, confirms Ansible can talk to it, and powers it off and on so you are
not paying for it overnight.

```sh
./green build              # render .colors/<profile>/ — contacts nothing
./green create --dry-run   # print the graph — touches nothing
./green create             # provision, and record the ssh alias
./green stop               # power off
./green start              # power on, and refresh the alias
./green delete             # destroy, dropping the ssh block first
```

Desired state is `colors.yml`, found by walking up from wherever you run it.
Credentials never live there — they arrive as `COLORS_PAR_*` environment
variables. See `skills/package-walter-green/references/configuration.md`.

## Install it into a project

```sh
npx skills add getcolors/walter
cp .agents/skills/package-walter-green/green green
```

The root launcher is a **copy** of the skill payload, not a symlink, so
`npx skills update -p` leaves it behind — re-copy after every update or the
project keeps running the old pin while `skills-lock.json` claims the new one.

## What v1 does and does not do

It creates a machine, gets you onto it, and installs **nix**, a **terminfo entry
for Ghostty**, and **cloudflared kernel networking settings** — all on every
machine, gated on nothing. nix makes anything else one `nix profile install`
away; terminfo keeps `vim`, `top` and `less` working when `TERM` travels over
SSH; and the sysctls grant the login user's group ICMP sockets and QUIC-sized
buffers so `cloudflared` needs no sudo and emits no permissions or buffer
warnings.

Set `github-account` and `git-email` and the machine comes up with its own
GitHub identity: gh logged in, git cloning and pushing over https through it,
and the commit identity configured. No token lives in desired state — the
create *mints* one with GitHub's device flow as its very first action: a
one-time code to approve from any browser, and once approved the rest of the
run is unattended. A machine that already holds a login keeps it, so
re-creates stay non-interactive. The clone-bearing features
(`emacs-config-repo`, `clone-orgs`, `dotfiles-checkout`) authenticate through
this identity and refuse to build without it.

Set `compute-keygen: true` and walter also generates the machine-access
keypair per deployment — `~/.ssh/walter_<profile>` on your workstation —
feeds the public half to the provider, and pins `ssh <profile>` and Ansible
to it. Leave it out and you supply a key per provider, exactly as before.

Set `emacs-config-repo` (an https URL) and the remote playbook also installs
Emacs from a pinned nixpkgs and clones that configuration with the machine's
own token. Leave the key out and the rendered playbook does not mention Emacs
at all. Packages are not pre-fetched: the first interactive launch does that.

No agent forwarding, anywhere. Nothing on the machine authenticates with your
workstation's keys: GitHub work rides the machine's own token, and the only
private key involved in reaching the machine is the one walter generated for
exactly that.

Nothing else is installed. Other toolchains are the user's `nix profile
install`, not a walter feature.

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
