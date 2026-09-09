(ns io.github.getcolors.walter.tools
  "Application tooling and seat lifecycle around shared library compute."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [green.ansible :as ansible]
   [green.cli :as green-cli]
   [green.process :as process]
   [green.scaffold :as sc]
   [green.workflow :as wf]
   [io.github.getcolors.compute :as library]
   [io.github.getcolors.compute-planning :as planning]
   [io.github.getcolors.compute-orchestration :as orchestration]
   [io.github.getcolors.compute-inspection :as inspection]
   [io.github.getcolors.compute-ssh :as ssh]
   [io.github.getcolors.walter.compute :as compute]
   [io.github.getcolors.walter.github :as github]
   [io.github.getcolors.walter.utils :as utils]
   [io.github.getcolors.walter.validate :as validate])
  (:import [java.util Base64]))

(def compute-tool
  "The compute stage directory, and half of its OpenTofu state key.

  Deliberately not `tofu-compute`. Remote state is keyed `<profile>/<tool>` and
  nothing but convention keeps this project's profile distinct from another's,
  so a walter-specific stage name means a colliding profile still cannot produce
  ONCE's state key."
  "walter-compute")

(def ansible-bootstrap-tool "walter-ansible-bootstrap")
(def ansible-seats-tool "walter-ansible-seats")
(def ansible-local-tool "walter-ansible-local")
(def ansible-remote-tool "walter-ansible-remote")
(def converge-nix-tool "walter-converge-nix")
(def converge-asdf-tool "walter-converge-asdf")
(def emacs-packages-tool "walter-emacs-packages")

(def ^:private walter-root "io.github.getcolors.walter.tools")

(def ^:private template-opts
  "Selmer reads `<{ var }>` and `<% if %>`, leaving `{{ }}` and `{% %}` to Jinja2
  in the Ansible files."
  sc/preserve-jinja-delimiters)

(defn tool-dir
  "The isolated working directory for `tool` in the active profile.

  A relative workdir resolves against the directory holding colors.yml rather
  than the current one, so walter renders to the same place however deep in the
  project it was invoked from."
  [opts tool]
  (green-cli/stage-dir opts tool {:default-profile "walter"}))

(defn- walter-template
  [tool file]
  (keyword (str walter-root "." tool) file))

(defn- template-spec
  [template target data]
  {:template template :target target :data data :opts template-opts})

(defn- raw-spec [target content]
  (sc/content-spec target content))

