# Walter's file, not ONCE's. OpenTofu merges every .tf in a directory, so this
# registers the generated machine-access key without touching ONCE's template —
# the same trick outputs.tf uses on OCI.
#
# DigitalOcean's ssh_keys takes IDs or fingerprints of keys already registered
# with the account. ONCE's main.tf interpolates whatever string walter supplies
# there, and with compute-keygen on walter supplies
# "${digitalocean_ssh_key.walter.fingerprint}" — which resolves to this
# resource and doubles as the dependency edge, so the key exists before the
# droplet asks for it.
#
# The public key below is a stable placeholder on build (generation is a
# create-time side effect) and the real generated key on create and delete.
resource "digitalocean_ssh_key" "walter" {
  name       = "walter-<{ profile }>"
  public_key = "<{ compute-pubkey }>"
}
