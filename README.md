# Walter

Walter provisions one Google Cloud development machine and its user environments
using Python and [PocketDeploy](https://github.com/pocketcontext/pocketdeploy).
There is no Terraform/OpenTofu, Ansible or Clojure orchestration. Nix, asdf, Git,
OpenSSH, Google Cloud CLI and downloaded tool installers remain external tools.

The new profile is **walter-gcp**. Existing Walter deployments and their state
are independent. There is no state migration, compatibility launcher, start or stop.

## Setup

Install uv, OpenSSH, gcloud and GitHub CLI. Authenticate Google Application Default
Credentials for the configured project and authenticate `gh` as `github-account`.
The portable `walter` launcher fetches an immutable package pin. Source development:

```sh
uv sync --locked --extra test
uv run walter --help
uv run walter init
uv run walter plan
uv run walter vault-save
uv run walter converge --verbose
uv run walter vault-save
uv run walter status
uv run walter ssh
uv run walter ssh --user rose
uv run walter converge-nix
uv run walter converge-asdf
```

Run without arguments for help. Configuration defaults to `colors.yml` in the
current directory; use `-f /absolute/path/colors.yml` to select another deployment.
`plan` and `converge --dry-run` read cloud resources without creating authority.
The plan describes infrastructure changes and intended user reconciliation;
it does not predict every package-manager change.

## Configuration

The existing `google-*` and tooling keys remain the desired-state vocabulary.
They normalize into PocketDeploy's `gcp-*` settings internally. `provider-compute`
is `google`; only Google Cloud is supported. The durable `profile` supplies the
resource-name prefix. New names use `<profile>-<component>-<purpose>`, omitting
redundant components, with immutable provider IDs and ownership labels recorded
separately. `COLORS_PAR_PROFILE` is refused.

Existing `google-network` and `google-subnet` are required infrastructure
references (both default to `default`). Region/zone must agree. Walter owns only
its VM, boot disk and firewall rules; it does not own the network or subnet.
SSH sources default to all IPv4 addresses; set `walter-ssh-sources` to narrower
CIDRs when appropriate. The shared HTTP/HTTPS firewall rule is disabled.
Inherited network firewall rules can grant broader access.

N4A uses ARM64 Ubuntu, `hyperdisk-balanced` and `GVNIC`. C4A `-lssd` shapes require
their fixed `google-local-ssd-count`; `local-ssd-scratch: true` initializes private
cache/build directories on disposable scratch. Persistent boot disks retain source
checkouts, credentials and `/nix`. The helper refuses partial disk loss, foreign
filesystems and unsafe ownership rather than formatting surviving data.

Walter uses PocketDeploy's `.colors.sqlite`, `.ssh/` authority and local lock.
One controller at a time is supported. There is no backend bucket. Missing state
or authority requires recovery, not adoption by name. Host bootstrap keys rotate
before application credentials are transmitted. `ssh-install` refreshes owned
primary/seat aliases; `ssh-uninstall` removes only aliases. `ssh` resolves the live
owned address and supports `--ssh-command` for explicit commands.

## Private settings

Every variable assigned in `.envrc.private` must start with **`COLORS_PAR_`**.
Keep the file private and ignored. Values never belong in `colors.yml`, command
arguments, logs or Git. Load private settings in your trusted shell; the CLI does
not source shell files. Atuin requires:

```sh
export COLORS_PAR_ATUIN_PASSWORD=""
export COLORS_PAR_ATUIN_KEY=""
```

Fill the values privately. `COLORS_PAR_*` overrides for supported configuration
fields follow the shared Colors convention. Unrelated environment variables are
not copied into desired state. Google and GitHub authenticate through their CLI
credential stores; VaultContext uses its already configured client session.

Convergence verifies the current `gh` account and transfers its token only when a
configured remote login lacks the expected GitHub identity. It does not create a
new GitHub OAuth grant. Tokens travel through SSH stdin, never argv or SQLite.
Deleting a VM does not revoke the underlying GitHub credential.

Optional agent seeding reads only the named credential files for Claude, Codex and
Pi, never transcript directories. Existing remote credential files are preserved.
Seat users have private homes and no sudo or Docker-group access. They share the
configured GitHub/agent identities and host networking; seats are not separate
cloud or service accounts. The primary user remains privileged.

## Convergence and recovery

The workflow is preflight → local keys → compute → verified host trust → remote
Python provisioning → local aliases. Remote users run with permanently dropped
privileges. Nix/asdf and standalone agent installers configure each home. `latest`
asdf versions resolve once per invocation and are shared across seats. Focused
commands operate on the recorded owned machine. Dotfiles use a small Python
renderer for the checkout's Ubuntu profile, preserving existing user files; the
checkout's old launcher is never executed. Emacs package warming is asynchronous;
inspect `~/.local/state/walter/emacs-packages.log` for completion.

`vault-id` selects a dedicated vault; `vault-command` selects its installed CLI.
The user must authenticate and unlock it. `vault-save` is an explicit checkpoint
of private bindings, operator/bootstrap keys, known hosts and SQLite. It does not
back up the remote home directories or boot volume. Preserve remote work separately.
Save after successful mutations and after failures that changed resource state.

```sh
walter vault-restore --document DOCUMENT --version VERSION \
  --destination /absolute/recovery/directory
```

Place matching Git configuration in the destination first. Restore never runs a
deployment or sources private bindings. Review `plan` before takeover. Old recovery
checkpoints may require reconciliation against live resources.

Deletion requires `compute-prevent-destroy: false`. Review `delete --dry-run`,
then `delete` removes owned compute, boot storage, firewall rules and local SSH
keys/aliases. It retains the SQLite receipt, configuration and Vault history.
Deletion destroys remote work; recovery checkpoints do not contain that work.

## Development

```sh
uv sync --locked --extra test
uv run pytest -q
uv build
uv run python scripts/test-launcher.py
```

Synthetic tests cover configuration, controller behavior, secrets, privilege
boundaries, local aliases and scratch recovery. Shared cloud/SSH tests live in
PocketDeploy. Live verification is recorded separately in docs/verification.md.
