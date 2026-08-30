# Plan Review Log: harden walter's focused convergence feature

Phases 0-1 (recon + interrogation) complete — plan locked with the user.
MAX_ROUNDS=5. Base commit b75e53d. Reviewer model: gpt-5.6-sol
(service_tier=fast, pinned by the skill) — codex-cli 0.147.0.

## Round 1 — Codex

Material flaws found:

- The proposed pin exemption is inapplicable to this commit: steps 3–4 change `nix-packages.yml`, which is rendered into the normal create stage, contradicting PLAN.md’s requirement that launcher-only bumps leave create output unchanged. Fix: remove step 7 from this change or prove byte-identical create output and ship the exemption separately in a genuinely launcher-only commit.
- `WALTER_LIB_ROOT` proves the working-tree library path, not the copied/stamped launcher or its pin, as the plan itself admits. Fix: defer the deployment proof until the commit is pushed, pinned, copied, and tested as the actual deployment artifact.
- Retaining last-segment fallback makes `foo` match and upgrade `python3Packages.foo`, violating the stated guarantee that only declared entries are advanced; `originalUrl` merely limits this to the same nixpkgs flake. Fix: strip known system prefixes and require exact equality with the remaining declared attribute path—remove the fallback.
- The proposed `converge-asdf` emptiness rule says “no `asdf-tools` entry,” but rendering uses normalized `tools/asdf-tools`; entries with blank/missing name or version can pass validation while producing no tasks. Fix: validate normalized usable entries with a shared normalization function, including malformed-entry tests.
- The plan’s test update list misses existing tests that will immediately fail: `focused-convergence-needs-no-provider-credentials` and `a-dry-run-touches-nothing` invoke focused events using `vt/base`, which declares neither package set. Fix: update those fixtures with the event-specific declarations while preserving what each test is intended to prove.
- “Build = create plus two stages” is underspecified for a branching DAG and can be satisfied by incomplete successor checks. Fix: assert the full reachable-node sets for both events and explicitly assert both converge nodes are unreachable from `:create`.
- The golden inventory checks are described as textual presence/absence checks and may not catch duplicate, missing, or unexpected hosts. Fix: parse `inventory.json` and assert its host-key set exactly equals the expected alias-plus-seats set and that every host-var map is empty.
- The host-key recovery guidance is incomplete for multiple seat aliases and hashed `known_hosts`; clearing one alias may leave every `<alias>-<seat>` failing independently. Fix: document and test removal for the primary alias and every declared seat alias using `ssh-keygen -R`.
- Assumption 2 is internally contradictory: it requires create output to remain byte-identical “apart from what step 2 adds,” while step 2 explicitly must not affect create and steps 3–4 do affect its rendered files. Fix: replace it with separately testable semantic and byte-level invariants that accurately enumerate intended create-output changes.
- Step 7 proposes modifying another repository despite listing pinning/pushing as out of scope and lacking the prerequisite stamped artifact, leaving the plan knowingly unfinishable. Fix: make deployment rollout a separately authorized follow-up with explicit repository scope and prerequisites.

VERDICT: REVISE
### Claude's response

All ten findings accepted; none rejected. Changes to PLAN.md:

1. Pin exemption / step 7 — the sharpest finding, accepted in full. Steps 3-4
   edit `tasks/nix-packages.yml`, which the create stage renders, so this work
   fails the carve-out's own launcher-only precondition. The deployment proof
   is removed: the rule and its mechanical test ship, `walter-many` is not
   touched, and rollout becomes a separately authorized follow-up with its
   prerequisites written out. Escalated to the user at sign-off, because it
   contradicts the answer they gave in Phase 1.
2. `WALTER_LIB_ROOT` — accepted, now stated explicitly as *not* a substitute
   for the stamped artifact.
3. Last-segment fallback — removed rather than retained. Added the rationale
   that `originalUrl` and exactness cover different failures, plus a prefix
   rule that only strips `<legacyPackages|packages>.<arch>-<os>.`.
4. Normalization — accepted. `nix-package-names` and `asdf-tools` move into
   `validate.clj` so the refusal runs over exactly what rendering consumes;
   malformed-entry tests added.
5. Pre-existing tests that would break — accepted verbatim;
   `focused-convergence-needs-no-provider-credentials` and
   `a-dry-run-touches-nothing` are named as must-update-not-delete.
6. Reachable-node sets — accepted; the replacement test asserts full sets for
   both events and unreachability from `:create`.
7. Golden inventory checks — accepted; they now parse `inventory.json` with
   `bb -e` and assert the exact host-key set and empty var maps.
8. Host-key recovery — accepted; `ssh-keygen -R` for the primary alias and
   every seat alias, with hashed `known_hosts` called out.
9. Assumption 2 — accepted; replaced with a four-part enumeration of exactly
   how the create path may change, plus the semantic invariant.
10. Step 7 unfinishable — same fix as 1.

## Round 2 — Codex

Most prior findings are addressed, but step 7 remains materially unsound:

