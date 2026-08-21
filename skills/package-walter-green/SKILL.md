---
name: package-walter-green
description: Creates and operates a remote development machine with Green, OpenTofu and Ansible, and powers it off and on to stop paying for it while you sleep. Use when initializing a walter project, generating colors.yml, selecting a compute or state provider, building or dry-running configuration, provisioning or destroying the machine, or stopping and starting it.
license: MIT
---

# A remote development machine, with Walter

Use this skill to initialize or operate a walter project in the user's current
directory. Walter provisions one machine, records it in `~/.ssh/config` so
`ssh <profile>` reaches it, and powers it off and on.

## Requirements

Babashka runs the launcher. `create` and `delete` also need OpenTofu and
Ansible. `stop` and `start` need the `oci` CLI and a live session. With
`github-account` set, a real `create` also needs `gh` on the workstation — it
runs GitHub's device flow as its first action. Provider credentials use
`COLORS_PAR_*` variables, except OCI, which uses the profile named in
`~/.oci/config`, and S3, which uses OpenTofu's ambient AWS credential chain.

## Non-negotiable safety rules

- Never ask the user to paste a secret into chat.
- Never put API tokens, passwords, private keys or access keys in `colors.yml`,
  in the `green` launcher, in shell history, or in generated examples. Every
  credential arrives through a `COLORS_PAR_*` environment variable named after
  the key it fills. Suggest a gitignored `.envrc.private`, never an inline
  export a shell history records.
- Public SSH keys are not secrets; private ones are. `oci-ssh-authorized-keys`
  holds the **path** to a public-key file that OpenTofu reads at plan time —
  record the path, never inline the contents, and never read a private key.
  With `compute-keygen: true` walter generates and manages
  `~/.ssh/<profile>` itself; never read or move that file either.
- With `github-account` set, a real `create` starts by printing a one-time
  code and waiting — up to about fifteen minutes — for the user to approve it
  at https://github.com/login/device. That is the design, not a hang: the
  workflow is interactive at the beginning only. Relay the code and URL to the
  user and wait; never try to acquire, read, or echo the token itself.
- **Never set `COLORS_PAR_PROFILE`.** Walter refuses to run when it is set, and
  suggesting it as a workaround defeats the guard. The profile identifies the
  project, and the project is the directory. If the user wants a different
  profile, edit `colors.yml`.
- Do not overwrite an existing `green` launcher or `colors.yml` without
  explicit approval. If a project is already valid, operate it rather than
  regenerating it.
- Default to `build` and `create --dry-run`. Run a real `create` or `delete`
  only after the user confirms that exact operation.
- `build` and `create --dry-run` are credential-free by design and check no
  `COLORS_PAR_*` at all. A clean dry-run says nothing about whether real
  provisioning would authenticate; never report it as credential validation.
- Before `delete`, remind the user that a development machine holds uncommitted
  work and that the boot volume goes with it. `compute-prevent-destroy` defaults
  to `true`; authorize an intentional delete with
  `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` rather than editing desired state.
- Never edit anything under `.colors/` — it is generated output.

Read [references/configuration.md](references/configuration.md) before
generating or changing desired state, and before any real `create` or `delete`.

## Commands

```sh
./green build              # render .colors/<profile>/ only; contacts nothing
./green create --dry-run   # print the graph; touches nothing
./green create             # provision, and write the ssh config block
./green stop               # power off
./green start              # power on, and refresh the ssh config block
./green delete             # destroy, dropping the ssh block first
```

`-f/--file` overrides the `colors.yml` found by walking up from the working
directory.

## Initialize in the current directory

1. Copy the `green` payload beside this file into the project root and
   `chmod +x` it.
2. Write `colors.yml`. Ask for the provider first, then only the keys that
   provider needs — `references/configuration.md` lists them.
3. **Choose a `profile` unique to this project**, conventionally the directory
   name. It names the work directory, the OpenTofu state keys and the ssh alias.
   Two projects sharing a profile and a state bucket address the same state,
   which is how a development machine ends up managing a production server.
4. Ask whether the machine should have the user's GitHub identity. If so, set
   `github-account` to their login and `git-email` to their commit email, and
   tell them a real `create` will start with a one-time device-flow code to
   approve from a browser. This is required before offering
   `emacs-config-repo`, `clone-orgs` or `dotfiles-checkout` — their clones
   authenticate through it.
5. Ask whether walter should generate the machine-access ssh keypair
   (`compute-keygen: true`) or whether the user supplies a provider key as
   before.
