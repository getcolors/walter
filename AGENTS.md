# Walter

Walter is a Google-only Python development-machine controller using PocketDeploy
as a pinned Python dependency. Read README.md before changes.

## Rules

- Simplification is the goal. PocketDeploy wins whenever both projects implement
  the same feature differently. Change PocketDeploy to simplify shared behavior;
  do not duplicate its cloud lifecycle, state, SSH authority or recovery code.
- Clean up obsolete code, fixtures, scripts, documentation and dependencies as
  part of the rewrite; keep only maintained runtime and validation paths.
- No backward compatibility is required. No Terraform/OpenTofu, Ansible or
  Clojure orchestration, migration shims, or start/stop commands.
- Minimize changes to colors.yml. Keep useful Walter tooling and google-* names;
  normalize infrastructure configuration internally into PocketDeploy.
- Every variable assigned in .envrc.private must start with COLORS_PAR_. Never
  print its values, source-file contents, tokens, keys or raw provider responses.
- New profile: walter-gcp. Resource names follow the wiki convention
  <profile>-<component>-<purpose>, omitting redundant components. Provider IDs and
  ownership labels, not names, establish authority.
- The user authorized commits, pushes and live deployment tests for this rewrite.
  Keep existing deployments untouched. Use separate owned resources and existing
  networks; never adopt another controller's resources by name.
- Work on the current branch and preserve user changes. Keep private state,
  credentials, keys, recovery output and generated packages ignored.

## Development

Use `uv sync --locked --extra test`, `uv run pytest -q`, `uv build`, and
`uv run python scripts/test-launcher.py`. Tests use synthetic temporary files and
mock providers; the default suite never provisions or changes the local system.
The privilege-boundary test additionally runs under sudo with a temporary fixture.

Package source is src/walter/. Remote provisioning is a stdlib Python program
sent over SSH; input travels on stdin. Only machine setup runs as root; user
installers permanently drop privileges. Preserve private seats, credential
non-overwrite, explicit resource ownership, host-key verification and idempotent
recovery. Review live diagnostics inside the collecting process and print only
allowlisted results, never raw cloud/SSH output.

Publish the package commit before pinning skills/walter/walter. The root walter
symlink shares that payload. Test the copied launcher outside this checkout.
Configuration is selected from the caller's directory or -f; never select state
relative to the installed package. Keep secrets outside workflow options/results
and never automatically execute restored private configuration.
