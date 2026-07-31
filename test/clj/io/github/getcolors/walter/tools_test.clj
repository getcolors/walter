(ns io.github.getcolors.walter.tools-test
  (:require
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
