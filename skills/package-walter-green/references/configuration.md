# colors.yml for walter

A single flat YAML map, found by walking up from the working directory. Keys
arrive as kebab-case keywords. Credentials never live here.

**Omit an optional key you are not using — do not set it to `REPLACE_ME`.** A
key that is absent is absent, and its block does not render. A key holding a
placeholder is *present*, so the block renders and carries the placeholder into
the generated files verbatim (`repo: "REPLACE_ME"`), which builds cleanly and
then fails on the machine. Walter refuses to build in that state, naming the
key; the message is telling you to delete the line, not to fill in a value you
did not want.

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

## Machine access

| Key | Meaning |
|---|---|
| `compute-keygen` | Optional, `true` or `false`. Walter generates the machine-access keypair per deployment and feeds it to the provider. |

Set `true` and walter generates `~/.ssh/<profile>` (ed25519, no
passphrase) on the workstation at create time, if absent, and derives the
per-provider key values from it: `oci-ssh-authorized-keys` becomes the
generated public key's path, `compute-pubkey` its content, and on hcloud and
DigitalOcean — whose instances take a key already registered with the
provider — walter renders an `ssh-key.tf` resource beside the compute template
and references it. The managed `~/.ssh/config` block then pins
`ssh <profile>` to that key (`IdentityFile` + `IdentitiesOnly`), and Ansible
connects with it. The apply itself runs under a short-lived ssh-agent walter
starts with that key loaded, because the compute template's "wait for ssh"
provisioner authenticates through an agent and nothing else would hold a key
this new; the agent dies with the apply.

**Leave the per-provider ssh key values out when this is on** — they are
walter's to derive, and the build refuses the combination. An existing key
file is never regenerated, and `delete` leaves the files in `~/.ssh` alone: a
keypair is not provider state, and the next create of the same profile adopts
it.

The file carries no prefix, so the profile is the whole name and the whole
namespace. "Adopts it" is the trap: a profile that happens to match a key the
operator already has authorizes *that* key on the machine rather than minting a
new one, with no warning. A profile named for the deployment
(`walter-liliana` → `~/.ssh/walter-liliana`) cannot collide.

Left unset, you supply a key per provider exactly as documented below.

## GitHub identity

| Key | Meaning |
|---|---|
| `github-account` | Optional. The GitHub login the machine acts as. Gates the whole feature. |
| `git-email` | Required with `github-account`. Becomes `git config user.email`; `user.name` is the account. |

Set both and the machine comes up with gh logged in as that account, git
cloning and pushing over **https** through it (`gh auth setup-git`), and the
commit identity configured. The clone-bearing features below —
`emacs-config-repo`, `clone-orgs`, `dotfiles-checkout` — authenticate through
this and refuse to build without it.

**No token lives anywhere in desired state, and none is asked of you.** The
create *mints* one: its first action, before any provider is touched, is
GitHub's device flow — a one-time code and
`https://github.com/login/device`, approved from any browser, bounded by the
code's own ~15-minute life. Once approved, the rest of the run is unattended;
that is the design — the workflow is interactive at the beginning only. The
token carries gh's default scopes plus `workflow`, is verified to belong to
`github-account` before anything uses it, is seeded into the machine's own
`~/.config/gh/hosts.yml` over stdin, and the temporary controller-side copy is
deleted before the create returns. The operator's own gh login on the
workstation is never touched — the flow runs in a sandboxed `GH_CONFIG_DIR`.
`gh` must be installed on the workstation running `create`.

A machine that already holds a gh login is detected over the managed ssh alias
and left exactly as it is, so re-creates stay non-interactive — including a
login made on the machine directly. A create that fails *part-way* keeps the
minted token in its sandbox (`~/.local/state/walter/github-token-<profile>`,
0700), so the retry reuses it — re-verified against the account — instead of
prompting again; the sandbox is deleted the moment a create seeds the
machine. Deleting the machine does **not** revoke the token; do that under
GitHub Settings → Applications → GitHub CLI.

## Compute providers

Only the selected provider's keys are required — except under
`compute-keygen`, which derives the ssh key values (and only those) itself.

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

Name the **https** form (`https://github.com/you/emacs.d.git`). The clone
authenticates through the machine's own gh login — `github-account` above is
required — so a private repository works and the working copy can push back,
with nothing of the workstation's involved. The old `ssh://`/`git@` form is
refused at build time: the machine holds no ssh key for GitHub and no agent is
forwarded to it.

