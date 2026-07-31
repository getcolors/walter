(ns io.github.getcolors.walter.validate-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.walter.validate :as validate]))

(def base
  "A minimal renderable desired state: OCI compute, local backend."
  {:profile "walter-test"
   :workdir ".colors"
   :provider-compute "oci"
   :provider-backend "local"
   :compute-prevent-destroy true
   :oci-config-file-profile "DEFAULT"
   :oci-subnet-id "ocid1.subnet.oc1.eu-frankfurt-1.aaaaexample"
   :oci-compartment-id "ocid1.tenancy.oc1..aaaaexample"
   :oci-availability-domain "XquT:EU-FRANKFURT-1-AD-1"
   :oci-display-name "walter-test"
   :oci-shape "VM.Standard.A2.Flex"
   :oci-ocpus 2
   :oci-memory-in-gbs 12
   :oci-boot-volume-size-in-gbs 100
   :oci-boot-volume-vpus-per-gb 30
   :oci-ssh-authorized-keys "/home/example/.ssh/id_ed25519.pub"})

(defn- errors-matching
  [opts re]
  (filter #(re-find re %) (validate/state-errors opts)))

(deftest a-complete-desired-state-is-renderable
  (is (= [] (validate/state-errors base))))

(deftest required-keys-come-from-the-selected-provider
  (testing "a missing OCI key is reported"
    (is (seq (errors-matching (dissoc base :oci-subnet-id) #":oci-subnet-id"))))
  (testing "REPLACE_ME counts as missing"
    (is (seq (errors-matching (assoc base :oci-shape "REPLACE_ME") #":oci-shape"))))
  (testing "another provider's keys are not required"
    (is (= [] (validate/state-errors (dissoc base :hcloud-name :yandex-cloud-id)))))
  (testing "switching provider switches the requirement"
    (let [hcloud (assoc base :provider-compute "hcloud")]
      (is (seq (errors-matching hcloud #":hcloud-name"))))))

(deftest unsupported-providers-are-named
  (is (seq (errors-matching (assoc base :provider-compute "azure")
                            #"unsupported :provider-compute")))
  (is (seq (errors-matching (assoc base :provider-backend "gcs")
                            #"unsupported :provider-backend"))))

(deftest walter-fills-only-two-of-onces-four-slots
  (testing "no SMTP or DNS provider is demanded"
    (is (= [:provider-compute :provider-backend] validate/slots))
    (is (= [] (validate/state-errors (dissoc base :provider-smtp :provider-dns))))))

(deftest prevent-destroy-must-be-a-boolean
  (is (seq (errors-matching (assoc base :compute-prevent-destroy "true")
                            #":compute-prevent-destroy")))
  (is (= [] (validate/state-errors (assoc base :compute-prevent-destroy false)))))

(deftest instance-id-must-look-like-an-instance-ocid
  (testing "absent is fine — it is the optional escape hatch"
    (is (= [] (validate/state-errors base))))
  (testing "a real one passes"
    (is (= [] (validate/state-errors
               (assoc base :oci-instance-id "ocid1.instance.oc1.eu-frankfurt-1.aaaaexample")))))
  (testing "a wrong-resource OCID is caught here, not half way through a power cycle"
    (is (seq (errors-matching (assoc base :oci-instance-id "ocid1.image.oc1..aaaa")
                              #":oci-instance-id")))))

(deftest power-wait-must-be-a-positive-integer
  (is (= [] (validate/state-errors (assoc base :power-wait-seconds 60))))
  (is (seq (errors-matching (assoc base :power-wait-seconds 0) #":power-wait-seconds")))
  (is (seq (errors-matching (assoc base :power-wait-seconds "300") #":power-wait-seconds"))))

(deftest compute-pubkey-is-checked-when-present
  (is (= [] (validate/state-errors (assoc base :compute-pubkey "ssh-ed25519 AAAA"))))
  (is (seq (errors-matching (assoc base :compute-pubkey "not-a-key") #":compute-pubkey"))))

(deftest colors-par-profile-is-rejected-outright
  (testing "the variable that would point walter at another project's state"
    (let [errs (validate/env-errors {"COLORS_PAR_PROFILE" "once-colors"})]
      (is (= 1 (count errs)))
      (is (str/includes? (first errs) "COLORS_PAR_PROFILE"))))
  (testing "an empty value is not a set value"
    (is (nil? (validate/env-errors {"COLORS_PAR_PROFILE" ""}))))
  (testing "other COLORS_PAR_ variables are none of its business"
    (is (nil? (validate/env-errors {"COLORS_PAR_HCLOUD_TOKEN" "x"})))))

(deftest stoppable-is-a-fact-about-the-provider-api
  (is (validate/stoppable? {:provider-compute "oci"}))
  (is (not (validate/stoppable? {:provider-compute "hcloud"})))
  (is (not (validate/stoppable? {:provider-compute "digitalocean"})))
  (is (not (validate/stoppable? {:provider-compute "yandex"})))
  (is (not (validate/stoppable? {:provider-compute "no-infra"})))
  (is (not (validate/stoppable? {}))))

(deftest secret-errors-follow-the-selected-providers
  (testing "OCI needs none — it authenticates from ~/.oci/config"
    (is (= [] (vec (validate/secret-errors base)))))
  (testing "hcloud needs its token"
    (is (seq (validate/secret-errors (assoc base :provider-compute "hcloud")))))
  (testing "r2 needs both keys, and naming them satisfies it"
    (let [r2 (assoc base :provider-backend "r2"
                    :r2-bucket "b" :r2-endpoint "https://e")]
      (is (= 2 (count (validate/secret-errors r2))))
      (is (= [] (vec (validate/secret-errors
                      (assoc r2 :r2-access-key-id "k" :r2-secret-access-key "s")))))))
  (testing "the message names the variable to export, not the key"
    (is (str/includes? (first (validate/secret-errors (assoc base :provider-compute "hcloud")))
                       "COLORS_PAR_HCLOUD_TOKEN"))))

(deftest a-login-shell-must-be-one-of-the-installed-packages
  (testing "nothing but nix-packages puts a binary in the profile, so a shell
           missing from it names a path that will not exist — and the failure on
           the machine is an account whose shell does not start"
    (is (seq (errors-matching (assoc base :login-shell "fish") #":login-shell")))
    (is (seq (errors-matching (assoc base :login-shell "fish"
                                     :nix-packages ["ripgrep"])
                              #":login-shell"))))
  (testing "naming it in both places is what makes it valid"
    (is (= [] (validate/state-errors (assoc base :login-shell "fish"
                                            :nix-packages ["ripgrep" "fish"])))))
  (testing "the flat-key spelling of nix-packages is accepted here too"
    (is (= [] (validate/state-errors (assoc base :login-shell "fish"
                                            :nix-packages "ripgrep fish")))))
  (testing "no shell named is the common case and never an error"
    (is (= [] (validate/state-errors (assoc base :nix-packages ["fish"]))))))

(deftest asdf-tools-need-asdf-installed
  (testing "asdf reaches the machine as a nix-packages entry like anything else,
           so asking for tools without it renders a playbook that cannot work"
    (is (seq (errors-matching (assoc base :asdf-tools [{:name "nodejs" :version "24.18.1"}])
                              #":asdf-tools"))))
  (testing "naming asdf-vm satisfies it"
    (is (= [] (validate/state-errors
               (assoc base :nix-packages ["asdf-vm"]
                      :asdf-tools [{:name "nodejs" :version "24.18.1"}]))))))

(deftest corepack-needs-a-node-to-ship-inside
  (let [with-asdf (assoc base :nix-packages ["asdf-vm"])]
    (testing "corepack is part of Node, not a package of its own"
      (is (seq (errors-matching (assoc with-asdf :corepack-packages ["pnpm"])
                                #":corepack-packages")))
      (is (seq (errors-matching (assoc with-asdf
                                       :asdf-tools [{:name "python" :version "3.13.0"}]
                                       :corepack-packages ["pnpm"])
                                #":corepack-packages"))))
    (testing "a nodejs entry satisfies it"
      (is (= [] (validate/state-errors
                 (assoc with-asdf
                        :asdf-tools [{:name "nodejs" :version "24.18.1"}]
                        :corepack-packages ["pnpm"])))))))

(deftest dotfiles-need-babashka-installed
  (testing "the installer is a bb script, and nothing but nix-packages puts bb on
           the machine — so the combination fails here rather than as a
           command-not-found half way through a create"
    (is (seq (errors-matching (assoc base :dotfiles-repo "git@github.com:me/dotfiles.git")
                              #":dotfiles-repo")))
    (is (seq (errors-matching (assoc base
                                     :nix-packages ["ripgrep"]
                                     :dotfiles-repo "git@github.com:me/dotfiles.git")
                              #":dotfiles-repo"))))
  (testing "naming babashka satisfies it"
    (is (= [] (validate/state-errors
               (assoc base
                      :nix-packages ["babashka"]
                      :dotfiles-repo "git@github.com:me/dotfiles.git"
                      :dotfiles-profile "ubuntu")))))
  (testing "no repository named is the common case and never an error"
    (is (= [] (validate/state-errors (assoc base :nix-packages ["ripgrep"]))))))

(deftest atuin-needs-atuin-installed
  (testing "there is nothing to log in without it"
    (is (seq (errors-matching (assoc base :atuin-username "someone")
                              #":atuin-username"))))
  (testing "naming atuin satisfies it"
    (is (= [] (validate/state-errors
               (assoc base :nix-packages ["atuin"] :atuin-username "someone")))))
  (testing "the password and key are deliberately not validated here — build
           renders from desired state alone and must stay credential-free, so
           the remote playbook asserts them at create time instead"
    (is (= [] (validate/state-errors
               (assoc base :nix-packages ["atuin"] :atuin-username "someone"))))))
