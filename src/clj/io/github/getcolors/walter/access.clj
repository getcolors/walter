(ns io.github.getcolors.walter.access
  "Encrypted SSH authority and a workflow-scoped agent; never an ambient agent."
  (:require [green.scope :as scope] [green.cli :as cli] [clojure.string :as str] [green.process :as process] [clojure.java.io :as io]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.compute-node :as library]
            [io.github.getcolors.compute-local :as local]
            [io.github.getcolors.walter.compute :as compute])
  (:import [java.nio.channels FileChannel]
           [java.nio.file StandardOpenOption LinkOption OpenOption]))
(def ^:dynamic *register!* nil)
(def ^:dynamic *seat* nil)
(defn scoped [f]
  (scope/with-scope (fn [register!] (binding [*register!* register!] (f)))))
(defn failed-result [opts result]
  (assoc opts :green/exit 1 :green/err
         (str (or (get-in result [:error :message]) "Compute operation refused")
              (when-let [detail (get-in result [:error :stderr])] (str "\n" detail)))))
(defn lock! [opts]
  (when-not *register!* (throw (ex-info "Walter runtime requires an access scope" {})))
  (let [root (compute/sdk-workdir opts)
        directory (local/path (str (io/file root (:profile opts))))
        path (.resolve directory ".walter.lock")]
    (local/private-owned-directory! root directory)
    (local/prepare! (str path))
    (let [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))]
      (try
        (local/prepare! (str path))
        (let [lock (.tryLock channel)]
          (when-not lock (throw (ex-info "another Walter operation owns this profile" {})))
          (*register!* :resource #(.close channel)))
        (catch Exception e (.close channel) (throw e))))))

(defn resource-step
  ([opts] (resource-step opts ssh/ssh-resource!))
  ([opts run-fn]
  (when-not (compute/planning? opts) (lock! opts))
  (let [existing? (.exists (io/file (compute/sdk-workdir opts) (:profile opts) compute/node-id "compute.tf.json"))
        operation (if (or existing? (:compute-require-existing-state opts) (not (#{:build :create} (:green/event opts)))) "inspect" "create")
        result (if (compute/planning? opts) compute/placeholder-resource
                   (run-fn (compute/library-options opts) (compute/ssh-request opts) operation (System/getenv)))]
    (if (= "ready" (:status result)) (assoc opts :walter/ssh-resource result :green/exit 0)
        (failed-result opts result)))))
(defn registration-step
  ([opts] (registration-step opts library/compute-registration!))
  ([opts run-fn]
  (if-not (compute/registration? opts) opts
    (let [result (if (compute/planning? opts)
                   (library/build-registration! (compute/library-options opts) (compute/registration-request opts))
                   (run-fn (compute/library-options opts) (compute/registration-request opts)
                     (if (= :create (:green/event opts)) "create" "inspect")))]
      (if (#{"ready" "built"} (:status result))
        (cond-> (assoc opts :green/exit 0) (= "ready" (:status result)) (assoc :walter/ssh-registration result))
        (failed-result opts result))))))
(defn agent-step
  ([opts] (agent-step opts ssh/start-agent!))
  ([opts run-fn]
  (if (compute/planning? opts)
    (assoc opts :ssh-private-key-path (compute/placeholder-key opts)
           :walter/agent-socket "/home/build-placeholder/agent.sock" :green/exit 0)
    (let [agent (run-fn [{:opts (compute/library-options opts) :request (compute/ssh-request opts)
                                  :resource (:walter/ssh-resource opts)}] (System/getenv) *register!*)]
      (assoc opts :walter/agent-socket (:socket agent)
             :ssh-private-key-path (get (:identities agent) (:reference (:walter/ssh-resource opts))) :green/exit 0)))))
(defn registration-delete-step
  ([opts] (registration-delete-step opts library/compute-registration!))
  ([opts run-fn]
  (if-not (compute/registration? opts) opts
    (let [result (run-fn (compute/library-options opts) (compute/registration-request opts) "delete")]
      (if (= "destroyed" (:status result)) (assoc opts :green/exit 0) (failed-result opts result))))))
(defn identity-args [opts]
  (when-let [identity (:ssh-private-key-path opts)]
    ["-i" identity "-o" "IdentitiesOnly=yes" "-o" (str "IdentityAgent=" (or (:walter/agent-socket opts) "none"))
     "-o" "ForwardAgent=no" "-o" "ControlMaster=no" "-o" "ControlPersist=no" "-S" "none"]))

(defn ssh-step
  ([opts] (ssh-step opts process/run-inherit))
  ([opts run-fn]
   (let [seats (:users opts)
         seats (if (sequential? seats) seats (str/split (str seats) #"\s+"))]
     (if (and *seat* (not (some #{*seat*} seats)))
       (assoc opts :green/exit 2 :green/err "SSH seat must be one of the configured users")
       (let [alias (str (:profile opts) (when *seat* (str "-" *seat*)))
             result (run-fn (into ["ssh"] (concat (identity-args opts) [alias])) {})]
         (assoc opts :green/exit (or (:exit result) 1)))))))

(defn run-cli [workflow args]
  (let [seat (when (and (= "ssh" (first args)) (second args)
                        (not (str/starts-with? (second args) "-"))) (second args))
        args (if seat (cons (first args) (drop 2 args)) args)]
    (binding [*seat* seat]
      (scoped #(cli/run-cli workflow args)))))
