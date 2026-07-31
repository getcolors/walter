# Rendered by walter beside ONCE's main.tf. OpenTofu merges every .tf file in a
# directory, so publishing the instance id needs no change to ONCE's template
# and no fork of it — which matters, because forking it would mean owning a
# divergent copy of the pinned-image branch and the prevent_destroy wiring,
# exactly the code walter set out to reuse.
#
# `stop` and `start` act on this OCID. The alternative considered was finding
# the machine with `oci compute instance list --display-name`, which targets
# whatever matches a human-typed string in a compartment that for this tenancy
# is the root — so a display name copied from another project would power off
# that project's server. A configuration typo, not an exotic race.
#
# oci_core_instance.ampere_vm is ONCE's resource address, and ONCE's own
# template comments call it out as a state address that must not be renamed.
# scripts/golden.sh asserts it is still present in the rendered main.tf, so a
# rename upstream fails loudly here rather than as an opaque `tofu validate`
# error at apply time.
output "instance_id" {
  value = oci_core_instance.ampere_vm.id
}
