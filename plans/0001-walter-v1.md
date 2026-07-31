# Walter v1 — a remote dev machine as a Package Skill

Written 2026-07-31, before the work. Like every `plans/` file in this stack it
records intent and rejected alternatives, not current behaviour. Read the code
before acting on anything here.

## What walter is

A Package Skill that provisions one remote development machine and operates it
day to day. It is the second package built on the Colors SDK, after ONCE, and
it exists partly to prove the SDK carries more than one package.

The layers are the same as ONCE's:

```text
green (SDK)  →  walter (package source)  →  walter-oci (the deployment)
```

`walter-oci` is to walter what `once-colors` is to once: a project holding
desired state and a copied launcher, not a clone of the package.

## Decisions

| Decision | Value |
|---|---|
| Colours | green only — no red, no blue, no parity harness |
| Steps reused from ONCE | compute, ansible-local, ansible-remote |
| Steps dropped | tofu-smtp, tofu-dns, smtp-post, github/deploy-keys |
| Verbs | `build` `create` `delete` `stop` `start` |
| Desired state | `colors.yml` — same schema and filename as ONCE, its own file |
| `compute-prevent-destroy` | unchanged; still defaults true, still needs `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` |
| Credentials | `COLORS_PAR_*`, the one namespace every package shares |
| OCI auth | session token + browser login, deliberately — temporary credentials over a static API key |
| Provider set | ONCE's compute registry unchanged; `stop`/`start` are no-ops off OCI |

Green-only removes the three-colour tax. It also removes `parity.sh`, which was
ONCE's golden-file regression net as much as its parity check — so walter builds
its own golden harness (below).

## The DAG

`wire-fn` returns a different graph per `:green/event`, exactly as ONCE's does
for `:delete`. No engine change is needed for the two new verbs.

```text
create / build   start ─ compute ─┬─ ansible-local
                                  └─ ansible-remote

delete           start ─ ansible-cleanup ─ compute

stop             start ─ power-off

start            start ─ power-on ─ ansible-local
```

`create` and `build` fork after compute; the two Ansible stages are independent
and neither joins. `delete` drops the managed `~/.ssh/config` block before
anything is destroyed, mirroring ONCE.

## Why stop and start skip OpenTofu entirely

The obvious design was a power state in desired state, rendered into the
instance resource and applied. It does not work, for a reason worth recording:

**ONCE's `tofu/oci/main.tf` renders no power state.** `oci_core_instance` is
declared with availability domain, compartment, shape, vnic, source, metadata,
connection, provisioner and lifecycle — and nothing to set `state` through.
Adding one means either forking the template into walter, which forfeits the
reuse that motivated the whole design, or pushing a variable ONCE never uses
through three colours and a parity fixture, in the repository running the live
website. Walter's one novel feature would be the one thing it could not reuse.

Skipping OpenTofu is not merely the cheaper option, it is the more honest one.
An attribute the configuration never sets produces no diff on refresh, so
stopping the instance out of band causes **no drift** — there is nothing for
OpenTofu to reconcile because it was never managing power. `prevent_destroy`
becomes irrelevant to `stop`, and the provider's schema drops off the critical
path.

The cost is that power state is imperative rather than desired. That is
acceptable: `describe` in ONCE is already a live read rather than a
convergence, and power was never in the file to begin with.

### Finding the instance

By OCID, never by display name. Walter renders one extra file into the compute
stage directory alongside ONCE's `main.tf` — OpenTofu merges every `.tf` in a
directory — declaring:

```hcl
output "instance_id" {
  value = oci_core_instance.ampere_vm.id
}
```

`oci_core_instance.ampere_vm` is a resource address ONCE's own template
comments call out as one that must not be renamed, which makes it the most
durable handle available.

The rejected alternative was `oci compute instance list --display-name`. It
targets whatever matches a human-typed string, in a compartment which for this
tenancy is the root — so a display name left as `once` would power off the
production website server. A configuration typo, not an exotic race.

Reading the OCID from OpenTofu outputs costs the property that stop/start work
when state is unreachable. `oci-instance-id` is therefore an optional flat key:
set it and walter uses it, leave it unset and walter reads the output. Same
shape and same rationale as `oci-image-id`, which ONCE documents as "set it to
the image you are actually running once the stack is real."

### The CLI calls

```sh
oci compute instance action --instance-id <ocid> --action SOFTSTOP \
    --wait-for-state STOPPED --max-wait-seconds 300
oci compute instance list-vnics --instance-id <ocid>
```

`--wait-for-state` is not optional. The CLI returns before the transition
completes, so without it `stop` reports success on a running box and `start`
hands `ansible-local` an address that is not up yet.

## The reuse surface

Walter consumes exactly two things from ONCE, and both are chosen to be as
stable as that repository has:

1. **`io.github.getcolors.once.validate/providers`** — the provider registry, as
   data. Walter drives its own validation over two of the four slots.
2. **The compute templates**, by classpath keyword
   (`:io.github.getcolors.once.tools.tofu.<provider>/main.tf`). This is the
   valuable part: the provider HCL, the pinned-image branch, the ForceNew
   commentary.

Everything else — `tool-dir`, the inventory builder, both Ansible stages, the
step functions themselves — walter owns. ONCE's spec helpers are private and
its `tofu-compute-step` hard-codes a single-spec vector that walter cannot add
its `outputs.tf` to, so the step is reimplemented over `green.tofu` directly.

