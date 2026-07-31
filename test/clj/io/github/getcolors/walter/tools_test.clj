(ns io.github.getcolors.walter.tools-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.tofu :as tofu]
   [io.github.getcolors.walter.tools :as tools]))

(deftest stage-names-are-walter-specific
  (testing "remote state is keyed <profile>/<tool>, and only convention keeps
           two projects' profiles apart — a walter-specific stage means a
           colliding profile still cannot address another package's state"
    (is (= "walter-compute" tools/compute-tool))
    (is (not= "tofu-compute" tools/compute-tool))))

(deftest a-relative-workdir-resolves-next-to-colors-yml
  (testing "not next to the caller, so walter renders to one place however deep
           in the project it was invoked from"
    (is (= "/srv/project/.colors/p/walter-compute"
           (tools/tool-dir {:workdir ".colors"
                            :profile "p"
                            :green/state-file "/srv/project/colors.yml"}
                           "walter-compute")))))

(deftest an-absolute-workdir-is-taken-as-given
  (is (= "/tmp/out/p/walter-compute"
         (tools/tool-dir {:workdir "/tmp/out"
                          :profile "p"
                          :green/state-file "/srv/project/colors.yml"}
                         "walter-compute"))))

(deftest the-profile-names-the-directory
  (is (str/includes? (tools/tool-dir {:workdir "/tmp/out" :profile "walter-oci"} "x")
                     "/walter-oci/")))

;; ---------------------------------------------------------------------------
;; specs

(deftest oci-renders-onces-template-plus-walters-output
  (let [specs (tools/compute-specs {:provider-compute "oci"} "/w")]
    (is (= 2 (count specs)))
    (testing "the provider HCL is ONCE's, by classpath keyword"
      (is (= :io.github.getcolors.once.tools.tofu.oci/main.tf
             (:template (first specs)))))
    (testing "the instance-id output is walter's, rendered beside it — OpenTofu
             merges every .tf in a directory, so no fork of ONCE is needed"
      (is (= :io.github.getcolors.walter.tools.tofu.oci/outputs.tf
             (:template (second specs))))
      (is (= "/w/outputs.tf" (:target (second specs)))))))

(deftest providers-walter-cannot-power-cycle-get-no-extra-output
  (doseq [provider ["hcloud" "digitalocean" "yandex" "no-infra"]]
    (let [specs (tools/compute-specs {:provider-compute provider} "/w")]
      (is (= 1 (count specs)) (str provider " should render main.tf alone"))
      (is (= (keyword (str "io.github.getcolors.once.tools.tofu." provider) "main.tf")
             (:template (first specs)))))))

(deftest templates-read-selmer-delimiters-that-leave-jinja-alone
  (let [spec (first (tools/compute-specs {:provider-compute "oci"} "/w"))]
    (is (= {:tag-open \< :tag-close \> :filter-open \{ :filter-close \}}
           (:opts spec)))))

;; ---------------------------------------------------------------------------
;; fallbacks

(deftest a-build-never-reaches-for-state
  (testing "OCI's cloud image logs in as ubuntu"
    (let [p (tools/fallback-compute-params {:provider-compute "oci" :profile "w"})]
      (is (= "ubuntu" (:user p)))
      (is (= "ubuntu" (:sudoer p)))
      (is (= "w" (:name p)))
      (is (some? (:ip p)))))
  (testing "no-infra takes what desired state already knows"
    (let [p (tools/fallback-compute-params {:provider-compute "no-infra"
                                            :no-infra-compute-ip "198.51.100.10"
                                            :no-infra-compute-user "dev"
                                            :profile "w"})]
      (is (= "198.51.100.10" (:ip p)))
      (is (= "dev" (:user p)))))
  (testing "an unknown provider still renders"
    (is (some? (:ip (tools/fallback-compute-params {:provider-compute "hcloud"}))))))

;; ---------------------------------------------------------------------------
;; inventory

(deftest the-inventory-is-one-host-under-one-group
  (testing "ONCE's admin/users split and root@host keys serve a fleet; walter
           manages exactly one machine"
    (let [parsed (json/parse-string
                  (tools/inventory {:ip "203.0.113.7" :user "ubuntu" :host-alias "walter-oci"}))]
      (is (= {"all" {"hosts" {"walter-oci" {"ansible_host" "203.0.113.7"
                                            "ansible_user" "ubuntu"}}}}
             parsed)))))

