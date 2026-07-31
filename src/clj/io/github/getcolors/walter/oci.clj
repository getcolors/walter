(ns io.github.getcolors.walter.oci
  "The OCI CLI calls walter's power verbs make.

  `stop` and `start` deliberately never reach OpenTofu. No template walter
  renders declares a power state, so an attribute the configuration never sets
  produces no diff on refresh — stopping the machine out of band causes no
  drift, because OpenTofu was never managing power. That also keeps
  `prevent_destroy` irrelevant to `stop`.

  The cost is a second authentication path. OpenTofu's `oracle/oci` provider
  detects `security_token_file` by itself; the CLI rejects that profile unless
  `OCI_CLI_AUTH=security_token` is exported, which the consuming project's
  `.envrc` does. A power verb can therefore fail on credentials where a create
  would not, so `session-error` is checked before anything is attempted.

  Everything that shells out has a second arity taking a runner, so the tests
  cover the parsing and the decisions without starting a process."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [green.process :as process]))

(def default-wait-seconds
  "How long to wait for a power transition. The CLI returns as soon as the
  request is accepted, so without an explicit wait `stop` would report success
  on a still-running machine and `start` would hand the next step an address
  that is not up yet."
  300)

(def refresh-hint
  "The globally installed skill that renews an expired session, named in the
  error rather than described. Session tokens last 60 minutes."
  "bb ~/.claude/skills/refresh-oci-token/refresh-oci-token.clj")

(def actions
  "Walter's verb -> the CLI action and the lifecycle state it settles into.

  SOFTSTOP is an ACPI shutdown, so the guest flushes and unmounts. STOP is the
  power cord, and on a machine whose whole purpose is holding a day's
  uncommitted work that is the wrong default."
  {:stop  {:action "SOFTSTOP" :state "STOPPED"}
   :start {:action "START" :state "RUNNING"}})

;; ---------------------------------------------------------------------------
;; parsing — pure, and the part most worth testing

(defn parse-public-ip
  "The first public address in an `oci compute instance list-vnics` payload.

  An instance may have several VNICs, and a VNIC may carry no public address at
  all — a stopped machine commonly reports none — so this answers nil rather
  than guessing."
  [out]
  (try
    (->> (get (json/parse-string (str out)) "data")
         (keep #(not-empty (str/trim (str (get % "public-ip")))))
         first)
    (catch Exception _ nil)))

(defn parse-lifecycle-state
  "The `lifecycle-state` from an `oci compute instance get` payload, upper-cased,
  or nil when the output cannot be read as one."
  [out]
  (try
    (some-> (get-in (json/parse-string (str out)) ["data" "lifecycle-state"])
            str
            str/trim
            str/upper-case
            not-empty)
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; the CLI

(defn cli
  "Run `oci` with `args`, returning green.process's {:exit :out :err}. A command
  that could not be started reports exit -1 rather than throwing, which keeps
  callers inside the same outcome contract every step uses."
  [args]
  (process/run (into ["oci"] (map str args))))

(defn profile
  "The `~/.oci/config` profile walter authenticates with. Sharing one profile
  with the other projects on this machine means one session refresh serves them
  all."
  [opts]
  (or (not-empty (str (:oci-config-file-profile opts))) "DEFAULT"))

(defn session-error
  "nil when the CLI can still authenticate, or a message naming the fix.

  `--local` is not optional. Plain `oci session validate` asks \"Do you want to
  re-authenticate?\" when it fails, and a prompt with nothing reading stdin
  hangs the workflow instead of failing it."
  ([opts] (session-error opts cli))
  ([opts runner]
   (let [p (profile opts)
         {:keys [exit]} (runner ["session" "validate" "--profile" p "--local"])]
     (when-not (zero? exit)
       (str "the OCI session for profile " p " has expired or is missing."
            "\nRun: " refresh-hint)))))

(defn power!
  "Ask OCI to move `instance-id` into the state `verb` wants, and wait for it."
  ([opts verb instance-id] (power! opts verb instance-id cli))
  ([opts verb instance-id runner]
   (let [{:keys [action state]} (actions verb)]
     (runner ["compute" "instance" "action"
              "--instance-id" instance-id
              "--action" action
              "--wait-for-state" state
              "--max-wait-seconds" (or (:power-wait-seconds opts) default-wait-seconds)]))))

(defn public-ip
  "The instance's current public address, read live. Nil when the call fails or
  the machine has none."
  ([opts instance-id] (public-ip opts instance-id cli))
  ([_opts instance-id runner]
   (let [{:keys [exit out]} (runner ["compute" "instance" "list-vnics"
                                     "--instance-id" instance-id])]
     (when (zero? exit) (parse-public-ip out)))))

(defn lifecycle-state
  "The instance's current lifecycle state, read live, or nil when unavailable."
  ([opts instance-id] (lifecycle-state opts instance-id cli))
  ([_opts instance-id runner]
   (let [{:keys [exit out]} (runner ["compute" "instance" "get"
                                     "--instance-id" instance-id])]
     (when (zero? exit) (parse-lifecycle-state out)))))
