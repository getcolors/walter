(ns io.github.getcolors.walter.tools
  "The three step functions, their template specs, and the generated inventory.

  Walter reuses exactly one thing from ONCE here: the compute provider templates,
  by classpath keyword. That is the valuable part — the provider HCL, the
  pinned-image branch, the ForceNew commentary — and it is a *resource*, which
  `scripts/golden.sh` can watch, rather than a function signature ONCE is free
  to reshape. Everything else in this namespace is walter's own, including both
  Ansible stages: reusing ONCE's `ansible-local` would mean an unrelated change
  there rewriting ~/.ssh/config on the operator's workstation at pin-bump time."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [green.ansible :as ansible]
   [green.scaffold :as sc]
   [green.tofu :as tofu]
   [green.workflow :as wf]
   [io.github.getcolors.walter.utils :as utils]
   [io.github.getcolors.walter.validate :as validate]))

(def compute-tool
  "The compute stage directory, and half of its OpenTofu state key.

  Deliberately not `tofu-compute`. Remote state is keyed `<profile>/<tool>` and
  nothing but convention keeps this project's profile distinct from another's,
  so a walter-specific stage name means a colliding profile still cannot produce
  ONCE's state key."
  "walter-compute")

(def ansible-local-tool "walter-ansible-local")
(def ansible-remote-tool "walter-ansible-remote")
(def emacs-packages-tool "walter-emacs-packages")

(def ^:private walter-root "io.github.getcolors.walter.tools")
(def ^:private once-root "io.github.getcolors.once.tools")
(def ^:private raw-template :io.github.getcolors.walter/raw)

(def ^:private template-opts
  "Selmer reads `<{ var }>` and `<% if %>`, leaving `{{ }}` and `{% %}` to Jinja2
  in the Ansible files."
  {:tag-open \<
   :tag-close \>
   :filter-open \{
   :filter-close \}})

(defn tool-dir
  "The isolated working directory for `tool` in the active profile.

  A relative workdir resolves against the directory holding colors.yml rather
  than the current one, so walter renders to the same place however deep in the
  project it was invoked from."
  [opts tool]
  (let [workdir (io/file (or (:workdir opts) ".colors"))
        state-dir (when-not (.isAbsolute workdir)
                    (some-> (:green/state-file opts) io/file .getAbsoluteFile .getParent))
        root (if state-dir (io/file state-dir workdir) workdir)]
    (str (io/file root (or (:profile opts) "walter") tool))))

(defn- once-template
  [tool provider file]
  (keyword (str once-root "." tool "." provider) file))

(defn- walter-template
  [tool file]
  (keyword (str walter-root "." tool) file))

(defn- template-spec
  [template target data]
  {:template template :target target :data data :opts template-opts})

(defn- raw-spec
  [target content]
  (template-spec raw-template target {:content content}))

(defn- credential-env
  "Environment additions for the providers in `slots`, plus the state backend —
  every stage reads and writes state, so the backend credentials belong to all of
  them. Unset credentials are omitted, so build and dry-run stay credential-free."
  [opts & slots]
  (not-empty
   (into {}
         (keep (fn [[k env-var]]
                 (when-let [v (not-empty (str (get opts k)))]
                   [env-var v])))
         (apply merge (map #(validate/tofu-env opts %)
                           (conj (vec slots) :provider-backend))))))

(defn backend-credential-env
  "Environment additions for a process that only reads OpenTofu state. Provider
  credentials are left out: reading state never calls a provider API."
  [opts]
  (credential-env opts))

(defn fallback-compute-params
  "What a build or a dry-run stands in for the values only a real apply knows.
  Rendering must never need state, or `build` would stop being credential-free."
  [{:keys [profile provider-compute] :as opts}]
  (let [name (or profile "walter")]
    (case provider-compute
      "oci" {:ip "192.168.0.1" :sudoer "ubuntu" :uid "1001" :name name :user "ubuntu"}
      "yandex" {:ip "192.168.0.1" :sudoer "ubuntu" :uid "1000" :name name :user "ubuntu"}
      "no-infra" (cond-> {:ip (or (:no-infra-compute-ip opts) "192.168.0.1")
                          :sudoer (or (:no-infra-compute-sudoer opts) "root")
                          :name name
                          :user (or (:no-infra-compute-user opts) "root")}
                   (:no-infra-compute-uid opts) (assoc :uid (:no-infra-compute-uid opts)))
      {:ip "192.168.0.1" :sudoer "root" :name name :user "root"})))