(deftest the-inventory-host-is-the-alias-you-would-ssh-with
  (is (contains? (get-in (json/parse-string
                          (tools/inventory {:ip "1.2.3.4" :user "u" :host-alias "dev"}))
                         ["all" "hosts"])
                 "dev")))

;; ---------------------------------------------------------------------------
;; the instance id

(deftest desired-state-wins-over-opentofu-state
  (testing "so stop and start keep working when the backend is unreachable — a
           broken bucket should not strand you with a machine you cannot stop"
    (with-redefs [tofu/outputs (fn [& _] (throw (ex-info "backend unreachable" {})))]
      (is (= "ocid1.instance.oc1..fromfile"
             (tools/instance-id {:oci-instance-id "ocid1.instance.oc1..fromfile"}))))))

(deftest otherwise-it-comes-from-the-compute-stages-output
  (with-redefs [tofu/outputs (fn [& _] {:instance_id "ocid1.instance.oc1..fromstate"
                                        :params {"ip" "203.0.113.7"}})]
    (is (= "ocid1.instance.oc1..fromstate"
           (tools/instance-id {:profile "p" :workdir "/tmp/x"})))))

(deftest an-unreachable-backend-answers-nil-rather-than-throwing
  (with-redefs [tofu/outputs (fn [& _] (throw (ex-info "no state" {})))]
    (is (nil? (tools/instance-id {:profile "p" :workdir "/tmp/x"})))))

;; ---------------------------------------------------------------------------
;; template data

(deftest ansible-data-always-has-an-address-a-login-and-an-alias
  (let [data (tools/data-fn {:profile "walter-oci"})]
    (is (= "walter-oci" (:host-alias data)))
    (is (some? (:ip data)))
    (is (some? (:user data))))
  (testing "real values win over the placeholders"
    (let [data (tools/data-fn {:profile "p" :ip "203.0.113.7" :user "ubuntu"})]
      (is (= "203.0.113.7" (:ip data)))
      (is (= "ubuntu" (:user data))))))

(deftest the-emacs-destination-defaults-to-the-xdg-path
  (testing "a repo with no destination is unambiguous intent, and the
           alternative is a playbook carrying dest: \"\" that only fails on the
           machine"
    (is (= "~/.config/emacs"
           (:emacs-config-dest (tools/data-fn {:profile "p"}))))
    (is (= "~/.config/neoemacs"
           (:emacs-config-dest (tools/data-fn {:profile "p"
                                               :emacs-config-dest "~/.config/neoemacs"}))))))

;; ---------------------------------------------------------------------------
;; the remote playbook

(defn- render-remote-playbook
  "Run the remote step as a build — which renders and returns without reaching
  for Ansible — and hand back the playbook it wrote."
  [opts]
  (let [dir (str (fs/create-temp-dir))
        merged (merge {:profile "p"
                       :workdir dir
                       :provider-compute "oci"
                       :green/event :build}
                      opts)]
    (tools/ansible-remote-step merged)
    (slurp (str (tools/tool-dir merged tools/ansible-remote-tool) "/main.yml"))))

(deftest the-remote-playbook-installs-nix-unconditionally
  (testing "nix is the one thing walter treats as part of what a development
           machine is — with it present, anything else is one nix profile
           install away and needs no change to walter"
    (let [rendered (render-remote-playbook {})]
      (is (str/includes? rendered "install.determinate.systems/nix"))
      (is (str/includes? rendered "--no-confirm")
          "the installer prompts otherwise, and Ansible has given it no TTY")
      (is (str/includes? rendered "creates: /nix/receipt.json")
          "the receipt is what makes a re-run a no-op instead of a reinstall"))))

