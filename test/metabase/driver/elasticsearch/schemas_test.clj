(ns metabase.driver.elasticsearch.schemas-test
  "Every public function of the pure namespaces honours its Malli schema on the recorded data."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [metabase.driver.elasticsearch.client]
            [metabase.driver.elasticsearch.dsl]
            [metabase.driver.elasticsearch.sync]
            [metabase.driver.elasticsearch.types]
            [metabase.driver.elasticsearch.values]
            [metabase.driver.elasticsearch.test-util]))

(def ^:private namespaces
  '#{metabase.driver.elasticsearch.client metabase.driver.elasticsearch.dsl
     metabase.driver.elasticsearch.sync metabase.driver.elasticsearch.types
     metabase.driver.elasticsearch.values})

(deftest every-public-function-has-a-schema
  (let [schemas (m/function-schemas)]
    (doseq [ns-sym namespaces
            [fn-sym v] (ns-publics ns-sym)
            :when (fn? @v)]
      (is (get-in schemas [ns-sym fn-sym]) (str ns-sym "/" fn-sym " has no m/=> schema")))))

(deftest schemas-are-valid-malli
  (doseq [ns-sym namespaces
          [_ {:keys [schema]}] (get (m/function-schemas) ns-sym)]
    (is (m/schema? (m/schema schema)))))