(defn- compute-json [value indent]
  (let [padding #(apply str (repeat % " "))]
    (cond
      (map? value) (if (empty? value) "{}"
                      (str "{\n" (str/join ",\n" (for [[key item] (sort-by (comp name key) value)]
                                                       (str (padding (+ indent 2)) (json/generate-string key) ": " (compute-json item (+ indent 2)))))
                           "\n" (padding indent) "}"))
      (sequential? value) (if (empty? value) "[]"
                              (str "[\n" (str/join ",\n" (map #(str (padding (+ indent 2)) (compute-json % (+ indent 2))) value)) "\n" (padding indent) "]"))
      :else (json/generate-string value))))

(defn compute-step [opts]
  (try
    (let [planning? (or (= :build (:green/event opts)) (:green/dry-run opts))
          result (if planning?
                   (planning/plan-deployment opts (compute/topology opts) (compute/requirements opts))
                   (orchestration/orchestrate opts (compute/topology opts) (compute/requirements opts)))]
      (when planning?
        (doseq [[stage key] (cons ["shared" (get-in result [:state_keys :shared])]
                                 (map (fn [[id key]] [(str "nodes/" (name id)) key]) (get-in result [:state_keys :nodes]))) ]
          (let [target (io/file (tool-dir opts compute-tool) stage "backend.tf.json")]
            (io/make-parents target)
            (spit target (str (compute-json (:config (library/backend-plan opts key)) 0) "\n"))))
        (doseq [[stage documents] (cons ["shared" (get-in result [:documents :shared])]
                                      (map (fn [[id documents]] [(str "nodes/" id) documents]) (get-in result [:documents :nodes])))
                [filename document] documents]
          (let [target (io/file (tool-dir opts compute-tool) stage filename)]
            (io/make-parents target)
            (spit target (str (compute-json document 0) "\n")))))
      (if-not (contains? #{"ready" "planned" "destroyed"} (:status result))
        (assoc opts :green/exit 1 :green/err (if (seq (:errors result)) (str/join "\n" (:errors result)) "compute lifecycle refused; inspect state ownership and configuration"))
        (cond-> (assoc opts :green/exit 0)
          (:shared result) (assoc :colors-compute/shared (:shared result))
          (:cluster result) (assoc :colors-compute/cluster (:cluster result) :ip (get-in result [:cluster :nodes 0 :ip]) :user (get-in result [:cluster :nodes 0 :user]))
          (get-in result [:key :private_key_path])
          (assoc :ssh-private-key-path (if planning? (str/replace (get-in result [:key :private_key_path]) "$HOME/.ssh" "/home/build-placeholder/.ssh") (get-in result [:key :private_key_path]))))))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "compute lifecycle refused; legacy monolithic state requires explicit migration"))))

(defn load-compute-step [opts]
  (try
    (let [result (inspection/read-deployment opts (into {} (System/getenv)) {} (compute/requirements opts))]
      (case (:status result)
        "present" (let [node (first (get-in result [:cluster :nodes]))]
                    (cond-> (assoc opts :colors-compute/cluster (:cluster result)
                                       :colors-compute/shared (:shared result)
                                       :ip (:ip node) :user (compute/login node) :green/exit 0)
                      (:ssh_identity_file node) (assoc :ssh-private-key-path (:ssh_identity_file node))))
        "destroyed" (if (= :delete (:green/event opts)) (assoc opts :walter/already-destroyed true :green/exit 0)
                        (assoc opts :green/exit 1 :green/err "compute deployment is destroyed"))
        (assoc opts :green/exit 1 :green/err "compute inspection refused; existing owned state is required")))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "compute inspection refused; existing owned state is required"))))


(defn fallback-compute-params [opts] (compute/node opts))
(defn machine-key-file [opts]
  (or (:ssh-private-key-path opts)
      (when (validate/keygen? opts)
        (str (io/file (if (= :build (:green/event opts)) "/home/build-placeholder" (System/getProperty "user.home")) ".ssh" (:profile opts))))))
(defn machine-key-ssh-path [opts]
  (when (validate/keygen? opts) (str "~/.ssh/" (:profile opts))))
(defn with-machine-key [opts]
  (if-not (validate/keygen? opts) opts
    (assoc opts :compute-pubkey
      (if (= :build (:green/event opts)) ssh/placeholder-public
        (str/trim (slurp (str (machine-key-file opts) ".pub")))))))

(defn users
  "The `users` entries — seat logins provisioned beside the primary one, each
  a real unix account with its own home and the same environment, isolated by
  file permissions rather than by machines. Names only: everything
  identity-shaped in desired state stays singular, because the seats are one
  person's workspaces, not people.

  Same string tolerance as `nix-package-names`, for the same `COLORS_PAR_*`
  reason, and `distinct` because validate.clj has already refused a genuine
  duplicate."
  [opts]
  (vec (distinct (validate/user-names opts))))

(defn alias-inventory
  "An inventory that deliberately resolves through Walter's managed SSH aliases.

  Focused convergence events must not read OpenTofu state or call a provider
  merely to rediscover an address already recorded in ~/.ssh/config. The
  aliases also carry each login and the dedicated IdentityFile. A missing alias
  therefore fails as an existing-machine prerequisite rather than falling back
  to a build placeholder address."
  [data seats]
  (let [alias (or (:host-alias data) "walter")]
    (json/generate-string
     ;; `distinct` at the source rather than trusting the caller: an inventory
     ;; is a JSON object, so a repeated seat would emit a duplicate key that
     ;; every reader silently collapses — including the golden assertion that
     ;; exists to police this file.
     {:all {:hosts (apply array-map
                          (concat [alias {}]
                                  (mapcat (fn [s] [(str alias "-" s) {}])
                                          (distinct seats))))}}
     {:pretty true})))

(defn inventory
  "The Ansible inventory for the one machine walter manages.

  ONCE's builder carries an admin/users split and a `root@host` key convention
  that a single-machine package has no use for, so this is walter's own: one
  group, keyed by the alias you would `ssh` with — plus, for the stages that
  provision every login, one extra host per seat, riding the same address as
  `<alias>-<seat>` and connecting as its own login.

  The primary host is deliberately first, in an array-map rather than whatever
  a hash-map hashes to: the remote play's machine-scoped tasks `run_once`, and
  first-in-inventory is what run_once executes on."
  ([data] (inventory data nil))
  ([{:keys [ip user host-alias]} seats]
   (let [alias (or host-alias "walter")]
     (json/generate-string
      {:all {:hosts (apply array-map
                           (concat [alias {:ansible_host ip :ansible_user user}]
                                   (mapcat (fn [s] [(str alias "-" s)
                                                    {:ansible_host ip :ansible_user s}])
                                           seats)))}}
      {:pretty true}))))

(def nixpkgs-ref
  "The nixpkgs every `nix profile add` here resolves against.

  Deliberately a channel branch and not a revision: a development machine is
  wanted current, and the tools it carries move faster than any release branch
  does — asdf is the clearest case, since 0.16 rewrote it in Go and renamed the
  verbs, and no stable branch carries that yet.

  The cost is stated rather than hidden: two creates months apart do not produce
  the same machine, and a create can pick up an upstream change that a previous
  one did not have. That is the trade this project has chosen; if a machine ever
  has to be reproducible, this becomes a revision and the asdf verbs below have
  to match whatever that revision ships."
  "github:NixOS/nixpkgs/nixpkgs-unstable")

(defn nix-package-flakerefs
  "The names as pinned flakerefs, space-separated for one `nix profile add`.

  Empty when nothing is named, which is what gates the step. One invocation
  rather than one per package, so nix resolves the set together and the profile
  takes a single generation."
  [opts]
  (->> (validate/nix-package-names opts)
       (map #(str nixpkgs-ref "#" %))
       (str/join " ")))

(defn clone-orgs
  "The `clone-orgs` entries — GitHub organisations whose every source repository
  is checked out under `~/code/<org>/`.

  Only the organisation is desired state. The repository list is not: it is
  whatever the organisation holds at create time, read from GitHub's API on the
  machine, so a repository added upstream arrives on the next create without
  anything here changing. That is the whole point of naming an org rather than
  fifteen repositories.

  Same string tolerance as `nix-package-names`, for the same `COLORS_PAR_*`
  reason, and `distinct` because naming one twice would clone it twice into the
  same path."
  [opts]
  (let [names (:clone-orgs opts)]
    (->> (if (sequential? names) names (str/split (str names) #"\s+"))
         (map (comp str/trim str))
         (remove str/blank?)
         distinct
         vec)))

(defn seed-agent-credentials
  "The `seed-agent-credentials` entries, resolved to {:agent :path} against
  `validate/agent-credential-paths`.

  The path is relative to $HOME and the playbook prefixes each side with a
  different one — the controller's for the source, the machine's for the
  destination — so one entry describes both ends of the copy and they cannot
  drift apart.

  Same string tolerance as `nix-package-names`, for the same `COLORS_PAR_*`
  reason. Unknown names drop out here and are refused by validate.clj, so a typo
  fails the build rather than rendering a task that copies nothing."
  [opts]
  (let [names (:seed-agent-credentials opts)]
    (->> (if (sequential? names) names (str/split (str names) #"\s+"))
         (map (comp str/trim str))
         (remove str/blank?)
         distinct
         (keep (fn [agent]
                 (when-let [path (get validate/agent-credential-paths agent)]
                   {:agent agent :path path})))
         vec)))

(defn data-fn
  "Template data for the Ansible stages: opts, with the address, login and alias
  guaranteed present so a build renders without ever reaching for state.

  `emacs-config-dest` is defaulted rather than required, because a repo with no
  destination is an unambiguous intent and the alternative is a rendered
  playbook carrying `dest: \"\"` that only fails on the machine. The default is
  the XDG path Emacs 29+ reads on its own; a configuration that expects another
  one — and so an `--init-directory` to reach it — says so in colors.yml."
  [opts]
  (assoc opts
         :ssh-keygen (validate/keygen? opts)
         :ip (or (not-empty (str (:ip opts))) "192.168.0.1")
         :user (or (not-empty (str (:user opts))) "root")
         :host-alias (utils/host-alias opts)
         :nix-package-flakerefs (nix-package-flakerefs opts)
         :nix-package-count (count (validate/nix-package-names opts))
         :nix-package-names-b64 (.encodeToString
                                 (Base64/getEncoder)
                                 (.getBytes (json/generate-string
                                             (vec (validate/nix-package-names opts)))
                                            "UTF-8"))
         :nixpkgs-ref nixpkgs-ref
         :login-shell-is-fish (= "fish" (not-empty (str/trim (str (:login-shell opts)))))
         ;; Rendered as JSON rather than looped in the template: a JSON array is
         ;; a valid YAML flow sequence, so the playbook keeps one task with an
         ;; Ansible `loop` instead of N generated ones, and the indentation
         ;; cannot drift.
         :asdf-tools-json (let [tools (validate/asdf-tools opts)]
                            (when (seq tools) (json/generate-string tools)))
         :corepack-packages-json (let [pkgs (validate/corepack-packages opts)]
                                   (when (seq pkgs) (json/generate-string pkgs)))
         ;; JSON for the same reason as asdf-tools above: one Ansible `loop`
         ;; over a flow sequence rather than N generated tasks whose
         ;; indentation can drift. Only the agent name and its relative path —
         ;; there is nothing secret in this, and the credentials themselves are
         ;; read from the controller at play time and never rendered.
         :seed-agent-credentials-json (let [agents (seed-agent-credentials opts)]
                                        (when (seq agents)
                                          (json/generate-string agents)))
         ;; Claude Code keeps the bearer tokens in the credential file above,
         ;; but gates an interactive start separately in ~/.claude.json. This
         ;; only controls whether the playbook renders that non-secret repair;
         ;; the task still checks that the controller credential actually exists.
         :seed-claude-credentials (boolean
                                   (some #(= "claude" (:agent %))
                                         (seed-agent-credentials opts)))
         ;; JSON for the same reason as the three above. Organisation names
         ;; only: what gets cloned is decided on the machine at create time,
         ;; against GitHub's API, so nothing here can go stale between a build
         ;; and the create that uses it.
         :clone-orgs-json (let [orgs (clone-orgs opts)]
                            (when (seq orgs) (json/generate-string orgs)))
         :emacs-config-dest (or (not-empty (str (:emacs-config-dest opts)))
                                "~/.config/emacs")
         ;; The rendered ssh-config block names the generated key by its
         ;; literal ~ form, so the playbook stays byte-identical across
         ;; workstations; ssh_config expands the tilde itself. nil when
         ;; compute-keygen is off, which is what gates the IdentityFile lines.
         :machine-key-path (machine-key-ssh-path opts)
         ;; Normalised in place, so the templates never re-parse the raw key:
         ;; the local play loops over it with Selmer, and the seats play takes
         ;; it as JSON for one Ansible `loop` — same shape as the lists above.
         :users (users opts)
         :users-json (let [seats (users opts)]
                       (when (seq seats) (json/generate-string seats)))
         ;; The union of the steps that stamp a once-only action. Computed here
         ;; rather than as an `or` in the template because Selmer's `<% if %>`
         ;; takes one value, and a second feature needing the directory should
         ;; extend this expression rather than duplicate the task.
         :needs-state-dir (boolean (or (not-empty (str (:dotfiles-checkout opts)))
                                       (not-empty (str (:atuin-username opts)))))))

(def ^:private bootstrap-probe-timeout-ms 10000)

(defn bootstrap-user
  "Choose the login for an idempotent root-image bootstrap.

  A fresh provider image exposes root. Once Walter has created ubuntu and
  disabled root SSH, later creates must enter through ubuntu instead. Probe the
  final login first with the dedicated key and fall back to root only when that
  fails. Builds perform no probe and render the first-create shape."
  ([opts] (bootstrap-user opts process/run-with-timeout))
  ([opts run-fn]
   (if (= :build (:green/event opts))
     "root"
     (let [result (run-fn (vec (concat ["ssh" "-o" "BatchMode=yes" "-o" "StrictHostKeyChecking=no" "-o" "UserKnownHostsFile=/dev/null"]
                                  (when (machine-key-file opts) ["-i" (machine-key-file opts)])
                                  (when (validate/keygen? opts) ["-o" "IdentitiesOnly=yes"])
                                  [(str "ubuntu@" (:ip opts)) "true"]))
                          {} bootstrap-probe-timeout-ms)]
       (if (:ok? result) "ubuntu" "root")))))

(defn ansible-bootstrap-step
  "Turn a normalized root-login image into Walter's Ubuntu login.

  This is the sole normal root SSH connection. It creates ubuntu with the
  dedicated key and passwordless sudo, then disables root and password SSH.
  Every downstream stage receives ubuntu as the authoritative login. Non-root
  logins pass through without rendering a bootstrap stage."
  [opts]
  (if (not= "root" (:user (compute/node opts)))
    (assoc opts :green/exit 0)
    (let [dir (tool-dir opts ansible-bootstrap-tool)
          bootstrap-user (bootstrap-user opts)
          data (assoc (data-fn (with-machine-key opts)) :user bootstrap-user)
          specs [(template-spec (walter-template "ansible-bootstrap" "ansible.cfg")
                                (str dir "/ansible.cfg") data)
                 (template-spec (walter-template "ansible-bootstrap" "main.yml")
                                (str dir "/main.yml") data)
                 (raw-spec (str dir "/inventory.json") (inventory data))]
          rendered (sc/scaffold opts specs)
          adopted #(assoc % :green/exit 0 :user "ubuntu" :sudoer "ubuntu" :uid "1000"
                          :walter/compute-params
                          (merge (:walter/compute-params %)
                                 {:user "ubuntu" :sudoer "ubuntu" :uid "1000"}))]
      (if (= :build (:green/event opts))
        (adopted rendered)
        (let [result (ansible/ansible-step
                      rendered
                      {:dir dir
                       :inventory "inventory.json"
                       :playbooks {:create "main.yml"}
                       :host-key-checking false
                       :private-key (machine-key-file opts)})]
          (if (wf/failed? result) result (adopted result)))))))

(defn ansible-seats-step
  "Create the seat logins: real unix users beside the primary one, isolated by
  file permissions.

  Each seat gets a private home and exactly the authorized keys the primary
  login holds — read from the machine at play time, so the stage is
  key-mode-agnostic — and deliberately NO sudo: a sudoer can read every home,
  which would be the isolation feature deleting itself. The primary login
  keeps sudo and remains the trust root.

  Runs as the primary login with become, after the root-login bootstrap has adopted
  ubuntu, so it needs no root SSH on any provider. It sits before the fork
  because the remote play's inventory then connects as each seat — the
  accounts must exist first.

  Gated in Clojure like emacs-packages: a project with no seats renders no
  directory at all rather than a playbook that loops over nothing. Delete
  skips it — the homes go with the boot volume, and the local play removes the
  seats' ssh blocks."
  [opts]
  (if (or (empty? (users opts))
          (= :delete (:green/event opts)))
    (assoc opts :green/exit 0)
    (let [dir (tool-dir opts ansible-seats-tool)
          data (data-fn opts)
          specs [(template-spec (walter-template "ansible-seats" "ansible.cfg")
                                (str dir "/ansible.cfg") data)
                 (template-spec (walter-template "ansible-seats" "main.yml")
                                (str dir "/main.yml") data)
                 (raw-spec (str dir "/inventory.json") (inventory data))]
          rendered (sc/scaffold opts specs)]
      (if (= :build (:green/event opts))
        rendered
        (ansible/ansible-step rendered
                              (cond-> {:dir dir
                                       :inventory "inventory.json"
                                       :playbooks {:create "main.yml"}
                                       :host-key-checking false}
                                (machine-key-file opts)
                                (assoc :private-key (machine-key-file opts))))))))

(defn ansible-local-step
  "Manage the `Host <alias>` block in `~/.ssh/config`.

  The playbook's variables are Ansible's, not Selmer's, so they arrive as
  extra-vars: the local inventory targets localhost only and carries no host
  vars. `name` is reserved in Ansible, hence host_alias. block_state drives
  blockinfile in both directions, so a delete removes what a create wrote."
  [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        data (data-fn opts)
        specs [(template-spec (walter-template "ansible-local" "ansible.cfg")
                              (str dir "/ansible.cfg") data)
               (template-spec (walter-template "ansible-local" "inventory.ini")
                              (str dir "/inventory.ini") data)
               (template-spec (walter-template "ansible-local" "main.yml")
                              (str dir "/main.yml") data)]
        delete? (= :delete (:green/event opts))
        config {:dir dir
                :inventory "inventory.ini"
                :playbooks {:create "main.yml" :delete "main.yml"}
                :extra-vars {:host_alias (:host-alias data)
                             :ssh_hosts (vec (cons {:name (:host-alias data) :ip (:ip data) :user (:user data)}
                                                   (map (fn [seat] {:name (str (:host-alias data) "-" seat) :ip (:ip data) :user seat}) (users opts))))
                             :ssh_legacy_marker_prefix "walter"
                             :block_state (if delete? "absent" "present")}}]
    (ansible/ansible-with-spec opts config specs)))

(defn ansible-remote-step
  "Reach the machine, then provision it: nix always, and Emacs plus a cloned
  configuration when `emacs-config-repo` names one.

  The ping is kept because it is what fails first and most legibly when the
  inventory, the login or the key is wrong. It does not prove the machine is up:
  ONCE's compute template carries a `remote-exec` provisioner behind an SSH
  connection, so `tofu apply` has already blocked on that.

  The Emacs half is gated in the *template*, not at runtime, so a project that
  names no repository renders a playbook that does not mention Emacs at all —
  which is what `scripts/golden.sh` then holds still.

  The GitHub token travels as an extra-var holding a *path* on the controller;
  the playbook reads the file with `lookup('file', …)` at play time, so the
  token itself never reaches a rendered file. Once the play has seeded the
  machine, the sandbox directory holding the token is deleted — from then on
  the machine's own login is the token's home. A *failed* play keeps it,
  deliberately: the sandbox surviving is what spares the retry a second
  device-code approval."
  [opts]
  (let [dir (tool-dir opts ansible-remote-tool)
        data (data-fn opts)
        specs [(template-spec (walter-template "ansible-remote" "ansible.cfg")
                              (str dir "/ansible.cfg") data)
               (template-spec (walter-template "ansible-remote" "main.yml")
                              (str dir "/main.yml") data)
               ;; One host per login: the primary plus every seat, so the same
               ;; play provisions each home as its own user — no become_user,
               ;; no per-seat task surgery.
               (raw-spec (str dir "/inventory.json") (inventory data (users opts)))
               ;; Shared sources: the focused convergence events render these
               ;; same task files into their own stages.
               (template-spec (walter-template "tasks" "nix-packages.yml")
                              (str dir "/nix-packages.yml") data)
               (template-spec (walter-template "tasks" "asdf.yml")
                              (str dir "/asdf.yml") data)]
        rendered (sc/scaffold opts specs)]
    (if (or (= :build (:green/event opts))
            (= :delete (:green/event opts)))
      rendered
      (let [result (ansible/ansible-step
                    rendered
                    (cond-> {:dir dir
                             :inventory "inventory.json"
                             :playbooks {:create "main.yml"}
                             :host-key-checking false
                             :extra-vars {:github_token_file
                                          (str (:walter/github-token-file opts))}}
                      (machine-key-file opts)
                      (assoc :private-key (machine-key-file opts))))]
        (if (wf/failed? result)
          result
          (github/delete-token-dir! result))))))

(defn- converge-step
  "Run one focused, existing-machine convergence through managed SSH aliases.

  No provider or backend is consulted. The alias is the prerequisite and the
  dedicated playbook checks the per-login binary before including the same task
  source the full create uses. Host-key checking is deliberately left enabled:
  focused convergence trusts the alias already established by create."
  [opts kind]
  (let [{:keys [tool title binary binary-path task-file]}
        (case kind
          :nix {:tool converge-nix-tool
                :title "Converge the declared Nix profile"
                :binary "nix"
                :binary-path "/nix/var/nix/profiles/default/bin/nix"
                :task-file "nix-packages.yml"}
          :asdf {:tool converge-asdf-tool
                 :title "Converge the declared asdf runtimes"
                 :binary "asdf"
                 :binary-path "{{ ansible_env.HOME }}/.nix-profile/bin/asdf"
                 :task-file "asdf.yml"})
        dir (tool-dir opts tool)
        data (assoc (data-fn opts)
                    :converge-title title
                    :converge-binary binary
                    :converge-binary-path binary-path
                    :converge-task-file task-file)
        specs [(template-spec (walter-template "ansible-converge" "ansible.cfg")
                              (str dir "/ansible.cfg") data)
               (template-spec (walter-template "ansible-converge" "main.yml")
                              (str dir "/main.yml") data)
               (template-spec (walter-template "tasks" task-file)
                              (str dir "/" task-file) data)
               (raw-spec (str dir "/inventory.json")
                         (alias-inventory data (users opts)))]
        rendered (sc/scaffold opts specs)]
    (if (= :build (:green/event opts))
      rendered
      (ansible/ansible-step rendered
                            {:dir dir
                             :inventory "inventory.json"
                             :playbooks {:create "main.yml"}
                             :extra-vars (when (= kind :nix)
                                           {:walter_nix_upgrade true})}))))

(defn converge-nix-step
  "Converge the declared Nix profile, or pass through when nothing is declared.

  The pass-through is what a `build` needs: validation refuses a real
  `converge-nix` with an empty `nix-packages`, but a build of the same project
  renders every stage, and a stage with nothing to converge should render no
  directory at all rather than a play with an empty command — the gate
  `emacs-packages` already applies to a project with no Emacs configuration."
  [opts]
  (if (seq (validate/nix-package-names opts)) (converge-step opts :nix) opts))

(defn converge-asdf-step
  "Converge the declared asdf runtimes, or pass through when none are declared.
  Same build-time gate as `converge-nix-step`."
  [opts]
  (if (seq (validate/asdf-tools opts)) (converge-step opts :asdf) opts))

(defn emacs-packages-step
  "Start the Emacs package bootstrap on the machine and return without waiting.

  Gated on `emacs-config-repo` in *Clojure* rather than in the template, unlike
  every other optional block here. Those gate in Selmer because they are tasks
  inside a play that runs regardless; this is the whole stage, and a project
  with no Emacs should render no directory at all rather than a playbook whose
  only content is an absence.

  The fire-and-forget is the design, not an optimisation. Nothing downstream
  reads what this produces — it is a cache being warmed — so waiting would buy
  only the ability to fail a create on an ELPA outage, which is the trade the
  remote play already refused when it left packages unfetched. What changes here
  is *when* the wait happens, not whether: it moves off the first interactive
  launch, where Emacs shows nothing for minutes, onto a machine nobody is
  watching.

  Delete skips it too. There is no work to undo — the packages go with the boot
  volume — and scaffolding a stage against `:delete` only to remove it would
  render a tree for a machine being destroyed."
  [opts]
  (if (or (str/blank? (str (:emacs-config-repo opts)))
          (= :delete (:green/event opts)))
    (assoc opts :green/exit 0)
    (let [dir (tool-dir opts emacs-packages-tool)
          data (data-fn opts)
          specs [(template-spec (walter-template "emacs-packages" "ansible.cfg")
                                (str dir "/ansible.cfg") data)
                 (template-spec (walter-template "emacs-packages" "main.yml")
                                (str dir "/main.yml") data)
                 ;; Seats too: each home got the Emacs clone, so each cache is
                 ;; worth warming — the daemonized job runs once per login.
                 (raw-spec (str dir "/inventory.json") (inventory data (users opts)))]
          rendered (sc/scaffold opts specs)]
      (if (= :build (:green/event opts))
        rendered
        (ansible/ansible-step rendered
                              (cond-> {:dir dir
                                       :inventory "inventory.json"
                                       :playbooks {:create "main.yml"}
                                       :host-key-checking false}
                                (machine-key-file opts)
                                (assoc :private-key (machine-key-file opts))))))))
