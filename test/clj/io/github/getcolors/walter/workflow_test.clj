(ns io.github.getcolors.walter.workflow-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.workflow :as wf]
   [io.github.getcolors.compute-power :as power]
   [io.github.getcolors.walter.tools :as tools]
   [io.github.getcolors.walter.validate-test :as vt]
   [io.github.getcolors.walter.workflow :as workflow]))

(defn- steps-for
  "The successors wire-fn declares for `step` under `event`."
  [event step]
  (rest (workflow/wire-fn step {:green/event event})))

(defn- reachable
  "Every node reachable from start, including start, across the branching DAG."
  [event]
  (loop [seen #{} pending [:walter/start]]
    (if-let [step (first pending)]
      (if (contains? seen step)
        (recur seen (rest pending))
        (recur (conj seen step) (concat (rest pending) (steps-for event step))))
      seen)))

;; ---------------------------------------------------------------------------
;; the graphs

(deftest create-bootstraps-before-forking-into-the-normal-ansible-stages
  (testing "the token step sits between start and compute — the one interactive
           moment has to land before anything long-running begins"
    (is (= [:walter/github-token] (steps-for :create :walter/start)))
    (is (= [:walter/compute] (steps-for :create :walter/github-token))))
  (is (= [:walter/ansible-bootstrap] (steps-for :create :walter/compute)))
  (testing "the seat stage sits between bootstrap and the fork: it connects as
           the login the Vultr bootstrap adopts, and the remote play's
           inventory connects as each seat it creates"
    (is (= [:walter/ansible-seats] (steps-for :create :walter/ansible-bootstrap)))
    (is (= [:walter/ansible-local :walter/ansible-remote]
           (steps-for :create :walter/ansible-seats))))
  (testing "neither normal stage joins — they are independent"
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

(deftest build-renders-focused-stages-that-create-never-runs
  (let [create #{:walter/start :walter/github-token :walter/compute
                 :walter/ansible-bootstrap :walter/ansible-seats
                 :walter/ansible-local :walter/ansible-remote
                 :walter/emacs-packages}
        build (conj create :walter/converge-nix :walter/converge-asdf)]
    (is (= create (reachable :create)))
    (is (= build (reachable :build)))
    (is (not (contains? (reachable :create) :walter/converge-nix)))
    (is (not (contains? (reachable :create) :walter/converge-asdf)))))

(deftest focused-convergence-reaches-only-its-own-step
  (is (= [:walter/converge-nix]
         (steps-for :converge-nix :walter/start)))
  (is (= [] (steps-for :converge-nix :walter/converge-nix)))
  (is (= [:walter/converge-asdf]
         (steps-for :converge-asdf :walter/start)))
  (is (= [] (steps-for :converge-asdf :walter/converge-asdf))))

(deftest delete-drops-the-ssh-block-before-destroying
  (testing "so a machine that is already gone still cleans up the workstation"
    (is (= [:walter/ansible-cleanup] (steps-for :delete :walter/start)))
    (is (= [:walter/compute] (steps-for :delete :walter/ansible-cleanup)))
    (is (= [] (steps-for :delete :walter/compute))
        "the local machine key goes only after the compute destroy succeeded")
))

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
  (doseq [step [:walter/github-token
                :walter/compute :walter/ansible-bootstrap
                :walter/ansible-local :walter/ansible-remote
                :walter/emacs-packages
                :walter/converge-nix :walter/converge-asdf
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
  (testing "lifting the guard inspects existing ownership before any cleanup"
    (with-redefs [tools/load-compute-step #(assoc % :green/exit 1 :green/err "owned state required")]
      (is (= "owned state required" (:green/err
             (start (assoc vt/base :green/event :delete)
                    {"COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"})))))))

(deftest a-dry-run-needs-no-credentials
  (testing "hcloud needs a token for a real create and none for a rehearsal"
    (let [opts (assoc vt/base :provider-compute "hcloud"
                      :hcloud-name "w" :hcloud-image "ubuntu-24.04"
                      :hcloud-server-type "cx23" :hcloud-location "hel1"
                      :hcloud-ssh-keys "k")]
      (is (= 0 (:green/exit (start (assoc opts :green/event :create)))))
      (is (= 0 (:green/exit (start (assoc opts :green/event :create
                                          :green/dry-run true))))))))

(deftest focused-convergence-needs-no-provider-credentials
  (doseq [[event declaration] [[:converge-nix {:nix-packages ["ripgrep"]}]
                               [:converge-asdf {:nix-packages ["asdf-vm"]
                                                :asdf-tools [{:name "ruby" :version "3.4.1"}]}]]]
    (is (= 0 (:green/exit (start (merge vt/base declaration {:green/event event})))))))

(deftest a-build-of-invalid-state-fails-before-rendering
  (let [result (start (assoc (dissoc vt/base :s3-bucket) :green/event :build))]
    (is (= 2 (:green/exit result)))
    (is (str/includes? (:green/err result) ":s3-bucket"))))

;; ---------------------------------------------------------------------------
;; power pre-flight

(deftest a-build-renders-every-stage-and-contacts-nothing
  (let [dir (str (fs/create-temp-dir))
        result (wf/run workflow/workflow
                       (assoc vt/base
                              :green/event :build
                              :workdir dir
                              :profile "walter-test"))
        stage #(str dir "/walter-test/" %)]
    (is (= 0 (:green/exit result)))
    (doseq [f ["walter-compute/shared/shared.tf.json"
               "walter-compute/nodes/0/node.tf.json"
               "walter-compute/shared/backend.tf.json"
               "walter-ansible-local/main.yml"
               "walter-ansible-local/inventory.ini"
               "walter-ansible-local/ansible.cfg"
               "walter-ansible-remote/main.yml"
               "walter-ansible-remote/inventory.json"
               "walter-ansible-remote/ansible.cfg"]]
      (is (fs/exists? (stage f)) (str f " should have been rendered")))
    (testing "the ssh block walter manages cannot collide with ONCE's"
      (is (str/includes? (slurp (stage "walter-ansible-local/main.yml"))
                         "legacy_marker_prefix")))))

(deftest a-dry-run-touches-nothing
  (doseq [[event declaration] [[:create {}]
                               [:converge-nix {:nix-packages ["ripgrep"]}]
                               [:converge-asdf {:nix-packages ["asdf-vm"]
                                                :asdf-tools [{:name "ruby" :version "3.4.1"}]}]]]
    (let [dir (str (fs/create-temp-dir))
          result (wf/run workflow/workflow
                         (merge vt/base declaration
                                {:green/event event
                                 :green/dry-run true
                                 :workdir dir
                                 :profile "walter-test"}))]
      (is (= 0 (:green/exit result)))
      (is (empty? (fs/list-dir dir))))))

(deftest power-start-uses-refreshed-normalized-address-and-login
  (let [seen (atom nil)]
    (with-redefs [power/power-deployment
                  (fn [opts action]
                    (reset! seen [opts action])
                    {:status "ready" :key {:private_key_path "/temporary/owned-key"}
                     :cluster {:nodes [{:node_id "0" :provider "vultr" :name "p"
                                        :ip "203.0.113.99" :vpc_ip nil :user "root" :sudoer "root"}]}})]
      (let [result (workflow/power-on-step {:profile "p" :provider-compute "vultr"})]
        (is (= 0 (:green/exit result)))
        (is (= "start" (second @seen)))
        (is (= "203.0.113.99" (:ip result)))
        (is (= "ubuntu" (:user result)))
        (is (= "/temporary/owned-key" (:ssh-private-key-path result)))))))

(deftest failed-power-is-fixed-error-and-never-a-successful-noop
  (with-redefs [power/power-deployment (fn [& _] (throw (ex-info "sensitive transport detail" {})))]
    (let [result (workflow/power-off-step {:profile "p" :provider-compute "unsupported"})]
      (is (= 1 (:green/exit result)))
      (is (= "compute power refused" (:green/err result))))))

(deftest cleanup-failure-prevents-destroy
  (let [destroyed (atom false)]
    (with-redefs [tools/load-compute-step #(assoc % :green/exit 0)
                  tools/ansible-local-step #(assoc % :green/exit 1 :green/err "local cleanup failed")
                  tools/ansible-remote-step (fn [_] (is false "failed local cleanup must stop") {})
                  tools/compute-step (fn [_] (reset! destroyed true) {})]
      (let [result (wf/run workflow/workflow (assoc vt/base :green/event :delete :compute-prevent-destroy false))]
        (is (= 1 (:green/exit result)))
        (is (false? @destroyed))))))