It is cloned **once**. A later `create` leaves an existing checkout alone, so
edits made on the machine are never discarded — pulling is the user's call.

## Toolchain

| Key | Meaning |
|---|---|
| `nix-packages` | Optional. A list of nixpkgs attribute paths, installed with one `nix profile add`. |
| `login-shell` | Optional. A shell from `nix-packages`, made the account's login shell. |

Resolved against `nixpkgs-unstable`. That is a channel branch and not a
revision, so these track upstream: two creates months apart do not produce the
same versions. Deliberate for a development machine — and it is what makes asdf
0.20 reachable at all.

The list is the machine's **baseline, not an inventory**. Anything installed by
hand on the machine is invisible here and does not survive a delete; adding a
name is what makes it come back.

**Unfree packages install.** The one `nix profile add` runs with
`NIXPKGS_ALLOW_UNFREE=1` and `--impure`, which are needed by each other: the
variable opens the licence gate, and flake evaluation is pure by default and
does not read the environment, so without `--impure` the variable is ignored and
the add still fails. This is what makes `claude-code` installable beside `codex`
and `pi-coding-agent`, which are not unfree. The cost is that the check is
relaxed for the **whole list**, so an unfree package added later installs without
announcing itself. `--impure` changes what evaluation may read, not what is
fetched — the flakeref is still pinned to the same nixpkgs ref.

`login-shell` must also appear in `nix-packages`, and walter refuses to build
otherwise — nothing else puts a shell there, so the alternative is an account
that cannot start a session. The playbook checks again on the machine before it
writes to passwd.

`fish` gets one extra file, `~/.config/fish/conf.d/nix.fish`, written **before**
the shell changes. nix reaches a login shell through `/etc/profile.d/nix.sh`,
which is sh-only, and a nix-built fish reads neither it nor `/etc/fish/conf.d`
because its sysconfdir is inside the store. Without that file a login fish
cannot find `nix` itself, which is unrecoverable from inside that shell.

Note that `ssh <profile> <command>` runs this shell. Anything scripted against
the machine gets its syntax, not bash's.

## Language runtimes

| Key | Meaning |
|---|---|
| `asdf-tools` | Optional. List of `{name, version, plugin?}`. `plugin` is optional — asdf resolves a bare name against its own index. |
| `corepack-packages` | Optional. Package managers enabled through Node's corepack. |

`asdf-tools` needs `asdf-vm` in `nix-packages`; asdf is not special-cased and
reaches the machine like anything else. `corepack-packages` needs a `nodejs`
entry in `asdf-tools`, because corepack ships inside Node rather than being a
package of its own.

The playbook uses `asdf set --home`, which is 0.16+ syntax. 0.16 rewrote asdf in
Go and removed `asdf global` outright — it answers "invalid command provided"
rather than warning — so a nixpkgs ref carrying 0.15 would need the playbook
changed in the same commit.

corepack installs its shims into that Node's own bin directory, which asdf does
not expose until told to look again; the playbook reshims. Skip that and
`corepack enable pnpm` reports success while `pnpm` stays command-not-found.

## Dotfiles

| Key | Meaning |
|---|---|
| `dotfiles-checkout` | Optional. A checkout of `getcolors/dotfiles` whose existing `./green` launcher Walter runs. |

Needs `babashka` in `nix-packages`, because the Green launcher is a Babashka
script, and `github-account` above, because `clone-orgs` is what creates the
checkout. The checkout owns its `colors.yml`: that file selects the packaged
profile and target, and Walter does not duplicate those keys. Walter invokes
`./green create` from the checkout with
`COLORS_PAR_DOTFILES_PREVENT_OVERWRITE=false`; it never sets
`COLORS_PAR_PROFILE`.

Normally `clone-orgs` creates `~/code/getcolors/dotfiles` before this step. A
successful create is stamped under `~/.local/state/walter`, so later Walter
creates do not reapply the profile over edits made in `$HOME`. Delete the stamp
to authorize another application.

## Organisation checkouts

| Key | Meaning |
|---|---|
| `clone-orgs` | Optional. A list of GitHub organisations whose every source repository is cloned to `~/code/<org>/<repo>`. |

**Organisations, not repositories.** The repository list is read from GitHub's
API on the machine at create time rather than rendered into the playbook, so one
added upstream arrives on the next create with nothing in `colors.yml` to keep in
step. That is the whole reason the key takes an org — a rendered list would be a
list gone stale. A value carrying a `/` or a scheme is refused at build time,
because the realistic mistake is pasting `org/repo` or a full URL into it.