(defn compute-specs
  "ONCE's provider template, plus — on a provider walter can power cycle — one
  extra file publishing the instance id.

  OpenTofu merges every .tf in a directory, so that output needs no change to
  ONCE's template and no fork of it. The address it names,
  `oci_core_instance.ampere_vm`, is one ONCE's own comments call out as a state
  address that must not be renamed, which makes it the most durable handle
  available. `scripts/golden.sh` asserts it is still there."
  [opts dir]
  (let [provider (or (:provider-compute opts) "oci")]
    (cond-> [(template-spec (once-template "tofu" provider "main.tf")
                            (str dir "/main.tf")
                            opts)]
      (validate/stoppable? opts)
      (conj (template-spec (walter-template (str "tofu." provider) "outputs.tf")
                           (str dir "/outputs.tf")
                           opts)))))

(defn- output-params
  [opts]
  (some-> (get-in opts [:tofu/outputs :params]) walk/keywordize-keys))

(defn compute-step
  "Render the compute stage and apply it, adopting the machine's address.

  The adopted params are merged flat into opts as well as kept under
  `:walter/compute-params`, because both Ansible stages read `ip` and `user`
  directly."
  [opts]
  (let [dir (tool-dir opts compute-tool)
        specs (compute-specs opts dir)
        fallback (fallback-compute-params opts)
        result (tofu/tofu-with-spec opts specs
                                    {:dir dir
                                     :env (credential-env opts :provider-compute)})]
    (cond
      (wf/failed? result) result
      (= :build (:green/event opts)) (merge result fallback {:walter/compute-params fallback})
      ;; A destroy has run; there are no outputs left to adopt.
      (= :delete (:green/event opts)) result
      :else (let [params (merge fallback (output-params result))]
              (merge result params {:walter/compute-params params})))))

(defn instance-id
  "The OCID the power verbs act on.

  Desired state wins when it carries one, so `stop` and `start` keep working
  with no access to OpenTofu state at all — a broken backend should not strand
  you with a running machine you cannot stop. Otherwise it comes from the
  compute stage's `instance_id` output, which is why walter renders that extra
  .tf file at all."
  [opts]
  (or (not-empty (str (:oci-instance-id opts)))
      (try
        (not-empty (str (:instance_id (tofu/outputs (tool-dir opts compute-tool)
                                                    (backend-credential-env opts)))))
        (catch Exception _ nil))))

(defn inventory
  "The Ansible inventory for the one machine walter manages.

  ONCE's builder carries an admin/users split and a `root@host` key convention
  that a single-machine package has no use for, so this is walter's own: one
  host, one group, keyed by the alias you would `ssh` with."
  [{:keys [ip user host-alias]}]
  (json/generate-string
   {:all {:hosts {(or host-alias "walter") {:ansible_host ip :ansible_user user}}}}
   {:pretty true}))

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

