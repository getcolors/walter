# colors.yml for walter

A single flat YAML map, found by walking up from the working directory. Keys
arrive as kebab-case keywords. Credentials never live here.

## Always required

| Key | Meaning |
|---|---|
| `profile` | Names the work directory, the OpenTofu state keys and the `~/.ssh/config` Host alias. Must be unique across projects on the machine. |
| `workdir` | Where walter renders, resolved next to `colors.yml`. Conventionally `.colors`. |
| `provider-compute` | `oci` \| `hcloud` \| `digitalocean` \| `yandex` \| `no-infra` |
| `provider-backend` | `local` \| `s3` \| `r2` |
| `compute-prevent-destroy` | `true` or `false`. Renders `lifecycle { prevent_destroy = … }`. |

### On `profile`

It is the only thing separating this project's OpenTofu state from another's.
Remote state is keyed `<profile>/walter-compute.tfstate`, so two walter projects
sharing a bucket must not share a profile. The stage name is walter-specific, so
a walter project can safely share a bucket with an ONCE deployment even on the
same profile — but do not rely on that, name the profile after the directory.

**`COLORS_PAR_PROFILE` is rejected.** Walter refuses to start when it is set.
There is no legitimate use: overriding the profile from the environment can only
point walter at state that belongs to something else.

## Power

| Key | Meaning |
|---|---|
| `power-wait-seconds` | How long to wait for a power transition. Default 300. |
| `oci-instance-id` | Optional. The OCID `stop`/`start` act on. |

`oci-instance-id` is an escape hatch, not a normal setting. Left unset, walter
reads the instance id from the compute stage's `instance_id` OpenTofu output,
which needs the state backend to be reachable. Setting it — copy what `tofu
output instance_id` reports in the stage directory — means power cycling keeps
working when the backend does not, so a broken bucket cannot strand the user
with a running machine they cannot stop.

Only `oci` can be power cycled. Every other provider makes `stop` and `start` a
reported no-op.

## Compute providers

Only the selected provider's keys are required.

**oci** — authenticates from `~/.oci/config`, no `COLORS_PAR_*` of its own.

```
oci-config-file-profile   oci-subnet-id            oci-compartment-id
oci-availability-domain   oci-display-name         oci-shape
oci-ocpus                 oci-memory-in-gbs        oci-boot-volume-size-in-gbs
oci-boot-volume-vpus-per-gb                        oci-ssh-authorized-keys
```

`oci-image-id` is optional and worth setting once the machine is real. Left
unset, the newest compatible Canonical Ubuntu 24.04 image is used — convenient
first time, a moving target afterwards. The image id forces replacement, so with
`compute-prevent-destroy: true` a later apply **fails** rather than destroying
anything. Safe, and confusing if you do not know why.

`oci-ssh-authorized-keys` is a **path** to a public key file, read by OpenTofu at
plan time. Not the key material.

**hcloud** — `COLORS_PAR_HCLOUD_TOKEN`

```
hcloud-name  hcloud-image  hcloud-server-type  hcloud-location  hcloud-ssh-keys
```

**digitalocean** — `COLORS_PAR_DO_TOKEN`

```
digitalocean-name  digitalocean-region  digitalocean-size
digitalocean-image digitalocean-ssh-keys
```

**yandex** — `COLORS_PAR_YANDEX_TOKEN`, and `compute-pubkey` holding the public
key content.

```
yandex-cloud-id  yandex-folder-id  yandex-zone      yandex-image-family
yandex-name      yandex-subnet-cidr yandex-platform-id
yandex-cores     yandex-memory-gb  yandex-core-fraction yandex-disk-size-gb
```

**no-infra** — an existing machine walter configures but does not provision.

```
no-infra-compute-ip  no-infra-compute-user  no-infra-compute-sudoer
no-infra-compute-uid
```

## Editor

