(ns io.github.getcolors.walter.github-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.walter.github :as github]))

(def ^:private wanting
  {:green/event :create :github-account "someone" :profile "p"})

(defn- with-sandbox
  "The acquire tests inject a temp sandbox so nothing touches the real
  ~/.local/state/walter."
  [opts]
  (assoc opts :walter/github-token-dir (str (fs/create-temp-dir))))

(defn- no-reuse
  "A run-fn wrapper whose sandbox probe answers \"nothing to reuse\", so the
  test drives the device-flow path."
  [run-fn]
  (fn [args opts timeout]
    (if (= ["gh" "auth" "status" "--hostname" "github.com"] args)
      {:ok? false :exit 1 :out "" :err ""}
      (run-fn args opts timeout))))

(deftest the-step-passes-through-whenever-there-is-nothing-to-do
  (testing "build renders from desired state alone, delete has nothing to log
           in to, and no account means the feature is off"
    (doseq [opts [{:green/event :build :github-account "someone"}
                  {:green/event :delete :github-account "someone"}
                  {:green/event :create}]]
      (let [out (github/github-token-step
                 opts
                 (fn [_] (throw (ex-info "probed" {})))
                 (fn [_] (throw (ex-info "acquired" {}))))]
        (is (= 0 (:green/exit out)))
        (is (nil? (:walter/github-token-file out)))))))

(deftest a-machine-that-is-already-logged-in-keeps-the-create-non-interactive
  (let [acquired (atom false)
        out (github/github-token-step wanting
                                      (constantly true)
                                      (fn [o] (reset! acquired true) o))]
    (is (= 0 (:green/exit out)))
    (is (false? @acquired) "an existing login is left exactly as it is")))

(deftest an-unreachable-or-logged-out-machine-triggers-acquisition
  (let [acquired (atom false)]
    (github/github-token-step wanting
                              (constantly false)
                              (fn [o] (reset! acquired true) (assoc o :green/exit 0)))
    (is (true? @acquired))))

(deftest the-probe-rides-the-managed-alias-in-batch-mode
  (let [args (github/probe-args {:profile "walter-oci"})]
    (is (= "ssh" (first args)))
    (is (some #{"BatchMode=yes"} args)
        "a missing machine must fail in seconds, never prompt")
    (is (some #{"walter-oci"} args) "the alias is the profile")
    (is (str/includes? (last args) ".nix-profile/bin/gh")
        "a non-login shell has never heard of the profile, so the path is full")))

(deftest the-login-is-sandboxed-and-scoped
  (let [args (github/login-args "/tmp/sandbox")]
    (is (= "env" (first args))
        "GH_CONFIG_DIR travels on the child alone — the operator's own gh
         state is not walter's to write")
    (is (some #{"GH_CONFIG_DIR=/tmp/sandbox"} args))
    (is (some #{"--web"} args) "the device flow is the whole design")
    (is (= ["--scopes" "workflow"] (take-last 2 args))
        "gh's defaults plus workflow, so CI edits are not refused")))

(deftest acquisition-verifies-the-account-before-trusting-the-token
  (testing "a token approved from the wrong account fails here with the reason,
           not as a puzzling 404 half way through the clones"
    (let [out (github/acquire! (with-sandbox wanting)
                               (fn [_] {:exit 0})
                               (fn [args _ _]
                                 (if (= ["gh" "api" "user" "-q" ".login"] args)
                                   {:ok? true :exit 0 :out "somebody-else\n" :err ""}
                                   {:ok? true :exit 0 :out "tok" :err ""})))]
      (is (= 2 (:green/exit out)))
      (is (str/includes? (:green/err out) "somebody-else"))
      (is (nil? (:walter/github-token-dir out))
          "the sandbox is deleted on the way out"))))

(deftest a-failed-login-reports-and-leaves-nothing-behind
  (let [out (github/acquire! (with-sandbox wanting)
                             (fn [_] {:exit 1 :err "denied"})
                             (no-reuse (fn [& _] (throw (ex-info "unreachable" {})))))]
    (is (= 2 (:green/exit out)))
    (is (str/includes? (:green/err out) "gh auth login failed"))
    (is (nil? (:walter/github-token-dir out)))))

(deftest a-surviving-sandbox-spares-the-retry-a-second-approval
  (testing "a create that failed after the device flow keeps its sandbox; the
           retry reuses the token — verified against the account again — and
           never prompts"
    (let [out (github/acquire! (with-sandbox wanting)
                               (fn [_] (throw (ex-info "prompted" {})))
                               (fn [args _ _]
                                 (cond
                                   (= ["gh" "auth" "status" "--hostname" "github.com"] args)
                                   {:ok? true :exit 0 :out "" :err ""}
                                   (= ["gh" "api" "user" "-q" ".login"] args)
                                   {:ok? true :exit 0 :out "Someone\n" :err ""}
                                   (= ["gh" "auth" "token"] args)
                                   {:ok? true :exit 0 :out "gho_reuse\n" :err ""})))]
      (try
        (is (= 0 (:green/exit out)))
        (is (= "gho_reuse" (slurp (:walter/github-token-file out))))
        (finally
          (github/delete-token-dir! out)))))
  (testing "a sandbox holding another account's token is wiped and the flow
           runs fresh rather than seeding the wrong identity"
    (let [prompted (atom false)
          out (github/acquire! (with-sandbox wanting)
                               (fn [_] (reset! prompted true) {:exit 1 :err "x"})
                               (fn [args _ _]
                                 (cond
                                   (= ["gh" "auth" "status" "--hostname" "github.com"] args)
                                   {:ok? true :exit 0 :out "" :err ""}
                                   (= ["gh" "api" "user" "-q" ".login"] args)
                                   {:ok? true :exit 0 :out "somebody-else\n" :err ""})))]
      (is (true? @prompted) "the mismatched token must not be reused")
      (is (= 2 (:green/exit out))))))

(deftest a-successful-acquisition-stashes-a-path-and-never-the-token
  (let [out (github/acquire! (with-sandbox wanting)
                             (fn [_] {:exit 0})
                             (fn [args _ _]
                               (cond
                                 (= ["gh" "api" "user" "-q" ".login"] args)
                                 {:ok? true :exit 0 :out "Someone\n" :err ""}
                                 (= ["gh" "auth" "token"] args)
                                 {:ok? true :exit 0 :out "gho_test123\n" :err ""})))]
    (try
      (is (= 0 (:green/exit out)))
      (testing "the account check is case-insensitive, as GitHub logins are"
        (is (some? (:walter/github-token-file out))))
      (testing "only the path enters opts; the token stays in the 0600 file"
        (is (not-any? (fn [[_ v]] (and (string? v) (str/includes? v "gho_test123")))
                      out))
        (is (= "gho_test123" (slurp (:walter/github-token-file out)))))
      (finally
        (github/delete-token-dir! out)))))

(deftest deleting-the-token-dir-is-safe-and-complete
  (let [dir (str (fs/create-temp-dir))
        _ (spit (str dir "/token") "secret")
        out (github/delete-token-dir! {:walter/github-token-dir dir
                                       :walter/github-token-file (str dir "/token")})]
    (is (not (fs/exists? dir)))
    (is (nil? (:walter/github-token-dir out)))
    (is (nil? (:walter/github-token-file out)))
    (testing "and a second delete, or one with nothing to delete, is a no-op"
      (is (map? (github/delete-token-dir! out)))
      (is (map? (github/delete-token-dir! {}))))))
