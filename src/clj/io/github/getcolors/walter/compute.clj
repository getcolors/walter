(ns io.github.getcolors.walter.compute
  "Walter owns one stable v2 node and its public SSH identity."
  (:require [clojure.java.io :as io] [clojure.string :as str]
            [green.cli :as cli]
            [io.github.getcolors.compute :as library]
            [io.github.getcolors.compute-node :as node-api]
            [io.github.getcolors.compute-ssh :as ssh]))

(def node-id "walter-compute")
(def state-filename "walter-node-0.tfstate")
(defn sdk-workdir [opts]
  (-> (cli/stage-dir opts node-id) io/file .getAbsoluteFile .getParentFile .getParentFile .getCanonicalPath))
(defn library-options [opts]
  (dissoc opts :ssh-private-key-path :ssh-public-key-path :colors-compute/node :walter-ssh-passphrase))
(def placeholder-resource
  {:status "ready" :reference "ssh-resource:build-placeholder"
   :public_key "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
   :fingerprint "SHA256:kmYcvdi2GkPeWxB6XLjrZB8JHsy2Hm8luHMFp9GMvqk"})
(defn planning? [opts] (or (= :build (:green/event opts)) (:green/dry-run opts)))
(defn resource [opts]
  (or (:walter/ssh-resource opts) (when (planning? opts) placeholder-resource)
      (throw (ex-info "SSH resource unavailable" {}))))
(defn registration? [opts]
  (boolean (get-in library/registry [:compute (keyword (:provider-compute opts)) :registration])))
(defn ssh-request [opts]
  {:name "machine-access" :workdir (sdk-workdir opts)
   :passphrase_env "COLORS_PAR_WALTER_SSH_PASSPHRASE"})
(defn registration-request [opts]
  {:name "machine-access" :workdir (sdk-workdir opts)
   :state_filename "walter-ssh-registration.tfstate" :ssh_resource (resource opts)})
(defn request [opts]
  (let [provider (:provider-compute opts)
        sources (or (:walter-ssh-sources opts) (get opts (keyword (str provider "-ssh-sources"))) ["0.0.0.0/0"])
        sources (if (string? sources) (vec (remove str/blank? (str/split sources #"[,\s]+"))) sources)]
    (cond-> {:node_id node-id :state_filename state-filename :workdir (sdk-workdir opts)
             :ssh_resource (resource opts)
             :security {:egress "all" :private_filter false
                        :ingress [{:id "ssh" :protocol "tcp" :from_port 22 :to_port 22 :sources sources}]}}
      (#{"hcloud" "vultr" "digitalocean"} provider) (assoc :network {:mode "none"})
      (registration? opts)
      (assoc :ssh_registration
             (or (:walter/ssh-registration opts)
                 (when (planning? opts)
                   {:status "ready" :reference "registration:build-placeholder" :provider provider
                    :ssh_resource_reference (:reference (resource opts))
                    :fingerprint (:fingerprint (resource opts)) :id "0"}))))))
(defn plan [opts]
  (let [opts (assoc opts :green/dry-run true)]
    (ssh/ssh-plan (library-options opts) (ssh-request opts))
    (node-api/node-plan (library-options opts) (request opts))))
(defn placeholder-key [opts]
  (str "/home/build-placeholder/compute/" (:profile opts) "/ssh/machine-access/identity.pub"))
(defn placeholder-node [opts]
  {:node_id node-id :provider (:provider-compute opts) :ip "192.0.2.10"
   :user (get-in library/registry [:compute (keyword (:provider-compute opts)) :user])})
(defn node [opts]
  (or (:colors-compute/node opts) (when (planning? opts) (placeholder-node opts))
      (throw (ex-info "compute result unavailable; refusing placeholder inventory" {}))))
(defn login [node] (if (= "root" (:user node)) "ubuntu" (:user node)))
