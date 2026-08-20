# Walter's file, not ONCE's. OpenTofu merges every .tf in a directory, so this
# registers the generated machine-access key without touching ONCE's template —
# the same trick outputs.tf uses on OCI.
#
# Hetzner's ssh_keys takes a key already registered with the account, by name.
# ONCE's main.tf interpolates whatever string walter supplies there, and with
# compute-keygen on walter supplies "${hcloud_ssh_key.walter.name}" — which
# resolves to this resource and doubles as the dependency edge, so the key
# exists before the server asks for it.
#
# The public key below is a stable placeholder on build (generation is a
# create-time side effect) and the real generated key on create and delete.
resource "hcloud_ssh_key" "walter" {
  name       = "walter-walter-fixture"
  public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBUILDPLACEHOLDER0000000000000000000000"
}
