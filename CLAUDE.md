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
- `io.github.getcolors/once` — **a package dependency, not a library one**; see
  "The reuse surface" below
- OpenTofu, Ansible, and the `oci` CLI for the power verbs

## Commands

```bash
./green build                         # render the work directory only
./green create --dry-run              # print the graph, touch nothing
./green stop | start                  # power cycle (OCI and Vultr)
bb test                              # the unit suite, under babashka
bb golden                            # every provider variant vs committed output
bb golden:accept                     # regenerate after an intended change
./scripts/launcher.sh                # the launcher, in environments this checkout is not
bb pin                               # stamp the launcher (maintainers, after a push)
```

`./green build -f other.yml` overrides the `colors.yml` found by walking up.

## The reuse surface — read this before touching anything

Walter consumes **exactly three things** from ONCE:

1. `io.github.getcolors.once.validate/providers` — the provider registry, as
   data.
2. The compute templates, by classpath keyword
   (`:io.github.getcolors.once.tools.tofu.<provider>/main.tf`).
3. `io.github.getcolors.once.ssh` — the SSH Keypair Standard's machinery
   (keygen-mode detection, paths, the create matrix, the account preflight,
   the delete cleanup), added when walter adopted the standard rather than
   re-implementing it as a third copy.

Everything else is walter's own — `tool-dir`, the inventory builder, both
Ansible stages, all of its step functions. That is deliberate and it is narrower
than it first looks like it should be. In particular **walter does not reuse
ONCE's `ansible-local`**, even though it could: that stage writes to
`~/.ssh/config` on the operator's workstation, and reusing it would mean an
unrelated change in ONCE rewriting that file at pin-bump time.

**Nothing upstream protects this surface.** ONCE's `utils/contract` versions the
*launcher* handshake, not a library API, and ONCE's own rules treat its
internals as free to change as long as three colours move together. There is no
promise here to rely on. `scripts/golden.sh` is the mitigation: it renders every
provider variant against the pinned ONCE and diffs it, so a pin bump that
changes rendered output fails loudly instead of silently.

Four things in `golden.sh` are not golden diffs and matter just as much. The
first two are the two halves of the one coupling that can fail during a real
apply:

- ONCE's template still declares `resource "oci_core_instance" "ampere_vm"`,
  because walter's `outputs.tf` references that address. A rename upstream would
  otherwise surface as an opaque `tofu validate` failure during a real apply,
  against live infrastructure, half way through a create.
- Walter's OCI `outputs.tf` still publishes `oci_core_instance.ampere_vm.id`
  from it. Losing this end breaks `stop` and `start`, which read the OCID rather
  than matching a display name.
- ONCE's Vultr template still declares `vultr_instance.node1`, and Walter's
  Vultr `outputs.tf` publishes its immutable id for the same reason.
- The compute stage is still named `walter-compute`.
- Providers walter cannot power cycle render **no** `outputs.tf` at all —
  checked against hcloud. The output only makes sense where the power verbs
  work, and rendering it elsewhere would reference a resource the template never
  declares.

**Bump the ONCE pin deliberately and rarely.** Nothing forces it. Run
`bb golden` immediately after, and read the diff rather than accepting it.

## Architecture

### The DAG

`wire-fn` returns a different graph per `:green/event` — the same mechanism ONCE
uses for `:delete`, which is why the two new verbs needed no engine change.

