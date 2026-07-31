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
