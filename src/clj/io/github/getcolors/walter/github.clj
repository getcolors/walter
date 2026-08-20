(ns io.github.getcolors.walter.github
  "The GitHub token a create acquires, and the one interactive moment walter
  has.

  The token is minted by GitHub's device flow — `gh auth login --web` prints a
  one-time code, the operator approves it from any browser, and gh polls until
  GitHub answers. That is deliberately the *first* thing a create does, before
  any provider is touched: the workflow is interactive at the beginning only,
  and once the code is approved everything after runs unattended. Running it
  mid-play would bury the prompt minutes into an Ansible run nobody is
  watching.

  It runs in a sandboxed GH_CONFIG_DIR under a fresh temp directory, so the
  operator's own gh login on this workstation is never touched, read, or
  replaced. The minted token is exported to a 0600 file inside that directory
  and only the *path* enters the opts map — the ONCE deploy-key rule, for the
  same reason: the secret stays out of every opts print, trace, and rendered
  file. `ansible-remote` reads the file on the controller at play time and
  feeds it to the machine's `gh auth login --with-token` over stdin, then the
  directory is deleted.

  There is no PAT alternative on purpose. A pre-created token would be a
  credential every operator holds and rotates by hand; the device flow mints
  one scoped to this login when it is needed and never shows it to anyone.

  Scopes are gh's own defaults plus `workflow`, so pushes that touch
  .github/workflows are not refused — the one default gh omits that a
  development machine predictably needs."
  (:require
   [clojure.string :as str]
   [green.process :as process]
   [io.github.getcolors.walter.utils :as utils])
  (:import
   [java.nio.file Files FileVisitOption LinkOption Path]
   [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(def ^:private probe-timeout-ms 20000)
(def ^:private capture-timeout-ms 30000)

(defn wanted?
  "Whether a GitHub identity is configured at all."
  [opts]
  (not (str/blank? (str (:github-account opts)))))

(defn probe-args
  "The ssh probe deciding whether the machine already holds a login.

  Reuses the managed alias, so it needs nothing but a previous create's
  `~/.ssh/config` block. BatchMode so a missing machine fails in seconds
  instead of prompting; the full nix-profile path because a non-login shell
  over `ssh host cmd` has never heard of the profile."
  [opts]
  ["ssh" "-o" "BatchMode=yes" "-o" "ConnectTimeout=5" (utils/host-alias opts)
   "~/.nix-profile/bin/gh auth status --hostname github.com"])

(defn machine-logged-in?
  "Whether the machine already has a working gh login. Any failure — no
  machine, no alias, no gh, no login — answers no, and the create acquires a
  token; a machine that turns out to be logged in after all skips the seeding
  task on its own `gh auth status` check."
  ([opts] (machine-logged-in? opts process/run-with-timeout))
  ([opts run-fn]
   (boolean (:ok? (run-fn (probe-args opts) {} probe-timeout-ms)))))

(defn login-args
  "The device-flow login, sandboxed into `dir`.

  Wrapped in `env` because the config-dir override must reach gh without
  touching this process's environment — the operator's own gh state is not
  walter's to write. No ssh key is offered because none is wanted:
  `--git-protocol https` means git authenticates through this same token, via
  `gh auth setup-git` on the machine."
  [dir]
  ["env" (str "GH_CONFIG_DIR=" dir)
   "gh" "auth" "login" "--hostname" "github.com" "--git-protocol" "https"
   "--web" "--scopes" "workflow"])

(defn- capture
  [run-fn dir args]
  (run-fn args {:extra-env {"GH_CONFIG_DIR" dir}} capture-timeout-ms))

(defn token-dir
  "Where an acquired-but-not-yet-seeded token lives: a per-profile directory
  under walter's own state home, not a temp dir.

  Deliberate persistence, narrowly scoped. A create that fails after the
  device flow — a provider outage, an unreachable machine — must not cost a
  second code approval on the retry, so the sandbox survives the process and
  the next create reuses it after re-verifying the account. It is deleted the
  moment `ansible-remote` seeds the machine successfully: from then on the
  machine's own login is the token's home and nothing stays on the
  controller."
  [opts]
  (or (:walter/github-token-dir opts)
      (str (System/getProperty "user.home")
           "/.local/state/walter/github-token-" (or (:profile opts) "walter"))))

(defn- ensure-dir!
  [dir]
  (let [path (.toPath (java.io.File. (str dir)))]
    (Files/createDirectories path (make-array FileAttribute 0))
    (Files/setPosixFilePermissions
     path (PosixFilePermissions/fromString "rwx------"))
    dir))

(defn- delete-dir!
  [dir]
  (let [root (.toPath (java.io.File. (str dir)))]
    (when (Files/exists root (make-array LinkOption 0))
      (doseq [^Path p (reverse (sort (iterator-seq
                                      (.iterator (Files/walk root (make-array FileVisitOption 0))))))]
        (Files/deleteIfExists p)))))

(defn delete-token-dir!
  "Remove the sandbox and the token with it. Called once `ansible-remote` has
  seeded the machine — the one success after which nothing on the controller
  should keep holding a bearer token. Failure paths deliberately do *not*
  call this (except a wrong-account token, which is useless): the surviving
  sandbox is what spares the retry a second code approval."
  [opts]
  (when-let [dir (:walter/github-token-dir opts)]
    (delete-dir! dir))
  (dissoc opts :walter/github-token-dir :walter/github-token-file))

(defn- fail
  [opts msg]
  (assoc opts :green/exit 2 :green/err msg))

(defn- export-token!
  "Write the sandbox's current token to `<dir>/token`, 0600, and stash the
  path — never the token — in opts."
  [opts run-fn dir]
  (let [token (capture run-fn dir ["gh" "auth" "token"])]
    (if-not (:ok? token)
      (fail opts (str "gh auth token failed: " (str/trim (str (:err token)))))
      (let [path (str dir "/token")]
        (spit path (str/trim (str (:out token))))
        (Files/setPosixFilePermissions
         (.toPath (java.io.File. path))
         (PosixFilePermissions/fromString "rw-------"))
        (assoc opts
               :green/exit 0
               :walter/github-token-file path)))))

(defn- reusable-login
  "The login name the sandbox's surviving token answers for, or nil.

  A sandbox left by an earlier failed create holds a token that was already
  approved once; if it still authenticates, reusing it keeps the retry
  non-interactive. The account is re-verified rather than trusted — colors.yml
  may have changed between the runs."
  [run-fn dir]
  (when (:ok? (capture run-fn dir ["gh" "auth" "status" "--hostname" "github.com"]))
    (let [who (capture run-fn dir ["gh" "api" "user" "-q" ".login"])]
      (when (:ok? who)
        (not-empty (str/trim (str (:out who))))))))

(defn acquire!
  "Put a verified token for `github-account` in the sandbox and stash its file
  path in opts.

  A surviving sandbox from an earlier failed create is reused when its token
  still authenticates as the named account — the retry stays non-interactive.
  Otherwise the device flow runs: gh owns the terminal, so the one-time code
  and URL land in front of the operator unbuffered, and gh's own ~15-minute
  code expiry bounds the wait. The account check afterwards turns a token
  minted against the wrong login into an exit 2 here, rather than a puzzling
  404 half way through the clones."
  ([opts] (acquire! opts process/run-inherit process/run-with-timeout))
  ([opts inherit-fn run-fn]
   (let [account (str (:github-account opts))
         dir (ensure-dir! (token-dir opts))
         opts (assoc opts :walter/github-token-dir dir)]
     (if (some-> (reusable-login run-fn dir)
                 str/lower-case
                 (= (str/lower-case account)))
       (do (println (str "github: reusing the token an earlier create minted "
                         "for " account " — no code to approve"))
           (export-token! opts run-fn dir))
       (do
         ;; Whatever the sandbox held — nothing, an expired token, or one for
         ;; another account — the flow starts clean.
         (delete-dir! dir)
         (ensure-dir! dir)
         (println (str "walter needs a GitHub token for " account
                       ". Complete the one-time code login below; everything "
                       "after it runs unattended."))
         (let [login (inherit-fn (login-args dir))]
           (if-not (zero? (:exit login 1))
             (delete-token-dir!
              (fail opts (str "gh auth login failed"
                              (when-let [err (not-empty (str (:err login)))]
                                (str ": " err))
                              ". gh must be installed on this workstation, and "
                              "the one-time code approved before it expires.")))
             (let [who (capture run-fn dir ["gh" "api" "user" "-q" ".login"])
                   login-name (str/trim (str (:out who)))]
               (cond
                 (not (:ok? who))
                 (delete-token-dir!
                  (fail opts (str "the minted token could not be verified: "
                                  (str/trim (str (:err who))))))

                 (not= (str/lower-case login-name) (str/lower-case account))
                 (delete-token-dir!
                  (fail opts (str "the login approved the code as " (pr-str login-name)
                                  " but colors.yml names github-account "
                                  (pr-str account) " — approve the code from the "
                                  "account the machine is meant to act as")))

                 :else
                 (export-token! opts run-fn dir))))))))))

(defn github-token-step
  "Acquire the GitHub token, or establish that the machine already has one.

  Pass-through on any event but a real `:create` — `build` renders from
  desired state alone, and delete has nothing to log in to. The probe keeps a
  re-create of a healthy machine non-interactive: an existing login is left
  exactly as it is, including one made on the machine directly."
  ([opts] (github-token-step opts machine-logged-in? acquire!))
  ([opts logged-in-fn acquire-fn]
   (if (or (not= :create (:green/event opts))
           (not (wanted? opts)))
     (assoc opts :green/exit 0)
     (if (logged-in-fn opts)
       (do (println (str "github: " (utils/host-alias opts)
                         " already holds a gh login — nothing to acquire"))
           ;; A sandbox left by an earlier failed create has nothing left to
           ;; spare the operator — the machine is seeded — so it goes now.
           (delete-dir! (token-dir opts))
           (assoc opts :green/exit 0))
       (acquire-fn opts)))))
