(ns io.github.getcolors.walter.workflow-test
  (:require [babashka.fs :as fs] [clojure.string :as str]
            [clojure.test :refer [deftest is]] [green.workflow :as wf]
            [io.github.getcolors.walter.access :as access]
            [io.github.getcolors.walter.tools :as tools]
            [io.github.getcolors.walter.validate-test :as vt]
            [io.github.getcolors.walter.workflow :as workflow]))
(defn- successors [event step] (vec (rest (workflow/wire-fn step {:green/event event}))))
(defn- reachable [event]
  (loop [seen #{} pending [:walter/start]]
    (if-let [step (first pending)]
      (if (seen step) (recur seen (rest pending))
        (recur (conj seen step) (concat (rest pending) (successors event step)))) seen)))
(deftest create-orders-authority-access-and-compute-before-application
  (doseq [[a b] [[:walter/start :walter/ssh-resource] [:walter/ssh-resource :walter/agent]
                 [:walter/agent :walter/github-token] [:walter/github-token :walter/registration]
                 [:walter/registration :walter/compute] [:walter/compute :walter/ansible-bootstrap]
                 [:walter/ansible-bootstrap :walter/ansible-seats] [:walter/ansible-seats :walter/ansible-local]
                 [:walter/ansible-local :walter/ansible-remote] [:walter/ansible-remote :walter/emacs-packages]]]
    (is (= [b] (successors :create a)))))
(deftest delete-preserves-authority-and-removes-registration-last
  (is (not ((reachable :delete) :walter/agent)))
  (is (= [:walter/compute] (successors :delete :walter/ansible-cleanup)))
  (is (= [:walter/registration-delete] (successors :delete :walter/compute)))
  (is (= [] (successors :delete :walter/registration-delete))))
(deftest focused-access-needs-an-agent-but-no-compute
  (doseq [event [:converge-nix :converge-asdf :ssh]]
    (is ((reachable event) :walter/agent))
    (is (not ((reachable event) :walter/compute)))))
(deftest ssh-resolves-owned-connection-before-unlocking
  (doseq [[a b] [[:walter/ssh-resource :walter/registration]
                 [:walter/registration :walter/connection]
                 [:walter/connection :walter/agent]
                 [:walter/agent :walter/ssh]]]
    (is (= [b] (successors :ssh a))))
  (doseq [event [:converge-nix :converge-asdf]]
    (is (not ((reachable event) :walter/connection)))))
(deftest build-adds-only-focused-rendering
  (is (= (into (reachable :create) [:walter/converge-nix :walter/converge-asdf]) (reachable :build))))
(deftest dry-run-skips-all-effects
  (doseq [event [:create :delete :ssh :converge-nix :converge-asdf]]
    (is (every? (set workflow/side-effecting-steps) (disj (reachable event) :walter/start)))))
(deftest preflight-guards
  (is (= 0 (:green/exit (workflow/start-step (assoc vt/base :green/event :build) {}))))
  (is (= 2 (:green/exit (workflow/start-step (assoc vt/base :green/event :build) {"COLORS_PAR_PROFILE" "other"}))))
  (is (= 2 (:green/exit (workflow/start-step (assoc vt/base :green/event :delete) {}))))
  (is (= 0 (:green/exit (workflow/start-step (assoc vt/base :green/event :delete) {"COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"})))))
(deftest build-is-isolated-from-persistent-authority
  (let [result (workflow/start-step (assoc vt/base :green/event :build :workdir "/tmp/walter") {})]
    (is (= "/tmp/walter/build" (:workdir result)))))
(deftest unavailable-power-is-explicit
  (doseq [step [workflow/power-on-step workflow/power-off-step]]
    (let [result (step vt/base)]
      (is (= 2 (:green/exit result)))
      (is (str/includes? (:green/err result) "unavailable")))))
(deftest cleanup-failure-prevents-destroy
  (let [destroyed (atom false)]
    (with-redefs [access/resource-step identity access/registration-step identity
                  tools/load-compute-step identity
                  tools/ansible-local-step #(assoc % :green/exit 1 :green/err "local cleanup failed")
                  tools/compute-step (fn [_] (reset! destroyed true) {})]
      (let [result (wf/run workflow/workflow (assoc vt/base :green/event :delete :compute-prevent-destroy false))]
        (is (= 1 (:green/exit result)))
        (is (false? @destroyed))))))
