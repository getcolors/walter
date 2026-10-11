---
name: walter
description: Provision and converge a Google Cloud development machine, user seats and tools using Python and PocketDeploy; inspect, access, checkpoint or delete its owned resources.
---

# Walter

Use the adjacent portable `walter` executable from the deployment directory, or
select `-f /absolute/path/colors.yml`. Read the repository README for configuration
and recovery. No Terraform, Ansible or Clojure runtime is required.

Run `init`, `plan`, `converge`, `status`, `ssh`, `converge-nix`, `converge-asdf`,
`ssh-install`, `ssh-uninstall`, `vault-save`, `vault-restore`, or `delete`.
There are no start/stop operations. Do not adopt or migrate old Walter state.

Read desired configuration before mutation. Use the profile prefix for resource
names; trust recorded IDs and ownership metadata, never names alone. PocketDeploy
owns shared lifecycle behavior. Existing networks/subnets stay externally managed.
Every variable in `.envrc.private` must start with COLORS_PAR_. Never print private
bindings, tokens, SSH keys, raw cloud responses or host credential contents.

Cloud creation/deletion needs user-authorized scope. Plan before convergence;
record verification and checkpoint results. Deletion requires explicit destruction
protection removal and destroys remote files. Vault checkpoints cover controller
recovery, not machine data. Reuse an existing unlocked Vault session and never ask
for its passphrase in chat. Never execute restored private files automatically.

Use --json for structured safe output, --verbose for progress. Commands never send
email or other external notifications. Report partial provisioning honestly;
Emacs package warming continues asynchronously after convergence.
