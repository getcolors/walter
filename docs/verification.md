# Walter deployment verification

Verified on 11 October 2026 for the shared Go and dependency-driven provisioning
refactor. The portable launcher pins the published package identified in
`skills/walter/walter`; shared SSH behavior comes from the immutable PocketDeploy
pin in `pyproject.toml`.

## Automated checks

Walter's suite passed 117 tests and 28 subtests, with the root-only privilege test
skipped in the ordinary run. The remote suite passed all 14 tests and two subtests
under sudo, including permanent privilege dropping. Package build and copied
launcher checks passed.

Coverage includes ARM64/AMD64 Go archive selection, pinned checksums, replacement
without stale files, interrupted-promotion recovery, shared permissions, and
repeat installation. DAG tests exercise actual task overlap, immediate successor
readiness despite unrelated slow branches, a six-task concurrency bound, failure
draining, cancellation, exclusive resource locks and stale-run rejection.

## Authorized replacement and backups

The user explicitly authorized replacing the current `walter-gcp` deployment
under the same profile after implementation, tests and backup. Walter's existing
ownership-aware deletion removed its VM, boot volume and owned firewall rules;
its shared network and subnet were retained. Local SQLite history was preserved.
Destruction protection was disabled only for the deletion command and remains
enabled in the desired configuration.

Before deletion, a private archive preserved complete homes (including six dirty
Git checkouts and untracked files), `/root`, `/etc`, `/usr/local`, `/opt`, `/srv`
and `/var/lib`. Reinstallable `/nix` was excluded. The 4,088,435,513-byte archive
contained 157,113 entries, all fully read during verification, with SHA-256
`e65157987ff962ad214bc15cb2ec9f82029706325afdfb6d7d4073d3a0061c35`.
It remains outside the repository in the controller's private Walter backup
storage. Separate Vault checkpoints preserve controller recovery material.
Remote files were preserved in the archive, not automatically restored or executed.

## Live provisioning

The replacement retains the ARM64 `c4a-standard-4-lssd` configuration: four vCPUs,
16 GB RAM, one Local SSD, and a 100 GB Hyperdisk Balanced boot disk. Scratch mounts
cover `/home`, `/nix`, `/tmp`, `/var/tmp`, Docker and containerd data.

A separate `walter-dag-amd64-test` deployment uses `c4-standard-4`, the x86 Ubuntu
24.04 image and no Local SSD. It exercises the same three users and software.

On both architectures, the fresh DAG overlapped Go, Docker and Nix; Nix consumers
started while unrelated work continued. User Git and runtime branches overlapped.
The initial Atuin synchronization failed on both fresh machines. The DAG drained
all workers and withheld finalization; ordinary repeat convergence recovered the
both installations without changing desired software or adding blanket retries.

Live testing also exposed a shared SSH transport issue: Fish interprets
backslashes inside single-quoted source differently from Bash. Nested Python
programs in the Go verifier failed after switching the login shell to Fish.
The fix belongs to PocketDeploy's source transport, not a Walter shell workaround.

The shared transport fix is published in PocketDeploy commit
`f910976d601b3aaec3bc8fab26126606968c198a` and pinned by Walter. Its 834-test suite,
actual Bash/Fish source-preservation tests, package build and 36 copied-launcher
checks passed.

Full live verification passed on both architectures after the transport fix:

- Go 1.27.2 through direct invocation, Bash and Fish, with root-owned shared files
  and native compilation/execution as ubuntu, rose and jack.
- Private homes, correct GitHub identity, all configured Nix/asdf/agent tools,
  explicit repository checkouts, Atuin session evidence and ET service readiness.
- Emacs warming finished with exit zero for all users.
- Actual ARM64 SSH sessions used Fish, selected the correct Go version and user,
  and denied passwordless sudo to both seats.

A deliberate SIGINT on AMD64 returned the interrupted status after all five
started workers finished, with finalization skipped. Subsequent ordinary
convergence recovered successfully in 33.49 seconds. The initial Atuin failures
also exercised real failed-operation recovery on both architectures.

