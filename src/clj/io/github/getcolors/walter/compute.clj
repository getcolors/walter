(ns io.github.getcolors.walter.compute
  "One development host; provider provisioning belongs to colors-compute."
  (:require [io.github.getcolors.compute :as library]
            [io.github.getcolors.compute-deployment-request :as deployment]
            [io.github.getcolors.compute-planning :as planning]))
(defn topology [_] [{:role nil :count 1}])
(defn requirements [opts]
  {:single_host true :private false
   :legacy_state_keys [(str (:profile opts) "/walter-compute.tfstate")]
   :security {:egress "all" :private_filter false
              :ingress [{:id "ssh" :protocol "tcp" :from_port 22 :to_port 22
                         :sources (if (or (contains? opts :walter-ssh-sources) (contains? opts (keyword (str (:provider-compute opts) "-ssh-sources"))))
                                    (deployment/source-cidrs opts "ssh-sources" "walter-ssh-sources") ["0.0.0.0/0"])}]}})
(defn node [opts]
  (let [cluster (or (:colors-compute/cluster opts)
                    (when (or (= :build (:green/event opts)) (:green/dry-run opts))
                      (:cluster (planning/plan-deployment opts (topology opts) (requirements opts)))))]
    (when-not cluster (throw (ex-info "compute result unavailable; refusing placeholder inventory" {})))
    (first (:nodes (library/collect (mapv #(assoc % :provider (:provider-compute opts)) (library/expand (topology opts))) (:nodes cluster) "0")))))
(defn login [node]
  (if (= "root" (:user node)) "ubuntu" (:user node)))