(deftest the-remote-playbook-installs-a-terminfo-database
  (testing "TERM travels over SSH and the terminfo database does not, so a
           machine whose distro predates the operator's terminal cannot run
           emacs, top or less — ungated, because that is broken for everyone"
    (let [rendered (render-remote-playbook {})]
      (is (str/includes? rendered "#ghostty.terminfo"))
      (is (str/includes? rendered ".terminfo/{{ item.dir }}/{{ item.name }}")
          "linked into ~/.terminfo, which the system ncurses already reads —
           TERMINFO_DIRS would only reach a login shell"))))

(deftest the-remote-playbook-omits-nix-packages-when-none-are-named
  (testing "gated in the template, so a project naming no packages renders a
           playbook that does not carry a bare `nix profile add`"
    (let [rendered (render-remote-playbook {})]
      (is (not (str/includes? rendered "nix profile add")))
      (is (not (str/includes? rendered "walter_nix_packages"))))))

(deftest named-packages-are-added-from-the-pinned-nixpkgs
  (testing "attribute paths in colors.yml become flakerefs against the same pin
           the terminfo and Emacs steps use, so a rebuilt machine matches — all
           in one invocation, so the profile takes a single generation"
    (let [rendered (render-remote-playbook {:nix-packages ["asdf-vm" "ripgrep" "fd" "fish"]})]
      (is (str/includes? rendered
                         (str "cmd: nix profile add "
                              "github:NixOS/nixpkgs/nixpkgs-unstable#asdf-vm "
                              "github:NixOS/nixpkgs/nixpkgs-unstable#ripgrep "
                              "github:NixOS/nixpkgs/nixpkgs-unstable#fd "
                              "github:NixOS/nixpkgs/nixpkgs-unstable#fish"))))))

(deftest re-adding-is-safe-so-no-creates-guard-is-needed
  (testing "nix profile add warns and exits 0 on a flakeref already in the
           profile, so the task is re-runnable — and a `creates` check could not
           have been written, since the attribute rarely matches the binary
           (asdf-vm ships asdf, ripgrep ships rg)"
    (let [rendered (render-remote-playbook {:nix-packages ["ripgrep"]})]
      (is (not (str/includes? rendered "creates: \"{{ ansible_env.HOME }}/.nix-profile/bin/ripgrep\"")))
      (is (str/includes? rendered "is already added")
          "the no-op is detected from nix's own warning, per package")
      (is (str/includes? rendered "list | length < 1")
          "one warning per package means nothing was installed"))))

(deftest package-names-tolerate-the-flat-key-overlay
  (testing "nix-packages is walter's only non-scalar key, and read-pars overlays
           COLORS_PAR_* as strings — a string must not render as one impossible
           package name"
    (is (= ["asdf-vm" "ripgrep" "fd" "fish"]
           (tools/nix-package-names {:nix-packages ["asdf-vm" "ripgrep" "fd" "fish"]})))
    (is (= ["asdf-vm" "ripgrep" "fd" "fish"]
           (tools/nix-package-names {:nix-packages "asdf-vm ripgrep fd fish"})))
    (is (= "" (tools/nix-package-flakerefs {})))
    (is (= "" (tools/nix-package-flakerefs {:nix-packages []}))
        "an empty list is the same intent as no key at all")
    (is (= (str tools/nixpkgs-ref "#ripgrep")
           (tools/nix-package-flakerefs {:nix-packages ["  ripgrep  " ""]}))
        "blank entries are dropped rather than rendered as a bare pin#")))

(deftest the-remote-playbook-omits-the-login-shell-when-none-is-named
  (testing "gated, so a project that names no shell leaves passwd alone"
    (let [rendered (render-remote-playbook {:nix-packages ["ripgrep"]})]
      (is (not (str/includes? rendered "Set the login shell")))
      (is (not (str/includes? rendered "/etc/shells"))))))

(deftest the-login-shell-is-checked-on-the-machine-before-it-is-set
  (testing "a shell that is not in the profile would leave an account that
           cannot start a session, so the stat gates the usermod"
    (let [rendered (render-remote-playbook {:nix-packages ["fish"] :login-shell "fish"})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered ".nix-profile/bin/fish\""))
      (is (str/includes? rendered "that: walter_login_shell.stat.exists"))
      (is (str/includes? rendered "name: \"{{ ansible_user_id }}\"")
          "the connected user, not a name from colors.yml that a typo could
           point at root")
      (is (< (at "Locate the login shell") (at "Refuse to set") (at "Set the login shell"))))))

(deftest fish-gets-nix-on-path-before-it-becomes-the-login-shell
  (testing "/etc/profile.d/nix.sh is sh-only and a nix-built fish reads neither
           it nor /etc/fish/conf.d, so a clean login fish cannot find nix itself
           — unrecoverable from inside that shell, hence ordered before the switch"
    (let [rendered (render-remote-playbook {:nix-packages ["fish"] :login-shell "fish"})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered "nix-daemon.fish")
          "nix ships the fish half of its own integration; do not reimplement it")
      (is (str/includes? rendered ".config/fish/conf.d/nix.fish"))
      (is (< (at "conf.d/nix.fish") (at "Set the login shell"))))))

(deftest only-fish-gets-the-extra-shell-integration
  (testing "bash and sh already reach nix through /etc/profile.d/nix.sh, so the
           fish file would be dead weight — and wrong — for them"
    (let [rendered (render-remote-playbook {:nix-packages ["bash"] :login-shell "bash"})]
      (is (str/includes? rendered "Set the login shell"))
      (is (not (str/includes? rendered "nix.fish"))))))

(deftest the-remote-playbook-omits-emacs-when-no-repo-is-named
  (testing "gated in the template rather than at runtime, so a project that
           names no repository renders a playbook that does not mention Emacs"
    (let [rendered (render-remote-playbook {})]
      (is (not (str/includes? rendered "emacs")))
      (is (not (str/includes? rendered "Emacs"))))))

(deftest a-named-repo-adds-emacs-and-the-clone-after-nix
  (testing "order matters: a config cloned for an Emacs that is not installed
           leaves a machine that looks provisioned and is not"
    (let [rendered (render-remote-playbook {:emacs-config-repo "git@github.com:me/emacs.d.git"
                                            :emacs-config-dest "~/.config/neoemacs"})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered "nixpkgs-unstable#emacs-nox")
          "from the same nixpkgs ref as everything else here")
      (is (str/includes? rendered "repo: \"git@github.com:me/emacs.d.git\""))
      (is (str/includes? rendered "dest: \"~/.config/neoemacs\""))
      (is (str/includes? rendered "update: false")
          "a development machine's working copy is not a deployment")
      (is (< (at "install.determinate.systems") (at "#emacs-nox") (at "repo:"))))))

(deftest the-clone-never-writes-a-key-to-the-machine
  (testing "ansible.cfg forwards the agent, so the clone speaks SSH and can push
           back — accept_hostkey covers github.com being unknown on a fresh host"
    (let [rendered (render-remote-playbook {:emacs-config-repo "git@github.com:me/emacs.d.git"})]
      (is (str/includes? rendered "accept_hostkey: true"))
      (is (str/includes? rendered "dest: \"~/.config/emacs\"")))))

;; ---------------------------------------------------------------------------
;; the dotfiles

(deftest the-remote-playbook-omits-dotfiles-when-no-repo-is-named
  (testing "gated in the template rather than at runtime, so a project that
           names no repository renders a playbook that does not mention them"
    (let [rendered (render-remote-playbook {})]
      (is (not (str/includes? rendered "dotfiles")))
      (is (not (str/includes? rendered ".local/state/walter"))))))

(deftest the-dotfiles-are-installed-after-the-shell-and-the-editor
  (testing "the profile can carry ~/.config/fish/config.fish and ~/.doom.d, which
           only make sense once the shell and the editor they configure are
           already on the machine"
    (let [rendered (render-remote-playbook
                    {:nix-packages ["asdf-vm" "fish" "babashka"]
                     :login-shell "fish"
                     :emacs-config-repo "git@github.com:me/emacs.d.git"
                     :dotfiles-repo "git@github.com:me/dotfiles.git"
                     :dotfiles-dest "~/code/me/dotfiles"
                     :dotfiles-profile "ubuntu"})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered "repo: \"git@github.com:me/dotfiles.git\""))
      (is (str/includes? rendered "dest: \"~/code/me/dotfiles\""))
      (is (str/includes? rendered "cmd: bb install -p ubuntu"))
      (is (str/includes? rendered "chdir: \"~/code/me/dotfiles\"")
          "chdir is type: path, so Ansible expanduser's it and no shell is needed")
      (is (< (at "Set the login shell") (at "#emacs-nox") (at "Clone the dotfiles"))))))

(deftest the-dotfiles-clone-does-not-discard-work-done-on-the-machine
  (testing "same update: false as the Emacs clone — a development machine's
           working copy is not a deployment"
    (let [rendered (render-remote-playbook {:nix-packages ["babashka"]
                                            :dotfiles-repo "git@github.com:me/dotfiles.git"})]
      (is (str/includes? rendered "update: false"))
      (is (str/includes? rendered "accept_hostkey: true")))))

(deftest the-install-is-stamped-so-a-converge-does-not-overwrite-home
  (testing "the installer copies rendered files over $HOME with replace-existing,
           so re-running it on every create would silently undo edits made on the
           machine — which is the one thing the clone goes out of its way to avoid"
    (let [rendered (render-remote-playbook {:nix-packages ["babashka"]
                                            :dotfiles-repo "git@github.com:me/dotfiles.git"
                                            :dotfiles-profile "ubuntu"})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered
                         "creates: \"{{ ansible_env.HOME }}/.local/state/walter/dotfiles-ubuntu\"")
          "the stamp carries the profile, so switching profile is what re-runs it")
      (is (< (at "Ensure walter's state directory")
             (at "cmd: bb install")
             (at "Record that the dotfiles profile is installed"))
          "the stamp is written after the install, so a failure retries next create"))))

