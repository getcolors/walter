(ns io.github.getcolors.walter.workflow
  "The DAG the launcher runs, and the steps that are not a tool.

      create / build   start ─ github-token ─ compute ─ bootstrap ─ seats ─┬─ ansible-local
                                                                          └─ ansible-remote ─ emacs-packages

      delete           start ─ ansible-cleanup ─ compute

      stop             start ─ power-off

      start            start ─ power-on ─ ansible-local

      converge-nix     start ─ converge-nix
      converge-asdf    start ─ converge-asdf

  `wire-fn` returns a different graph per `:green/event`, which is how ONCE
  already handles `:delete` — the two power verbs need no engine change at all.

  `github-token` sits between start and compute because it is the one
  interactive step walter has: the device-flow prompt has to land in front of
  the operator before anything long-running begins, so the workflow is
  interactive at the beginning only and unattended after. On builds, deletes,
  and projects with no `github-account` it passes through untouched.

  Create and build fork after the seat stage: the two normal Ansible stages are
  independent and neither joins. `seats` creates the extra unix logins `users`
  names — it must follow the root-login bootstrap and precede the remote play,
  whose inventory connects as each seat. Delete drops the managed ssh block before anything is
  destroyed, so a machine that is already gone still cleans up. Stop and start
  never reach OpenTofu; OCI uses its CLI and Vultr its HTTP API.

  `emacs-packages` hangs off `ansible-remote` rather than off `compute`, because
  it needs what that stage installs — Emacs and the cloned configuration. It is
  last, and it is the only step walter starts without waiting for: the job is
  daemonized on the machine and outlives the create. So the graph finishing is
  not the same as the machine being finished, which is stated here because it is
  true nowhere else in walter."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]
   [green.dry-run :as dry-run]
   [green.lifecycle :as lifecycle]
   [green.progress :as progress]
   [io.github.getcolors.compute-power :as power]
   [io.github.getcolors.walter.compute :as compute]
   [green.workflow :as wf]
   [io.github.getcolors.walter.github :as github]
   [io.github.getcolors.walter.tools :as tools]
   [io.github.getcolors.walter.validate :as validate]))

(def ^:private lifecycle-events #{:create :delete})
(def ^:private power-events #{:stop :start})

(def ^:private defaults
  {:compute-prevent-destroy true
   :provider-compute "oci"
   :provider-backend "s3"
   :workdir ".colors"})

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
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
          :after-validate
          (fn [opts _ {:keys [event real?]}]
            (if (and real? (= :delete event))
              (tools/load-compute-step opts)
              (assoc opts :green/exit 0)))}
    env)))

(defn power-step
  "Use the library's coordinated power capability and observed address."
  [verb]
  (fn [opts]
    (try
      (let [result (power/power-deployment opts (name verb))]
        (if (= "planned" (:status result))
          (assoc opts :green/exit 0)
          (let [_ (when-not (= "ready" (:status result)) (throw (ex-info "power result unavailable" {})))
                cluster (:cluster result) node (compute/node (assoc opts :colors-compute/cluster cluster))]
            (assoc opts :green/exit 0 :colors-compute/cluster cluster
                   :ssh-private-key-path (get-in result [:key :private_key_path])
                   :ip (:ip node) :user (compute/login node)))))
      (catch Exception _ (assoc opts :green/exit 1 :green/err "compute power refused")))))

(def power-off-step (power-step :stop))
(def power-on-step (power-step :start))
(def ansible-local-after-start tools/ansible-local-step)

(defn ansible-cleanup-step
  "Drop the managed `~/.ssh/config` block, then remove both rendered trees.

  ansible-local replays its playbook with block_state absent; both steps then
  scaffold against `:green/event :delete`, which deletes their targets. The alias
  comes from `profile` rather than from OpenTofu state, so this works when the
  machine is already gone."
  [opts]
  (let [local (tools/ansible-local-step opts)]
    (if (wf/failed? local) local (tools/ansible-remote-step local))))

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

    :converge-nix
    (case step
      :walter/start        [start-step :walter/converge-nix]
      :walter/converge-nix [tools/converge-nix-step])

    :converge-asdf
    (case step
      :walter/start         [start-step :walter/converge-asdf]
      :walter/converge-asdf [tools/converge-asdf-step])

    ;; :build is :create plus the two focused stages, and is derived from it
    ;; rather than spelled out a second time: a create graph written twice is a
    ;; create graph that eventually differs from itself. The focused steps hang
    ;; off the same fork as the two Ansible stages — they render from desired
    ;; state alone, so nothing orders them against anything — and they render
    ;; only, because `converge-step` returns after scaffolding on :build.
    :build
    (case step
      :walter/converge-nix  [tools/converge-nix-step]
      :walter/converge-asdf [tools/converge-asdf-step]
      (cond-> (vec (wire-fn step (assoc run-opts :green/event :create)))
        (= :walter/ansible-seats step)
        (into [:walter/converge-nix :walter/converge-asdf])))

    ;; :create
    ;;
    ;; `seats` sits between bootstrap and the fork because ordering is load-
    ;; bearing on both sides: it must follow the root-login bootstrap (it connects
    ;; as the adopted ubuntu login) and precede ansible-remote (whose inventory
    ;; connects as each seat, so the accounts have to exist first). With no
    ;; `users` in desired state it renders nothing and passes through.
    (case step
      :walter/start          [start-step :walter/github-token]
      :walter/github-token     [github/github-token-step :walter/compute]
      :walter/compute          [tools/compute-step :walter/ansible-bootstrap]
      :walter/ansible-bootstrap [tools/ansible-bootstrap-step :walter/ansible-seats]
      :walter/ansible-seats  [tools/ansible-seats-step :walter/ansible-local :walter/ansible-remote]
      :walter/ansible-local  [tools/ansible-local-step]
      :walter/ansible-remote [tools/ansible-remote-step :walter/emacs-packages]
      :walter/emacs-packages [tools/emacs-packages-step])))

;; ---------------------------------------------------------------------------
;; backends

(def side-effecting-steps
  [:walter/github-token
   :walter/compute :walter/ansible-bootstrap :walter/ansible-seats
   :walter/ansible-local :walter/ansible-remote
   :walter/emacs-packages
   :walter/converge-nix :walter/converge-asdf
   :walter/ansible-cleanup
   :walter/power-off :walter/power-on])

(def workflow
  (-> (wf/workflow {:start :walter/start :wire-fn wire-fn
                    :next-fn (fn [_ successors opts]
                               (if (or (wf/failed? opts) (:walter/already-destroyed opts))
                                 [] (mapv #(vector % opts) successors)))})
      progress/advise
      (dry-run/advise side-effecting-steps)))
