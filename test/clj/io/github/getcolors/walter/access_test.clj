(ns io.github.getcolors.walter.access-test
  (:require [babashka.fs :as fs] [clojure.test :refer [deftest is]]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.compute-node :as node-api]
            [io.github.getcolors.walter.compute :as compute]
            [io.github.getcolors.walter.access :as access]))

(deftest controller-profile-lock-is-exclusive-and-released-on-failure
  (let [directory (str (fs/create-temp-dir)) opts {:workdir directory :profile "test"}]
    (try
      (is (thrown? Exception
            (access/scoped #(do (access/lock! opts)
                                (is (thrown? Exception (access/scoped (fn [] (access/lock! opts)))))
                                (throw (ex-info "application failed" {}))))))
      (is (nil? (access/scoped #(access/lock! opts))))
      (finally (fs/delete-tree directory)))))

(deftest runtime-access-requires-an-explicit-scope
  (is (thrown? Exception (access/lock! {:profile "p" :workdir "/tmp"}))))

(deftest missing-authority-is-inspected-not-recreated-for-delete-and-convergence
  (let [directory (str (fs/create-temp-dir)) calls (atom [])]
    (try
      (with-redefs [ssh/ssh-resource! (fn [_ _ operation _]
                                     (swap! calls conj operation)
                                     {:status "error" :error {:message "missing authority"}})]
        (doseq [event [:delete :ssh :converge-nix :converge-asdf]]
          (is (= 1 (:green/exit (access/scoped #(access/resource-step
                                 {:profile "p" :workdir directory :green/event event})))))))
      (is (= ["inspect" "inspect" "inspect" "inspect"] @calls))
      (finally (fs/delete-tree directory)))))

(deftest agent-capability-is-cleaned-up-on-every-exit
  (let [stopped (atom 0) opts {:profile "p" :workdir "/tmp" :walter/ssh-resource compute/placeholder-resource}]
    (with-redefs [ssh/start-agent! (fn [_ _ register!]
                                    (register! :resource #(swap! stopped inc))
                                    {:socket "/private/agent.sock" :identities {(:reference compute/placeholder-resource) "/public/identity.pub"}})]
      (let [result (access/scoped #(access/agent-step opts))]
        (is (= "/public/identity.pub" (:ssh-private-key-path result)))
        (is (= "/private/agent.sock" (:walter/agent-socket result))))
      (is (= 1 @stopped))
      (is (thrown? Exception (access/scoped #(do (access/agent-step opts) (throw (ex-info "failed" {}))))))
      (is (= 2 @stopped)))))

(deftest direct-public-key-providers-never-create-or-delete-registration
  (with-redefs [node-api/compute-registration! (fn [& _] (throw (ex-info "unexpected registration" {})))]
    (doseq [provider ["google" "azure" "oci" "yandex"]]
      (let [opts {:provider-compute provider}]
        (is (= opts (access/registration-step opts)))
        (is (= opts (access/registration-delete-step opts)))))))

(deftest interactive-ssh-selects-only-the-scoped-identity
  (let [seen (atom nil) result (access/ssh-step
          {:profile "walter-google" :ssh-private-key-path "/public/identity.pub" :walter/agent-socket "/private/agent.sock"}
          (fn [argv _] (reset! seen argv) {:exit 7}))]
    (is (= 7 (:green/exit result)))
    (is (= "walter-google" (last @seen)))
    (is (some #{"IdentityAgent=/private/agent.sock"} @seen))
    (is (some #{"ForwardAgent=no"} @seen))))

(deftest scoped-ssh-selects-configured-seats-only
  (let [opts {:profile "walter-google" :users ["rose" "jack"]} seen (atom nil)]
    (binding [access/*seat* "rose"]
      (is (= 0 (:green/exit (access/ssh-step opts (fn [argv _] (reset! seen argv) {:exit 0}))))))
    (is (= "walter-google-rose" (last @seen)))
    (binding [access/*seat* "stranger"]
      (is (= 2 (:green/exit (access/ssh-step opts (fn [& _] (throw (ex-info "must not connect" {}))))))))))
