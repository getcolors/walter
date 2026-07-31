(ns io.github.getcolors.walter.validate
  "Walter's desired-state rules, driven by ONCE's provider registry.

  The registry is consumed as data rather than reimplemented. It is the single
  place recording, per provider, the non-secret keys its templates interpolate
  and the credentials it needs, and keeping one copy is what stops a provider
  being validated against one set of keys and run with another. Walter drives it
  over two of ONCE's four slots: there is no SMTP and no DNS here.

  Nothing upstream promises this registry's shape. `scripts/golden.sh` is what
  actually catches a change to it — see plans/0001."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]
   [io.github.getcolors.once.validate :as once-validate]))

(def providers
  "ONCE's provider registry, verbatim. One of the two things walter reuses."
  once-validate/providers)

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
  #{"oci"})

(defn stoppable?
  "Whether the selected compute provider supports `stop` and `start`."
  [opts]
  (contains? stoppable (str (:provider-compute opts))))

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

(defn state-errors
  "Everything wrong with `opts` that does not depend on credentials, as a vector
  of messages. Empty means the desired state is renderable."
  [opts]
  (vec
   (concat
    (map #(str % " is required")
         (missing-keys opts (concat [:profile :workdir] (slot-keys opts :required))))
    (for [slot slots
          :let [provider (get opts slot)]
          :when (not (contains? (get providers slot) provider))]
      (str "unsupported " slot " " (pr-str provider)))
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    ;; Yandex requires :compute-pubkey; elsewhere it is optional. Either way a
    ;; value that is present must look like a public key.
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
    (when-not (or (nil? (:power-wait-seconds opts))
                  (and (integer? (:power-wait-seconds opts))
                       (pos? (:power-wait-seconds opts))))
      [":power-wait-seconds must be a positive integer"])
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
       ;; Same shape as the asdf-vm rule above, and for the same reason: the
       ;; dotfiles installer is a Babashka script, and nothing but :nix-packages
       ;; puts bb on the machine. Caught here rather than as a
       ;; command-not-found half way through a create.
       (when (and (not (placeholder? (:dotfiles-repo opts)))
                  (not (contains? packages "babashka")))
         [(str ":dotfiles-repo needs \"babashka\" in :nix-packages — "
               "the installer is a bb script and nothing else puts bb on the machine")]))))))

(defn secret-errors
  "Credentials the selected providers need that no `COLORS_PAR_*` variable
  supplied."
  [opts]
  (map #(str "required credential is not set: " (green-cli/par-name %))
       (distinct (missing-keys opts (slot-keys opts :secrets)))))
