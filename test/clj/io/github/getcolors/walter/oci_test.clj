(ns io.github.getcolors.walter.oci-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.walter.oci :as oci]))

(defn- returning
  "A runner that answers `result` and forgets the call."
  [result]
  (fn [_args] result))

(defn- recording
  "A runner that records the argument vector it was handed."
  [a result]
  (fn [args] (reset! a (vec args)) result))

;; ---------------------------------------------------------------------------
;; parsing

(deftest public-ip-is-read-out-of-the-vnic-list
  (is (= "203.0.113.7"
         (oci/parse-public-ip "{\"data\":[{\"public-ip\":\"203.0.113.7\"}]}"))))

(deftest public-ip-skips-vnics-that-have-none
  (testing "a machine may have several VNICs and only one public address"
    (is (= "203.0.113.7"
           (oci/parse-public-ip
            "{\"data\":[{\"public-ip\":null},{\"public-ip\":\"\"},{\"public-ip\":\"203.0.113.7\"}]}")))))

(deftest public-ip-is-nil-rather-than-a-guess
  (testing "a stopped instance commonly reports no public address"
    (is (nil? (oci/parse-public-ip "{\"data\":[{\"public-ip\":null}]}"))))
  (testing "and unreadable output is not an exception"
    (is (nil? (oci/parse-public-ip "not json")))
    (is (nil? (oci/parse-public-ip "")))
    (is (nil? (oci/parse-public-ip nil)))))

(deftest lifecycle-state-is-normalised
  (is (= "RUNNING" (oci/parse-lifecycle-state "{\"data\":{\"lifecycle-state\":\"RUNNING\"}}")))
  (is (= "STOPPED" (oci/parse-lifecycle-state "{\"data\":{\"lifecycle-state\":\"stopped\"}}")))
  (is (nil? (oci/parse-lifecycle-state "{\"data\":{}}")))
  (is (nil? (oci/parse-lifecycle-state "garbage"))))

;; ---------------------------------------------------------------------------
;; the session

(deftest a-live-session-is-not-an-error
  (is (nil? (oci/session-error {} (returning {:exit 0 :out "" :err ""})))))

(deftest an-expired-session-names-the-fix
  (let [err (oci/session-error {:oci-config-file-profile "DEFAULT"}
                               (returning {:exit 1 :out "" :err ""}))]
    (is (some? err))
    (is (str/includes? err "DEFAULT"))
    (testing "the message is a command to run, not a description of the problem"
      (is (str/includes? err "refresh-oci-token")))))

(deftest session-validation-is-always-local
  (testing "plain `oci session validate` prompts on failure, which hangs a
           non-interactive caller — --local keeps it offline and silent"
    (let [seen (atom nil)]
      (oci/session-error {:oci-config-file-profile "P"} (recording seen {:exit 0}))
      (is (= ["session" "validate" "--profile" "P" "--local"] @seen)))))

(deftest the-profile-defaults-to-default
  (is (= "DEFAULT" (oci/profile {})))
  (is (= "WALTER" (oci/profile {:oci-config-file-profile "WALTER"}))))

;; ---------------------------------------------------------------------------
;; power

(deftest stop-is-a-soft-shutdown
  (testing "SOFTSTOP lets the guest flush and unmount; STOP is the power cord,
           and this machine holds a day's uncommitted work"
    (let [seen (atom nil)]
      (oci/power! {} :stop "ocid1.instance.oc1..x" (recording seen {:exit 0}))
      (is (str/includes? (str/join " " @seen) "--action SOFTSTOP"))
      (is (not (str/includes? (str/join " " @seen) "--action STOP "))))))

(deftest start-asks-for-start
  (let [seen (atom nil)]
    (oci/power! {} :start "ocid1.instance.oc1..x" (recording seen {:exit 0}))
    (is (str/includes? (str/join " " @seen) "--action START"))))

(deftest power-always-waits-for-the-transition
  (testing "the CLI returns as soon as the request is accepted, so without this
           stop would report success on a still-running machine"
    (let [seen (atom nil)]
      (oci/power! {} :stop "ocid1.instance.oc1..x" (recording seen {:exit 0}))
      (is (str/includes? (str/join " " @seen) "--wait-for-state STOPPED"))
      (is (str/includes? (str/join " " @seen) "--max-wait-seconds 300")))
    (let [seen (atom nil)]
      (oci/power! {} :start "ocid1.instance.oc1..x" (recording seen {:exit 0}))
      (is (str/includes? (str/join " " @seen) "--wait-for-state RUNNING")))))

(deftest the-wait-is-configurable
  (let [seen (atom nil)]
    (oci/power! {:power-wait-seconds 60} :stop "ocid1.instance.oc1..x"
                (recording seen {:exit 0}))
    (is (str/includes? (str/join " " @seen) "--max-wait-seconds 60"))))

(deftest power-acts-on-the-ocid-it-is-given
  (let [seen (atom nil)]
    (oci/power! {} :stop "ocid1.instance.oc1.eu-frankfurt-1.aaaaexample"
                (recording seen {:exit 0}))
    (is (str/includes? (str/join " " @seen)
                       "--instance-id ocid1.instance.oc1.eu-frankfurt-1.aaaaexample"))))

;; ---------------------------------------------------------------------------
;; reads

(deftest public-ip-answers-nil-when-the-call-fails
  (is (nil? (oci/public-ip {} "ocid1.instance.oc1..x"
                           (returning {:exit 1 :out "" :err "boom"}))))
  (is (= "203.0.113.7"
         (oci/public-ip {} "ocid1.instance.oc1..x"
                        (returning {:exit 0 :out "{\"data\":[{\"public-ip\":\"203.0.113.7\"}]}"})))))

(deftest lifecycle-state-answers-nil-when-the-call-fails
  (is (nil? (oci/lifecycle-state {} "ocid1.instance.oc1..x" (returning {:exit 255}))))
  (is (= "STOPPED"
         (oci/lifecycle-state {} "ocid1.instance.oc1..x"
                              (returning {:exit 0
                                          :out "{\"data\":{\"lifecycle-state\":\"STOPPED\"}}"})))))