The listing is **authenticated** through the machine's gh login —
`github-account` above is required — so it sees what the account sees, private
repositories included, and gh paginates it itself: organisations of any size
clone completely.

Two filters, applied in different places because the API only offers one.
`type=sources` drops forks at the server; archived repositories are skipped at
the clone with an Ansible `when:`, so the run names what it passed over instead
of quietly producing a shorter list. Both are the same judgement: neither is a
working copy.

The clones use `https://github.com/` and `update: false`, like the editor
clone: they authenticate with the machine's own token, so each checkout can
push back with nothing of the workstation's involved, and a later create
leaves an existing one alone. `git pull` on the machine is how one moves. They
run after credential seeding and before the dotfiles launcher, whose checkout
they may create.

## Shell history

| Key | Meaning |
|---|---|
| `atuin-username` | Optional. Logs the machine into an existing atuin account and syncs its history. |

Needs `atuin` in `nix-packages`.

**The password and the encryption key are not keys in this file.** They are
`COLORS_PAR_ATUIN_PASSWORD` and `COLORS_PAR_ATUIN_KEY`, read from the
environment at create time — `colors.yml` is committed, and that key decrypts
the account's history on every machine it reaches. The playbook asserts both
before it uses them, and the task is `no_log`, so a failure is opaque by design:
diagnosing one means running `atuin login` by hand on the machine.

They are checked in the playbook rather than in validation because `build`
renders from desired state alone and must stay credential-free.

Logging in **replaces** whatever key atuin generated locally, so history written
on the machine beforehand becomes unreadable. That is what adopting an existing
account means, and it is why the login is stamped under
`~/.local/state/walter/atuin-<username>` rather than converged — atuin keeps its
session inside `meta.db` and offers no file to watch, and its CLI output is not
a stable enough contract to parse.

`atuin sync` runs after the login on every create, and is deliberately not
guarded: pulling history is the point, and a second run undoes nothing.

## Agent CLI credentials

| Key | Meaning |
|---|---|
| `seed-agent-credentials` | Optional. A list of agent names whose subscription login is copied from the machine running `create`. |

`claude`, `codex` and `pi` are the names walter knows; anything else fails the
build rather than rendering a task that copies nothing. Each resolves to **one
file**, never the directory around it:

| Name | File, under `$HOME` on both sides |
|---|---|
| `claude` | `.claude/.credentials.json` |
| `codex` | `.codex/auth.json` |
| `pi` | `.pi/agent/auth.json` |

The directories those live in are overwhelmingly session transcripts and caches —
a few hundred megabytes against a few kilobytes of tokens — and a development
machine has no use for another machine's history. That is also why the key names
agents rather than paths: a "copy these local files to the machine" key would be
the same feature with nothing stopping it pointing at `~/.ssh`.

Claude Code also gates interactive startup on `hasCompletedOnboarding` in
`~/.claude.json`; the credential file alone can make `claude auth status` report
logged in while `claude` still presents first-run login methods. Walter does not
copy that machine-local file. When the controller's Claude credential exists, it
atomically adds `hasCompletedOnboarding: true` only if the key is absent,
preserving every existing field and an existing true or false value.

**Nothing here is a secret and nothing is rendered.** Only the agent names are
desired state; the files are read from the operator's home directory at create
time, so `build` stays credential-free and no token ever reaches `.colors/`. The
credential copy is `no_log`, because `ansible-playbook --diff` prints a copy
module's file content and that content is a bearer token.

**A missing file is reported and skipped, by name.** A create from CI, or from a
laptop that has not logged in, still succeeds and leaves those CLIs logged out —
which is the behaviour of a machine that was never seeded at all.

**Seeding happens once and never repairs.** The guard is `force: false` on the
copy, deliberately *not* a `~/.local/state/walter` stamp like the atuin login:
the credential file is its own evidence, and watching it answers the question a
stamp cannot — whether *this machine* has a login, rather than whether walter
once wrote one. These are OAuth refresh tokens the CLI rotates in place, so two
overwrites have to be prevented, and a stamp only prevents the first: a token the
machine refreshed for itself, and a login made on the machine directly, which a
stamp would clobber the first time it ran. When a machine's tokens expire, log in
there, or delete the file there and run `create` again.

