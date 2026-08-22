# Walter owns this account registration; the dedicated local keypair survives a
# delete, while this Vultr object follows the deployment's OpenTofu state.
# ONCE's instance template consumes the resource id below, which is also the
# dependency edge that registers the key before the instance is created.
resource "vultr_ssh_key" "walter" {
  name    = "walter-<{ profile }>"
  ssh_key = "<{ compute-pubkey }>"
}