(defn nix-package-names
  "The `nix-packages` entries, trimmed and with blanks dropped.

  A YAML list is the shape colors.yml wants — it reads well and
  `green.cli/keywordize` carries it through untouched. A plain string is accepted
  too, and not as a convenience: `nix-packages` is otherwise the only non-scalar
  key walter has, and `green.cli/read-pars` overlays `COLORS_PAR_*` onto flat keys
  as strings. Without this, setting COLORS_PAR_NIX_PACKAGES would replace the
  vector with a string that renders as one impossible package name."
  [opts]
  (let [names (:nix-packages opts)]
    (->> (if (sequential? names) names (str/split (str names) #"\s+"))
         (map (comp str/trim str))
         (remove str/blank?))))

(defn nix-package-flakerefs
  "The names as pinned flakerefs, space-separated for one `nix profile add`.

  Empty when nothing is named, which is what gates the step. One invocation
  rather than one per package, so nix resolves the set together and the profile
  takes a single generation."
  [opts]
  (->> (nix-package-names opts)
       (map #(str nixpkgs-ref "#" %))
       (str/join " ")))

(defn asdf-tools
  "The `asdf-tools` entries, normalised to {:name :version :plugin}.

  `plugin` is optional: asdf resolves a bare name against its own plugin index,
  and only a plugin outside that index — or one deliberately pinned to a fork —
  needs the URL spelled out."
  [opts]
  (->> (:asdf-tools opts)
       (keep (fn [t]
               (let [name* (not-empty (str/trim (str (:name t))))
                     version (not-empty (str/trim (str (:version t))))]
                 (when (and name* version)
                   (cond-> {:name name* :version version}
                     (not-empty (str/trim (str (:plugin t))))
                     (assoc :plugin (str/trim (str (:plugin t)))))))))
       vec))

(defn corepack-packages
  "The `corepack-packages` names — package managers Node's own corepack enables.

  Same string tolerance as `nix-package-names`, for the same `COLORS_PAR_*`
  reason."
  [opts]
  (let [names (:corepack-packages opts)]
    (->> (if (sequential? names) names (str/split (str names) #"\s+"))
         (map (comp str/trim str))
         (remove str/blank?)
         vec)))

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
         :ip (or (not-empty (str (:ip opts))) "192.168.0.1")
         :user (or (not-empty (str (:user opts))) "root")
         :host-alias (utils/host-alias opts)
         :nix-package-flakerefs (nix-package-flakerefs opts)
         :nix-package-count (count (nix-package-names opts))
         :login-shell-is-fish (= "fish" (not-empty (str/trim (str (:login-shell opts)))))
         ;; Rendered as JSON rather than looped in the template: a JSON array is
         ;; a valid YAML flow sequence, so the playbook keeps one task with an
         ;; Ansible `loop` instead of N generated ones, and the indentation
         ;; cannot drift.
         :asdf-tools-json (let [tools (asdf-tools opts)]
                            (when (seq tools) (json/generate-string tools)))
         :corepack-packages-json (let [pkgs (corepack-packages opts)]
                                   (when (seq pkgs) (json/generate-string pkgs)))
         ;; JSON for the same reason as asdf-tools above: one Ansible `loop`
         ;; over a flow sequence rather than N generated tasks whose
         ;; indentation can drift. Only the agent name and its relative path —
         ;; there is nothing secret in this, and the credentials themselves are
         ;; read from the controller at play time and never rendered.
         :seed-agent-credentials-json (let [agents (seed-agent-credentials opts)]
                                        (when (seq agents)
                                          (json/generate-string agents)))
         ;; JSON for the same reason as the three above. Organisation names
         ;; only: what gets cloned is decided on the machine at create time,
         ;; against GitHub's API, so nothing here can go stale between a build
         ;; and the create that uses it.
         :clone-orgs-json (let [orgs (clone-orgs opts)]
                            (when (seq orgs) (json/generate-string orgs)))
         :emacs-config-dest (or (not-empty (str (:emacs-config-dest opts)))
                                "~/.config/emacs")
         ;; Defaulted for the same reason as the Emacs destination: a repo with
         ;; no destination is unambiguous intent, and the alternative is a
         ;; playbook carrying `dest: ""` that only fails on the machine. There
         ;; is no equivalent of Emacs' XDG path to defer to here, so this is a
         ;; convention rather than a lookup — say so in colors.yml to override it.
         :dotfiles-dest (or (not-empty (str (:dotfiles-dest opts)))
                            "~/.dotfiles")
         ;; "default" is the installer's own default when neither -p nor
         ;; DOTFILES_PROFILE is given, so this defers to the tool rather than
         ;; inventing a second answer.
         :dotfiles-profile (or (not-empty (str/trim (str (:dotfiles-profile opts))))
                               "default")
         ;; The union of the steps that stamp a once-only action. Computed here
         ;; rather than as an `or` in the template because Selmer's `<% if %>`
         ;; takes one value, and a second feature needing the directory should
         ;; extend this expression rather than duplicate the task.
         :needs-state-dir (boolean (or (not-empty (str (:dotfiles-repo opts)))
                                       (not-empty (str (:atuin-username opts)))))))

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
                             :ip (:ip data)
                             :user (:user data)
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
  which is what `scripts/golden.sh` then holds still."
  [opts]
  (let [dir (tool-dir opts ansible-remote-tool)
        data (data-fn opts)
        specs [(template-spec (walter-template "ansible-remote" "ansible.cfg")
                              (str dir "/ansible.cfg") data)
               (template-spec (walter-template "ansible-remote" "main.yml")
                              (str dir "/main.yml") data)
               (raw-spec (str dir "/inventory.json") (inventory data))]
        rendered (sc/scaffold opts specs)]
    (if (or (= :build (:green/event opts))
            (= :delete (:green/event opts)))
      rendered
      (ansible/ansible-step rendered {:dir dir
                                      :inventory "inventory.json"
                                      :playbooks {:create "main.yml"}
                                      :host-key-checking false}))))

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
                 (raw-spec (str dir "/inventory.json") (inventory data))]
          rendered (sc/scaffold opts specs)]
      (if (= :build (:green/event opts))
        rendered
        (ansible/ansible-step rendered {:dir dir
                                        :inventory "inventory.json"
                                        :playbooks {:create "main.yml"}
                                        :host-key-checking false})))))
