(ns io.github.getcolors.walter.vultr-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.walter.vultr :as vultr]))

(def instance-id "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")

(defn response
  [state ip]
  {:exit 0
   :out (str "{\"instance\":{\"power_status\":\"" state
             "\",\"main_ip\":\"" ip "\"}}")})

(deftest parses-live-instance-fields
  (is (= "running" (vultr/power-state (:out (response "running" "203.0.113.8")))))
  (is (= "203.0.113.8" (vultr/parse-public-ip
                         (:out (response "running" "203.0.113.8")))))
  (is (nil? (vultr/parse-instance "not json"))))

(deftest credentials-never-have-a-fallback
  (is (some? (vultr/credential-error {})))
  (is (nil? (vultr/credential-error {:vultr-api-key "secret"}))))

(deftest stop-is-a-graceful-halt-and-waits
  (let [seen (atom [])
        request (fn [_ method path]
                  (swap! seen conj [method path])
                  (if (= method :post) {:exit 0 :out ""}
                      (response "stopped" "203.0.113.8")))]
    (is (= 0 (:exit (vultr/power! {} :stop instance-id request (fn [_])))))
    (is (= [[:post (str "/instances/" instance-id "/halt")]
            [:get (str "/instances/" instance-id)]]
           @seen))))

(deftest start-polls-until-running
  (let [states (atom ["stopped" "running"])
        sleeps (atom [])
        request (fn [_ method _]
                  (if (= method :post)
                    {:exit 0 :out ""}
                    (response (let [s (first @states)]
                                (swap! states rest)
                                s)
                              "203.0.113.9")))]
    (is (= 0 (:exit (vultr/power! {:power-wait-seconds 10} :start instance-id
                                   request #(swap! sleeps conj %)))))
    (is (= [5000] @sleeps))))

(deftest failed-actions-and-timeouts-are-reported
  (testing "the action failure wins before polling"
    (let [result (vultr/power! {} :start instance-id
                               (fn [_ _ _] {:exit 1 :err "denied"})
                               (fn [_]))]
      (is (= 1 (:exit result)))
      (is (= "denied" (:err result)))))
  (testing "a state that never changes times out"
    (let [result (vultr/wait-for-state
                  {:power-wait-seconds 1} instance-id "running"
                  (fn [_ _ _] (response "stopped" "203.0.113.9"))
                  (fn [_]))]
      (is (= 1 (:exit result)))
      (is (re-find #"did not reach running" (:err result))))))

(deftest public-ip-is-read-from-the-live-instance
  (is (= "203.0.113.10"
         (vultr/public-ip {} instance-id
                          (fn [_ _ _] (response "running" "203.0.113.10")))))
  (is (nil? (vultr/public-ip {} instance-id
                             (fn [_ _ _] {:exit 1 :out ""})))))