6. Ask whether the user wants their Emacs configuration on the machine. If so,
   set `emacs-config-repo` to its **https** git URL (`git@`/`ssh://` forms are
   refused) and `emacs-config-dest` to where it must live — the default is
   `~/.config/emacs`, and a configuration expecting another path needs
   `--init-directory` to reach it. Leave both out otherwise; the rendered
   playbook then does not mention Emacs.
7. Run `./green build` and show the user what was rendered.

## What create puts on the machine

Every machine gets **nix**, a **Ghostty terminfo entry**, and kernel networking
settings for unprivileged `cloudflared`, unconditionally. The sysctls allow the
login user's primary group to use ping sockets and raise QUIC's receive/send
buffer ceilings, so a tunnel should run without sudo or those warnings. Tell
the user about nix rather than proposing walter changes for other tooling: once
it is there, anything else is `nix profile install` and needs nothing from
walter.

The terminfo is why `Terminal type xterm-ghostty is not defined` does not
happen. If a user reports that error — from `vim`, `top`, `less` or Emacs — on a
machine created before this existed, the fix is to re-run `create`, not to
change `TERM`. For a terminal walter does not cover, the one-liner is:

```sh
infocmp -x "$TERM" | ssh <alias> -- tic -x -
```

With `github-account` set, `create` also logs the machine's own gh in with the
token the device flow minted, makes it git's https credential helper, and
configures the commit identity — every clone below authenticates through it,
and nothing of the workstation's (no key, no agent) is involved. A machine
already logged in skips the interactive step entirely, so re-creates stay
unattended.

With `emacs-config-repo` set, `create` also installs Emacs (a full build from a
pinned nixpkgs) and clones the configuration over https with the machine's own
token — no private key is written to the machine, and the checkout can push
back. The clone happens **once**; a later `create` leaves an existing one alone,
so work done on the machine is never discarded. Offer `git pull` on the machine
rather than a re-run when the user wants the config refreshed.

Emacs packages are not pre-fetched. The first `emacs` launch fetches from
ELPA/MELPA, native-compiles and clones tree-sitter grammars, which takes minutes
and is expected. Do not report it as a provisioning failure.

`nix` and `emacs` reach `PATH` via `/etc/profile.d/nix.sh`, a **login** shell
mechanism: `ssh walter-oci` sees them, `ssh walter-oci emacs …` as a one-shot
command does not.

## Stopping and starting

`stop` and `start` never reach OpenTofu. No template declares a power state, so
powering the machine off out of band causes no drift — there is nothing for
OpenTofu to reconcile.

Consequences worth telling the user about:

- **Only OCI can be power cycled today.** Everywhere else `stop` reports that
  and exits 0. That is deliberate, not a bug. Do not present it as a failure.
- **`create` will not restart a stopped machine.** With no power state in the
  configuration there is no diff, so an apply leaves it stopped. `start` is the
  only way up.
- **Stopping stops the compute meter, not the storage one.** The boot volume
  bills whether the machine runs or not.
- **`stop` and `start` need the `oci` CLI to authenticate**, which OpenTofu does
  not. Session tokens last 60 minutes. When walter reports an expired session it
  names the command that fixes it; run that, then retry.

## When something fails

- **`COLORS_PAR_PROFILE is set`** — the user has it exported, probably from
  another project's `.envrc`. Unset it; do not work around it.
- **`required credential is not set: COLORS_PAR_X`** — name the variable and let
  the user export it themselves.
- **`no instance id`** — walter could not read the compute stage's `instance_id`
  output and desired state carries none. Either the machine was never created,
  or the state backend is unreachable. `oci-instance-id` in `colors.yml` is the
  documented escape hatch.
- **`gh auth login failed`** — `gh` is missing on the workstation, or the
  one-time code expired unapproved. Install gh or re-run `create` and approve
  the code; there is no token to paste anywhere.
- **A create failed after the code was approved** — just re-run it. The minted
  token survives under `~/.local/state/walter/github-token-<profile>` for
  exactly this, so the retry does not prompt again; it is removed once a
  create seeds the machine.
- **the login approved the code as X but colors.yml names github-account Y** —
  the user approved from the wrong GitHub account. Re-run `create` and approve
  from the account the machine is meant to act as, or fix `github-account`.
- **A contract mismatch** — the pinned commit is older than this launcher.
  Re-copy `green` from an updated skill; nothing inside the project fixes it.
