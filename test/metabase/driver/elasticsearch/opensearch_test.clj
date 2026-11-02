(ns metabase.driver.elasticsearch.opensearch-test
  "The OpenSearch side of the pure namespaces: the SQL plugin's requests and answers, sync from
  the mappings, literals and the engine check, on recorded OpenSearch 2.19 answers."
  (:require [clojure.test :refer [deftest is testing]]
            [metabase.driver.elasticsearch.client :as es]
            [metabase.driver.elasticsearch.sync :as es.sync]
            [metabase.driver.elasticsearch.test-util :as tu]
            [metabase.driver.elasticsearch.types :as types]
            [metabase.driver.elasticsearch.values :as values])
  (:import (java.time LocalDate OffsetDateTime ZoneOffset)))

(defn- os-client [log engine & responses]
  (es/client {:host "os" :fetch-size 2} (tu/fake-transport log responses) engine))

(deftest query-uses-the-sql-plugin
  (let [log  (atom [])
        c    (os-client log :opensearch {:status 200 :body (tu/fixture "opensearch-page.json")})
        page (es/query c "SELECT customer, SUM(total) AS spent, MAX(placed_at) AS last FROM orders WHERE customer IN (?, ?) GROUP BY customer"
                       ["alice" "bob"])]
    (testing "the JDBC format, the parameters written in, no fetch_size"
      (is (= "http://os:9200/_plugins/_sql?format=jdbc" (:url (first @log))))
      (is (= {"query" "SELECT customer, SUM(total) AS spent, MAX(placed_at) AS last FROM orders WHERE customer IN ('alice', 'bob') GROUP BY customer"}
             (:body (first @log)))))
    (testing "a column is named by its alias, rows come from datarows"
      (is (= [{:name "customer" :type "keyword"} {:name "spent" :type "double"}
              {:name "last" :type "timestamp"}]
             (:columns page)))
      (is (= [["alice" 20.0 "2024-04-02 09:00:00"] ["bob" 41.0 "2024-05-21 12:00:00"]]
             (:rows page)))
      (is (nil? (:cursor page))))))

(deftest opensearch-cursor-paths
  (let [log  (atom [])
        c    (os-client log :opensearch
                        {:status 200 :body "{\"schema\":[{\"name\":\"n\",\"type\":\"long\"}],\"datarows\":[[1]],\"cursor\":\"n:abc\"}"}
                        {:status 200 :body "{\"succeeded\":true}"})
        page (es/query c "SELECT n FROM many" nil)]
    (is (= [[1]] (into [] (take 1) (es/reducible-rows c page))))
    (is (= "http://os:9200/_plugins/_sql/close" (:url (last @log))))
    (is (= {"cursor" "n:abc"} (:body (last @log))))))

(deftest opensearch-errors-carry-the-details
  (let [c (os-client (atom []) :opensearch {:status 400 :body (tu/fixture "opensearch-error.json")})]
    (try
      (es/query c "SELECT nope FROM orders" nil)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Invalid SQL query: can't resolve Symbol(namespace=FIELD_NAME, name=nope) in type env"
               (ex-message e)))
        (is (= {:status 400 :error-type "SemanticCheckException"} (ex-data e))))))
  (testing "only the first line of the details"
    (is (= (str "Error occurred in OpenSearch engine: no such index [nope_index]: "
                "[nope_index] IndexNotFoundException[no such index [nope_index]]")
           (es/error-message 404 (tu/fixture "opensearch-error-index.json") :opensearch))))
  (testing "a body that is not JSON names the engine"
    (is (= "OpenSearch answered HTTP 502: Bad Gateway"
           (es/error-message 502 "Bad Gateway" :opensearch)))))

