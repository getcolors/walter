(ns io.github.getcolors.walter.workflow
  "V2 singleton lifecycle. SSH authority and a scoped agent precede application
  work. Delete removes aliases, compute and registration while retaining SSH
  authority. Builds use an isolated root. Power transport is unavailable in v2."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]
   [green.dry-run :as dry-run]
   [green.lifecycle :as lifecycle]
   [green.progress :as progress]
   [io.github.getcolors.walter.access :as access]
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
  "Overlay `COLORS_PAR_*`, validate v2 desired state and destruction guards.

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
            (cond-> (assoc opts :green/exit 0)
              (= :build event) (update :workdir #(str % "/build"))))}
    env)))

(defn power-step [verb]
  (fn [opts]
    (assoc opts :green/exit 2 :green/err
           "Power operations are unavailable in colors-compute v2; no provider mutation was attempted.")))

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
      :walter/start           [start-step :walter/ssh-resource]
      :walter/ssh-resource [access/resource-step :walter/registration]
      :walter/registration [access/registration-step :walter/load-compute]
      :walter/load-compute [tools/load-compute-step :walter/ansible-cleanup]
      :walter/ansible-cleanup [ansible-cleanup-step :walter/compute]
      :walter/compute [tools/compute-step :walter/registration-delete]
      :walter/registration-delete [access/registration-delete-step])

    :stop
    (case step
      :walter/start     [start-step :walter/power-off]
      :walter/power-off [power-off-step])

    :start
    (case step
      :walter/start         [start-step :walter/power-on]
      :walter/power-on      [power-on-step :walter/ansible-local]
      :walter/ansible-local [ansible-local-after-start])

    :ssh
    (case step
      :walter/start [start-step :walter/ssh-resource]
      :walter/ssh-resource [access/resource-step :walter/registration]
      :walter/registration [access/registration-step :walter/connection]
      :walter/connection [access/connection-step :walter/agent]
      :walter/agent [access/agent-step :walter/ssh]
      :walter/ssh [access/ssh-step])

    :converge-nix
    (case step
      :walter/start [start-step :walter/ssh-resource]
      :walter/ssh-resource [access/resource-step :walter/agent]
      :walter/agent [access/agent-step :walter/converge-nix]
      :walter/converge-nix [tools/converge-nix-step])

    :converge-asdf
    (case step
      :walter/start [start-step :walter/ssh-resource]
      :walter/ssh-resource [access/resource-step :walter/agent]
      :walter/agent [access/agent-step :walter/converge-asdf]
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
      :walter/start          [start-step :walter/ssh-resource]
      :walter/ssh-resource [access/resource-step :walter/agent]
      :walter/agent [access/agent-step :walter/github-token]
      :walter/github-token [github/github-token-step :walter/registration]
      :walter/registration [access/registration-step :walter/compute]
      :walter/compute          [tools/compute-step :walter/ansible-bootstrap]
      :walter/ansible-bootstrap [tools/ansible-bootstrap-step :walter/ansible-seats]
      :walter/ansible-seats  [tools/ansible-seats-step :walter/ansible-local]
      :walter/ansible-local  [tools/ansible-local-step :walter/ansible-remote]
      :walter/ansible-remote [tools/ansible-remote-step :walter/emacs-packages]
      :walter/emacs-packages [tools/emacs-packages-step])))

;; ---------------------------------------------------------------------------
;; backends

(def side-effecting-steps
  [:walter/connection :walter/ssh :walter/ssh-resource :walter/registration :walter/registration-delete :walter/agent :walter/load-compute
   :walter/github-token
   :walter/compute :walter/ansible-bootstrap :walter/ansible-seats
   :walter/ansible-local :walter/ansible-remote
   :walter/emacs-packages
   :walter/converge-nix :walter/converge-asdf
   :walter/ansible-cleanup
   :walter/power-off :walter/power-on])

(def workflow
  (-> (wf/workflow {:start :walter/start :wire-fn wire-fn
                    :next-fn (fn [_ successors opts]
                               (if (wf/failed? opts) [] (mapv #(vector % opts) successors)))})
      progress/advise
      (dry-run/advise side-effecting-steps)))
