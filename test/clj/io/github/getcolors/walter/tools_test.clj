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

(deftest the-remote-playbook-configures-unprivileged-cloudflared
  (testing "cloudflared can use its optional ICMP proxy and QUIC socket buffers
           without being run as root"
    (let [rendered (render-remote-playbook {})]
      (is (str/includes? rendered
                         "net.ipv4.ping_group_range = {{ ansible_user_gid }} {{ ansible_user_gid }}")
          "only the login user's primary group receives ping-socket access")
      (is (str/includes? rendered "net.core.rmem_max = 7500000"))
      (is (str/includes? rendered "net.core.wmem_max = 7500000"))
      (is (str/includes? rendered
                         "cmd: sysctl --load /etc/sysctl.d/90-cloudflared.conf")
          "create also repairs runtime drift instead of waiting for a reboot"))))

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
                         (str "cmd: nix profile add --impure "
                              "github:NixOS/nixpkgs/nixpkgs-unstable#asdf-vm "
                              "github:NixOS/nixpkgs/nixpkgs-unstable#ripgrep "
                              "github:NixOS/nixpkgs/nixpkgs-unstable#fd "
                              "github:NixOS/nixpkgs/nixpkgs-unstable#fish"))))))

(deftest unfree-packages-are-installable-and-the-two-flags-travel-together
  (testing "some of what a development machine wants is unfree — claude-code is,
           where codex and pi-coding-agent are not — and one shared invocation
           is what makes nix resolve the set together, so the gate is opened for
           the whole list rather than by splitting it in two"
    (let [rendered (render-remote-playbook {:nix-packages ["claude-code" "codex"]})]
      (is (str/includes? rendered "NIXPKGS_ALLOW_UNFREE: \"1\""))
      (is (str/includes? rendered "nix profile add --impure")
          "flake evaluation is pure by default and does not read the
           environment, so without --impure the variable is silently ignored
           and the add still fails on the licence")
      (testing "the flakeref is untouched, so --impure changes what evaluation
               may read and not what is fetched"
        (is (str/includes? rendered
                           "github:NixOS/nixpkgs/nixpkgs-unstable#claude-code"))))))

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
      (is (str/includes? rendered "nixpkgs-unstable#emacs")
          "from the same nixpkgs ref as everything else here")
      (is (str/includes? rendered "repo: \"git@github.com:me/emacs.d.git\""))
      (is (str/includes? rendered "dest: \"~/.config/neoemacs\""))
      (is (str/includes? rendered "update: false")
          "a development machine's working copy is not a deployment")
      (is (< (at "install.determinate.systems") (at "#emacs") (at "repo:"))))))

(deftest the-clone-never-writes-a-key-to-the-machine
  (testing "ansible.cfg forwards the agent, so the clone speaks SSH and can push
           back — accept_hostkey covers github.com being unknown on a fresh host"
    (let [rendered (render-remote-playbook {:emacs-config-repo "git@github.com:me/emacs.d.git"})]
      (is (str/includes? rendered "accept_hostkey: true"))
      (is (str/includes? rendered "dest: \"~/.config/emacs\"")))))

;; ---------------------------------------------------------------------------
;; the dotfiles

(deftest the-remote-playbook-omits-dotfiles-when-no-checkout-is-named
  (testing "gated in the template rather than at runtime"
    (let [rendered (render-remote-playbook {})]
      (is (not (str/includes? rendered "dotfiles")))
      (is (not (str/includes? rendered ".local/state/walter"))))))

(deftest the-dotfiles-package-runs-its-existing-launcher
  (testing "the checkout owns colors.yml and the package interface"
    (let [rendered (render-remote-playbook
                    {:nix-packages ["babashka"]
                     :clone-orgs ["getcolors"]
                     :dotfiles-checkout "~/code/getcolors/dotfiles"})]
      (is (str/includes? rendered "cmd: ./green create"))
      (is (str/includes? rendered "chdir: \"~/code/getcolors/dotfiles\""))
      (is (str/includes? rendered
                         "COLORS_PAR_DOTFILES_PREVENT_OVERWRITE: \"false\""))
      (is (not (str/includes? rendered "COLORS_PAR_PROFILE:"))))))

