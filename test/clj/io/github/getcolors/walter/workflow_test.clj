(ns io.github.getcolors.walter.workflow-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.workflow :as wf]
   [io.github.getcolors.walter.oci :as oci]
   [io.github.getcolors.walter.tools :as tools]
   [io.github.getcolors.walter.validate-test :as vt]
   [io.github.getcolors.walter.workflow :as workflow]))

(defn- steps-for
  "The successors wire-fn declares for `step` under `event`."
  [event step]
  (rest (workflow/wire-fn step {:green/event event})))

;; ---------------------------------------------------------------------------
;; the graphs

(deftest create-forks-into-the-two-ansible-stages
  (is (= [:walter/compute] (steps-for :create :walter/start)))
  (is (= [:walter/ansible-local :walter/ansible-remote]
         (steps-for :create :walter/compute)))
  (testing "neither joins — they are independent"
    (is (= [] (steps-for :create :walter/ansible-local)))))

(deftest emacs-packages-comes-last-and-hangs-off-the-remote-stage
  (testing "it needs Emacs and the cloned configuration, which is what that
           stage installs — so it cannot hang off compute beside it"
    (is (= [:walter/emacs-packages] (steps-for :create :walter/ansible-remote)))
    (is (= [] (steps-for :create :walter/emacs-packages))))
  (testing "the local branch is untouched — it still does not join"
    (is (= [] (steps-for :create :walter/ansible-local))))
  (testing "and it is not in any other graph: there is nothing to warm on a
           power cycle, and nothing to undo on a delete"
    (is (= [] (steps-for :delete :walter/compute)))
    (is (= [:walter/ansible-local] (steps-for :start :walter/power-on)))))

(deftest build-runs-the-same-graph-as-create
  (is (= (steps-for :create :walter/compute) (steps-for :build :walter/compute))))

(deftest delete-drops-the-ssh-block-before-destroying
  (testing "so a machine that is already gone still cleans up the workstation"
    (is (= [:walter/ansible-cleanup] (steps-for :delete :walter/start)))
    (is (= [:walter/compute] (steps-for :delete :walter/ansible-cleanup)))
    (is (= [] (steps-for :delete :walter/compute)))))

(deftest stop-never-reaches-opentofu
  (testing "no compute stage in the graph at all — power is not desired state"
    (is (= [:walter/power-off] (steps-for :stop :walter/start)))
    (is (= [] (steps-for :stop :walter/power-off)))))

(deftest start-refreshes-the-ssh-config
  (testing "the address may have changed across a power cycle, and OpenTofu's
           stored output is not refreshed by one"
    (is (= [:walter/power-on] (steps-for :start :walter/start)))
    (is (= [:walter/ansible-local] (steps-for :start :walter/power-on)))))

(deftest every-side-effecting-step-is-dry-runnable
  (doseq [step [:walter/compute :walter/ansible-local :walter/ansible-remote
                :walter/emacs-packages
                :walter/ansible-cleanup :walter/power-off :walter/power-on]]
    (is (contains? (set workflow/side-effecting-steps) step)
        (str step " must be skipped by --dry-run"))))

;; ---------------------------------------------------------------------------
;; start-step

(defn- start
  ([opts] (start opts {}))
  ([opts env] (workflow/start-step opts env)))

(deftest a-valid-build-passes
  (is (= 0 (:green/exit (start (assoc vt/base :green/event :build))))))

(deftest colors-par-profile-stops-the-run-before-anything-happens
  (let [result (start (assoc vt/base :green/event :build)
                      {"COLORS_PAR_PROFILE" "once-colors"})]
    (is (= 2 (:green/exit result)))
    (is (str/includes? (:green/err result) "COLORS_PAR_PROFILE"))))

(deftest delete-is-protected-until-the-guard-is-lifted
  (let [result (start (assoc vt/base :green/event :delete))]
    (is (= 2 (:green/exit result)))
    (is (str/includes? (:green/err result) "COMPUTE_PREVENT_DESTROY")))
  (testing "and the guard is lifted through the environment, not the file"
    (is (= 0 (:green/exit
              (start (assoc vt/base :green/event :delete)
                     {"COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"}))))))

(deftest a-dry-run-needs-no-credentials
  (testing "hcloud needs a token for a real create and none for a rehearsal"
    (let [opts (assoc vt/base :provider-compute "hcloud"
                      :hcloud-name "w" :hcloud-image "ubuntu-24.04"
                      :hcloud-server-type "cx23" :hcloud-location "hel1"
                      :hcloud-ssh-keys "k")]
      (is (= 2 (:green/exit (start (assoc opts :green/event :create)))))
      (is (= 0 (:green/exit (start (assoc opts :green/event :create
                                          :green/dry-run true))))))))

(deftest a-build-of-invalid-state-fails-before-rendering
  (let [result (start (assoc (dissoc vt/base :oci-subnet-id) :green/event :build))]
    (is (= 2 (:green/exit result)))
    (is (str/includes? (:green/err result) ":oci-subnet-id"))))

;; ---------------------------------------------------------------------------
;; power pre-flight

(deftest a-provider-with-no-power-api-is-a-reported-no-op
  (testing "not an error — but not silent either, because a cost-saving command
           that quietly does nothing is one you discover on the invoice"
    (let [result (workflow/power-preflight {:provider-compute "hcloud"})]
      (is (= 0 (:green/exit result)))
      (is (:walter/no-op result)))
    (let [out (with-out-str
                (let [r ((workflow/power-step :stop)
                         {:provider-compute "hcloud" :walter/no-op true})]
                  (is (= 0 (:green/exit r)))))]
      (is (str/includes? out "hcloud"))
      (is (str/includes? out "nothing to do")))))