That is a better dependency than the one originally sketched. Walter ends up
coupled to a **template resource and a resource address** — things a golden
diff can watch — rather than to function signatures ONCE is free to reshape.

There is no contract protecting this. `utils/contract` versions the *launcher*
handshake, not a library API, and ONCE's rules treat its internals as free to
change as long as three colours move together. The mitigation is the golden
harness, not a promise.

## Mitigations

Every one of these came out of the design review and is part of v1, not a
follow-up.

### Golden-file build tests

`scripts/golden.sh` runs `build` for each provider variant into a temp
directory and diffs against committed goldens. It is `parity.sh` minus the
cross-language half, and it is the mitigation for the coupling above: the
failure mode being guarded against is "bump the ONCE pin, something silently
changed," and a golden diff turns that into a loud failure at the moment the
pin moves.

Paired with an assertion that the rendered `main.tf` still contains
`resource "oci_core_instance" "ampere_vm"`, since walter's extra output
references that address and a rename upstream would otherwise surface as a
confusing `tofu validate` failure at apply time.

### Walter owns `ansible-local`

ONCE's version is reusable and walter copies it anyway. It is three files and a
17-line playbook; reusing it would mean an unrelated change in ONCE rewriting
`~/.ssh/config` on the operator's workstation at pin-bump time. Worst
risk-to-saving ratio in the design.

Copying also lets walter use its own marker — `# {mark} walter <alias> ANSIBLE
MANAGED BLOCK` — so a collision with ONCE's block is impossible rather than
merely unlikely.

### `COLORS_PAR_PROFILE` is rejected outright

`profile` is a flat key and `read-pars` overlays any flat key, so one
environment variable would point walter at `once-colors/…tfstate` in the same
bucket, compartment and subnet. Walter has no legitimate use for overriding the
profile from the environment — the profile identifies the project and the
project is the directory — so walter refuses when the variable is set at all,
rather than trying to detect a wrong value it cannot see (`read-pars` has
already overwritten the file's value before any step runs).

Defence in depth: walter's stage directories are `walter-compute`,
`walter-ansible-local`, `walter-ansible-remote`, so even a colliding profile
produces a state key that can never be ONCE's.

### Session pre-flight

`stop` and `start` on a stoppable provider check the OCI session before doing
anything:

```sh
oci session validate --profile <p> --local
```

`--local` is mandatory — plain `session validate` prompts "Do you want to
re-authenticate?" on failure, which hangs a non-interactive caller. The failure
message names the fix rather than describing the problem:

```
OCI session for profile DEFAULT has expired.
Run: bb ~/.claude/skills/refresh-oci-token/refresh-oci-token.clj
```

That skill is installed globally on this machine, so the path is stable.

### The launcher harness travels with the launcher

Walter copies ONCE's launcher pattern — a single Babashka file, the
`launcher-contract` handshake, `bb pin` — and copies `scripts/launcher.sh` with
it. Duplicating the one piece of code that cannot be tested from inside the
checkout without duplicating its harness would be the wrong half. ONCE's rule
that the launcher holds no logic of its own applies here too.

### Stale `ip` is a rule, not code

`start` calls `list-vnics` for the address it hands to `ansible-local`, so the
ssh config is always correct; the staleness lives only in OpenTofu state, whose
`ip` output is not refreshed by an out-of-band power cycle.

**Rule: outputs' `ip` is authoritative only immediately after an apply.**
Nothing outside the compute step reads it — delete removes the ssh block by
alias, not by address. A future `describe` must query OCI live rather than read
outputs.

Adding a `tofu refresh` to `start` was rejected: it puts a tofu invocation and
backend credentials on the fast path for a value nothing reads.

## Known limits of v1

- **The remote playbook is a ping.** It proves walter's own plumbing —
  inventory rendering, ansible.cfg, user and key resolution — which is what is
  most likely to be wrong in a new package. It does not prove the box is
  reachable; ONCE's `remote-exec` provisioner already blocked on that during
  apply. Actual dev tooling is a later playbook, and this stage is where it
  lands.
- **`create` will not restart a stopped instance.** With no `state` in the
  configuration there is no diff, so an apply leaves a stopped box stopped.
  `start` is the only way up. Walter emits a hint rather than appearing to
  succeed.
- **Nothing records that walter stopped anything.** No state, no `describe`;
  ask OCI. `describe` is the natural next verb, and the OCI query helpers are
  factored so it is a report rather than a refactor.
- **walter-oci shares once-colors' subnet, availability domain and OCI
  profile**, deliberately. The dev box inherits the production subnet's
  security list and can reach the website server over private IPs. One session
  refresh serves both projects.

## Rejected

- **A nested `walter:` block in a shared `colors.yml`.** Briefly considered when
  the two packages looked like they would share one file. They do not — separate
  directories, separate files — and the flat provider keys would otherwise have
  forced the dev box to take the website server's shape.
- **An API-key OCI profile** to keep `start` off the expiring session token.
  Rejected in favour of temporary credentials. The cost is bounded: `oci session
  refresh` extends in place without a browser, so the login is once per working
  session, not once per command, and the long verb (`create`) is the one that
  suffers from expiry, not the daily ones.
- **Extracting the compute templates into the green SDK.** Cleanest long-term
  story, and it breaks `parity.sh`'s resource-tree diff, forcing a three-colour
  change to a repository running live infrastructure for the benefit of a
  green-only package. Revisit if a second consumer appears.
