(ns io.github.getcolors.walter.workflow
  "The DAG the launcher runs, and the steps that are not a tool.

      create / build   start ─ compute ─┬─ ansible-local
                                        └─ ansible-remote

      delete           start ─ ansible-cleanup ─ compute

      stop             start ─ power-off

      start            start ─ power-on ─ ansible-local

  `wire-fn` returns a different graph per `:green/event`, which is how ONCE
  already handles `:delete` — the two power verbs need no engine change at all.

  Create and build fork after compute: the two Ansible stages are independent
  and neither joins. Delete drops the managed ssh block before anything is
  destroyed, so a machine that is already gone still cleans up. Stop and start
  never reach OpenTofu (see io.github.getcolors.walter.oci)."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]
   [green.dry-run :as dry-run]
   [green.progress :as progress]
   [green.tofu :as tofu]
   [green.workflow :as wf]
   [io.github.getcolors.walter.oci :as oci]
   [io.github.getcolors.walter.tools :as tools]
   [io.github.getcolors.walter.validate :as validate]))

(def ^:private lifecycle-events #{:create :delete})
(def ^:private power-events #{:stop :start})

(def ^:private defaults
  {:compute-prevent-destroy true
   :provider-compute "oci"
   :provider-backend "local"
   :workdir ".colors"})

(defn power-preflight
  "Everything a power verb needs before it touches the provider: a provider that
  can be power cycled, a live CLI session, and an instance to act on.

  Not being stoppable is not an error. The verb reports it and exits 0 — the
  no-op is deliberate, and it is reported rather than silent, because a
  cost-saving command that quietly does nothing is one you discover on the
  invoice."
  ([opts] (power-preflight opts oci/cli))
  ([opts runner]
   (cond
     (not (validate/stoppable? opts))
     (assoc opts :green/exit 0 :walter/no-op true)

     :else
     (if-let [err (oci/session-error opts runner)]
       (assoc opts :green/exit 2 :green/err err)
       (if-let [id (tools/instance-id opts)]
         (assoc opts :green/exit 0 :walter/instance-id id)
         (assoc opts :green/exit 2
                :green/err
                (str "no instance id for profile " (:profile opts) ".\n"
                     "Set oci-instance-id in colors.yml, or run create so the "
                     "compute stage publishes it as an OpenTofu output.")))))))

(defn start-step
  "Overlay `COLORS_PAR_*`, validate, and — for a real power verb — check the
  session and resolve the instance.

  Credentials are only required for an event that actually reaches a provider:
  `build` and `--dry-run` render from desired state alone, so they stay usable
  with nothing set.

  The two-argument arity takes the environment to overlay, so a test does not
  inherit whatever `COLORS_PAR_*` variables the developer happens to have set."
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (let [opts (green-cli/read-pars (merge defaults opts) env)
         event (:green/event opts)
         real? (not (:green/dry-run opts))
         errors (vec (concat
                      (validate/env-errors env)
                      (validate/state-errors opts)
                      (when (and real? (lifecycle-events event))
                        (validate/secret-errors opts))
                      (when (and real? (= :delete event) (:compute-prevent-destroy opts))
                        [(str "compute destruction is protected; set "
                              (green-cli/par-name :compute-prevent-destroy)
                              "=false to delete")])))]
     (cond
       (seq errors) (assoc opts :green/exit 2 :green/err (str/join "\n" errors))
       (and real? (power-events event)) (power-preflight opts)
       :else (assoc opts :green/exit 0)))))

(defn- logln
  [& xs]
  (locking *out* (apply println xs) (flush)))

(defn- no-op-message
  [opts verb]
  (str (name verb) ": " (:provider-compute opts)
       " has no power API walter can drive — nothing to do. "
       "Only " (str/join ", " (sort validate/stoppable)) " can be power cycled."))

(defn power-step
  "Move the machine into the state `verb` wants.

  Returns unchanged and successful when the provider cannot be power cycled, so
  the same graph serves every provider."
  [verb]
  (fn [opts]
    (if (:walter/no-op opts)
      (do (logln (no-op-message opts verb))
          (assoc opts :green/exit 0))
      (let [{:keys [exit err out]} (oci/power! opts verb (:walter/instance-id opts))]
        (if (zero? exit)
          (assoc opts :green/exit 0)
          (assoc opts
                 :green/exit (max 1 exit)
                 :green/err (str "oci compute instance action failed: "
                                 (or (not-empty err) (not-empty out) "(no output)"))))))))

(def power-off-step (power-step :stop))

(defn power-on-step
  "Start the machine, then read its address back from OCI.

  OpenTofu's stored `ip` output is not refreshed by an out-of-band power cycle,
  so it may be stale here — and rendering a stale address into `~/.ssh/config` is
  exactly the silent breakage this exists to prevent. Outputs' `ip` is
  authoritative only immediately after an apply; this reads live."
  [opts]
  (let [started ((power-step :start) opts)]
    (cond
      (wf/failed? started) started
      (:walter/no-op started) started
      :else (if-let [ip (oci/public-ip started (:walter/instance-id started))]
              (assoc started :green/exit 0 :ip ip)
              (assoc started
                     :green/exit 1
                     :green/err "the instance reported no public address after starting")))))

(defn ansible-local-after-start
  "ansible-local, unless the power verb was a no-op — there is no new address to
  record and writing a placeholder one would break `ssh <alias>`."
  [opts]
  (if (:walter/no-op opts)
    (assoc opts :green/exit 0)
    (tools/ansible-local-step opts)))

(defn ansible-cleanup-step
  "Drop the managed `~/.ssh/config` block, then remove both rendered trees.

  ansible-local replays its playbook with block_state absent; both steps then
  scaffold against `:green/event :delete`, which deletes their targets. The alias
  comes from `profile` rather than from OpenTofu state, so this works when the
  machine is already gone."
  [opts]
  (-> opts tools/ansible-local-step tools/ansible-remote-step))

;; ---------------------------------------------------------------------------
;; wiring

(defn wire-fn
  [step run-opts]
  (case (:green/event run-opts)
    :delete
    (case step
      :walter/start           [start-step :walter/ansible-cleanup]
      :walter/ansible-cleanup [ansible-cleanup-step :walter/compute]
      :walter/compute         [tools/compute-step])

    :stop
    (case step
      :walter/start     [start-step :walter/power-off]
      :walter/power-off [power-off-step])

    :start
    (case step
      :walter/start         [start-step :walter/power-on]
      :walter/power-on      [power-on-step :walter/ansible-local]
      :walter/ansible-local [ansible-local-after-start])

    ;; :create and :build
    (case step
      :walter/start          [start-step :walter/compute]
      :walter/compute        [tools/compute-step :walter/ansible-local :walter/ansible-remote]
      :walter/ansible-local  [tools/ansible-local-step]
      :walter/ansible-remote [tools/ansible-remote-step])))

;; ---------------------------------------------------------------------------
;; backends

(defn backend-advice
  "The `:before` advice writing backend.tf.json for the compute stage. Remote
  state is keyed by profile and stage, and the stage is `walter-compute` rather
  than `tofu-compute` precisely so a colliding profile still cannot address
  another package's state."
  [tool]
  (let [dir-fn #(tools/tool-dir % tool)
        state-key #(str (or (:profile %) "walter") "/" tool ".tfstate")]
    (tofu/backends
     #(or (:provider-backend %) "local")
     {"local" (tofu/local-backend-advice dir-fn)
      "s3" (tofu/s3-backend-advice dir-fn
                                   (fn [opts]
                                     {:bucket (:s3-bucket opts)
                                      :key (state-key opts)
                                      :region (:s3-region opts)}))
      "r2" (tofu/r2-backend-advice dir-fn
                                   (fn [opts]
                                     {:bucket (:r2-bucket opts)
                                      :key (state-key opts)
                                      :endpoint (:r2-endpoint opts)}))})))

(def side-effecting-steps
  [:walter/compute :walter/ansible-local :walter/ansible-remote
   :walter/ansible-cleanup :walter/power-off :walter/power-on])

(def workflow
  (-> (wf/workflow {:start :walter/start :wire-fn wire-fn})
      (wf/advice-add :walter/compute :before ::backend
                     (backend-advice tools/compute-tool))
      progress/advise
      (dry-run/advise side-effecting-steps)))