(deftest an-expired-session-stops-a-power-verb-before-it-starts
  (let [result (workflow/power-preflight {:provider-compute "oci"
                                          :oci-config-file-profile "DEFAULT"}
                                         (fn [_] {:exit 1}))]
    (is (= 2 (:green/exit result)))
    (is (str/includes? (:green/err result) "refresh-oci-token"))))

(deftest a-power-verb-with-no-resolvable-instance-says-what-to-do
  (with-redefs [tools/instance-id (fn [_] nil)]
    (let [result (workflow/power-preflight {:provider-compute "oci" :profile "p"}
                                           (fn [_] {:exit 0}))]
      (is (= 2 (:green/exit result)))
      (is (str/includes? (:green/err result) "oci-instance-id")))))

(deftest a-resolved-instance-is-carried-to-the-power-step
  (let [result (workflow/power-preflight
                {:provider-compute "oci"
                 :oci-instance-id "ocid1.instance.oc1..x"}
                (fn [_] {:exit 0}))]
    (is (= 0 (:green/exit result)))
    (is (= "ocid1.instance.oc1..x" (:walter/instance-id result)))))

(deftest a-failed-power-call-reports-the-cli-output
  (with-redefs [oci/power! (fn [& _] {:exit 1 :out "" :err "ServiceError"})]
    (let [result ((workflow/power-step :stop) {:walter/instance-id "x"})]
      (is (= 1 (:green/exit result)))
      (is (str/includes? (:green/err result) "ServiceError")))))

(deftest start-reads-the-address-back-from-the-provider
  (testing "OpenTofu's stored ip is not refreshed by an out-of-band power cycle,
           so rendering it into ~/.ssh/config would be the silent breakage this
           whole step exists to prevent"
    (with-redefs [oci/power! (fn [& _] {:exit 0})
                  oci/public-ip (fn [& _] "203.0.113.99")]
      (let [result (workflow/power-on-step {:walter/instance-id "x" :ip "198.51.100.1"})]
        (is (= 0 (:green/exit result)))
        (is (= "203.0.113.99" (:ip result)))))))

(deftest start-carries-the-provider-login-into-the-ssh-block
  (testing "the start graph has no compute step to adopt `user` from, and the
           root default data-fn would otherwise apply writes an alias OCI
           refuses to log in as"
    (with-redefs [oci/power! (fn [& _] {:exit 0})
                  oci/public-ip (fn [& _] "203.0.113.99")]
      (let [result (workflow/power-on-step {:walter/instance-id "x"
                                            :provider-compute "oci"})]
        (is (= "ubuntu" (:user result)))))))

(deftest a-started-machine-with-no-address-is-a-failure
  (with-redefs [oci/power! (fn [& _] {:exit 0})
                oci/public-ip (fn [& _] nil)]
    (is (= 1 (:green/exit (workflow/power-on-step {:walter/instance-id "x"}))))))

(deftest a-no-op-start-writes-no-ssh-block
  (testing "there is no new address to record, and a placeholder one would break
           ssh <alias>"
    (is (= 0 (:green/exit (workflow/ansible-local-after-start {:walter/no-op true}))))))

;; ---------------------------------------------------------------------------
;; backends

(deftest remote-state-is-keyed-by-profile-and-a-walter-stage
  (let [advice (workflow/backend-advice tools/compute-tool)
        result (advice {:provider-backend "r2"
                        :profile "walter-oci"
                        :workdir (str (fs/create-temp-dir))
                        :r2-bucket "shared-state"
                        :r2-endpoint "https://example.r2.cloudflarestorage.com"})
        written (slurp (str (tools/tool-dir result tools/compute-tool) "/backend.tf.json"))]
    (testing "sharing a bucket with another project is safe when the key differs"
      (is (str/includes? written "walter-oci/walter-compute.tfstate"))
      (is (not (str/includes? written "tofu-compute.tfstate"))))))

;; ---------------------------------------------------------------------------
;; the whole graph

(deftest a-build-renders-every-stage-and-contacts-nothing
  (let [dir (str (fs/create-temp-dir))
        result (wf/run workflow/workflow
                       (assoc vt/base
                              :green/event :build
                              :workdir dir
                              :profile "walter-test"))
        stage #(str dir "/walter-test/" %)]
    (is (= 0 (:green/exit result)))
    (doseq [f ["walter-compute/main.tf"
               "walter-compute/outputs.tf"
               "walter-compute/backend.tf.json"
               "walter-ansible-local/main.yml"
               "walter-ansible-local/inventory.ini"
               "walter-ansible-local/ansible.cfg"
               "walter-ansible-remote/main.yml"
               "walter-ansible-remote/inventory.json"
               "walter-ansible-remote/ansible.cfg"]]
      (is (fs/exists? (stage f)) (str f " should have been rendered")))
    (testing "the ssh block walter manages cannot collide with ONCE's"
      (is (str/includes? (slurp (stage "walter-ansible-local/main.yml"))
                         "walter {{ host_alias }} ANSIBLE MANAGED BLOCK")))))

(deftest a-dry-run-touches-nothing
  (let [dir (str (fs/create-temp-dir))
        result (wf/run workflow/workflow
                       (assoc vt/base
                              :green/event :create
                              :green/dry-run true
                              :workdir dir
                              :profile "walter-test"))]
    (is (= 0 (:green/exit result)))
    (is (empty? (fs/list-dir dir)))))