- Qualification is measured against the wrong delta. A future launcher-only/stamp commit would point an old deployment from `d743117` to the cumulative new library, including steps 3–4’s create-rendered template changes; checking only the final commit’s diff does not make that pin bump launcher-only. Fix: compare the deployment’s currently pinned SHA against the candidate pinned SHA across `src/resources/` and create goldens; this change cannot qualify, so remove the claimed future rollout path or use a separately backported verb-only commit based on the old pin.
- “A genuinely launcher-only commit on top of this one” cannot isolate the new verbs: the launcher resolves the entire library at its stamped SHA, not selected commits or features. Fix: describe a verb-only backport branch/commit whose cumulative delta from `d743117` contains only the required launcher/API additions, or withdraw the exemption proof.
- Moving normalization into `validate.clj` is insufficient unless all existing dependent rules use it. The current `asdf-vm` prerequisite checks raw `(seq (:asdf-tools opts))`, and the Corepack rule searches raw entries, producing misleading or inconsistent errors for entries that normalize away. Fix: explicitly require the existing asdf/Corepack validation rules and all rendering gates to consume the shared normalized functions.
- The host-key section asserts every seat alias has an independent stale entry, conflicting with `walter-many/CLAUDE.md`, which says accepting the shared `HostName` once covers the seats. Fix: avoid asserting storage behavior; instruct users to remove the exact hostname or address reported by SSH, plus affected aliases only when present.

The validation, full-DAG assertions, parsed inventory checks, exact Nix attribute matching, broken-test updates, and corrected create-output assumptions adequately address the other prior findings.

VERDICT: REVISE
### Claude's response

All four accepted; none rejected.

1-2. Cumulative-delta point accepted — the sharpest finding of the loop. The
   qualification test is now written as a diff between the *deployment's pinned
   SHA* and the *candidate SHA* over `src/resources/` and the create goldens,
   not as a per-commit check, and the plan states plainly that walter-many has
   no qualifying path to this work at all. The only shape that could qualify —
   a verb-only branch off `d743117` that gives the converge stages their own
   task copies — is named, costed (it reintroduces the second copy the feature
   removes), and left out of scope as the user's call.
3. Raw-vs-normalized rules accepted: the existing `asdf-vm` and `corepack`
   rules and `data-fn`'s gates must all read the shared normalization, with
   malformed-entry tests reaching each rule.
4. Host-key claim accepted and independently verified before revising: the
   ansible-local template sets no `HostKeyAlias` and all aliases share one
   `HostName`, so there is one address-keyed entry, exactly as
   `walter-many/CLAUDE.md` says. The entry now says `ssh-keygen -R
   <address-from-the-error>` and names the narrow same-address-different-key
   case that `accept-new` cannot absorb.

## Round 3 — Codex

The technical fixes from prior rounds are now addressed: shared normalization is applied consistently, Nix matching is exact, DAG coverage is explicit, inventory checks are parsed, and host-key guidance no longer assumes per-alias entries.

Two material problems remain:

- The stated goal still requires proving the exemption on one deployment, while step 7 explicitly declines that proof and lists it as unresolved. Fix: either remove deployment proof from the goal and rename step 7 as documentation-only, or obtain authorization and provide a genuinely executable proof plan.
- The proposed qualification test is internally impossible: `test/resources/golden` will contain the new build-only converge directories, so its cumulative diff cannot be empty; moreover, new verbs require workflow/library changes because CLAUDE.md forbids launcher logic, contradicting “the only other difference may be the launcher.” Fix: define allowed cumulative source changes explicitly and compare separately rendered `create` outputs between the deployment pin and candidate SHA, rather than diffing build goldens or requiring a launcher-only file delta.
- `git diff --stat` is descriptive output, not a mechanical pass/fail assertion. Fix: render both SHAs into temporary directories and use `diff -qr`, or use `git diff --quiet` only for precisely identified paths whose emptiness is actually required.

VERDICT: REVISE
### Claude's response

All three accepted.

1. Goal rewritten: the exemption ships as documentation only, and the goal now
   states why the proof is unavailable instead of promising it. Step 7 renamed
   accordingly.
2. Qualification test redefined. It is no longer a source diff — new verbs must
   change the library (CLAUDE.md forbids launcher logic) and step 2 puts the
   converge directories into the committed goldens, so neither path can be
   required to match. The test now renders the *deployment's own* colors.yml at
   both SHAs through WALTER_LIB_ROOT worktrees and compares the create-path
   stages, excluding the two build-only converge directories the older SHA
   cannot render.
3. `diff -qr` replaces `git diff --stat`, so the test is a pass/fail assertion
   rather than descriptive output.

## Round 4 — Codex

The previous findings are mostly addressed, but the qualification test still does not prove the exemption it documents:

