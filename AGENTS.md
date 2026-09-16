# CLAUDE.md

This file describes the `walter` codebase for AI assistants. Read it before
making changes.

## What this is

`walter` provisions and operates one remote **development machine** with
OpenTofu and Ansible, and powers it off and on. It is a Package Skill built on
[`green`](https://github.com/getcolors/green), the same SDK ONCE is built on,
and it is the second package on that SDK.

Unlike ONCE, walter is **green only**. There is no red, no blue, and no parity
harness. That removes the three-colour tax and also removes `parity.sh`, which
was ONCE's golden-file regression net as much as its parity check —
`scripts/golden.sh` is walter's replacement and is load-bearing.

The repository ships two things from one file:

- **The launcher** `skills/package-walter-green/green`, a single Babashka
  script. `./green` in the root is a symlink to it.
- **The `package-walter-green` skill**, whose payload is that launcher.

`plans/0001-walter-v1.md` records why the design is what it is, including the
alternatives that were rejected and why. It is history, not specification — read
the code before acting on it.

## Tech stack

- Clojure 1.12.5, plus Babashka for the launcher
- `io.github.getcolors/green` — the workflow engine
- `io.github.getcolors/colors-compute` — versioned compute, remote backend, SSH ownership and coordinated power library
- OpenTofu, Ansible, and the `oci` CLI for the power verbs

## Commands

```bash
./green build                         # render the work directory only
./green create --dry-run              # print the graph, touch nothing
./green stop | start                  # power cycle (OCI and Vultr)
./green converge-nix | converge-asdf  # focused tooling convergence on every login
bb test                              # the unit suite, under babashka
bb golden                            # every provider variant vs committed output
bb golden:accept                     # regenerate after an intended change
./scripts/launcher.sh                # the launcher, in environments this checkout is not
bb pin                               # stamp the launcher (maintainers, after a push)
```

`./green build -f other.yml` overrides the `colors.yml` found by walking up.

## Compute library boundary

Walter depends on colors-compute's Green library. It owns provider selection,
shared/node templates, S3/R2 state and coordination, SSH registration/keypair
ownership, and power transports. New providers require only a dependency bump.
The package supplies one public-only node and SSH ingress; all application
Ansible plays, inventories, GitHub device flow and local SSH config remain here.

Build renders library documents under `walter-compute/shared` and
`walter-compute/nodes/0`, including credential-free backend JSON. Runtime uses
library workspaces and owned state. Existing `profile/walter-compute.tfstate`
requires explicit migration; no automatic adoption or missing-state fallback.
Golden checks cover both key modes, S3/R2, normalized logins and focused aliases.

## Architecture

### The DAG

`wire-fn` returns a different graph per `:green/event` — the same mechanism ONCE
uses for `:delete`, which is why the two new verbs needed no engine change.

```text
create / build   start ─ github-token ─ compute ─ bootstrap ─ seats ─┬─ ansible-local
                                                                     └─ ansible-remote

delete           start ─ ansible-cleanup ─ compute

stop             start ─ power-off

start            start ─ power-on ─ ansible-local

converge-nix     start ─ converge-nix
converge-asdf    start ─ converge-asdf
```

`github-token` is the one interactive step walter has, and it is first on
purpose: **the workflow is interactive at the beginning only**. On a real
create with `github-account` set and no logged-in machine (probed over the
managed alias), it runs GitHub's device flow on the controller — `gh` owns the
terminal, sandboxed into a per-profile `GH_CONFIG_DIR` under
`~/.local/state/walter/github-token-<profile>` so the operator's own gh login
is untouched — verifies the token belongs to the named account, and stashes it
as a *file path* in opts, never the token itself (ONCE's deploy-key rule).
`ansible-remote` feeds the file to the machine over stdin and deletes the
sandbox **only once the machine is seeded**: a create that fails part-way
keeps it, deliberately, and the retry reuses the surviving token — after
re-verifying the account — instead of asking for a second code. On build,
delete, dry-run, and projects without the key it passes through untouched.

Create and build fork after the seat stage; the two normal Ansible stages are
independent and neither joins. `seats` exists only when `users` names extra
logins: it creates each seat — a real unix user with a private `0700` home,
the primary login's authorized keys, and **no sudo** (a sudoer can read every
home, which would delete the isolation) — connecting as the primary login
with become, which is why it must follow the Vultr bootstrap and precede the
remote play, whose inventory carries one host per login so the same play
provisions every home as its own user. The remote play's machine-scoped tasks
(`sysctls`, `sshd` drop-in, the nix install, the SSH-reload handler) are
`run_once` and delegated to the primary host — the only sudoer — and the two
passwd writes (`/etc/shells`, the login shell) delegate per host for the same
reason. The cloudflared `ping_group_range` spans the lowest to the highest
login gid, because the sysctl takes one contiguous range. Bootstrap is a
selected by the normalized node login. Root-login images use the same stage:
Walter adopts UID/GID 1000 as ubuntu, authorizes the managed key or preserves
existing external root authorized keys, installs sudo, then disables root and
password SSH. Later creates probe ubuntu first. Non-root logins pass through.

### Coordinated power

`compute-power/power-deployment` owns OCI/Vultr transport, bounded waits and
refresh. It acquires existing deployment coordination and uses only the
immutable provider ID from owned node state. Unsupported providers and legacy
instance-ID overrides refuse. A successful start refreshes the local aliases
from the observed address; uncertain failures retain coordination for recovery.
Power tests are offline transport/state-machine checks, not live deployment proof.

### Stages

| Step | Directory | Does |
|---|---|---|
| `:walter/github-token` | — | the device-flow token acquisition above; no directory, nothing rendered |
| `:walter/compute` | `walter-compute` | library singleton orchestration with shared/node remote state and normalized outputs |
| `:walter/ansible-bootstrap` | `walter-ansible-bootstrap` | normalized root login: bootstrap ubuntu + key + sudo, then disable root/password SSH; later creates enter as ubuntu |
| `:walter/ansible-seats` | `walter-ansible-seats` | only with `users`: creates each seat login — no sudo, `0700` home, the primary login's authorized keys — as the primary login with become; renders nothing without seats |
| `:walter/ansible-local` | `walter-ansible-local` | the managed `Host <profile>` block in `~/.ssh/config` plus one `Host <profile>-<seat>` block per seat, with `IdentityFile`/`IdentitiesOnly` in keygen mode |
| `:walter/ansible-remote` | `walter-ansible-remote` | ping, unprivileged cloudflared sysctls, nix, terminfo, and — when the gating key is set — the gh login and git identity, packages, shell, runtimes, Emacs, dotfiles, agent credentials, atuin; with seats, one inventory host per login so every home is provisioned as its own user |
| `:walter/converge-nix` | `walter-converge-nix` | through the managed aliases, ensures declared Nix entries exist and advances only those entries on the primary login and every seat |
| `:walter/converge-asdf` | `walter-converge-asdf` | through the managed aliases, installs exact declared asdf versions and refreshes Corepack shims on every login |
| `:walter/emacs-packages` | `walter-emacs-packages` | starts the ELPA/MELPA bootstrap and does **not** wait for it |

### The GitHub identity and the machine keypair

Two opt-in features changed walter's credential story; both invariants below
are deliberate reversals of v1's.

**`github-account` + `git-email`** put the machine's own GitHub identity on
it: gh logged in with the device-flow token, `gh auth setup-git` making that
token git's https credential, `user.name`/`user.email` configured. Every
clone in the remote play — Emacs config, `clone-orgs`, the dotfiles checkout
it feeds — rides this over https, and validate.clj refuses those keys without
the identity, and refuses an ssh:// or git@ `emacs-config-repo` outright. The
`clone-orgs` listing is authenticated too (`gh api --paginate --slurp`), so it
sees private repositories and complete organisations; the old anonymous
one-page-of-100 refusal is retired. **There is no ssh key for GitHub and no
agent forwarding anywhere** — both ansible.cfg files and the managed
ssh-config block dropped `ForwardAgent`. The machine holds its own token and
nothing of the workstation's; deleting the machine does not revoke the token
(GitHub Settings → Applications → GitHub CLI does).

**The library generates the machine keypair by default.** Presence of the
selected provider SSH setting opts out; there is no compute-key-mode flag.
Build uses stable placeholders. Real lifecycle records intent before generation,
refuses unowned collisions or missing owned keys, and removes keys only after
confirmed resource destruction. Delete never repairs or regenerates a keypair.
Local SSH configuration uses a package-owned locked atomic updater with legacy
Walter primary/seat marker migration. Only managed mode emits IdentityFile and
IdentitiesOnly; external private paths may still be used by Ansible.

`nix profile add` runs with `NIXPKGS_ALLOW_UNFREE=1` and `--impure` so unfree
attributes (`claude-code`) install beside free ones in the one invocation that
lets nix resolve the set together. The two flags need each other — flake
evaluation is pure by default and would ignore the variable — and the cost is
that the licence check is relaxed for the whole list.

`emacs-packages` is the one step walter starts without waiting for. `async` with
`poll: 0` daemonizes the job, so it outlives the play, the SSH connection and the
create — **the graph finishing is not the machine being finished**, which is true
nowhere else here. Nothing downstream reads the result, so waiting would buy only
the ability to fail a create on an ELPA outage, which the remote play already
refused when it left packages unfetched; what moves is *when* the wait happens,
off the first interactive launch where Emacs shows nothing for minutes.

Two traps live in that stage. `--batch` implies `-q`, so an `--init-directory`
without an explicit `-l init.el` sets `user-emacs-directory`, leaves
`user-init-file` nil, installs nothing and exits 0 in under a tenth of a
second — indistinguishable from an already-warm cache. And the log's exit status
is saved to `rc` *before* the timestamp is taken, because bash expands `$(date)`
first and it always succeeds, so reading `$?` after it reports 0 for every
failure. On a job nobody waits for, that log is the only diagnostic there is.

It is gated in Clojure rather than in the template, unlike every other optional
block: those are tasks inside a play that runs regardless, where this is the
whole stage, so a project with no `emacs-config-repo` renders no directory at
all. Delete skips it too — the packages go with the boot volume.

`seed-agent-credentials` copies one credential file per named agent from the
controller, never the directory around it: `validate/agent-credential-paths` is
the registry, and those directories are mostly session transcripts. The guard
is `force: false` rather than a `~/.local/state/walter` stamp, deliberately — the
credential file is its own evidence, and it is the only guard that also refuses
to clobber a login made on the machine directly. These are OAuth refresh tokens
rotated in place, so both overwrites matter.

Claude Code has one non-credential companion to that copy: interactive startup
checks `hasCompletedOnboarding` in `~/.claude.json` separately, and otherwise
shows login methods even while `claude auth status` recognizes the copied bearer
tokens. When the controller's Claude credential exists, the playbook atomically
adds that key as `true` only if it is absent. It never copies the workstation's
large, machine-local `~/.claude.json`, and it preserves an existing value — true
or false — rather than overriding a choice made on the machine.

`clone-orgs` names GitHub organisations, never repositories, and checks each
one's source repositories out under `~/code/<org>/<repo>`. The list is read from
GitHub's API **on the machine at create time** rather than rendered, which is the
point of the key: a repository added upstream arrives on the next create with
nothing in desired state to keep in step. The listing rides the machine's gh
login (`gh api --paginate --slurp`), so it sees what the account sees —
private repositories included — and complete organisations of any size. Forks
are dropped by the API's `type=sources`; archived repositories are skipped
with an Ansible `when:`, so the run names what it passed over. Same
`update: false` and https as the Emacs clone above, for the same reasons.

`dotfiles-checkout` runs that checkout's existing `./green create` after
`clone-orgs`, using the checkout's own `colors.yml`. Walter supplies only
`COLORS_PAR_DOTFILES_PREVENT_OVERWRITE=false`; it never overlays profile. A
successful run is stamped under `~/.local/state/walter` and is not repeated by
later creates.

The Emacs half is gated in the **template**, with Selmer's `<% if %>`, not with
an Ansible `when:`. A project that names no repository therefore renders a
playbook that does not mention Emacs at all, which is what `scripts/golden.sh`
holds still; the fixture sets both keys so the eight goldens cover the other
branch, and `tools_test` covers the absence once rather than eight times.

The stage names are load-bearing. Remote state is keyed `<profile>/<tool>`, and
naming the stage `walter-compute` rather than `tofu-compute` means a colliding
profile still cannot address another package's state.

### Secrets and the profile guard

Credentials use `COLORS_PAR_*`, the namespace every package in this stack
shares, and travel in the process environment — never into a rendered file.

**`COLORS_PAR_PROFILE` is rejected outright.** `profile` is a flat key and
`read-pars` overlays any flat key, so one environment variable would point
walter at another project's OpenTofu state. Walter refuses when it is set rather
than checking for a wrong value, because `read-pars` has already overwritten the
file's value before any step runs. Do not add an escape hatch for this.

### The `ip` rule

**Outputs' `ip` is authoritative only immediately after an apply.** An
out-of-band power cycle does not refresh OpenTofu state, so the stored address
can be stale. `power-on-step` reads the address live from OCI for exactly this
reason. Nothing else reads `ip` from outputs — delete removes the ssh block by
alias, not by address. A future `describe` must query live rather than read
outputs.

## Code conventions

- **Namespaces**: `io.github.getcolors.walter.*` — `utils` (contract, alias),
  `validate` (application rules), `compute` (singleton requirements),
  `github` (the device-flow token), `tools` (the steps), and
  `workflow` (the graph). A new namespace needs a genuinely new concern.
- **Keys**: plain kebab-case keywords for desired state (they match template
  variable names); namespaced for engine state (`:green/…`, `:walter/…`).
- **Steps** take `opts` and return `opts`, reporting failure through
  `:green/exit` / `:green/err` rather than throwing.
- **Anything that shells out gets a runner arity.** `oci.clj`'s functions all
  take an injectable runner in their second arity, so the tests cover the
  parsing and the decisions without starting a process. Preserve that split —
  it is the only reason the power verbs are testable at all.
- **The launcher holds no logic.** Validation, the graph and the steps live
  where the tests reach them; a copied payload is the one place code cannot be
  tested. `scripts/launcher.sh` enforces this.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable so one Analytics
property can separate repositories, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

## Git

Work on the current branch. Do not commit or push unless explicitly asked.

`walter-sha` in the launcher is managed by `bb pin` — **never hand-edit it, and
never invent a SHA.** `pin` reads the HEAD of the checkout surrounding it and
refuses a dirty or unpushed tree, so the sequence for a change that consumers
need is: commit, push, `bb pin`, commit the stamp, push again. The stamp names
the commit *before* it, which is correct — it points at the library code, and
the stamp commit only rewrites the payload that fetches it.

Consumers hold a **copy** of the payload, not a symlink, so re-copy it into
every project after a repin or they keep running the old pin:

```sh
cp skills/package-walter-green/green ../walter-oci/green
cp skills/package-walter-green/green ../walter-oci/.agents/skills/package-walter-green/green
```

The never-migrate rule has one narrow exemption: an existing deployment may
receive a pin whose only consumer-visible effect is new launcher verbs. Both
conditions below must hold over the cumulative delta from the deployment's
pinned SHA to the candidate SHA; a later launcher-only commit does not hide an
earlier template change.

1. The change allowlist contains only command registration, the focused events'
   own wiring, steps and resources, tests, and documentation. Any change to an
   existing event's steps, templates, validation, credentials, or behaviour
   disqualifies the bump even when rendered bytes happen to match.
2. Render the deployment's own `colors.yml` at both SHAs with
   `WALTER_LIB_ROOT`, using a distinct `COLORS_PAR_WORKDIR` for each, then run
   `diff -qr --exclude=walter-converge-nix --exclude=walter-converge-asdf`
   over the two profile directories. Exit 0 is required. This proves build-
   render equivalence, not create equivalence; the allowlist covers shared
   stages that branch on `:green/event`.

```sh
# Repeat the first two commands for <deployment-pinned-sha> and <candidate-sha>.
git -C ~/code/getcolors/walter worktree add "$tmp/src-$sha" "$sha"
(cd <deployment> && WALTER_LIB_ROOT="$tmp/src-$sha" \
  COLORS_PAR_WORKDIR="$tmp/out-$sha" ./green build)
diff -qr --exclude=walter-converge-nix --exclude=walter-converge-asdf \
  "$tmp/out-<pinned>/<profile>" "$tmp/out-<candidate>/<profile>"
```

Use temporary worktrees and workdirs so stale output cannot contaminate the
comparison. `WALTER_LIB_ROOT` is only a qualification aid: it exercises a
working-tree library, not the stamped artifact a deployment ultimately runs.
This change itself does not qualify because `tasks/nix-packages.yml` is also
rendered by create. In particular, a deployment pinned at `d743117` has no
qualifying path to this branch; no rollout is implied.
