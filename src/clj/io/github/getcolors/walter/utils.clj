(ns io.github.getcolors.walter.utils
  "The launcher compatibility number and the pure helpers shared across steps.")

(def contract
  "Minimum interface version a launcher must require to drive this library.

  Bump on any change a launcher pinned to an older commit could not survive — a
  renamed desired-state key, a changed template variable, a new function the
  launcher calls — and bump `launcher-contract` in the bundled launcher to
  match. The handshake turns a stale pin into an actionable exit 2 rather than a
  confusing resolution failure.

  3: the SSH Keypair Standard (workspace standards/ssh-keypair.md).
     `compute-keygen` is superseded: generation is the default, an explicit
     machine key is the opt-out, the keypair moves from ~/.ssh/<profile> to
     the deployment's .ssh/<profile>, and the delete DAG gains
     :walter/ssh-cleanup. A launcher pinned older still requires the machine
     key in desired state and refuses a colors.yml written for keygen mode."
  3)

(defn host-alias
  "The `~/.ssh/config` Host alias walter manages.

  `profile` names the project, so it names the alias: `ssh <profile>` reaches
  the machine. Unlike ONCE this does not consult an OpenTofu output first —
  walter's alias has to be answerable without reading state, because `stop` and
  `start` may run when the backend is unreachable."
  [opts]
  (or (not-empty (str (:profile opts))) "walter"))
