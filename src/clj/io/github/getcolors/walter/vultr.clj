(ns io.github.getcolors.walter.vultr
  "The Vultr HTTP API calls used by Walter's power verbs.

  Power state stays outside OpenTofu, just as it does on OCI: the compute
  template declares no power attribute, so stopping a machine out of band
  creates no drift. Requests use Java's HTTP client so the API token travels in
  an Authorization header without appearing in a process argument. Every
  operation has an injectable request arity for deterministic tests."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
    HttpResponse$BodyHandlers]
   [java.time Duration]))

(def api-root "https://api.vultr.com/v2")
(def default-wait-seconds 300)
(def poll-seconds 5)

(def actions
  {:stop  {:path "halt" :state "stopped"}
   :start {:path "start" :state "running"}})

(defn credential-error
  "A validation message when a real Vultr power operation has no API token."
  [opts]
  (when (str/blank? (str (:vultr-api-key opts)))
    "Vultr power operations need COLORS_PAR_VULTR_API_KEY"))

(defn request
  "Call the Vultr API and return {:exit :status :out :err}."
  [opts method path]
  (try
    (let [builder (doto (HttpRequest/newBuilder)
                    (.uri (URI/create (str api-root path)))
                    (.timeout (Duration/ofSeconds 30))
                    (.header "Authorization" (str "Bearer " (:vultr-api-key opts)))
                    (.header "Accept" "application/json"))
          req (case method
                :get (.GET builder)
                :post (.POST builder (HttpRequest$BodyPublishers/noBody)))
          response (.send (HttpClient/newHttpClient)
                          (.build req)
                          (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)
          body (.body response)]
      (if (<= 200 status 299)
        {:exit 0 :status status :out body :err ""}
        {:exit 1 :status status :out body
         :err (str "Vultr API returned HTTP " status
                   (when-not (str/blank? body) (str ": " body))) }))
    (catch Exception e
      {:exit 1 :out "" :err (str "Vultr API request failed: " (.getMessage e))})))

(defn parse-instance
  "The instance object from a Vultr GET response, or nil for malformed input."
  [out]
  (try
    (get (json/parse-string (str out)) "instance")
    (catch Exception _ nil)))

(defn power-state
  "The normalized power_status from a Vultr instance response."
  [out]
  (some-> (parse-instance out) (get "power_status") str str/lower-case not-empty))

(defn parse-public-ip
  "The current main_ip from a Vultr instance response."
  [out]
  (some-> (parse-instance out) (get "main_ip") str str/trim not-empty))

(defn instance
  "Read one instance live."
  ([opts instance-id] (instance opts instance-id request))
  ([opts instance-id request-fn]
   (request-fn opts :get (str "/instances/" instance-id))))

(defn wait-for-state
  "Poll until an instance reaches target. The sleep function is injectable."
  ([opts instance-id target] (wait-for-state opts instance-id target request #(Thread/sleep %)))
  ([opts instance-id target request-fn sleep-fn]
   (let [wait-seconds (or (:power-wait-seconds opts) default-wait-seconds)
         attempts (max 1 (inc (quot wait-seconds poll-seconds)))]
     (loop [remaining attempts]
       (let [{:keys [exit out] :as result} (instance opts instance-id request-fn)
             state (when (zero? exit) (power-state out))]
         (cond
           (not (zero? exit)) result
           (= target state) result
           (<= remaining 1)
           {:exit 1 :out out
            :err (str "Vultr instance " instance-id " did not reach " target
                      " within " wait-seconds " seconds (last state: "
                      (or state "unknown") ")")}
           :else (do (sleep-fn (* poll-seconds 1000))
                     (recur (dec remaining)))))))))

(defn power!
  "Request a Vultr power action and wait for its terminal state."
  ([opts verb instance-id] (power! opts verb instance-id request #(Thread/sleep %)))
  ([opts verb instance-id request-fn sleep-fn]
   (let [{:keys [path state]} (actions verb)
         acted (request-fn opts :post (str "/instances/" instance-id "/" path))]
     (if (zero? (:exit acted -1))
       (wait-for-state opts instance-id state request-fn sleep-fn)
       acted))))

(defn public-ip
  "The instance's current public address, read live."
  ([opts instance-id] (public-ip opts instance-id request))
  ([opts instance-id request-fn]
   (let [{:keys [exit out]} (instance opts instance-id request-fn)]
     (when (zero? exit) (parse-public-ip out)))))
