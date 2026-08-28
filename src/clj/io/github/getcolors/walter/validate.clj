(ns io.github.getcolors.walter.validate
  "Walter's desired-state rules, driven by ONCE's provider registry.

  The registry is consumed as data rather than reimplemented, then its compute
  slot is filtered to Walter's deliberately supported providers. It remains the
  single place recording the keys and credentials each selected template needs,
  while a new ONCE provider cannot silently become a Walter feature at pin-bump
  time. Walter drives two of ONCE's four slots: there is no SMTP or DNS here.

  Nothing upstream promises this registry's shape. `scripts/golden.sh` is what
  actually catches a change to it — see plans/0001."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]
   [io.github.getcolors.once.ssh :as once-ssh]
   [io.github.getcolors.once.validate :as once-validate]))

(def compute-providers
  "The ONCE compute templates Walter deliberately supports. New ONCE providers
  do not become Walter features merely because a dependency pin moves."
  #{"oci" "hcloud" "digitalocean" "vultr" "yandex" "no-infra"})

(def providers
  "ONCE's provider registry, filtered to Walter's advertised compute surface."
  (update once-validate/providers :provider-compute select-keys compute-providers))

(def slots
  "The provider slots walter fills. ONCE has four; walter provisions a machine
  and stores state, and does not send mail or manage DNS."
  [:provider-compute :provider-backend])

(def stoppable
  "Compute providers walter can power cycle.

  Membership is a fact about the provider's API, not about its OpenTofu
  template — `stop` and `start` never reach OpenTofu. Every other provider
  answers \"no\" and the power verbs become a *reported* no-op rather than a
  silent success: a cost-saving command that quietly does nothing is one you
  discover on the invoice.

  This lives in walter, not in ONCE. ONCE has no power verb, so a `:stoppable`
  key there would be carried purely for a downstream consumer — and because it
  never reaches a generated file it is exactly the blind spot ONCE's own rules
  call out, needing a three-colour commit and a parity fixture to add honestly."
  #{"oci" "vultr"})

(defn stoppable?
  "Whether the selected compute provider supports `stop` and `start`."
  [opts]
  (contains? stoppable (str (:provider-compute opts))))

(defn keygen?
  "Whether walter generates and manages the machine-access keypair.

  The SSH Keypair Standard (workspace standards/ssh-keypair.md) semantics,
  delegated to ONCE's implementation: an absent machine-key value in desired
  state selects keygen mode, a present one is opt-out, and there is no flag.
  The old `compute-keygen: true` opt-in is superseded — with generation the
  default, the switch is the explicit key itself. ONCE's templates now render
  the keygen branches (the provider key resource named after the profile, the
  `private_key` connection), so walter's old `ssh-key.tf` merge trick and its
  per-run ssh-agent are gone with it."
  [opts]
  (once-ssh/keygen? opts))

(def agent-credential-paths
  "Agent CLIs walter can carry a subscription login for, and the one file each
  keeps it in — relative to $HOME, because both sides read the same path under
  whichever home they find themselves in and the two homes differ: the
  controller's is the operator's, the machine's is the login user's.

  One file per agent, never the directory holding it. All three of ~/.claude,
  ~/.codex and ~/.pi are overwhelmingly session transcripts and caches — a few
  hundred megabytes against a few kilobytes of tokens — and copying those to a
  development machine would put every conversation held on the workstation onto
  a host in a shared subnet, for no benefit.

  A map here rather than paths in colors.yml, and that is the reason this key
  names agents rather than files: a `copy these local paths to the machine` key
  would be the same feature with nothing stopping it pointing at ~/.ssh.

  Walter seeds these and does nothing else with them. It never reads a value,
  and no rule in this namespace can — they are secrets, and `build` renders from
  desired state alone."
  {"claude" ".claude/.credentials.json"
   "codex" ".codex/auth.json"
   "pi" ".pi/agent/auth.json"})

(defn- entry
  [opts slot]
  (get-in providers [slot (get opts slot)]))

(defn tofu-env
  "Flat key -> the environment variable OpenTofu reads it from, for the provider
  selected in `slot`."
  [opts slot]
  (:tofu-env (entry opts slot) {}))

(defn- slot-keys
  [opts field]
  (mapcat #(get (entry opts %) field []) slots))

(defn placeholder?
  "Whether a value is missing in the ways a hand-edited file produces: absent,
  blank, or still carrying the scaffold's REPLACE_ME."
  [x]
  (or (nil? x)
      (and (string? x)
           (or (str/blank? x)
               (= "REPLACE_ME" (str/upper-case x))))))

(defn- missing-keys
  [opts ks]
  (keep (fn [k] (when (placeholder? (get opts k)) k)) ks))

(def ^:private gated-keys
  "Walter's optional keys that a template interpolates behind an
  `<% if key|not-empty %>` gate.

  Deliberately a list rather than a scan of everything unrequired, which is what
  this started as and got wrong. A colors.yml carries REPLACE_ME for every
  provider it is *not* using — walter's own example ships `s3-region` and the
  whole yandex block that way — and those are genuinely harmless, because the
  template that would read them is never rendered.

  These are the ones where a placeholder is not harmless. Adding a gated feature
  means adding its key here, which is the same discipline its own rule below
  already needs."
  [:nix-packages :login-shell
   :emacs-config-repo :emacs-config-dest
   :dotfiles-checkout
   :atuin-username
   :seed-agent-credentials
   :clone-orgs
   :github-account :git-email
   :oci-image-id])

(defn- leftover-placeholders
  "Gated keys still carrying the scaffold's REPLACE_ME.

  A missing optional key is genuinely absent and its block does not render. One
  left as REPLACE_ME is *present*, so the gate fires and the placeholder reaches
  the generated file verbatim — `repo: \"REPLACE_ME\"` — which builds cleanly and
  then fails on the machine, during a create, against live infrastructure.

  The fix is to delete the line, not to invent a value, and the message says so."
  [opts]
  (for [k gated-keys
        :when (and (contains? opts k)
                   (placeholder? (get opts k))
                   (some? (get opts k))
                   (not (str/blank? (str (get opts k)))))]
    (str k " still says REPLACE_ME — fill it in, or delete the key. "
         "An optional key is not treated as absent while it holds a "
         "placeholder: it renders into the generated files verbatim.")))

(def profile-par
  "The one `COLORS_PAR_*` variable walter refuses to honour."
  (green-cli/par-name :profile))

(defn env-errors
  "Errors that depend on the environment rather than on the file.

  `profile` names the work directory, the OpenTofu state keys and the managed
  ssh alias, and the project it identifies is the directory holding colors.yml.
  An override from the environment can therefore only point walter at another
  project's state — in this stack, plausibly at one running a production website
  from the same bucket, compartment and subnet.

  There is no legitimate use for it, so the variable is rejected outright rather
  than checked against an expected value: `green.cli/read-pars` has already
  overwritten the file's value by the time any step runs, so walter cannot see
  what it was supposed to be."
  [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set. Walter takes its profile from colors.yml only — "
          "run from the project directory rather than overriding it.")]))

(def ^:private instance-id-re #"^ocid1\.instance\.[A-Za-z0-9._-]+$")
(def ^:private vultr-instance-id-re
  #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

(def ^:private github-login-re
  "A GitHub account name: alphanumerics and interior hyphens, 39 characters at
  most. Deliberately strict about what it excludes rather than clever about what
  it allows — the realistic mistakes are pasting `getcolors/walter` or a full
  https://github.com/getcolors URL into a key that wants the org alone, and both
  carry a character this rejects."
  #"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")

(def ^:private email-re
  "Plausibility, not RFC 5322: something before an @, something after it,
  and a dot in the domain. This value only reaches `git config user.email`,
  where the realistic mistake is pasting a username or leaving a REPLACE_ME —
  both of which this rejects — not crafting an exotic-but-legal address."
  #"^[^@\s]+@[^@\s]+\.[^@\s]+$")

(defn clone-org-names
  "The `clone-orgs` entries as plain strings, with the same flat-key string
  tolerance every list key here has."
  [opts]
  (let [orgs (:clone-orgs opts)]
    (->> (if (sequential? orgs) orgs (str/split (str orgs) #"\s+"))
         (map (comp str/trim str))
         (remove str/blank?))))

(def ^:private unix-login-re
  "A seat login name: lowercase letters, digits and interior hyphens, starting
  with a letter, 32 characters at most. Deliberately stricter than useradd —
  the realistic mistakes are an email address, a capitalised display name, or
  a `user@host` paste, and all three carry a character this rejects."
  #"^[a-z](?:[a-z0-9-]{0,30}[a-z0-9])?$")

(def reserved-logins
  "Logins `users` may not name. `ubuntu` is the primary login walter already
  owns — naming it again would double-provision one home — and `root` stays
  closed: the Vultr bootstrap disables its SSH, and a root seat would be the
  isolation feature deleting itself."
  #{"root" "ubuntu"})

(defn user-names
  "The `users` entries as plain strings — seat logins provisioned beside the
  primary one — with the same flat-key string tolerance every list key here
  has, and deliberately without `distinct`: duplicate detection below needs to
  see what was actually written."
  [opts]
  (let [u (:users opts)]
    (->> (if (sequential? u) u (str/split (str u) #"\s+"))
         (map (comp str/trim str))
         (remove str/blank?))))

(defn state-errors
  "Everything wrong with `opts` that does not depend on credentials, as a vector
  of messages. Empty means the desired state is renderable."
  [opts]
  (vec
   (concat
    ;; The machine-key keys are no longer in the registry's :required — their
    ;; absence selects keygen mode (SSH Keypair Standard), so nothing needs
    ;; waiving here any more.
    (map #(str % " is required")
         (missing-keys opts (concat [:profile :workdir]
                                    (slot-keys opts :required))))
    (leftover-placeholders opts)
    (for [slot slots
          :let [provider (get opts slot)]
          :when (not (contains? (get providers slot) provider))]
      (str "unsupported " slot " " (pr-str provider)))
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    ;; The flag is gone: generation is the default and the explicit key is the
    ;; opt-out (SSH Keypair Standard). A colors.yml still carrying it gets a
    ;; migration message rather than a silent ignore.
    (when (some? (:compute-keygen opts))
      [":compute-keygen is superseded by the SSH Keypair Standard — remove it; generation is the default, and setting the provider's machine key is the opt-out"])
    ;; Walter closes Vultr's root-only bootstrap login. Without the generated
    ;; key's private half there is no way to enter as root and install ubuntu's
    ;; key before that door closes, so opt-out would create a machine nobody
    ;; can enter.
    (when (and (= "vultr" (str (:provider-compute opts)))
               (not (placeholder? (:vultr-ssh-keys opts))))
      [":vultr-ssh-keys is not accepted — walter must hold the private key to bootstrap ubuntu before disabling root SSH; leave it unset and the profile-named keypair is generated"])
    ;; A present :compute-pubkey is Yandex's opt-out; absent selects keygen.
    ;; Either way a value that is present must look like a public key.
    (when-not (or (nil? (:compute-pubkey opts))
                  (placeholder? (:compute-pubkey opts))
                  (str/starts-with? (str (:compute-pubkey opts)) "ssh-"))
      [":compute-pubkey must be an SSH public key"])
    ;; Optional, and the escape hatch that lets stop/start work without reading
    ;; OpenTofu state — so a malformed one has to fail here rather than as an
    ;; opaque CLI error half way through a power cycle.
    (when-not (or (nil? (:oci-instance-id opts))
                  (placeholder? (:oci-instance-id opts))
                  (re-matches instance-id-re (str (:oci-instance-id opts))))
      [":oci-instance-id must be an instance OCID (ocid1.instance....)"])
    (when-not (or (nil? (:vultr-instance-id opts))
                  (placeholder? (:vultr-instance-id opts))
                  (re-matches vultr-instance-id-re (str (:vultr-instance-id opts))))
      [":vultr-instance-id must be a Vultr instance UUID"])
    (when-not (or (nil? (:power-wait-seconds opts))
                  (and (integer? (:power-wait-seconds opts))
                       (pos? (:power-wait-seconds opts))))
      [":power-wait-seconds must be a positive integer"])
    ;; An agent walter has no path for would render a task that looks like it
    ;; seeds something and copies nothing, and the symptom is a CLI asking you
    ;; to log in on a machine you thought was provisioned. Nothing on the
    ;; machine runs these credentials, so unlike :atuin-username there is no
    ;; companion rule about :nix-packages — the playbook only writes a file, and
    ;; a login seeded for a CLI installed by some other means is legitimate.
    (let [known (set (keys agent-credential-paths))
          named (let [a (:seed-agent-credentials opts)]
                  (->> (if (sequential? a) a (str/split (str a) #"\s+"))
                       (map (comp str/trim str))
                       (remove str/blank?)))]
      (for [agent named
            :when (not (contains? known agent))]
        (str ":seed-agent-credentials does not know " (pr-str agent)
             " — walter knows where " (str/join ", " (sort known))
             " keep their credentials, and nothing else")))
    ;; This key names an organisation, not a repository and not a URL. Anything
    ;; else is interpolated straight into an API path and a clone URL, where a
    ;; slash produces a 404 from GitHub half way through a create — legible only
    ;; if you already know the key's shape. There is no rule about `git` in
    ;; :nix-packages to go with it: Ubuntu ships one, and the Emacs and dotfiles
    ;; clones above have always relied on that.
    (for [org (clone-org-names opts)
          :when (not (re-matches github-login-re org))]
      (str ":clone-orgs entry " (pr-str org)
           " is not a GitHub organisation name — this key takes the org alone, "
           "as in \"getcolors\", not a URL and not owner/repo"))
    ;; Seat logins are unix account names walter will pass to useradd and
    ;; interpolate into home paths, ssh aliases and an inventory — so anything
    ;; not a plain login has to fail here, not as a useradd error half way
    ;; through a create. Naming the primary login again, or root, is refused
    ;; rather than deduplicated: both indicate a misunderstanding of what the
    ;; key does, and the message is the correction.
    (let [names (user-names opts)]
      (concat
       (for [u names
             :when (not (re-matches unix-login-re u))]
         (str ":users entry " (pr-str u) " is not a usable seat login — "
              "lowercase letters, digits and interior hyphens, starting with "
              "a letter, 32 characters at most"))
       (for [u names
             :when (contains? reserved-logins u)]
         (str ":users must not name " (pr-str u) " — ubuntu is the primary "
              "login walter already provisions, and root SSH is closed"))
       (let [dups (->> names frequencies (filter #(> (val %) 1)) (map key) sort)]
         (when (seq dups)
           [(str ":users names " (str/join ", " (map pr-str dups))
                 " more than once — one seat is one entry")]))))
    ;; The login shell has to come from the nix profile, and nothing else puts
    ;; anything there — so a shell that is not also in :nix-packages names a
    ;; binary that will not exist. Caught here rather than on the machine,
    ;; because the failure there is a user whose shell does not start.
    (let [shell (not-empty (str/trim (str (:login-shell opts))))
          packages (let [p (:nix-packages opts)]
                     (set (map (comp str/trim str)
                               (if (sequential? p) p (str/split (str p) #"\s+")))))]
      (concat
       (when (and shell (not (contains? packages shell)))
         [(str ":login-shell " (pr-str shell) " is not in :nix-packages — "
               "the shell has to be installed before it can be set")])
       ;; asdf is not special-cased anywhere: it reaches the machine as an entry
       ;; in :nix-packages like everything else, so asking for tools without it
       ;; renders a playbook whose every asdf task fails.
       (when (and (seq (:asdf-tools opts)) (not (contains? packages "asdf-vm")))
         [(str ":asdf-tools needs \"asdf-vm\" in :nix-packages — "
               "nothing else puts asdf on the machine")])
       ;; corepack is part of Node, not a package of its own.
       (when (and (seq (:corepack-packages opts))
                  (not (some #(= "nodejs" (str (:name %))) (:asdf-tools opts))))
         [(str ":corepack-packages needs a \"nodejs\" entry in :asdf-tools — "
               "corepack ships inside Node and cannot be installed separately")])
       ;; The dotfiles checkout's Green launcher is a Babashka script, and
       ;; nothing but :nix-packages puts bb on the machine. Catch the missing
       ;; runtime here rather than half way through a create.
       (when (and (not (placeholder? (:dotfiles-checkout opts)))
                  (not (contains? packages "babashka")))
         [(str ":dotfiles-checkout needs \"babashka\" in :nix-packages — "
               "its Green launcher is a bb script and nothing else puts bb on the machine")])
       ;; Same shape again. The password and key are deliberately not checked
       ;; here — they are COLORS_PAR_* secrets, and `build` renders from desired
       ;; state alone and must stay credential-free, so the remote playbook
       ;; asserts them at create time instead.
       (when (and (not (placeholder? (:atuin-username opts)))
                  (not (contains? packages "atuin")))
         [(str ":atuin-username needs \"atuin\" in :nix-packages — "
               "there is nothing to log in without it")])))
    ;; The GitHub identity is two keys that only mean anything together:
    ;; `github-account` names whose token the create acquires, `git-email`
    ;; completes the commit identity it configures. One without the other is a
    ;; half-configured machine, caught here rather than discovered as an
    ;; anonymous-looking commit or a login against the wrong expectation.
    (let [account (not-empty (str (:github-account opts)))
          email (not-empty (str (:git-email opts)))]
      (concat
       (when (and account (not (re-matches github-login-re account)))
         [(str ":github-account " (pr-str account)
               " is not a GitHub account name — this key takes the login "
               "alone, as in \"getcolors\", not a URL and not an email")])
       (when (and email (not (re-matches email-re (str email))))
         [(str ":git-email " (pr-str email) " does not look like an email "
               "address")])
       (when (and account (not email))
         [":github-account needs :git-email — the commit identity is both"])
       (when (and email (not account))
         [":git-email needs :github-account — the commit identity is both"])
       ;; Every clone in the remote play now rides HTTPS with the token that
       ;; `github-account` acquires — agent forwarding is gone, so without the
       ;; identity these features render tasks that cannot authenticate. A
       ;; build-time refusal beats a permission-denied half way through a
       ;; create.
       (when-not account
         (for [k [:emacs-config-repo :clone-orgs :dotfiles-checkout]
               :when (not (placeholder? (get opts k)))]
           (str k " needs :github-account — its clone authenticates with the "
                "GitHub token walter acquires at create time")))
       ;; An ssh:// or git@ URL reaches for a key or an agent the machine no
       ;; longer has. The clone would fail on the machine with a bare
       ;; permission-denied; say what changed here instead.
       (let [repo (str (:emacs-config-repo opts))]
         (when (and (not (placeholder? (:emacs-config-repo opts)))
                    (or (str/starts-with? repo "git@")
                        (str/starts-with? repo "ssh://")))
           [(str ":emacs-config-repo is an ssh URL, but the machine holds no "
                 "ssh key for GitHub — clones authenticate over https with "
                 "the acquired token, so name the https:// form")])))))))

(defn secret-errors
  "Credentials the selected providers need that no `COLORS_PAR_*` variable
  supplied."
  [opts]
  (map #(str "required credential is not set: " (green-cli/par-name %))
       (distinct (missing-keys opts (slot-keys opts :secrets)))))
