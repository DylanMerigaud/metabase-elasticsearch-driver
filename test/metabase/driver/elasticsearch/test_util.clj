(ns metabase.driver.elasticsearch.test-util
  "Recorded responses and a fake transport for the unit tests. Loading it instruments every
  public function of the pure namespaces, so each test also checks the Malli schemas."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [malli.instrument :as mi]
            [metabase.driver.elasticsearch.client]
            [metabase.driver.elasticsearch.dsl]
            [metabase.driver.elasticsearch.sync]
            [metabase.driver.elasticsearch.types]
            [metabase.driver.elasticsearch.values]))

(mi/instrument! {:filters [(mi/-filter-ns 'metabase.driver.elasticsearch.client
                                          'metabase.driver.elasticsearch.dsl
                                          'metabase.driver.elasticsearch.sync
                                          'metabase.driver.elasticsearch.types
                                          'metabase.driver.elasticsearch.values)]})

(defn fixture
  "The text of test/fixtures/<file>."
  [file]
  (slurp (io/file "test/fixtures" file)))

(defn fixture-json
  "test/fixtures/<file> parsed."
  [file]
  (json/parse-string (fixture file)))

(defn fake-transport
  "A transport answering each request with the next of `responses` ({:status :body}), recording
  every request it receives into the atom `log`."
  [log responses]
  (let [queue (atom (vec responses))]
    (fn [req]
      (swap! log conj (cond-> req (:body req) (update :body json/parse-string)))
      (let [[r & more] @queue]
        (reset! queue (vec more))
        (or r (throw (ex-info "no more recorded responses" {:request req})))))))
