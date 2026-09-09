(ns io.github.getcolors.walter.validate
  "Walter application settings; compute and backend contracts belong to colors-compute."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.compute :as library]
            [io.github.getcolors.compute-ssh :as ssh]))

(defn keygen? [opts]
  (and (some? (:provider-compute opts)) (= "managed" (:mode (ssh/mode opts)))))

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
   :github-account :git-email])

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

(defn nix-package-names
  "The usable `nix-packages` entries, trimmed and with blanks dropped.

  A YAML list is the shape colors.yml wants — it reads well and
  `green.cli/keywordize` carries it through untouched. A plain string is accepted
  too, and not as a convenience: `nix-packages` is otherwise the only non-scalar
  key walter has, and `green.cli/read-pars` overlays `COLORS_PAR_*` onto flat keys
  as strings. Without this, setting COLORS_PAR_NIX_PACKAGES would replace the
  vector with a string that renders as one impossible package name.

  It lives here rather than in `tools` because the rules and the templates have
  to agree on what counts as declared: an entry that normalizes away must not
  satisfy a prerequisite rule while rendering nothing."
  [opts]
  (let [names (:nix-packages opts)]
    (->> (if (sequential? names) names (str/split (str names) #"\s+"))
         (map (comp str/trim str))
         (remove str/blank?))))

(defn asdf-tools
  "The usable `asdf-tools` entries, normalised to {:name :version :plugin}.

  `plugin` is optional: asdf resolves a bare name against its own plugin index,
  and only a plugin outside that index — or one deliberately pinned to a fork —
  needs the URL spelled out. An entry missing either a name or a version is
  dropped rather than rendered, which is why the rules read this and not the raw
  key: a half-written entry that renders no task must not satisfy the
  `asdf-vm` or Corepack prerequisites either."
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
  "The usable `corepack-packages` names — package managers Node's own corepack
  enables.

  Same string tolerance as `nix-package-names`, for the same `COLORS_PAR_*`
  reason, and here for the same agreement reason as `asdf-tools`: a blank entry
  renders no corepack task, so it must not demand a `nodejs` runtime either."
  [opts]
  (let [names (:corepack-packages opts)]
    (->> (if (sequential? names) names (str/split (str names) #"\s+"))
         (map (comp str/trim str))
         (remove str/blank?)
         vec)))

(defn state-errors
  "Everything wrong with `opts` that does not depend on credentials, as a vector
  of messages. Empty means the desired state is renderable."
  [opts]
  (vec
   (concat
    (map #(str % " is required") (missing-keys opts [:profile :workdir :provider-compute :provider-backend]))
    (leftover-placeholders opts)
    (try (library/backend-plan opts (str (:profile opts) "/shared.tfstate")) []
         (catch Exception e [(ex-message e)]))
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    ;; The flag is gone: generation is the default and the explicit key is the
    ;; opt-out (SSH Keypair Standard). A colors.yml still carrying it gets a
    ;; migration message rather than a silent ignore.
    (when (some? (:compute-keygen opts))
      [":compute-keygen is superseded by the SSH Keypair Standard — remove it; generation is the default, and setting the provider's machine key is the opt-out"])
    (for [key [:oci-instance-id :vultr-instance-id] :when (contains? opts key)]
      (str key " is retired; power operations require the owned deployment state"))
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
          packages (set (nix-package-names opts))
          tools (asdf-tools opts)]
      (concat
       (when (and (= :converge-nix (:green/event opts)) (empty? packages))
         [":converge-nix needs at least one entry in :nix-packages — there is nothing to converge"])
       (when (and (= :converge-asdf (:green/event opts)) (empty? tools))
         [":converge-asdf needs at least one entry in :asdf-tools — there is nothing to converge"])
       (when (and shell (not (contains? packages shell)))
         [(str ":login-shell " (pr-str shell) " is not in :nix-packages — "
               "the shell has to be installed before it can be set")])
       ;; asdf is not special-cased anywhere: it reaches the machine as an entry
       ;; in :nix-packages like everything else, so asking for tools without it
       ;; renders a playbook whose every asdf task fails.
       (when (and (seq tools) (not (contains? packages "asdf-vm")))
         [(str ":asdf-tools needs \"asdf-vm\" in :nix-packages — "
               "nothing else puts asdf on the machine")])
       ;; corepack is part of Node, not a package of its own.
       (when (and (seq (corepack-packages opts))
                  (not (some #(= "nodejs" (:name %)) tools)))
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

(defn secret-errors [opts]
  (try (library/compute-credential-errors opts (into {} (System/getenv)))
       (catch Exception _ ["invalid compute provider"])))