(deftest the-dotfiles-launcher-runs-after-its-org-checkout
  (testing "a fresh machine has no checkout until clone-orgs creates it"
    (let [rendered (render-remote-playbook {:nix-packages ["babashka"]
                                            :clone-orgs ["getcolors"]
                                            :dotfiles-checkout "~/code/getcolors/dotfiles"})
          at #(str/index-of rendered %)]
      (is (< (at "Clone the organisations' repositories")
             (at "Apply the dotfiles checkout once"))))))

(deftest the-dotfiles-create-is-stamped
  (testing "later creates preserve edits made in $HOME"
    (let [rendered (render-remote-playbook {:nix-packages ["babashka"]
                                            :dotfiles-checkout "~/code/getcolors/dotfiles"})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered
                         "creates: \"{{ ansible_env.HOME }}/.local/state/walter/dotfiles\""))
      (is (< (at "Ensure walter's state directory")
             (at "cmd: ./green create")
             (at "Record that the dotfiles checkout was applied"))
          "a failed launcher run leaves no stamp, so the next create retries"))))

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
                                                :dotfiles-checkout "~/code/getcolors/dotfiles"})
                       "Ensure walter's state directory exists"))
    (testing "and it is created before anything writes a stamp into it"
      (let [rendered (render-remote-playbook {:nix-packages ["atuin" "babashka"]
                                              :dotfiles-checkout "~/code/getcolors/dotfiles"
                                              :atuin-username "someone"})
            at #(str/index-of rendered %)]
        (is (= 1 (count (re-seq #"Ensure walter's state directory exists" rendered)))
            "one task, not one per feature")
        (is (< (at "Ensure walter's state directory")
               (at "Record that the dotfiles checkout was applied")))
        (is (< (at "Ensure walter's state directory")
               (at "Record that atuin is logged in")))))))

(deftest atuin-logs-in-after-the-dotfiles-that-configure-it
  (testing "a dotfiles profile is what puts ~/.config/atuin/config.toml there,
           and that file can name a self-hosted sync_address — logging in first
           would authenticate against the wrong server"
    (let [rendered (render-remote-playbook {:nix-packages ["atuin" "babashka"]
                                            :dotfiles-checkout "~/code/getcolors/dotfiles"
                                            :atuin-username "someone"})
          at #(str/index-of rendered %)]
      (is (< (at "Apply the dotfiles checkout once") (at "atuin login"))))))

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

;; ---------------------------------------------------------------------------
;; emacs packages

(defn- render-emacs-packages
  "Run the emacs-packages step as a build, and hand back the playbook it wrote —
  or nil when the step declined to render a stage at all."
  [opts]
  (let [dir (str (fs/create-temp-dir))
        merged (merge {:profile "p"
                       :workdir dir
                       :provider-compute "oci"
                       :green/event :build}
                      opts)]
    (tools/emacs-packages-step merged)
    (let [f (str (tools/tool-dir merged tools/emacs-packages-tool) "/main.yml")]
      (when (fs/exists? f) (slurp f)))))

(deftest no-emacs-config-means-no-stage-at-all
  (testing "gated in Clojure rather than in the template, unlike every other
           optional block — those are tasks inside a play that runs regardless,
           where this is the whole stage, and a project with no Emacs should
           render no directory rather than a playbook full of absence"
    (is (nil? (render-emacs-packages {})))
    (is (nil? (render-emacs-packages {:emacs-config-repo "   "}))))
  (testing "and a delete renders nothing either — the packages go with the boot
           volume, so there is no work to undo"
    (is (nil? (render-emacs-packages {:emacs-config-repo "git@github.com:me/e.git"
                                      :green/event :delete})))))

(deftest the-batch-load-names-init-el-explicitly
  (testing "--batch implies -q: an --init-directory without -l sets
           user-emacs-directory and leaves user-init-file nil, so Emacs exits 0
           in under a tenth of a second having installed nothing — a silent
           no-op indistinguishable from an already-warm cache"
    (let [rendered (render-emacs-packages
                    {:emacs-config-repo "git@github.com:me/e.git"
                     :emacs-config-dest "~/.config/neoemacs"})]
      (is (str/includes? rendered "--batch"))
      (is (str/includes? rendered "-l {{ (walter_emacs_dest ~ '/init.el') | quote }}")
          "the -l is what actually loads the configuration")
      (testing "walter carries no elisp of its own — loading init.el is the
               whole mechanism, and the package list lives in the configuration
               where it belongs. Asserted on `--eval` rather than on the word
               `use-package`, which appears in the commentary explaining why."
        (is (not (str/includes? rendered "--eval")))
        (is (not (str/includes? rendered "package-install-selected")))))))

(deftest a-tilde-destination-is-resolved-against-the-machines-home
  (testing "a quoted ~ does not expand in bash, so the naive fix silently
           creates a directory literally named ~"
    (let [rendered (render-emacs-packages
                    {:emacs-config-repo "git@github.com:me/e.git"
                     :emacs-config-dest "~/.config/neoemacs"})]
      (is (str/includes? rendered "regex_replace('^~', ansible_env.HOME)"))
      (is (str/includes? rendered "'~/.config/neoemacs'"))
      (testing "and every use of it is quoted, so a path with a space in it
               does not split into two arguments"
        (is (str/includes? rendered "{{ walter_emacs_dest | quote }}"))))))

(deftest the-fetch-is-started-and-not-waited-for
  (testing "nothing downstream reads what this produces — it is a cache being
           warmed — so waiting would buy only the ability to fail a create on an
           ELPA outage, which the remote play already refused"
    (let [rendered (render-emacs-packages
                    {:emacs-config-repo "git@github.com:me/e.git"})]
      (is (str/includes? rendered "poll: 0")
          "fire-and-forget: Ansible's wrapper daemonizes the job so it outlives
           the play, the SSH connection and the create")
      (is (str/includes? rendered "async: 3600")
          "a ceiling on the job, not a wait")
      (testing "started is not installed, so this is never reported as a change"
        (is (str/includes? rendered "changed_when: false")))
      (testing "a job nobody waits for is a job nobody can debug without a log"
        (is (str/includes? rendered ".local/state/walter/emacs-packages.log")))
      (testing "the exit status is saved before the timestamp is taken. bash
               expands `$(date)` first and it always succeeds, so reading `$?`
               after it would log rc=0 for every failed fetch — and on a job
               nobody waits for, that log is the only diagnostic there is."
        (is (str/includes? rendered "rc=$?"))
        (is (str/includes? rendered "rc=$rc"))
        (is (not (str/includes? rendered "rc=$?\"")))
        (is (< (str/index-of rendered "rc=$?")
               (str/index-of rendered "rc=$rc")))))))

;; ---------------------------------------------------------------------------
;; agent credentials

(deftest the-remote-playbook-omits-agent-credentials-when-none-are-named
  (testing "gated in the template, so a project that names no agent renders a
           playbook that does not mention their credential files at all"
    (let [rendered (render-remote-playbook {})]
      (is (not (str/includes? rendered "Seed the agent credentials")))
      (is (not (str/includes? rendered "Complete Claude onboarding")))
      (is (not (str/includes? rendered ".credentials.json")))
      (is (not (str/includes? rendered "walter_agent_credentials"))))))

(deftest agents-resolve-to-one-file-each-not-to-their-directory
  (testing "~/.claude, ~/.codex and ~/.pi are overwhelmingly session
           transcripts and caches — hundreds of megabytes against a few
           kilobytes of tokens — so walter names the credential file and never
           the directory holding it"
    (is (= [{:agent "claude" :path ".claude/.credentials.json"}
            {:agent "codex" :path ".codex/auth.json"}
            {:agent "pi" :path ".pi/agent/auth.json"}]
           (tools/seed-agent-credentials
            {:seed-agent-credentials ["claude" "codex" "pi"]})))
    (testing "an unknown name drops out here and is refused by validate.clj"
      (is (= [{:agent "codex" :path ".codex/auth.json"}]
             (tools/seed-agent-credentials
              {:seed-agent-credentials ["codex" "cursor"]}))))
    (testing "a COLORS_PAR_* overlay arrives as one string, like nix-packages"
      (is (= 2 (count (tools/seed-agent-credentials
                       {:seed-agent-credentials "claude pi"})))))
    (testing "named twice is copied once"
      (is (= 1 (count (tools/seed-agent-credentials
                       {:seed-agent-credentials ["pi" "pi"]})))))))

(deftest the-two-homes-are-resolved-on-different-sides
  (testing "the source home is the operator's and the destination home is the
           login user's, so one relative path describes both ends of the copy
           and they cannot drift apart"
    (let [rendered (render-remote-playbook {:seed-agent-credentials ["claude"]})]
      (is (str/includes? rendered
                         "src: \"{{ lookup('env', 'HOME') }}/{{ item.item.path }}\"")
          "lookup runs on the controller, whichever host the task targets")
      (is (str/includes? rendered
                         "dest: \"{{ ansible_env.HOME }}/{{ item.item.path }}\"")
          "ansible_env is the machine's, from the facts the play gathers")
      (is (str/includes? rendered "delegate_to: localhost")
          "the source is stat'd on the workstation, not on the machine"))))

(deftest no-credential-ever-reaches-a-rendered-file
  (testing "the files are read from the controller at play time, so `build`
           stays credential-free and nothing under .colors/ holds a token — the
           goldens are what hold this still"
    (let [rendered (render-remote-playbook {:seed-agent-credentials ["claude" "codex" "pi"]})]
      (is (str/includes? rendered "loop: [{\"agent\":\"claude\""))
      (testing "only names and relative paths are interpolated"
        (is (not (str/includes? rendered "accessToken")))
        (is (not (str/includes? rendered "refresh_token")))
        (is (not (str/includes? rendered "COLORS_PAR"))))
      (testing "ansible-playbook --diff prints a copy module's file content, and
               that content is a bearer token"
        (is (str/includes? rendered "no_log: true"))))))

(deftest claude-seeding-completes-onboarding-without-copying-machine-state
  (testing "Claude Code recognizes the bearer tokens for `auth status` but
           refuses an interactive start until ~/.claude.json says onboarding
           completed; walter adds only that missing bit rather than copying the
           workstation's machine-local project and usage state"
    (let [rendered (render-remote-playbook {:seed-agent-credentials ["claude"]})]
      (is (str/includes? rendered "Complete Claude onboarding for the seeded login"))
      (is (str/includes? rendered
                         "if \"hasCompletedOnboarding\" not in data:"))
      (is (str/includes? rendered
                         "data[\"hasCompletedOnboarding\"] = True"))
      (is (str/includes? rendered "item.item.agent == \"claude\""))
      (is (str/includes? rendered "item.stat.exists")
          "a missing controller credential must leave Claude logged out")
      (is (str/includes? rendered
                         "changed_when: walter_claude_onboarding.stdout == \"added\""))
      (is (str/includes? rendered "os.replace(temporary, path)")
          "the merge writes atomically")
      (is (str/includes? rendered "os.chmod(temporary, 0o600)")))
    (testing "an existing true or false is preserved by the missing-key guard"
      (is (not (str/includes?
                (render-remote-playbook {:seed-agent-credentials ["codex"]})
                "Complete Claude onboarding"))
          "other agent seeds do not render Claude-specific machine state"))))

(deftest seeding-never-overwrites-a-login-the-machine-already-has
  (testing "these are OAuth refresh tokens the CLI rotates in place, so two
           overwrites have to be prevented: one the machine refreshed for
           itself, and one made on the machine directly"
    (let [rendered (render-remote-playbook {:seed-agent-credentials ["claude"]})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered "force: false")
          "the credential file is its own evidence")
      (testing "and so, unlike the atuin login and the dotfiles install, this
               needs no ~/.local/state/walter stamp — those need one because
               neither leaves a file to watch, where a stamp here would answer
               whether walter once wrote a login rather than whether the machine
               has one, and clobber a direct login the first time it ran"
        (is (not (str/includes? rendered ".local/state/walter/agent"))))
      (testing "0600 on the file and 0700 on the directory holding it"
        (is (str/includes? rendered "mode: \"0600\""))
        (is (< (at "Ensure the agent configuration directories exist")
               (at "- name: Seed the agent credentials"))
            "the destination has to exist before the copy names it"))
      (testing "a missing source is reported and skipped, so a create from CI or
               a colleague's laptop still succeeds with those CLIs logged out"
        (is (str/includes? rendered "rejectattr('stat.exists')"))
        (is (str/includes? rendered "selectattr('stat.exists')"))
        (is (< (at "Report the agent credentials this workstation cannot supply")
               (at "- name: Seed the agent credentials")))))))

(deftest credentials-are-seeded-before-dotfiles-and-atuin
  (testing "getcolors/dotfiles excludes credential files, so its later create
           cannot overwrite these; atuin stays last for the reason given there"
    (let [rendered (render-remote-playbook {:nix-packages ["babashka" "atuin"]
                                            :dotfiles-checkout "~/code/getcolors/dotfiles"
                                            :seed-agent-credentials ["claude"]
                                            :atuin-username "someone"})
          at #(str/index-of rendered %)]
      (is (< (at "- name: Seed the agent credentials")
             (at "Apply the dotfiles checkout once")))
      (is (< (at "Apply the dotfiles checkout once")
             (at "cmd: atuin sync"))))))

;; ---------------------------------------------------------------------------
;; organisation checkouts

(deftest the-remote-playbook-omits-org-clones-when-none-are-named
  (testing "gated in the template, so a project that names no organisation
           renders a playbook that does not mention GitHub's API at all"
    (let [rendered (render-remote-playbook {})]
      (is (not (str/includes? rendered "api.github.com")))
      (is (not (str/includes? rendered "walter_org_repos"))))))

(deftest only-the-organisation-is-desired-state
  (testing "the repository list is read on the machine at create time, so one
           added upstream arrives on the next create with nothing here changed —
           which is the entire reason this key names an org rather than repos"
    (is (= ["getcolors"] (tools/clone-orgs {:clone-orgs ["getcolors"]})))
    (testing "a COLORS_PAR_* overlay arrives as one string, like nix-packages"
      (is (= ["getcolors" "amiorin"] (tools/clone-orgs {:clone-orgs "getcolors amiorin"}))))
    (testing "named twice is cloned once — the two would share a path"
      (is (= ["getcolors"] (tools/clone-orgs {:clone-orgs ["getcolors" "getcolors"]}))))
    (testing "and it renders as a JSON flow sequence, like the other list keys"
      (is (= "[\"getcolors\"]" (:clone-orgs-json (tools/data-fn {:profile "p"
                                                                 :clone-orgs ["getcolors"]}))))
      (is (nil? (:clone-orgs-json (tools/data-fn {:profile "p"})))))))

(deftest the-checkout-layout-is-code-org-repo
  (testing "one flat loop carries both halves of the path, so the organisation
           asked for and the repository answered with cannot drift apart"
    (let [rendered (render-remote-playbook {:clone-orgs ["getcolors"]})]
      (is (str/includes? rendered "loop: [\"getcolors\"]"))
      (is (str/includes? rendered
                         "dest: \"{{ ansible_env.HOME }}/code/{{ item.0.item }}/{{ item.1.name }}\""))
      (is (str/includes? rendered "subelements('json')")))))

(deftest the-org-listing-needs-no-credential
  (testing "these repositories are public, and a token would be a credential
           every create then needs — so build stays credential-free here too"
    (let [rendered (render-remote-playbook {:clone-orgs ["getcolors"]})]
      (is (str/includes? rendered "https://api.github.com/orgs/{{ item }}/repos"))
      (is (not (str/includes? rendered "Authorization")))
      (is (not (str/includes? rendered "GITHUB_TOKEN")))
      (is (str/includes? rendered "changed_when: false")
          "a GET is `ok`, not `changed`, however many repositories it names"))))

(deftest forks-and-archived-repositories-are-not-working-copies
  (testing "forks are dropped at the server, where the API has a filter, and
           archived ones at the clone, where it does not"
    (let [rendered (render-remote-playbook {:clone-orgs ["getcolors"]})]
      (is (str/includes? rendered "type=sources"))
      (is (str/includes? rendered "when: not item.1.archived")
          "`when` rather than a loop filter, so the run says what it passed over"))))

(deftest a-partial-page-fails-rather-than-cloning-quietly
  (testing "per_page tops out at 100 and this does not follow the Link header,
           so an organisation past that boundary would clone a hundred
           repositories and report success"
    (let [rendered (render-remote-playbook {:clone-orgs ["getcolors"]})
          at #(str/index-of rendered %)]
      (is (str/includes? rendered "per_page=100"))
      (is (str/includes? rendered "that: item.json | length < 100"))
      (is (< (at "Refuse to clone a partial page")
             (at "Clone the organisations' repositories"))
          "the assertion runs before anything is cloned"))))

(deftest the-org-clones-never-write-a-key-to-the-machine
  (testing "git@github.com: rather than https://, so they ride the forwarded
           agent and can push back — and update: false, so a later create does
           not discard edits made on the machine"
    (let [rendered (render-remote-playbook {:clone-orgs ["getcolors"]})]
      (is (str/includes? rendered "repo: \"git@github.com:{{ item.0.item }}/{{ item.1.name }}.git\""))
      (is (not (str/includes? rendered "https://github.com/")))
      (is (str/includes? rendered "update: false"))
      (is (str/includes? rendered "accept_hostkey: true")))))

(deftest the-org-clones-run-after-credentials-and-before-dotfiles
  (testing "credentials are cheap, the checkout must exist before its launcher
           runs, and atuin remains last"
    (let [rendered (render-remote-playbook {:nix-packages ["babashka" "atuin"]
                                            :dotfiles-checkout "~/code/getcolors/dotfiles"
                                            :seed-agent-credentials ["claude"]
                                            :clone-orgs ["getcolors"]
                                            :atuin-username "someone"})
          at #(str/index-of rendered %)]
      (is (< (at "- name: Seed the agent credentials")
             (at "Clone the organisations' repositories")
             (at "Apply the dotfiles checkout once")
             (at "cmd: atuin sync"))))))