| Key | Meaning |
|---|---|
| `emacs-config-repo` | Optional. A git URL. Set it and the machine gets Emacs plus this configuration; leave it out and neither is mentioned. |
| `emacs-config-dest` | Where the clone lands. Defaults to `~/.config/emacs`. |

The default destination is the XDG path Emacs 29+ reads on its own. A
configuration that lives anywhere else needs `--init-directory` to reach it, so
set this key to say where — `~/.config/neoemacs`, say — and launch with
`emacs --init-directory ~/.config/neoemacs`.

Prefer an `ssh://`-style URL (`git@github.com:you/emacs.d.git`). The clone runs
over the agent walter already forwards, so no private key is written to the
machine and the working copy can push back. An HTTPS URL clones fine and is
read-only.

It is cloned **once**. A later `create` leaves an existing checkout alone, so
edits made on the machine are never discarded — pulling is the user's call.

## State backends

| Backend | Keys | Credentials |
|---|---|---|
| `local` | — | — |
| `s3` | `s3-bucket` `s3-region` | ambient AWS chain |
| `r2` | `r2-bucket` `r2-endpoint` | `COLORS_PAR_R2_ACCESS_KEY_ID` `COLORS_PAR_R2_SECRET_ACCESS_KEY` |

`local` keeps state in the work directory, which is generated output — fine for
a machine you can recreate, wrong for one you cannot. Use a remote backend for
anything you would be annoyed to lose.

## What walter renders

```
<workdir>/<profile>/
├── walter-compute/          backend.tf.json  main.tf  [outputs.tf]
├── walter-ansible-local/    ansible.cfg  inventory.ini  main.yml
└── walter-ansible-remote/   ansible.cfg  inventory.json  main.yml
```

`outputs.tf` appears only for providers walter can power cycle; it publishes the
instance id the power verbs act on.

Never edit any of it. It is regenerated on every run.

## What the machine gets

The remote playbook pings, then installs two things on **every** machine:

- **nix** — the Determinate Systems installer, non-interactive, skipped when
  `/nix/receipt.json` says it already ran. With nix present anything else the
  user wants is one `nix profile install` away and needs no change to walter.
- **a terminfo entry for Ghostty**, symlinked into `~/.terminfo`.

The terminfo is not a nicety. `TERM` travels over SSH and the terminfo database
does not, so a machine whose distro predates the operator's terminal answers
every full-screen program with:

```
Terminal type xterm-ghostty is not defined.
```

`vim`, `top`, `less` and Emacs all fail identically, over an SSH session that is
otherwise perfect. It is taken from a pinned nixpkgs rather than copied from the
controller with `infocmp`, because walter cannot assume the machine running it
has an interactive `TERM` at all — a create from CI would install nothing.

It is symlinked into `~/.terminfo` rather than exported via `TERMINFO_DIRS`
because the system ncurses reads that directory already, and `TERMINFO_DIRS`
would only reach a login shell — `ssh host vim …` is not one.

When `emacs-config-repo` names a repository, two more tasks follow: Emacs from a
pinned nixpkgs (`nixos-25.05#emacs-nox`, a terminal build with native
compilation and tree-sitter), then the configuration cloned over the forwarded
agent. Both are skipped when the binary or the checkout is already there, so a
re-run of `create` costs one SSH round trip.

Packages are **not** pre-fetched. The first interactive launch pulls from
ELPA/MELPA, native-compiles and clones tree-sitter grammars on its own; doing it
during provisioning would add minutes to every `create` and turn a transient
MELPA failure into a failed provision.

nix lands on `PATH` through `/etc/profile.d/nix.sh`, which the installer writes.
That is a *login* shell mechanism: `ssh walter-oci` picks it up, `ssh walter-oci
emacs …` as a one-shot command does not.

The local playbook writes a managed block into `~/.ssh/config`:

```
Host <profile>
    HostName <ip>
    User <user>
    Port 22
    ForwardAgent yes
```

Agent forwarding is on so the user can push to git from the machine without
copying a private key onto it. The block's marker carries `walter`, so it cannot
collide with a block another package manages.
