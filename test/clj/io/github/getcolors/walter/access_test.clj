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

(def connection-opts
  {:profile "walter-google" :provider-compute "google" :workdir "/tmp"
   :green/event :ssh :users ["rose" "jack"]
   :walter/ssh-resource compute/placeholder-resource
   :colors-compute/node {:ip "203.0.113.8" :user "root"}
   :ssh-private-key-path "/public/identity.pub" :walter/agent-socket "/private/agent.sock"})

(deftest interactive-ssh-uses-live-address-and-only-the-scoped-identity
  (let [seen (atom nil) result (access/ssh-step connection-opts
          (fn [argv _] (reset! seen argv) {:exit 7}))]
    (is (= 7 (:green/exit result)))
    (is (= ["ssh" "-F" "/dev/null" "-p" "22" "-l" "ubuntu"
            "-o" "StrictHostKeyChecking=accept-new" "-o" "IdentityFile=none"
            "-i" "/public/identity.pub" "-o" "IdentitiesOnly=yes"
            "-o" "IdentityAgent=/private/agent.sock" "-o" "ForwardAgent=no"
            "-o" "ControlMaster=no" "-o" "ControlPersist=no" "-S" "none"
            "--" "203.0.113.8"] @seen))))

(deftest scoped-ssh-selects-configured-seats-only
  (let [seen (atom nil)]
    (binding [access/*seat* "rose"]
      (is (= 0 (:green/exit (access/ssh-step connection-opts (fn [argv _] (reset! seen argv) {:exit 0}))))))
    (is (= ["-l" "rose"] (subvec @seen 5 7)))
    (is (= "203.0.113.8" (last @seen)))
    (binding [access/*seat* "stranger"]
      (is (= 2 (:green/exit (access/ssh-step connection-opts (fn [& _] (throw (ex-info "must not connect" {}))))))))))

(deftest ssh-refuses-incomplete-connection-and-identity
  (doseq [opts [(dissoc connection-opts :colors-compute/node)
               (assoc-in connection-opts [:colors-compute/node :ip] "")
               (assoc-in connection-opts [:colors-compute/node :user] nil)
               (dissoc connection-opts :ssh-private-key-path)
               (dissoc connection-opts :walter/agent-socket)]]
    (is (= 1 (:green/exit (access/ssh-step opts (fn [& _] (throw (ex-info "must not connect" {})))))))))

(deftest connection-resolution-propagates-live-params-and-fails-closed
  (let [seen (atom nil)
        result (access/connection-step connection-opts
                 (fn [opts request]
                   (reset! seen [opts request])
                   {:status "ready" :params {:ip "203.0.113.9" :user "admin"}}))]
    (is (= {:ip "203.0.113.9" :user "admin"} (:colors-compute/node result)))
    (is (= "walter-compute" (get-in @seen [1 :node_id])))
    (is (= compute/placeholder-resource (get-in @seen [1 :ssh_resource]))))
  (doseq [message ["missing state" "destroyed machine" "ownership mismatch" "no public address"]]
    (let [result (access/connection-step connection-opts
                   (fn [& _] {:status "error" :error {:message message}}))]
      (is (= 1 (:green/exit result)))
      (is (= message (:green/err result))))))

(deftest ssh-planning-never-resolves-or-connects
  (let [opts (assoc connection-opts :green/dry-run true)
        unexpected (fn [& _] (throw (ex-info "unexpected effect" {})))]
    (is (= opts (access/connection-step opts unexpected)))
    (is (= opts (access/ssh-step opts unexpected)))))

(deftest ssh-process-failure-cleans-up-scoped-agent
  (let [stopped (atom 0)]
    (doseq [run-fn [(fn [& _] {:exit 255})
                   (fn [& _] (throw (ex-info "ssh could not start" {})))]]
      (try
        (access/scoped
          #(access/ssh-step
            (access/agent-step connection-opts
              (fn [_ _ register!]
                (register! :resource (fn [] (swap! stopped inc)))
                {:socket "/private/agent.sock"
                 :identities {(:reference compute/placeholder-resource) "/public/identity.pub"}}))
            run-fn))
        (catch Exception _)))
    (is (= 2 @stopped))))

(deftest encrypted-install-preflights-config-and-never-starts-agent
  (with-redefs [access/install-lock! (constantly nil)]
  (let [calls (atom [])
        opts {:profile "p" :green/event :ssh-install :users ["seat"]
              :colors-compute/node {:ip "203.0.113.5" :user "root"}}
        export (fn [_ op] (swap! calls conj op)
                 {:status "installed" :private_key_file "/home/me/.ssh/walter/p/identity"})
        config (fn [payload] (swap! calls conj payload) {:exit 0})
        result (access/install-step opts export config)]
    (is (= 0 (:green/exit result)))
    (is (:check_only (first @calls)))
    (is (= "install" (second @calls)))
    (is (= "/home/me/.ssh/walter/p/identity" (:identity_file (last @calls))))
    (is (= [{:name "p" :ip "203.0.113.5" :user "ubuntu"}
            {:name "p-seat" :ip "203.0.113.5" :user "seat"}]
           (:ssh_hosts (last @calls))))
    (is (:installed (last @calls)))
    (reset! calls [])
    (is (= 1 (:green/exit (access/install-step opts export (constantly {:exit 1 :err "collision"})))))
    (is (empty? @calls)))))

(deftest uninstall-is-local-and-removes-config-before-key
  (let [calls (atom [])
        export (fn [_ operation] (swap! calls conj operation)
                 {:status (if (= operation "inspect") "installed" "removed")})
        config (fn [payload] (swap! calls conj (:block_state payload)) {:exit 0})]
    (with-redefs [access/lock! (constantly nil) access/install-lock! (constantly nil)]
      (is (= 0 (:green/exit (access/uninstall-step {:profile "p"} export config))))
      (is (= ["inspect" "absent" "remove"] @calls))
      (reset! calls [])
      (is (= 1 (:green/exit (access/uninstall-step {:profile "p"} export
                                                  (constantly {:exit 1 :err "unsafe config"})))))
      (is (= ["inspect"] @calls)))))

(deftest local-export-identity-is-inspected-only-at-runtime
  (with-redefs [access/install-lock! (constantly nil)]
  (with-redefs [access/export-operation
                (fn [_ operation]
                  (is (= "inspect" operation))
                  {:status "installed" :private_key_file "/private/encrypted"})]
    (is (= "/private/encrypted" (access/installed-identity {:green/event :create})))
    (is (nil? (access/installed-identity {:green/event :build})))
    (is (nil? (access/installed-identity {:green/dry-run true}))))
  (with-redefs [access/export-operation (fn [& _] {:status "absent"})]
    (is (nil? (access/installed-identity {}))))
  (with-redefs [access/export-operation (fn [& _] {:status "error" :error {:message "unowned collision"}})]
    (is (thrown? Exception (access/installed-identity {}))))))

(deftest export-planning-has-no-effects
  (doseq [opts [{:green/event :build} {:green/dry-run true}]
          step [access/install-step access/uninstall-step]]
    (is (= opts (step opts (fn [& _] (throw (Exception. "export called")))
                          (fn [& _] (throw (Exception. "config called"))))))))

(deftest offline-export-operation-binds-authority-without-cloud-secrets
  (let [calls (atom []) opts {:profile "p" :workdir "/tmp/walter-test"
                              :walter/ssh-resource compute/placeholder-resource}]
    (access/export-operation opts "inspect"
      (fn [_ request destination operation env]
        (swap! calls conj [request destination operation env]) {:status "absent"}))
    (let [[request destination operation env] (first @calls)]
      (is (= compute/placeholder-resource (:expected request)))
      (is (= "inspect" operation))
      (is (.endsWith destination "/.ssh/walter/p"))
      (is (every? #{"PATH" "HOME" "TMPDIR"} (keys env)))
      (is (string? (get env "PATH"))))))

(deftest installation-lock-is-shared-across-workdirs-and-released
  (let [home (str (fs/real-path (fs/create-temp-dir)))]
    (try
      (access/scoped
        #(do (access/install-lock! {:profile "p" :workdir "/one"} home)
             (is (thrown? Exception
                   (access/scoped (fn [] (access/install-lock! {:profile "p" :workdir "/two"} home)))))))
      (is (nil? (access/scoped #(access/install-lock! {:profile "p" :workdir "/two"} home))))
      (is (thrown? Exception
            (access/scoped #(do (access/install-lock! {:profile "p"} home) (throw (Exception. "failed"))))))
      (is (nil? (access/scoped #(access/install-lock! {:profile "p"} home))))
      (let [lock (fs/path home ".ssh" ".walter-install-p.lock")]
        (fs/delete lock)
        (fs/create-sym-link lock (fs/path home "other"))
        (is (thrown? Exception (access/scoped #(access/install-lock! {:profile "p"} home))))
        (fs/delete lock)
        (spit (str (fs/path home "other")) "owned by another purpose")
        (java.nio.file.Files/createLink lock (fs/path home "other"))
        (is (thrown? Exception (access/scoped #(access/install-lock! {:profile "p"} home)))))
      (finally (fs/delete-tree home)))))
