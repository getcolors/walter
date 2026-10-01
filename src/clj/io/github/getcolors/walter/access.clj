(ns io.github.getcolors.walter.access
  "Encrypted SSH authority and a workflow-scoped agent; never an ambient agent."
  (:require [cheshire.core :as json] [green.scope :as scope] [green.cli :as cli] [clojure.string :as str] [green.process :as process] [clojure.java.io :as io]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.compute-node :as library]
            [io.github.getcolors.compute-local :as local]
            [io.github.getcolors.walter.compute :as compute])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files FileAlreadyExistsException StandardOpenOption LinkOption OpenOption]))
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

(defn install-lock!
  "Serialize durable exports and aliases across workdirs until workflow exit."
  ([opts] (install-lock! opts (or (System/getenv "HOME") (System/getProperty "user.home"))))
  ([opts home]
   (when-not *register!* (throw (ex-info "Walter installation requires an access scope" {})))
   (when-not (and (string? (:profile opts)) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,62}" (:profile opts)))
     (throw (ex-info "invalid SSH deployment alias" {})))
   (let [directory (local/path (str (io/file home ".ssh")))
         path (.resolve directory (str ".walter-install-" (:profile opts) ".lock"))
         nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
         owner (System/getProperty "user.name")
         safe-file! (fn []
                      (when-not (and (Files/isRegularFile path nofollow)
                                     (= owner (str (Files/getOwner path nofollow)))
                                     (= 1 (Files/getAttribute path "unix:nlink" nofollow)))
                        (throw (ex-info "unsafe Walter installation lock" {}))))]
     (local/private-directory! directory)
     (when-not (= owner (str (Files/getOwner directory nofollow)))
       (throw (ex-info "unsafe SSH directory owner" {})))
     (Files/setPosixFilePermissions directory (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))
     (try (Files/createFile path (local/attrs "rw-------"))
          (catch FileAlreadyExistsException _ nil))
     (safe-file!)
     (let [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))]
       (try
         (safe-file!)
         (Files/setPosixFilePermissions path (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
         (let [lock (.tryLock channel)]
           (when-not lock (throw (ex-info "another Walter operation owns this SSH installation" {})))
           (*register!* :resource #(.close channel)))
         (catch Exception e (.close channel) (throw e)))))))

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

(defn connection-step
  "Resolve an owned machine's live address before unlocking its SSH identity."
  ([opts] (connection-step opts library/resolve-connection!))
  ([opts run-fn]
   (if (compute/planning? opts) opts
     (let [result (run-fn (compute/library-options opts) (compute/request opts))]
       (if (= "ready" (:status result))
         (assoc opts :colors-compute/node (:params result) :green/exit 0)
         (failed-result opts result))))))

(defn ssh-step
  ([opts] (ssh-step opts process/run-inherit))
  ([opts run-fn]
   (let [seats (:users opts)
         seats (if (sequential? seats) seats (str/split (str seats) #"\s+"))
         node (:colors-compute/node opts)
         login (or *seat* (compute/login node))]
     (cond
       (and *seat* (not (some #{*seat*} seats)))
       (assoc opts :green/exit 2 :green/err "SSH seat must be one of the configured users")

       (compute/planning? opts) opts

       (some #(or (not (string? %)) (str/blank? %))
             [(:ip node) login (:ssh-private-key-path opts) (:walter/agent-socket opts)])
       (assoc opts :green/exit 1 :green/err "SSH requires a resolved address, login and scoped identity")

       :else
       (let [result (run-fn (into ["ssh" "-F" "/dev/null" "-p" "22" "-l" login
                                  "-o" "StrictHostKeyChecking=accept-new" "-o" "IdentityFile=none"]
                                 (concat (identity-args opts) ["--" (:ip node)])) {})]
         (assoc opts :green/exit (or (:exit result) 1)))))))
(defn export-directory [opts]
  (str (io/file (or (System/getenv "HOME") (System/getProperty "user.home"))
                ".ssh" "walter" (:profile opts))))

(defn export-operation
  ([opts operation] (export-operation opts operation ssh/ssh-export!))
  ([opts operation run-fn]
   (run-fn (compute/library-options opts)
           (cond-> (compute/ssh-request opts)
             (:walter/ssh-resource opts) (assoc :expected (:walter/ssh-resource opts)))
           (export-directory opts) operation (if (= operation "install") (System/getenv)
                                 (select-keys (into {} (System/getenv)) ["PATH" "HOME" "TMPDIR"])))))

(defn installed-identity [opts]
  (when-not (compute/planning? opts)
    (install-lock! opts)
    (let [result (export-operation opts "inspect")]
      (case (:status result)
        "installed" (:private_key_file result)
        "absent" nil
        (throw (ex-info (or (get-in result [:error :message]) "Invalid installed SSH identity") {}))))))

(defn config-payload [opts mode identity]
  (let [node (:colors-compute/node opts)
        profile (:profile opts)
        seats (:users opts)
        seats (if (sequential? seats) seats (remove str/blank? (str/split (str seats) #"\s+")))]
    {:host_alias profile :block_state mode :keygen true :installed true
     :identity_file (or identity "") :legacy_marker_prefix "walter"
     :ssh_hosts (if (= mode "absent") []
                  (vec (cons {:name profile :ip (:ip node) :user (compute/login node)}
                         (map (fn [seat] {:name (str profile "-" seat) :ip (:ip node) :user seat}) seats))))}))

(defn update-config
  ([payload] (update-config payload process/run))
  ([payload run-fn]
   (run-fn ["python3" "-c"
            (str "import io, sys\nsys.stdin = io.StringIO(sys.argv[1])\n"
                 (slurp (io/resource "io/github/getcolors/walter/ssh_config.py")))
            (json/generate-string payload)] {})))

(defn- config-failure [opts result]
  (assoc opts :green/exit (or (:exit result) 1)
         :green/err (str "SSH config update failed: " (:err result))))

(defn install-step
  ([opts] (install-step opts export-operation update-config))
  ([opts export-fn config-fn]
   (if (compute/planning? opts) opts
     ;; Validate collisions before retrieving/writing a durable key. A later
     ;; config failure deliberately leaves an owned, encrypted export for retry.
     (let [_ (install-lock! opts)
           preflight (config-fn (assoc (config-payload opts "present"
                                       (str (io/file (export-directory opts) "identity"))) :check_only true))]
       (if-not (zero? (or (:exit preflight) 1)) (config-failure opts preflight)
         (let [exported (export-fn opts "install")]
           (if-not (= "installed" (:status exported)) (failed-result opts exported)
             (let [result (config-fn (config-payload opts "present" (:private_key_file exported)))]
               (if (zero? (or (:exit result) 1)) (assoc opts :green/exit 0)
                 (config-failure opts result))))))))))

(defn uninstall-step
  ([opts] (uninstall-step opts export-operation update-config))
  ([opts export-fn config-fn]
   (if (compute/planning? opts) opts
     (do
       (lock! opts)
       (install-lock! opts)
       (let [exported (export-fn opts "inspect")]
         (if-not (#{"installed" "absent"} (:status exported)) (failed-result opts exported)
           ;; Drop aliases first: an interrupted removal never leaves them
           ;; pointing at a deleted key. No backend/provider access is needed.
           (let [result (config-fn (config-payload opts "absent" nil))]
             (if-not (zero? (or (:exit result) 1)) (config-failure opts result)
               (let [removed (export-fn opts "remove")]
                 (if (#{"removed" "absent"} (:status removed)) (assoc opts :green/exit 0)
                   (failed-result opts removed)))))))))))

(defn run-cli [workflow args]
  (let [seat (when (and (= "ssh" (first args)) (second args)
                        (not (str/starts-with? (second args) "-"))) (second args))
        args (if seat (cons (first args) (drop 2 args)) args)]
    (binding [*seat* seat]
      (scoped #(cli/run-cli workflow args)))))