Copied launchers were run from outside the checkout, selecting deployment
configuration with an absolute path. ARM64 repeat convergence took 32.021 seconds
(10.021 seconds of remote DAG work); AMD64 took 32.97 seconds (10.553 seconds of
remote DAG work). Both retained their resource identities. These are observations,
not controlled performance comparisons with the previous implementation.

The replacement ARM64 deployment has a new provider ID, destruction protection
enabled and zero pending journal steps. Both unrelated Walter machines retained
their IDs and running state. Private backup and safe live evidence remain in the
controller's local state directories, outside the repository.

After validation, Walter deleted the temporary AMD64 compute, boot volume and
owned firewall resources. A read-only status check confirmed zero owned resources
and zero pending steps; shared networking and local history were retained.
The final ARM64 recovery state was saved to Vault after its successful copied
launcher run. Resolver and root task progress now explicitly finish at `complete`,
including successful retries after a previously failed stage.


## Structured diagnostics rollout plan

Apply shared diagnostics in PocketDeploy, then pin the published dependency in
Walter and Alice. Test each package and publish its source before updating and
testing the portable launcher. Keep raw command output private; no masking or
excerpt feature is part of this change.

Live deployment is authorized for Walter. Recreate disposable `walter-gcp` with
the same profile and desired configuration, without backing up or restoring its
remote files. Checkpoint controller recovery state, review owned deletion, remove
protection only for deletion, and create with protection enabled. Retain SQLite
history and external networks; leave unrelated machines unchanged.

Validate the fresh DAG, native shared Go for all users, safe diagnostic transport
and privilege-dropped child failures, graceful interruption and ordinary recovery.
Repeat through a copied published launcher, compare resource identities and save
a final controller checkpoint. Alice and PocketDeploy require no separate live
resource mutations for this rollout.

## Structured diagnostics rollout results

Published Walter source `a13326f` and launcher `b7daca5` pin PocketDeploy source
`75b20da` (shared launcher `950cfa3`). Alice source `8f27cab` and launcher
`5863c76` publish the corresponding adapters. Walter passed 125 tests with one
privilege-test skip and 41 subtests; the separate root run passed 14 tests and
two subtests. PocketDeploy passed 857 tests and Alice passed 42. Package builds
and copied-launcher checks passed for all three projects.

The disposable `walter-gcp` was deleted in 100.095 seconds and recreated under
the same profile without a remote-data backup or restoration. Fresh convergence
ran for 372.357 seconds before failing. The new diagnostics identified Jack's
`atuin-sync` operation in `user-finish`: exit code 1, category `nonzero_exit`,
and elapsed time 125.768 seconds. All three user-finish tasks failed, running
workers drained, and finalization was withheld. These fields do not establish
the underlying Atuin cause. Ordinary recovery succeeded in 32.872 seconds on
new provider instance `4792162071945336603`.

Full verification and actual SSH checks passed for ubuntu, rose and jack:
Go 1.27.2 on ARM64 through native execution, Bash and Fish; private homes;
seat sudo denial; Eternal Terminal readiness; and successful Emacs warming.
Controlled remote failures preserved exit 37, timeout, unavailable-command
and signal 15 diagnostics. A privilege-dropped Rose child preserved exit 37
and its stage across the child transport. Both suppressed private sentinels. No raw output excerpts or masking were enabled.

A deliberate SIGINT drained all five started workers and skipped finalization.
Recovery through the copied published launcher succeeded in 31.383 seconds and
retained the replacement instance ID. These timings are observed runs, not a
controlled performance comparison. Final audit confirmed the new owned instance
is running, the old instance is absent, both unrelated machines retain their
original IDs and running state, destruction protection is enabled and no journal
steps remain pending. SSH authority validates the replacement instance. The
original Atuin diagnostic remains in controller history after successful retries.
A final six-file controller recovery checkpoint was saved successfully to Vault.
