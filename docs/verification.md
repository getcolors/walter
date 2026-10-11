# Python Walter verification

The Google-only rewrite uses profile `walter-gcp` and PocketDeploy package
`22b9a5c9235a52ac4a772554e26b21bef6f571f5`. Existing `walter-google` and
`walter-google-big` machines are outside its ownership and remain untouched.

Synthetic tests cover configuration mapping, lifecycle protection, credential
privacy, owned aliases, dotfiles, per-user privilege separation and Local SSD
recovery. PocketDeploy's shared change passed 792 tests and its published/copied
launcher checks and GitHub CI. Walter's source and wheel build successfully.

Live verification started on 11 October 2026 against project `pocketcontext`,
zone `europe-west4-b`, using existing `default` network/subnet. Google ADC and the
configured GitHub account passed preflight. Local authority was initialized and
an explicit six-file-plus-SQLite recovery checkpoint saved to a dedicated vault.
No old backend, machine, network or state was adopted.

Full remote provisioning, repeated convergence, reboot, final checkpoint and
published Walter launcher verification are pending. This document will be
updated with observed outcomes; synthetic tests are not live deployment proof.