Note that seeding a shared account means two machines refreshing against one
refresh token, and `~/.pi/agent/auth.json` in particular can hold long-lived API
keys alongside the OAuth triple.

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
├── walter-compute/          backend.tf.json  main.tf  [outputs.tf]  [ssh-key.tf]
├── walter-ansible-local/    ansible.cfg  inventory.ini  main.yml
├── walter-ansible-remote/   ansible.cfg  inventory.json  main.yml
└── walter-emacs-packages/   ansible.cfg  inventory.json  main.yml
```

`outputs.tf` appears only for providers walter can power cycle; it publishes the
instance id the power verbs act on. `ssh-key.tf` appears only under
`compute-keygen` on hcloud and DigitalOcean, where keys are registered with the
provider rather than passed as material. `walter-emacs-packages/` appears only
when `emacs-config-repo` is set — it is a whole stage rather than a task, so
with no Emacs there is no directory at all.

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

Everything after that is gated on a key, and a project that names none of them
renders a playbook that does not mention them at all:

| Key | Adds |
|---|---|
| `github-account` + `git-email` | gh, logged in with the token the create minted; git authenticating through it; the commit identity |
| `nix-packages` | one `nix profile add` for the whole list |
| `login-shell` | `/etc/shells` plus a passwd change, guarded by a check on the machine |
| `asdf-tools` | plugin add, install, and `asdf set --home` |
| `corepack-packages` | `corepack enable`, then `asdf reshim nodejs` |
| `emacs-config-repo` | Emacs, then the configuration cloned over https with the machine's token |
| `dotfiles-checkout` | that checkout's `./green create`, once, with its own `colors.yml` |
| `seed-agent-credentials` | one credential file per named agent, copied from the controller; Claude also gets a missing onboarding flag |
| `clone-orgs` | every source repository of each org, cloned to `~/code/<org>/<repo>` |
| `atuin-username` | `atuin login`, then `atuin sync` |

Emacs comes from `nixpkgs-unstable#emacs`, the same ref as the terminfo and
`nix-packages` steps — the full build, with native compilation and tree-sitter.
Not `emacs-nox`: that one drops X and with it the image and SVG support a
configuration may assume, and on a machine that is rebuilt rather than rebooted
the larger closure costs download time rather than disk.

It and the clone are both skipped when the binary or the checkout is already
there, so a re-run of `create` costs one SSH round trip. That guard cannot tell
the two Emacs builds apart — both put `emacs` in `bin` — so **changing this
converges a new machine, not an existing one.** A machine already carrying the
other build needs `nix profile remove` first, or a delete and create.

Native compilation and any C-based package (`vterm`, tree-sitter grammars built
on the machine) need a compiler at run time, which Emacs does not bring with it.
Put `gcc` in `nix-packages` if the configuration needs one.

**Packages are fetched by a final stage that `create` starts and does not wait
for.** `walter-emacs-packages` runs `emacs --batch -l init.el` on the machine
under `async` with `poll: 0`, so the job is daemonized and keeps going after
`create` has already reported success. Nothing downstream reads the result — it
is a cache being warmed — so a transient MELPA failure still cannot fail a
provision, which was the original reason for not fetching at all. What changed is
only *when* the wait happens: off your first interactive launch, where Emacs
shows nothing at all for minutes, onto a machine nobody is watching.

Loading `init.el` is the whole mechanism, so walter carries no package list and
no elisp of its own — a configuration using `use-package` with `:ensure`, or
`package-vc-install`, installs whatever it names. Watch it with:

```sh
ssh <profile> tail -f ~/.local/state/walter/emacs-packages.log
```

The log records a start line, a finish line and the real exit status, and is
truncated per run. Two things are still **not** covered: tree-sitter grammars,
which most configurations install lazily the first time a matching file is
opened, and `nerd-icons-install-fonts`, which is interactive.

nix lands on `PATH` through `/etc/profile.d/nix.sh`, which the installer writes.
That is a *login* shell mechanism: `ssh walter-oci` picks it up, `ssh walter-oci
emacs …` as a one-shot command does not.

The local playbook writes a managed block into `~/.ssh/config`:

```
Host <profile>
    HostName <ip>
    User <user>
    Port 22
    IdentityFile ~/.ssh/<profile>   # only under compute-keygen
    IdentitiesOnly yes                     # only under compute-keygen
```

There is no `ForwardAgent`. Nothing on the machine authenticates with the
workstation's keys: GitHub work rides the machine's own gh token over https,
and the only private key involved in reaching the machine is the one
`compute-keygen` generated for exactly that. The block's marker carries
`walter`, so it cannot collide with a block another package manages.
