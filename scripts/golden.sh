#!/usr/bin/env bash
set -euo pipefail

# Review shared/node compute and application artifacts against pinned dependencies.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/test/fixtures/colors.yml"
goldens="$root/test/resources/golden"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

accept=0
[ "${1:-}" = "--accept" ] && accept=1

if [ -n "${COLORS_COMPUTE_LIB_ROOT:-}" ]; then
  echo "note: COLORS_COMPUTE_LIB_ROOT=$COLORS_COMPUTE_LIB_ROOT — comparing a working tree against pinned goldens"
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
build_variant aws COLORS_PAR_PROVIDER_COMPUTE=aws
build_variant azure COLORS_PAR_PROVIDER_COMPUTE=azure
build_variant google COLORS_PAR_PROVIDER_COMPUTE=google
build_variant oci
build_variant oci-pinned \
  COLORS_PAR_OCI_IMAGE_ID=ocid1.image.oc1.eu-frankfurt-1.aaaaaaaafixtureimage
build_variant hcloud COLORS_PAR_PROVIDER_COMPUTE=hcloud
build_variant digitalocean COLORS_PAR_PROVIDER_COMPUTE=digitalocean
build_variant vultr COLORS_PAR_PROVIDER_COMPUTE=vultr
build_variant yandex COLORS_PAR_PROVIDER_COMPUTE=yandex
build_variant vultr-external COLORS_PAR_PROVIDER_COMPUTE=vultr COLORS_PAR_VULTR_SSH_KEYS=fixture-account-key
build_variant s3 COLORS_PAR_PROVIDER_BACKEND=s3
build_variant r2 COLORS_PAR_PROVIDER_BACKEND=r2

# Focused convergence resolves entirely through the managed SSH aliases. Parse
# the inventory rather than grepping it: a grep passes on a missing or extra
# host, and on host vars that appear anywhere in the file. Duplicate keys are
# not checkable here — a JSON object collapses them before any reader sees
# them — so `alias-inventory` deduplicates at the source instead.
for v in aws azure google oci oci-pinned hcloud digitalocean vultr yandex vultr-external s3 r2; do
  for stage in walter-converge-nix walter-converge-asdf; do
    inventory="$tmp/$v/walter-fixture/$stage/inventory.json"
    [ -f "$inventory" ] || {
      echo "golden: FAIL — $v did not render $stage" >&2
      exit 1
    }
    bb -e '(require (quote [cheshire.core :as json]))
           (let [hosts (get-in (json/parse-string (slurp (first *command-line-args*)))
                               ["all" "hosts"])
                 expected ["walter-fixture" "walter-fixture-jack" "walter-fixture-emma"]]
             (when-not (= expected (vec (keys hosts)))
               (binding [*out* *err*] (println "unexpected focused inventory hosts:" (vec (keys hosts))))
               (System/exit 1))
             (when-not (every? empty? (vals hosts))
               (binding [*out* *err*] (println "focused inventory contains host vars:" hosts))
               (System/exit 1)))' "$inventory"
  done
done
echo "  ok — focused stages use exactly the managed aliases and no host vars"

# The library owns split state and all provider resource addresses.
for v in aws azure google oci oci-pinned hcloud digitalocean vultr yandex vultr-external s3 r2; do
  for artifact in shared/backend.tf.json nodes/0/backend.tf.json; do
    test -f "$tmp/$v/walter-fixture/walter-compute/$artifact"
  done
done
# Every root-login image uses the same application bootstrap.
for v in hcloud digitalocean vultr vultr-external; do
  bootstrap="$tmp/$v/walter-fixture/walter-ansible-bootstrap"
  test -f "$bootstrap/main.yml"
  for setting in 'PermitRootLogin no' 'PasswordAuthentication no' 'NOPASSWD: ALL'; do
    grep -q "$setting" "$bootstrap/main.yml"
  done
  grep -q '"ansible_user" : "ubuntu"' "$tmp/$v/walter-fixture/walter-ansible-remote/inventory.json"
done
test ! -d "$tmp/oci/walter-fixture/walter-ansible-bootstrap"
grep -q 'src: /root/.ssh/authorized_keys' "$tmp/vultr-external/walter-fixture/walter-ansible-bootstrap/main.yml"
echo "  ok — split state, normalized login bootstrap, managed and external keys"

# --------------------------------------------------------------------------
# Seats: real unix logins beside the primary one, isolated by file
# permissions. The fixture names two, so every variant renders the stage; the
# claim is checked at its sharpest points — no sudo grant in the seat play,
# and the stages that provision each home connecting as each seat.

seats="$tmp/oci/walter-fixture/walter-ansible-seats"
[ -f "$seats/main.yml" ] || {
  echo "golden: FAIL — the fixture names seats but no seat stage rendered" >&2
  exit 1
}
if grep -q 'NOPASSWD' "$seats/main.yml"; then
  echo "golden: FAIL — the seat play grants sudo; a sudoer can read every home" >&2
  exit 1
fi
grep -q '"ansible_user" : "ubuntu"' "$seats/inventory.json" || {
  echo "golden: FAIL — the seat stage no longer connects as the primary login" >&2
  exit 1
}
grep -q '"walter-fixture-jack"' \
  "$tmp/oci/walter-fixture/walter-ansible-remote/inventory.json" || {
  echo "golden: FAIL — the remote inventory no longer connects as each seat" >&2
  exit 1
}
grep -q 'ssh_hosts' \
  "$tmp/oci/walter-fixture/walter-ansible-local/main.yml" || {
  echo "golden: FAIL — the local play no longer manages a block per seat" >&2
  exit 1
}
echo "  ok — seats render without sudo, and each home is provisioned as its own login"

if [ "$accept" = 1 ]; then
  echo "goldens regenerated"
else
  echo "every provider variant matches its committed golden"
fi
