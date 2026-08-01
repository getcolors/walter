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

- **The launcher** `skills/package-walter-green/walter`, a single Babashka
  script. `./walter` in the root is a symlink to it.
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
bb walter build                      # render the work directory only
bb walter create --dry-run           # print the graph, touch nothing
bb walter stop | start               # power cycle (OCI only)
bb test                              # the unit suite, under babashka
bb golden                            # every provider variant vs committed output
bb golden:accept                     # regenerate after an intended change
./scripts/launcher.sh                # the launcher, in environments this checkout is not
bb pin                               # stamp the launcher (maintainers, after a push)
```

`bb walter build -f other.yml` overrides the `colors.yml` found by walking up.

## The reuse surface — read this before touching anything

Walter consumes **exactly two things** from ONCE:

1. `io.github.getcolors.once.validate/providers` — the provider registry, as
   data.
2. The compute templates, by classpath keyword
   (`:io.github.getcolors.once.tools.tofu.<provider>/main.tf`).

Everything else is walter's own — `tool-dir`, the inventory builder, both
Ansible stages, all three step functions. That is deliberate and it is narrower
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
- Walter's `outputs.tf` still publishes `oci_core_instance.ampere_vm.id` from
  it. Losing this end breaks `stop` and `start`, which read the OCID rather than
  matching a display name.
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
create / build   start ─ compute ─┬─ ansible-local
                                  └─ ansible-remote

delete           start ─ ansible-cleanup ─ compute

stop             start ─ power-off

start            start ─ power-on ─ ansible-local
```

Create and build fork after compute; the Ansible stages are independent and
neither joins. Delete drops the managed ssh block before destroying, so a
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

The instance is found by **OCID, never by display name**. Walter renders one
extra `outputs.tf` beside ONCE's `main.tf` — OpenTofu merges every `.tf` in a
directory — publishing `oci_core_instance.ampere_vm.id`. Finding it by display
name would target whatever matches a human-typed string in a compartment that is
often a tenancy root, so a name copied from another project would power off that
project's server.

`--wait-for-state` is not optional in any power call. The CLI returns before the
transition completes.

### Stages

| Step | Directory | Does |
|---|---|---|
| `:walter/compute` | `walter-compute` | ONCE's provider template + walter's `outputs.tf`; outputs ip/user/sudoer/name |
| `:walter/ansible-local` | `walter-ansible-local` | the managed `Host <profile>` block in `~/.ssh/config` |
| `:walter/ansible-remote` | `walter-ansible-remote` | ping, nix, terminfo, and — when the gating key is set — packages, shell, runtimes, Emacs, dotfiles, agent credentials, atuin |

`nix profile add` runs with `NIXPKGS_ALLOW_UNFREE=1` and `--impure` so unfree
attributes (`claude-code`) install beside free ones in the one invocation that
lets nix resolve the set together. The two flags need each other — flake
evaluation is pure by default and would ignore the variable — and the cost is
that the licence check is relaxed for the whole list.

`seed-agent-credentials` copies one file per named agent from the controller,
never the directory around it: `validate/agent-credential-paths` is the registry,
and those directories are mostly session transcripts. The guard is `force: false`
rather than a `~/.local/state/walter` stamp, deliberately — the credential file
is its own evidence, and it is the only guard that also refuses to clobber a
login made on the machine directly. These are OAuth refresh tokens rotated in
place, so both overwrites matter.

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
  `validate` (rules over ONCE's registry), `oci` (the CLI), `tools` (the steps),
  `workflow` (the graph). Adding a sixth needs a genuinely new concern.
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
cp skills/package-walter-green/walter ../walter-oci/walter
cp skills/package-walter-green/walter ../walter-oci/.agents/skills/package-walter-green/walter
```
