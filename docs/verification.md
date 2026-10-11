# Walter deployment verification

Verified on 11 October 2026. Walter package
`0e02cf318195af795e00460723d8aedd07f2bae4` uses PocketDeploy package
`2231acf6f720003e14593a24aa304e14bf989fcb`. The portable launcher is pinned to
that published Walter package and tested from an unrelated directory.

## Automated checks

Walter: 98 tests passed, one privilege test skipped in the ordinary run, and
23 subtests passed. The remote suite also passed all 13 tests and two subtests
under sudo, including the actual privilege boundary. Package build and copied
launcher checks passed. Tests cover SSD placement and boot ordering, partial Nix
installation, true DAG user concurrency, failure joins, immutable package
upgrades, explicit repository selection, runtime dependencies and ET service setup.

PocketDeploy: 807 tests, package build and 36 copied-launcher checks passed.
Configurable SSH companion TCP ports use the existing owned firewall lifecycle
and SSH source restrictions across supported providers.

## Fresh deployment

Deleted the previous `walter-gcp` VM, boot volume and owned firewall rules before
creating the new deployment. No old remote files were migrated. The shared
network and subnet were retained. The separate `walter-google` and
`walter-google-big` machines retained their provider IDs and running state.

The fresh `walter-gcp-compute` in `europe-west4-b` uses
`c4a-standard-4-lssd`: four ARM vCPUs, 16 GB RAM, one 375 GiB Local SSD and a
100 GB Hyperdisk Balanced boot disk. The SSD is ext4 at `/scratch`, with bind
mounts for `/home`, `/nix`, `/tmp`, `/var/tmp`, `/var/lib/docker` and
`/var/lib/containerd`. Homes are private and temporary directories use mode 1777.

Shared setup runs before parallel user branches for `ubuntu`, `rose` and `jack`.
The users share one resolved nixpkgs `nixos-26.05` revision and exact asdf versions:
Node.js 26.11.1, uv 0.13.0 and Bun 1.4.3. Python is absent from asdf configuration.
Node's `libatomic1` prerequisite is installed independently of Python tooling.
Nix service overrides are written after the installer completes; incomplete
installer receipts are resumed through the explicit saved plan.

The full read-only verifier passed before and after reboot, including:

- SSD placement, private homes, configured Nix packages and matching runtimes.
- GitHub identity, agent executables, dotfiles and Atuin authentication evidence.
  Atuin synchronization succeeded on retry after an initial transient failure.
- Explicit clones of `basecamp/once`, `pocketbase/pocketbase` and
  `benbjohnson/litestream` under each user's `~/code/<owner>/<repo>`.
- All three Emacs package-warming jobs completed with exit code zero.
- SSH identity checks for all three users; seats were denied passwordless sudo.
- Eternal Terminal 6.2.11 from Nix, its root-owned server profile, active service
  and reachable TCP 2022. Actual ET sessions verified all three user identities.

Repeat convergence through a copied portable launcher from an unrelated directory
completed in about 35 seconds. The three user branches overlapped and each took
about six seconds. All managed resources were retained with no drift and zero
pending journal steps. A scheduled reboot changed the boot ID and reconnected
with the pinned SSH host key; the full verifier passed again afterward.

## Synthetic performance sample

One run compared the boot filesystem with the SSD on the same VM after setup.
Temporary benchmark files were removed. These are synthetic checks, not claims
about application build speed.

| Check | Boot disk | Local SSD |
| --- | ---: | ---: |
| 128 MiB write followed by fsync | 0.3920 s | 0.3928 s |
| 500 small-file fsync operations | 0.7341 s | 0.0637 s |
| Synthetic C compilation and execution | 0.6009 s | 0.5981 s |

Small-file synchronization was about 11.5 times faster in this sample. Sequential
writes and CPU-dominated compilation were effectively unchanged.
