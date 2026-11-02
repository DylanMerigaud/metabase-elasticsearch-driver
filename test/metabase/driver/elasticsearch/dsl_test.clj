(ns metabase.driver.elasticsearch.dsl-test
  (:require [clojure.test :refer [deftest is]]
            [metabase.driver.elasticsearch.dsl :as dsl]
            [metabase.driver.elasticsearch.test-util :as tu]))

(deftest dsl-query-test
  (is (dsl/dsl-query? "  {\"index\": \"orders\"}"))
  (is (not (dsl/dsl-query? "SELECT 1")))
  (is (not (dsl/dsl-query? nil))))

(deftest parse-test
  (is (= {:index "orders" :body {"query" {"match_all" {}} "size" 5}}
         (dsl/parse "{\"index\": \"orders\", \"query\": {\"match_all\": {}}, \"size\": 5}")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names its index"
                        (dsl/parse "{\"query\": {}}")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not valid JSON"
                        (dsl/parse "{\"index\": "))))

(deftest hits-test
  (is (= {:columns ["_id" "customer" "total" "address.city" "address.zip" "paid"]
          :rows    [["1" "alice" 12.5 "Lyon" "69001" nil]
                    ["2" "bob" nil nil nil true]]}
         (dsl/response->result (tu/fixture-json "search-hits.json")))))

(deftest bucket-aggregations-test
  (is (= {:columns ["by_customer" "doc_count" "revenue" "by_month"]
          :rows    [["alice" 1 12.5 "2024-03-01T00:00:00.000Z"]
                    ["alice" 1 7.5 "2024-04-01T00:00:00.000Z"]
                    ["bob" 1 7.0 "2024-03-01T00:00:00.000Z"]]}
         (dsl/response->result (tu/fixture-json "search-aggs.json")))
      "one row per leaf bucket; the deepest level's count and metrics win"))

(deftest metric-aggregations-test
  (is (= {:columns ["avg_total" "max_total"] :rows [[8.25 12.5]]}
         (dsl/response->result (tu/fixture-json "search-metrics.json")))))

(deftest column-base-types-test
  (is (= [:type/Text :type/BigInteger :type/Float :type/Boolean :type/* :type/Float]
         (dsl/column-base-types {:columns ["a" "b" "c" "d" "e" "f"]
                                 :rows    [["x" 1 1.5 true nil 2]
                                           ["y" 2 nil false nil 2.5]]}))))
