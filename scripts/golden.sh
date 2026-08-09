#!/usr/bin/env bash
set -euo pipefail

# Walter is a single colour, so there is no parity harness — and ONCE's
# parity.sh was its golden-file regression net as much as its parity check.
# This is that net: render every provider variant and diff against committed
# output.
#
# It is the mitigation for walter's one real coupling. Walter consumes ONCE's
# provider registry and its compute templates, and nothing upstream promises
# either will hold still: ONCE's contract number versions the *launcher*
# handshake, not a library API. The failure mode being guarded against is "bump
# the once pin and something silently changed," and a golden diff turns that
# into a loud failure at exactly the moment the pin moves.
#
#   ./scripts/golden.sh            check
#   ./scripts/golden.sh --accept   regenerate after an intended change
#
# Goldens are rendered against the pins in deps.edn, not against a sibling
# checkout — bb.edn only local-roots walter itself. Setting ONCE_LIB_ROOT while
# running this compares the working tree against the pinned goldens, which is a
# useful thing to do on purpose and a confusing one to do by accident.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/test/fixtures/colors.yml"
goldens="$root/test/resources/golden"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

accept=0
[ "${1:-}" = "--accept" ] && accept=1

if [ -n "${ONCE_LIB_ROOT:-}" ]; then
  echo "note: ONCE_LIB_ROOT=$ONCE_LIB_ROOT — comparing a working tree against pinned goldens"
fi

build_variant() {
  local variant=$1
  shift
  (
    cd "$root"
    env COLORS_PAR_WORKDIR="$tmp/$variant" "$@" ./green build -f "$state" >/dev/null
  )
  # No rendered artefact may carry a real secret into a committed golden.
  # Checked before --accept copies anything. POSIX grep on purpose: a missing
  # binary inside `if` is simply false, so the guard must not depend on one
  # that may be absent.
  if grep -rEq 'client-key-data|client-certificate-data|BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_' "$tmp/$variant"; then
    echo "golden: FAIL — $variant rendered a credential-shaped value" >&2
    exit 1
  fi
  if [ "$accept" = 1 ]; then
    rm -rf "${goldens:?}/$variant"
    mkdir -p "$goldens/$variant"
    cp -r "$tmp/$variant/." "$goldens/$variant/"
    echo "  accepted — $variant"
  else
    if [ ! -d "$goldens/$variant" ]; then
      echo "golden: FAIL — no committed golden for $variant; run ./scripts/golden.sh --accept" >&2
      exit 1
    fi
    diff -qr "$goldens/$variant" "$tmp/$variant"
    echo "  ok — $variant"
  fi
}

# Both sides of the oci-image-id branch: the unpinned side renders a data source
# and the pinned side renders none, so a template-engine change shows up here
# rather than in production.
build_variant oci
build_variant oci-pinned \
  COLORS_PAR_OCI_IMAGE_ID=ocid1.image.oc1.eu-frankfurt-1.aaaaaaaafixtureimage
build_variant hcloud COLORS_PAR_PROVIDER_COMPUTE=hcloud
build_variant digitalocean COLORS_PAR_PROVIDER_COMPUTE=digitalocean
build_variant yandex COLORS_PAR_PROVIDER_COMPUTE=yandex
build_variant no-infra COLORS_PAR_PROVIDER_COMPUTE=no-infra
build_variant s3 COLORS_PAR_PROVIDER_BACKEND=s3
build_variant r2 COLORS_PAR_PROVIDER_BACKEND=r2

# --------------------------------------------------------------------------
# The resource address walter's extra output depends on.
#
# outputs.tf says `oci_core_instance.ampere_vm.id`, and that resource is
# declared in ONCE's template, not walter's. A rename upstream would otherwise
# surface as an opaque `tofu validate` failure during a real apply — against
# live infrastructure, half way through a create. Assert it here instead.

compute_main="$tmp/oci/walter-fixture/walter-compute/main.tf"
compute_outputs="$tmp/oci/walter-fixture/walter-compute/outputs.tf"

grep -q 'resource "oci_core_instance" "ampere_vm"' "$compute_main" || {
  echo "golden: FAIL — ONCE's compute template no longer declares" >&2
  echo "  resource \"oci_core_instance\" \"ampere_vm\"" >&2
  echo "which walter's outputs.tf references. Update the pin deliberately." >&2
  exit 1
}
echo "  ok — ONCE still declares oci_core_instance.ampere_vm"

grep -q 'oci_core_instance.ampere_vm.id' "$compute_outputs" || {
  echo "golden: FAIL — walter's outputs.tf no longer publishes the instance id" >&2
  exit 1
}
echo "  ok — walter publishes instance_id from it"

# The stage name is load-bearing: remote state is keyed <profile>/<tool>, and a
# walter-specific stage is what stops a colliding profile addressing another
# package's state. Catch a rename here rather than on a shared bucket.
[ -d "$tmp/oci/walter-fixture/walter-compute" ] || {
  echo "golden: FAIL — the compute stage is no longer named walter-compute" >&2
  exit 1
}
echo "  ok — compute stage is walter-compute"

# Only providers walter can power cycle get the extra output.
[ -f "$tmp/hcloud/walter-fixture/walter-compute/outputs.tf" ] && {
  echo "golden: FAIL — hcloud is not stoppable but rendered outputs.tf" >&2
  exit 1
}
echo "  ok — non-stoppable providers render no instance-id output"

if [ "$accept" = 1 ]; then
  echo "goldens regenerated"
else
  echo "every provider variant matches its committed golden"
fi