(deftest the-dotfiles-destination-and-profile-both-default
  (testing "a repo with no destination is unambiguous intent, and \"default\" is
           the installer's own answer when neither -p nor DOTFILES_PROFILE is given"
    (let [data (tools/data-fn {:profile "p"})]
      (is (= "~/.dotfiles" (:dotfiles-dest data)))
      (is (= "default" (:dotfiles-profile data))))
    (let [data (tools/data-fn {:profile "p"
                               :dotfiles-dest "~/code/me/dotfiles"
                               :dotfiles-profile "ubuntu"})]
      (is (= "~/code/me/dotfiles" (:dotfiles-dest data)))
      (is (= "ubuntu" (:dotfiles-profile data))))))

;; ---------------------------------------------------------------------------
;; atuin

(deftest the-remote-playbook-omits-atuin-when-no-username-is-named
  (testing "gated in the template, so a project that names no account renders a
           playbook that does not mention atuin at all"
    (let [rendered (render-remote-playbook {})]
      (is (not (str/includes? rendered "atuin"))))))

(deftest the-atuin-secrets-never-reach-the-rendered-playbook
  (testing "colors.yml is committed and the encryption key decrypts every
           machine's history, so only the username comes from desired state —
           the rest is read from walter's own environment at run time"
    (let [rendered (render-remote-playbook {:nix-packages ["atuin"]
                                            :atuin-username "someone"})]
      (is (str/includes? rendered "-u someone"))
      (is (str/includes? rendered "lookup('env', 'COLORS_PAR_ATUIN_PASSWORD')"))
      (is (str/includes? rendered "lookup('env', 'COLORS_PAR_ATUIN_KEY')"))
      (testing "no key named atuin-password or atuin-key is interpolated at all"
        (is (not (str/includes? rendered "atuin-password")))
        (is (not (str/includes? rendered "atuin-key")))))))

