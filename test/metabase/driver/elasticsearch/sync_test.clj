(ns metabase.driver.elasticsearch.sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [metabase.driver.elasticsearch.sync :as es.sync]
            [metabase.driver.elasticsearch.test-util :as tu]))

(defn- page [file]
  (let [j (tu/fixture-json file)]
    {:columns (mapv (fn [c] {:name (get c "name")}) (get j "columns"))
     :rows    (get j "rows")}))

(deftest table-names-test
  (testing "no pattern: every index and alias but the dot-prefixed ones, sorted and distinct"
    (is (= ["logs" "logs-2024.03" "orders"] (es.sync/table-names (page "show-tables.json") nil))))
  (testing "a comma separated list of globs"
    (is (= ["logs-2024.03" "orders"]
           (es.sync/table-names (page "show-tables.json") "logs-*, orders")))
    (is (= [".kibana_1"] (es.sync/table-names (page "show-tables.json") ".kibana*")))))

(deftest matches-pattern-test
  (is (es.sync/matches-pattern? "*" "anything"))
  (is (not (es.sync/matches-pattern? "logs" "logs-1")))
  (is (es.sync/matches-pattern? "a.b*" "a.bc"))
  (is (not (es.sync/matches-pattern? "a.b*" "axbc")) "a dot is literal"))

(deftest fields-test
  (let [fields (es.sync/fields (page "show-columns.json"))]
    (is (= ["customer" "notes" "notes.keyword" "total" "quantity" "placed_at" "paid" "client_ip"
            "address.city" "address.zip"]
           (map :name fields))
        "objects, nested fields and their leaves, and unsupported types are not columns")
    (is (= (range 10) (map :database-position fields)))
    (is (= {:name "placed_at" :database-type "datetime" :base-type :type/DateTimeWithLocalTZ
            :database-position 5}
           (nth fields 5)))
    (is (= [:type/Text :type/Text :type/Text :type/Float :type/Integer :type/DateTimeWithLocalTZ
            :type/Boolean :type/IPAddress :type/Text :type/Text]
           (map :base-type fields)))))

(deftest a-page-without-the-expected-column-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"without a column column"
                        (es.sync/fields {:columns [{:name "x"}] :rows [["a"]]}))))

(deftest quote-identifier-test
  (is (= "\"logs-2024.03\"" (es.sync/quote-identifier "logs-2024.03")))
  (is (= "\"a\"\"b\"" (es.sync/quote-identifier "a\"b"))))
