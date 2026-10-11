# Python Walter verification

Verified on 11 October 2026. Profile `walter-gcp` uses PocketDeploy package
`22b9a5c9235a52ac4a772554e26b21bef6f571f5` and Walter package
`bbfa62973407c9d25a357d89edd3444b3306bc50`. Both portable launchers are pinned,
published and tested from an unrelated directory; GitHub CI passed.

## Automated checks

Walter: 68 tests passed, one privilege test skipped in the ordinary non-root run,
and 23 subtests passed. The remote suite also passed all 13 tests under sudo,
including the actual privilege boundary and both Atuin credential prompt orders.
Source and wheel builds succeeded. Coverage includes configuration mapping,
lifecycle protection, credential privacy, owned aliases, dotfiles, per-user
privilege separation and Local SSD recovery.

PocketDeploy: 792 tests passed, plus package build, 36 copied-launcher checks and
GitHub CI. Shared changes cover GCP machine/disk/NIC validation, resource naming,
drift detection and Python provisioning over pinned SSH.

## Live deployment

Provisioned `walter-gcp-compute` in project `pocketcontext`, zone `europe-west4-b`,
using the existing `default` network/subnet. The machine is ARM64 N4A with a
100 GB persistent Hyperdisk Balanced boot volume and GVNIC. No old backend,
machine, network or state was adopted. Bootstrap host keys were rotated before
application credentials were sent.

Full convergence succeeded for `ubuntu`, `rose` and `jack`. Repeat convergence
completed in 50 seconds, retained the same VM ID and left zero pending journal
steps. The cloud plan retained all four managed resources with no drift.

The read-only live verifier passed before and after reboot:

- Correct architecture, private home ownership/modes and authorized SSH keys.
- Configured Nix packages, asdf runtimes, agent executables, GitHub identity,
  dotfiles and Atuin session evidence for all three users. Convergence also
  completed Atuin synchronization.
- All three asynchronous Emacs setup jobs completed with exit code zero.
- Seat users have no privileged groups; direct SSH tests confirmed their
  identities and denied passwordless sudo.

All three installed SSH aliases worked. A scheduled system reboot changed the
boot ID and reconnected with the pinned SSH host key. Docker, SSH and Nix daemon
services were active afterward. Both existing `walter-google` and
`walter-google-big` instances retained their original provider IDs and remained
running.

A final encrypted checkpoint saved six private files plus SQLite to the dedicated
VaultContext vault. Its document is `zvl3wloyofzx87u`, version
`3qqp05wor249r39`. This protects controller authority and state, not remote homes
or boot-volume contents.

## Limits

Local SSD/C4A behavior was tested synthetically, not on a live C4A machine.
Destructive deletion and Vault restoration were not exercised against this
retained development machine. The live checks establish the observed state on
the verification date; future upstream package changes require fresh validation.