- Render equality alone cannot establish that the “only consumer-visible effect” is new verbs; a candidate could change validation, delete, stop/start, credential handling, or other runtime behavior without changing rendered files and still qualify. Fix: combine render equality with a cumulative diff allowlist limited to command registration, focused-event wiring/steps/resources, tests, and documentation, rejecting changes to existing-event behavior.
- The test claims to compare create output but runs `build`; those graphs deliberately diverge after step 2, and future code may branch on `:green/event` inside an existing stage. Fix: add a dedicated test harness that scaffolds the actual create-reachable stages without executing providers or Ansible, or narrow and document the exemption as build-render equivalence rather than create equivalence.
- The sample writes both renders into the deployment’s real `.colors` tree, so stale files from the first render or ordinary deployment artifacts can contaminate the second comparison. Fix: set `COLORS_PAR_WORKDIR` to a distinct temporary directory for each SHA and compare those isolated outputs directly.
- The plan says the qualification test is “mechanical,” but the shown commands omit cleanup and do not fail explicitly if excluded converge directories contain unexpected nested create-stage material. Fix: provide a checked script with `set -euo pipefail`, `mktemp -d`, a cleanup trap, exact top-level exclusions, and assertions that excluded directories are precisely the two focused stages.

The six focused-convergence fixes themselves are otherwise sufficiently specified.

VERDICT: REVISE
### Claude's response

Two accepted, two accepted in part with reasons — all four concern step 7,
which is documentation only; the six code fixes were declared sufficient.

1. ACCEPTED. Render equality cannot prove "the only consumer-visible effect is
   new verbs". The test is now two conditions: a cumulative change allowlist
   (a) that disqualifies any edit to an existing event's behaviour whether or
   not it moves a rendered byte, plus render equivalence (b).
2. PARTIALLY ACCEPTED. The naming criticism is right and taken: the section now
   says plainly that this is build-render equivalence, not create equivalence,
   and that condition (a) is what covers a stage branching on :green/event.
   REJECTED: building a dedicated create-scaffolding harness that runs the
   create-reachable stages without providers or Ansible. That is a new test
   apparatus for a rule with no qualifying candidate today, in a plan whose own
   scope claims no rollout path; the residual it would close is named in the
   documentation instead.
3. ACCEPTED. Each SHA now renders into its own COLORS_PAR_WORKDIR, so neither
   the first render nor the deployment's ordinary .colors/ tree can contaminate
   the comparison.
4. PARTIALLY ACCEPTED. The snippet is now contamination-free and asserts rather
   than describes. REJECTED: shipping a hardened script (set -euo pipefail,
   mktemp, cleanup trap, assertions that the excluded directories contain
   exactly the two focused stages) into the repository. Nothing can qualify
   under this rule today; an executable harness for a hypothetical future bump
   is speculative work, and the documented procedure is enough for a human
   running it once under authorization.

## Round 5 — Codex

VERDICT: APPROVED. All prior material findings addressed: shared normalization,
full DAG coverage, exact Nix matching, parsed golden inventories, accurate
create-output assumptions, correct host-key guidance, and the pin exemption
treated honestly as documentation-only with this change explicitly disqualified.

## Act 3 — Build (Codex wrote, Claude reviewed)

Codex implemented all seven steps in a fresh session (thread 01a05283) under
instructions forbidding commits, pushes, `bb pin`, provider contact, and any
access outside this repository. Its own gate run: bb test 184/635, bb golden
nine variants, launcher.sh 9 checks.

### Claude's review of the Codex build — four defects fixed

1. `wire-fn`'s `:build` branch was a verbatim second copy of the whole `:create`
   graph. Replaced with a derivation: `:build` delegates to the `:create` branch
   and appends the two focused nodes at the existing fork. A create graph
   written twice is a create graph that eventually differs from itself — the
   same failure this feature removed from the task templates.
2. The `nix-package-names` docstring lost its `COLORS_PAR_NIX_PACKAGES` rationale
   and `asdf-tools` lost the `plugin` rationale in the move to `validate.clj`.
   Both restored, each with a sentence on why the rules and the templates must
   read the same normalization.
3. `converge-nix-step` / `converge-asdf-step` had no docstrings, in a namespace
   where every step carries one. Added, naming the build-time pass-through and
   its `emacs-packages` precedent.
4. Formatting slip in `a-dry-run-touches-nothing`'s merged map.

Gates re-run after the fixes: bb test 184/635, bb golden, launcher.sh — all pass.

## Post-build inspection (fresh Codex session, cold)

Thread 01a0528a, new session so the reviewer saw the code rather than its own
plan critiques. Two findings, both accepted:

- **[P2] Corepack rule still read the raw key.** `(seq (:corepack-packages
  opts))` while rendering used the normalizing `corepack-packages`, so
  `corepack-packages: ["  "]` renders no task and still demands a nodejs
  runtime. Fixed by moving `corepack-packages` into `validate.clj` alongside the
  other two and pointing both the rule and `data-fn` at it. Test added.
- **[P2] The golden inventory assertion cannot detect duplicate keys.**
  Cheshire collapses duplicate JSON object keys before `(keys hosts)` runs, so
  the check could not do what its comment claimed. Fixed at the source instead:
  `alias-inventory` now deduplicates seats, the comment says what the parser can
  and cannot see, and a `tools_test` case covers a repeated seat.

Reinspection (round 2 of 2): no new findings; `git diff --check`, bb test
184/637, bb golden all clean.