```text
create / build   start ─ github-token ─ compute ─ bootstrap ─┬─ ansible-local
                                                             └─ ansible-remote

delete           start ─ ansible-cleanup ─ compute

stop             start ─ power-off

start            start ─ power-on ─ ansible-local
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

Create and build fork after bootstrap; the two normal Ansible stages are
independent and neither joins. Bootstrap is a no-op except on Vultr. There it is
the sole root SSH connection: a fresh image exposes root, so Walter renames the
stock UID/GID 1000 account to `ubuntu` (or creates it when absent), installs its
dedicated key and passwordless sudo, validates
an sshd drop-in disabling root and password login, then reloads SSH. All normal
stages run as ubuntu. A later create probes ubuntu first, so convergence does
not depend on root access Walter already closed. Delete drops the managed ssh block before destroying, so a
machine that is already gone still cleans up the workstation.

### Why stop and start skip OpenTofu

ONCE's `tofu/oci/main.tf` renders no power state, and adding one would mean
either forking the template — forfeiting the reuse that motivated the design —
or pushing a variable ONCE never uses through three colours and a parity
fixture, in the repository running the live website.

Skipping OpenTofu is also the more honest design. An attribute the configuration
never sets produces no diff on refresh, so stopping the machine out of band
causes **no drift**: there is nothing to reconcile because power was never
managed. `prevent_destroy` stays irrelevant to `stop`.

The instance is found by an **immutable provider id, never by display name**.
Walter renders one extra `outputs.tf` beside ONCE's `main.tf` — OpenTofu merges
every `.tf` in a directory — publishing `oci_core_instance.ampere_vm.id` or
`vultr_instance.node1.id`. Finding by a human-typed name could power off another
project's server.

Every power call waits for the terminal state. OCI delegates that wait to the
CLI; Vultr polls the live HTTP API. A successful start then reads the live public
address and refreshes the managed SSH alias.

### Stages

| Step | Directory | Does |
|---|---|---|
| `:walter/github-token` | — | the device-flow token acquisition above; no directory, nothing rendered |
| `:walter/compute` | `walter-compute` | ONCE's provider template + walter's `outputs.tf` for OCI/Vultr; in keygen mode ONCE's template declares the profile-named key resource itself; outputs ip/user/sudoer/name |
| `:walter/ansible-bootstrap` | `walter-ansible-bootstrap` | Vultr only: root creates ubuntu + key + sudo, then disables root/password SSH; later creates enter as ubuntu |
| `:walter/ansible-local` | `walter-ansible-local` | the managed `Host <profile>` block in `~/.ssh/config`, with `IdentityFile`/`IdentitiesOnly` in keygen mode |
| `:walter/ansible-remote` | `walter-ansible-remote` | ping, unprivileged cloudflared sysctls, nix, terminfo, and — when the gating key is set — the gh login and git identity, packages, shell, runtimes, Emacs, dotfiles, agent credentials, atuin |
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

**The machine keypair is generated by default** (SSH Keypair Standard,
`workspace/standards/ssh-keypair.md`, contract 4): an absent machine-key value
selects keygen mode, where walter generates the profile-named
`~/.ssh/<profile>` on the first real create and delegates the standard's machinery to
`once.ssh` — the create matrix (a key without state, or state without a key,
refuses rather than regenerates), the DigitalOcean/hcloud/Vultr account
preflight (an unowned profile-named key refuses rather than adopts), and the
delete cleanup, wired as `:walter/ssh-cleanup` strictly after the compute
destroy. ONCE's compute templates render the provider key resource (named
after the profile) and the `private_key` connection themselves, so walter's
old `ssh-key.tf` sidecar and its short-lived ssh-agent are gone. An explicit
machine-key value is the opt-out (Vultr refuses one — the bootstrap needs the
generated private half); the retired `compute-keygen` flag gets a migration
error. On `:build` the paths and content are stable placeholders (ONCE's
deploy-key rule), so goldens stay byte-identical across workstations. A real
delete first rederives or regenerates missing key files so the destroy can
render, then removes them once it succeeds.

One consumer of the key is invisible in walter's own templates: ONCE's
compute templates carry a `remote-exec` "wait for ssh" provisioner whose
connection block names no key, so OpenTofu authenticates it through whatever
agent `SSH_AUTH_SOCK` points at — and nothing holds a key walter just
generated. `compute-step` therefore runs a real create's apply under its own
short-lived ssh-agent loaded with exactly that key
(`with-machine-key-agent`), killed when the apply returns. Without it the
provisioner dies with "attempted methods [none]" against a machine that is
otherwise fine — observed on the first live create, which is why this
exists.

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
  `validate` (rules over ONCE's registry), `oci` (the OCI CLI), `vultr` (the
  Vultr HTTP API), `github` (the device-flow token), `tools` (the steps), and
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
