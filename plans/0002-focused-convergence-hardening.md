# Plan: harden walter's focused convergence feature (converge-nix / converge-asdf)

_Locked via claudex-loop — by Claude + the user_

Base commit: `b75e53d` ("Add focused Nix and asdf convergence on an existing
machine"). Everything below is a fix on top of that commit; the feature itself
is not being redesigned.

## Goal

The two focused convergence events work and are documented, but six defects
found in assessment leave rendered surface outside the regression net, let an
undeclared convergence fail on the machine instead of on the workstation,
silently exclude dotted nixpkgs attributes from every upgrade, and assert an
idempotence property nothing has exercised. Close all six, and settle the
question the feature raises about walter's never-migrate rule by writing down a
narrow exemption for verb-only pin bumps together with the mechanical test that
decides whether a bump qualifies.

The exemption ships as **documentation only**. Phase 1 asked for it to be proven
on one existing deployment; Codex rounds 1-2 established that no such proof is
available here — the pin a deployment would move to carries this work's
create-rendered template edit, so exercising the exemption on `walter-many`
would break the rule the exemption is scoped by. No deployment repository is
touched.

## Approach

### 1. Refuse converging what was never declared (validate, exit 2)

`converge-asdf` on a project with no `asdf-tools` renders three tasks with a
bare `loop:` (null); `converge-nix` with no `nix-packages` renders `nix profile
add --impure` with no installable and `… | length < 0`. Both currently fail on
the machine, after connecting, with an opaque message. The stage's prerequisite
assert does not catch it: `asdf-vm` can be present in `nix-packages` while
`asdf-tools` is empty.

- Add event-aware rules to `validate.clj`, in the same style and message shape
  as the existing `:asdf-tools`/`:corepack-packages` rules:
  - `:green/event :converge-nix` with no usable `nix-packages` entry →
    `":converge-nix needs at least one entry in :nix-packages — there is nothing to converge"`
  - `:green/event :converge-asdf` with no usable `asdf-tools` entry →
    `":converge-asdf needs at least one entry in :asdf-tools — there is nothing to converge"`
- **"Usable" must mean what rendering means.** The refusal has to run over the
  *normalized* entries, not the raw key: `asdf-tools` entries with a blank or
  missing `name`/`version`, and `nix-packages` entries that are blank strings,
  otherwise pass validation and still render a play with nothing to do. There
  must be exactly one normalization in the codebase: move `nix-package-names`
  and `asdf-tools` from `tools.clj` into `validate.clj` (which `tools.clj`
  already requires, and which owns `user-names` for the same reason) and let
  `tools.clj` refer to them, rather than writing a second emptiness test.
- **Every existing rule and gate must consume the shared normalization too**, or
  the codebase reports inconsistent things about the same desired state: the
  `:asdf-tools`-needs-`asdf-vm` rule currently tests raw `(seq (:asdf-tools
  opts))` and the `:corepack-packages`-needs-`nodejs` rule searches raw entries,
  so an entry that normalizes away (blank `name` or `version`) can satisfy them
  while rendering nothing. Both rules, the `nix-packages` membership checks that
  back them, and `data-fn`'s rendering gates read the normalized values after
  this change. Tests must cover a malformed entry reaching each of those rules.
- The rules must not fire for any other event, so `create` on a project that
  declares neither key keeps working exactly as it does today.
- Tests in `validate_test.clj`: one per event for the refusal, one per event for
  malformed entries that normalize away to nothing, and one proving `:create`
  and `:build` are unaffected by the same desired state.

### 2. `build` renders the converge stages, and the goldens cover them

`golden.sh` renders `build`, and `build` never reaches the converge steps, so
`walter-converge-*/{main.yml,ansible.cfg,inventory.json}` sit outside walter's
only cross-provider regression net.

- Extend the `:build` branch of `wire-fn` so the graph also reaches
  `:walter/converge-nix` and `:walter/converge-asdf`. `:create` must **not**
  reach them — the two graphs stop being identical, and
  `build-runs-the-same-graph-as-create` has to be replaced rather than deleted.
  Its replacement asserts the **full reachable-node set** for `:build` and for
  `:create` — a successor-by-successor check can be satisfied by an incomplete
  walk of a branching DAG — and asserts explicitly that neither converge node
  is reachable from `:create`.
- `converge-step` returns after scaffolding when `:green/event` is `:build`,
  the way `ansible-remote-step` already short-circuits, so no Ansible call and
  no SSH is attempted by a build.
- Render each converge stage only when its own declaration is non-empty — the
  same condition as the refusal in step 1. A project with no `asdf-tools`
  renders no `walter-converge-asdf/` directory at all, matching the precedent
  set by `walter-emacs-packages` and `walter-ansible-seats`.
- `bb golden:accept` regenerates; the two new stage directories appear in all
  nine provider variants (the fixture declares both keys), and the diff must be
  read before acceptance — the create-path goldens must be **unchanged** by
  this step.
- Add invariant checks to `golden.sh`, beside the existing hand-written ones.
  These must **parse** `inventory.json` rather than grep it — a textual
  presence/absence check passes on a duplicated, missing, or extra host — using
  `bb -e` with `cheshire.core`, which is guaranteed present because `bb` is what
  runs `golden.sh`:
  - the host-key set is **exactly** the alias plus one `<alias>-<seat>` per seat
    the fixture declares, in that order and with no duplicates;
  - every host's var map is **empty** — no `ansible_host`, no `ansible_user`,
    nothing state-derived, because the focused events resolve through
    `~/.ssh/config` alone;
  - the stages are named `walter-converge-nix` / `walter-converge-asdf`.
- The "renders nothing when the key is absent" case stays a single `tools_test`
  assertion rather than a tenth golden variant, following the precedent stated
  in `CLAUDE.md` for the Emacs stage.
- **Existing tests that break and must be updated, not deleted:**
  `focused-convergence-needs-no-provider-credentials` and
  `a-dry-run-touches-nothing` (both in `workflow_test.clj`) drive the focused
  events from `vt/base`, which declares neither `nix-packages` nor
  `asdf-tools` — after step 1 they exit 2 instead of 0. Give each the
  event-specific declaration it needs while preserving what the test proves
  (no provider credentials; a dry run touches nothing).

### 3. Match dotted nixpkgs attributes when selecting stale elements

The resolver compares `attrPath.rsplit(".", 1)[-1]` against the declared names,
so a `nix-packages` entry written as `python3Packages.foo` installs correctly
and is then silently excluded from every upgrade — no warning, no failure.

- Strip the leading `legacyPackages.<system>.` / `packages.<system>.` prefix and
  compare the remaining attribute path to the declared name **exactly**. The
  last-segment comparison is removed, not kept as a fallback: retaining it means
  a declared bare `foo` matches and upgrades an element installed from
  `python3Packages.foo`, which contradicts the guarantee that only declared
  entries are advanced — and `originalUrl` does not save it, since both come
  from the same nixpkgs flake.
- Strip only a prefix that is actually a prefix: the first segment is
  `legacyPackages` or `packages` and the second looks like a Nix system
  (`<arch>-<os>`). Anything else is compared whole, so an unexpected `attrPath`
  shape fails to match rather than matching something arbitrary.
- The `originalUrl == <nixpkgs-ref>` condition stays exactly as it is: it is
  what keeps a coincidentally named element from another flake out of the
  upgrade set, and it must keep doing so under the widened attribute match.
- Extend the `tools_test` assertions on the rendered task file to pin the new
  matching rule.

### 4. Earn — or withdraw — the idempotence claim

No live run stands behind `SKILL.md`'s "A second real run must report no
changes", and the property rests on `changed_when: "'upgrading ' in
walter_nix_upgrades.stdout + walter_nix_upgrades.stderr"`, a string match on
Nix's output wording that nothing tests.

- Rewrite the claim in `SKILL.md` to state what is actually guaranteed: every
  task is safely re-runnable; `converge-asdf` reports no change on a converged
  machine; `converge-nix` reports a change when Nix actually advances an
  element, and its change detection reads Nix's own wording, so an upstream
  rewording misreports the change flag without affecting what is installed.
- Comment the `'upgrading '` fragment in `tasks/nix-packages.yml` the way its
  sibling tasks already document the `already added` / `already installed`
  fragments and which asdf versions word them differently.
- Keep `configuration.md` and `README.md` consistent with the softened wording.

### 5. Remove the dead template key

`data-fn` computes `:nix-package-names-json`, no template references it, and the
same JSON is serialized a second time for `:nix-package-names-b64`. Serialize
once, bind the b64 form only, drop the unused key.

### 6. Document the host-key asymmetry

The converge `ansible.cfg` deliberately omits the `host_key_checking = False`
and `StrictHostKeyChecking=no` that `ansible-remote` carries — a better posture,
already pinned by a test, but it means a machine recreated behind the same alias
converges under `create` and refuses under `converge-*`. Add one troubleshooting
entry (`SKILL.md`, and the matching section of `index.html`) naming the symptom
and the fix, and one sentence in the `ansible.cfg` template or `converge-step`
docstring recording that the omission is deliberate. The entry must describe
`known_hosts` as SSH actually keys it — by the **address SSH names in the
error**, not by alias: walter's managed blocks set no `HostKeyAlias` and every
seat alias shares one `HostName`, so there is a single entry covering all
logins, and `walter-many/CLAUDE.md` already states that accepting once covers
the seats. The fix is `ssh-keygen -R <address-from-the-error>`; do not claim
per-alias entries, and note that a hashed `known_hosts` cannot be repaired by
eye. The failure this addresses is the narrow one `accept-new` cannot absorb —
same address, different key, as after a rebuild onto a reserved address.

### 7. Write down the never-migrate exemption and its qualification test (documentation only)

Walter deployments are never migrated to a newer pin. Taken literally that makes
these verbs unreachable on every machine that exists today, which is the exact
population they were written for.

- Record the carve-out in `walter/CLAUDE.md`'s Git section: a pin bump whose
  only consumer-visible effect is **new launcher verbs** may be re-copied into
  an existing deployment, because the create graph's rendered output is
  unchanged and no existing stage is re-rendered. Anything that changes rendered
  create output stays under the original rule.
- **This work does not qualify for its own carve-out, and the deployment proof
  is therefore not part of it.** Steps 3 and 4 edit `tasks/nix-packages.yml`,
  which the *create* stage renders, so `b75e53d` plus these fixes is not a
  launcher-only change by the carve-out's own definition. Re-copying it into
  `walter-many` would hand an existing machine a re-rendered create stage —
  precisely what the never-migrate rule exists to prevent.
- What ships here is the **rule and its precondition**, written down, plus the
  mechanical test that decides whether a pin bump qualifies. The test is over
  the **cumulative delta between the SHA the deployment currently runs and the
  candidate SHA** — not over one commit. The launcher stamps a single SHA and
  resolves the whole library at it, so a "launcher-only commit" on top of a
  template change still hands the deployment that template change.
- The test has **two conditions, and both must hold**. Render equality alone is
  not enough: a candidate could change `delete`, `stop`/`start`, validation, or
  credential handling without changing a single rendered byte and still pass it.
  - **(a) Change allowlist.** Over the cumulative delta from the pinned SHA to
    the candidate, the only files that may differ are the launcher's command
    registration, the focused events' own wiring, steps and resources, tests,
    and documentation. Any edit to the behaviour of an existing event — its
    steps, its templates, its validation rules — disqualifies the bump outright,
    whether or not it moves a rendered byte.
  - **(b) Render equivalence**, as a pass/fail assertion over the deployment's
    own desired state, each SHA rendered into its **own** workdir so nothing
    from the first run or the deployment's ordinary `.colors/` tree contaminates
    the second:

    ```sh
    # for each of <deployment-pinned-sha> and <candidate-sha>
    git -C ~/code/getcolors/walter worktree add "$tmp/src-$sha" "$sha"
    (cd <deployment> && WALTER_LIB_ROOT="$tmp/src-$sha" \
       COLORS_PAR_WORKDIR="$tmp/out-$sha" ./green build)

    diff -qr --exclude=walter-converge-nix --exclude=walter-converge-asdf \
      "$tmp/out-<pinned>/<profile>" "$tmp/out-<candidate>/<profile>"
    ```

    Exit 0 is the qualification. The two converge directories are excluded
    because the older SHA cannot render them at all — that asymmetry is the
    feature, not a difference in what a create does. `diff -qr` is the assertion
    because `git diff --stat` only describes.
- **Name the guarantee honestly: this is build-render equivalence, not create
  equivalence.** After step 2 the two graphs deliberately differ, and a future
  step that branches on `:green/event` inside a shared stage would escape (b)
  entirely. Condition (a) is what covers that case, and the documentation says
  so rather than implying the render diff proves more than it does.
- Under that test `walter-many` (pinned `d743117`) **has no qualifying upgrade
  path to this work at all**, now or later: every candidate SHA on this branch
  carries steps 3-4's create-rendered template edit. The only shape that could
  qualify is a verb-only branch based on `d743117` that renders the converge
  stages from their own task copies and leaves `ansible-remote/` untouched —
  which deliberately reintroduces the second copy this feature exists to remove.
  That trade is the user's to make, and it is **out of scope here**; no rollout
  path is claimed.
- `WALTER_LIB_ROOT` is explicitly **not** a substitute for any of this: it
  exercises the working-tree library, not the artifact a deployment runs.

## Key decisions & tradeoffs

- **`build` renders the converge stages (chosen) over a bespoke render hook in
  `golden.sh`.** The build contract widens — `./green build` now renders two
  more directories — and every committed golden gains them. Bought: the new
  surface is covered by the same nine-variant net as everything else, with no
  second render path that only the harness exercises. `:build` and `:create`
  stop rendering the same tree, which is a real loss of a simple invariant and
  is why the existing equality test is replaced rather than deleted.
- **Refusal in `validate` (chosen) over a playbook-level assert.** Exit 2 before
  rendering, listing every problem at once, is the contract every other
  impossible combination in this repo already follows; failing on the machine
  after connecting is strictly worse feedback.
- **Widen the attribute match to exact full-path equality, keep the
  `originalUrl` guard.** The guard is what keeps a coincidentally named element
  from *another flake* out of the upgrade set; exactness is what keeps a
  different attribute *within nixpkgs* out of it. They cover different failures,
  so neither substitutes for the other — which is why the last-segment fallback
  is dropped rather than retained for compatibility.
- **Soften the docs rather than run a live converge.** Two real runs against
  `walter-many` would earn the claim, at the cost of a live operation on a
  machine three people's homes live on. The claim is worth less than the risk.
- **Exemption limited to launcher-only verb additions — and this change is not
  one.** The rule exists because a pin bump can re-render templates against a
  machine built under older semantics. A verb-only addition provably cannot;
  anything else stays frozen. Steps 3-4 touch a create-rendered template, so
  this commit is on the frozen side of its own rule and no deployment is
  re-copied. Writing the rule down without immediately exercising it is the
  honest outcome; claiming the proof would mean weakening the rule to fit the
  work in hand.

## Assumptions

1. The six assessment findings are the whole fix list; no new capability is in
   scope. — source: the user's instruction
2. The create path changes in exactly three enumerable ways, and no others —
   this replaces the earlier, self-contradictory "byte-identical apart from
   step 2":
   - `walter-ansible-remote/main.yml`: **byte-identical** to `b75e53d` in every
     one of the nine golden variants;
   - `walter-ansible-remote/nix-packages.yml`: changes only in the resolver's
     matching rule (step 3) and its comments (step 4) — the `nix profile add`
     task and both `changed_when` expressions are untouched;
   - `walter-ansible-remote/asdf.yml`: byte-identical;
   - no new directory appears under any *create* stage; the two new stage
     directories are reachable from `:build` only.
   Semantically, nothing a create does on a machine changes: the upgrade tasks
   stay gated behind `walter_nix_upgrade`, which only `converge-nix` passes.
   — source: normalized diff vs `HEAD~1`, `bb golden` at `b75e53d`
3. Fixes land in `walter/` only. No deployment repository is touched by this
   work — see step 7. — source: workspace `CLAUDE.md`, Codex round 1
4. `validate.clj` refuses impossible desired state with exit 2 and lists every
   problem at once. — source: `validate.clj:344-360`, workspace `CLAUDE.md`
5. `golden.sh` is walter's only cross-provider regression net; its invariants are
   hand-written assertions beside the diffs. — source: `scripts/golden.sh`,
   `walter/CLAUDE.md`
6. Nix's profile JSON is `{"elements": {<name>: {attrPath, originalUrl, url, …}}}`
   and `nix flake metadata --json` reports `url` in the same
   `github:NixOS/nixpkgs/<rev>` form the profile stores — verified live on
   Determinate Nix 3.22.2 / 2.35.2 against a real walter-provisioned profile
   (24 of 25 declared entries resolved and correctly flagged stale).
   — source: session verification, 2026-08-30
7. `nix profile upgrade <element-names>` takes element names positionally and
   only upgrades elements installed from an unlocked flake reference — which is
   what `originalUrl == github:NixOS/nixpkgs/nixpkgs-unstable` proves.
   — source: `nix profile upgrade --help`, same machine
8. `corepack` moving inside `asdf.yml` is behaviour-preserving because
   `validate.clj:351` already refuses `corepack-packages` without a `nodejs`
   entry in `asdf-tools`. — source: `validate.clj`
9. `bb pin`, the stamp commit, and pushing are outside this loop; the final
   commit is a normal feature commit on `main`. — source: `walter/CLAUDE.md`,
   the user's instruction

## Risks / open questions

- **The user asked for the exemption to be proven on one deployment, and this
  plan does not do it.** Round 1 established that these fixes are not a
  launcher-only change, so the proof would violate the rule it is meant to
  demonstrate. Raised at sign-off: accept the rule shipping unexercised, or
  authorize a separate launcher-only pin afterwards to carry the proof.
- Removing the last-segment fallback is a behaviour change for any project that
  declares a bare attribute whose element was installed from a dotted path. No
  such case exists in any current deployment's `nix-packages` — verified against
  a live 26-element profile, where every declared name equalled its element name
  — but it is the one way this fix could stop upgrading something it upgrades
  today.
- `walter-many` runs pin `d743117`; nothing here changes what that machine was
  built under, but the exemption is a genuine relaxation of a rule the user set
  deliberately, and its scope has to stay exactly as narrow as written.
- Moving `nix-package-names` and `asdf-tools` into `validate.clj` touches a
  namespace boundary `CLAUDE.md` guards. It is a move, not a new concern, and
  the alternative — a second emptiness rule that drifts from what rendering
  does — is the failure Codex round 1 named.

## Out of scope

- Redesigning the feature committed in `b75e53d`.
- Any live `create`, `delete`, or real `converge` run against any machine.
- `bb pin`, the stamp commit, and pushing any repository.
- Migrating `walter-oci`, `walter-ada`, `walter-liliana`, or `walter-vultr`.
- A `describe` verb, or making `converge-*` refresh the SSH alias itself.