(deftest a-failed-atuin-login-cannot-print-the-key
  (testing "Ansible echoes a failed command's arguments, and a failed login is
           precisely when the key would otherwise appear in the output"
    (let [rendered (render-remote-playbook {:nix-packages ["atuin"]
                                            :atuin-username "someone"})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered "no_log: true"))
      (testing "the credentials are checked before they are used, so an unset
               variable fails with walter's reason rather than atuin's"
        (is (< (at "Refuse to log in to atuin") (at "atuin login"))))
      (testing "atuin keeps its session in meta.db, so there is no session file
               to guard on — an earlier version pointed `creates` at one that
               never exists and the login silently re-ran on every converge"
        (is (not (str/includes? rendered ".local/share/atuin/session")))
        (is (str/includes? rendered
                           "creates: \"{{ ansible_env.HOME }}/.local/state/walter/atuin-someone\"")
            "the stamp carries the username, so a different account converges")
        (is (< (at "atuin login") (at "Record that atuin is logged in"))
            "stamped after the login, so a failure retries on the next create")))))

(deftest the-history-sync-runs-on-every-create
  (testing "pulling history is the point of the account and a second run undoes
           nothing, so unlike the login it is not stamped"
    (let [rendered (render-remote-playbook {:nix-packages ["atuin"]
                                            :atuin-username "someone"})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered "cmd: atuin sync"))
      (is (< (at "atuin login") (at "cmd: atuin sync"))
          "syncing before the login would run against the wrong account")
      (testing "it always transfers something, and a step that is always changed
               tells you nothing"
        (is (str/includes? rendered "changed_when: false"))))))