(deftest check-engine-names-the-right-driver
  (testing "an OpenSearch cluster added as Elasticsearch"
    (let [c (os-client (atom []) :elasticsearch {:status 200 :body (tu/fixture "opensearch-root.json")})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"This cluster is OpenSearch, not Elasticsearch: add it as an OpenSearch database"
                            (es/check-engine! c)))))
  (testing "an Elasticsearch cluster added as OpenSearch"
    (let [c (os-client (atom []) :opensearch {:status 200 :body (tu/fixture "elasticsearch-root.json")})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"This cluster is Elasticsearch, not OpenSearch"
                            (es/check-engine! c)))))
  (testing "the right engine passes, and so does a cluster that does not describe itself"
    (is (true? (es/check-engine! (os-client (atom []) :opensearch
                                            {:status 200 :body (tu/fixture "opensearch-root.json")}))))
    (is (true? (es/check-engine! (os-client (atom []) :elasticsearch
                                            {:status 403 :body "{\"error\":\"forbidden\"}"}))))))

(deftest distribution-test
  (is (= :opensearch (es/distribution (tu/fixture-json "opensearch-root.json"))))
  (is (= :elasticsearch (es/distribution (tu/fixture-json "elasticsearch-root.json"))))
  (is (= :elasticsearch (es/distribution nil))))

(deftest cat-table-names-test
  (let [indices (tu/fixture-json "cat-indices.json")
        aliases (tu/fixture-json "cat-aliases.json")]
    (testing "open indices and aliases, no dot-prefixed index, no closed one"
      (is (= ["events-2024" "orders" "orders-alias"] (es.sync/cat-table-names indices aliases nil))))
    (is (= ["orders" "orders-alias"] (es.sync/cat-table-names indices aliases "orders*")))))

(deftest mapping-fields-test
  (let [fields (es.sync/mapping-fields (tu/fixture-json "opensearch-mapping.json"))]
    (is (= ["address.city" "address.geo.zip" "customer" "notes" "paid" "placed_at" "quantity"
            "total" "refund"]
           (map :name fields))
        "object leaves by dotted name; no nested field, multi-field or vector; a second index adds its own fields")
    (is (= (range 9) (map :database-position fields)))
    (is (= {:name "placed_at" :database-type "date" :base-type :type/DateTimeWithLocalTZ
            :database-position 5}
           (nth fields 5)))
    (is (= "keyword" (:database-type (nth fields 2))) "the first index in name order wins a name")))

(deftest opensearch-literal-test
  (is (= ["NULL" "TRUE" "FALSE" "42" "2.5" "0.1" "'it''s'" "'kw'"
          "TIMESTAMP('2024-03-01 10:00:00.000')" "100000000000000000000"]
         (mapv values/opensearch-literal
               [nil true false 42 2.5 0.1 "it's" :kw
                (OffsetDateTime/of 2024 3 1 11 0 0 0 (ZoneOffset/ofHours 1))
                1e20])))
  (is (= "TIMESTAMP('2024-03-01 00:00:00.000')" (values/opensearch-literal (LocalDate/of 2024 3 1))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"backslash"
                        (values/opensearch-literal "a\\' OR 1=1 --"))))

(deftest splice-params-test
  (is (= "SELECT * FROM t WHERE a = 'x' AND b > 3" (values/splice-params "SELECT * FROM t WHERE a = ? AND b > ?" ["x" 3])))
  (testing "a ? in a string, an identifier or a comment is not a placeholder"
    (is (= "SELECT '?', `a?`, \"?\" FROM t -- ?\nWHERE c = 1 /* ? */ AND d = 'it''s ?' AND e = 2"
           (values/splice-params
            "SELECT '?', `a?`, \"?\" FROM t -- ?\nWHERE c = ? /* ? */ AND d = 'it''s ?' AND e = ?"
            [1 2]))))
  (is (= "SELECT 1" (values/splice-params "SELECT 1" nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"more \? placeholders"
                        (values/splice-params "SELECT ? , ?" [1])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fewer \? placeholders"
                        (values/splice-params "SELECT ?" [1 2]))))

(deftest opensearch-values-test
  (is (= (OffsetDateTime/of 2024 3 1 10 0 0 0 ZoneOffset/UTC)
         (values/parse-datetime "2024-03-01 10:00:00")))
  (is (= (OffsetDateTime/of 2024 3 1 10 0 0 123000000 ZoneOffset/UTC)
         (values/parse-datetime "2024-03-01 10:00:00.123")))
  (is (= :type/DateTimeWithLocalTZ (types/base-type "timestamp")))
  (is (types/temporal? "timestamp"))
  (is (not (types/queryable? "knn_vector"))))