(deftest the-state-directory-follows-the-steps-that-stamp
  (testing "hoisted out of the blocks that use it, but gated on their union — a
           machine that asked for neither should not carry an empty directory"
    (is (not (str/includes? (render-remote-playbook {}) ".local/state/walter")))
    (is (str/includes? (render-remote-playbook {:nix-packages ["atuin"]
                                                :atuin-username "someone"})
                       "Ensure walter's state directory exists"))
    (is (str/includes? (render-remote-playbook {:nix-packages ["babashka"]
                                                :dotfiles-repo "git@github.com:me/d.git"})
                       "Ensure walter's state directory exists"))
    (testing "and it is created before anything writes a stamp into it"
      (let [rendered (render-remote-playbook {:nix-packages ["atuin" "babashka"]
                                              :dotfiles-repo "git@github.com:me/d.git"
                                              :atuin-username "someone"})
            at #(str/index-of rendered %)]
        (is (= 1 (count (re-seq #"Ensure walter's state directory exists" rendered)))
            "one task, not one per feature")
        (is (< (at "Ensure walter's state directory")
               (at "Record that the dotfiles profile is installed")))
        (is (< (at "Ensure walter's state directory")
               (at "Record that atuin is logged in")))))))

(deftest atuin-logs-in-after-the-dotfiles-that-configure-it
  (testing "a dotfiles profile is what puts ~/.config/atuin/config.toml there,
           and that file can name a self-hosted sync_address — logging in first
           would authenticate against the wrong server"
    (let [rendered (render-remote-playbook {:nix-packages ["atuin" "babashka"]
                                            :dotfiles-repo "git@github.com:me/dotfiles.git"
                                            :atuin-username "someone"})
          at #(str/index-of rendered %)]
      (is (< (at "Clone the dotfiles") (at "atuin login"))))))

(deftest asdf-tools-render-as-one-looped-task-each
  (testing "a JSON array is a valid YAML flow sequence, so the playbook keeps one
           task with an Ansible loop rather than N generated ones"
    (let [rendered (render-remote-playbook
                    {:nix-packages ["asdf-vm"]
                     :asdf-tools [{:name "nodejs" :version "24.18.1"
                                   :plugin "https://github.com/asdf-vm/asdf-nodejs.git"}]})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered "\"name\":\"nodejs\""))
      (is (str/includes? rendered "\"version\":\"24.18.1\""))
      (is (str/includes? rendered "cmd: asdf set --home {{ item.name }} {{ item.version }}")
          "0.16 rewrote asdf in Go and removed `global` outright — it answers
           \"invalid command provided\", so the two are not interchangeable")
      (is (< (at "Add the asdf plugins") (at "Install the asdf versions") (at "Set the home asdf")))
      (testing "0.20 writes its no-op messages to stderr, so checking stdout
               alone reported a change on every converged run"
        (is (str/includes? rendered "walter_asdf_plugins.stdout + walter_asdf_plugins.stderr"))
        (is (str/includes? rendered "walter_asdf_installs.stdout + walter_asdf_installs.stderr")))))
  (testing "the plugin URL is optional — asdf resolves a bare name itself"
    (let [rendered (render-remote-playbook
                    {:nix-packages ["asdf-vm"]
                     :asdf-tools [{:name "nodejs" :version "24.18.1"}]})]
      (is (str/includes? rendered "item.plugin | default('', true)")))))

(deftest corepack-is-reshimmed-or-it-is-invisible
  (testing "corepack writes into Node's own bin directory, which asdf does not
           expose until told to look again — without the reshim `corepack enable`
           reports success and the binary stays command-not-found"
    (let [rendered (render-remote-playbook
                    {:nix-packages ["asdf-vm"]
                     :asdf-tools [{:name "nodejs" :version "24.18.1"}]
                     :corepack-packages ["pnpm"]})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered "cmd: corepack enable {{ item }}"))
      (is (< (at "corepack enable") (at "asdf reshim nodejs"))))))

(deftest no-asdf-tools-means-no-asdf-tasks
  (testing "matched on task names, not the bare word — the nix-packages comment
           mentions asdf-vm to explain why a `creates` guard cannot be written"
    (let [rendered (render-remote-playbook {:nix-packages ["ripgrep"]})]
      (is (not (str/includes? rendered "- name: Add the asdf plugins")))
      (is (not (str/includes? rendered "- name: Install the asdf versions")))
      (is (not (str/includes? rendered "- name: Enable the corepack package managers"))))))

(deftest the-loop-list-is-json-that-survived-selmer
  (testing "Selmer escapes by default and JSON is all double quotes, so without
           |safe every loop renders as &quot; and Ansible sees no list at all"
    (let [rendered (render-remote-playbook
                    {:nix-packages ["asdf-vm"]
                     :asdf-tools [{:name "nodejs" :version "24.18.1"}]
                     :corepack-packages ["pnpm"]})]
      (is (not (str/includes? rendered "&quot;")))
      (is (str/includes? rendered "loop: [{\"name\":\"nodejs\""))
      (is (str/includes? rendered "loop: [\"pnpm\"]")))))
