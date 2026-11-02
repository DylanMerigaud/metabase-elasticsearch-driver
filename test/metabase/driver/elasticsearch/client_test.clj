(ns metabase.driver.elasticsearch.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [metabase.driver.elasticsearch.client :as es]
            [metabase.driver.elasticsearch.test-util :as tu]))

(deftest base-url-test
  (is (= "http://localhost:9200" (es/base-url {:host "localhost"})))
  (is (= "https://es.example.com:9243" (es/base-url {:host "es.example.com" :port 9243 :ssl true})))
  (is (= "https://es.example.com:9200" (es/base-url {:host "https://es.example.com/"})))
  (is (= "http://es:9201" (es/base-url {:host "http://es:9201" :port 9200})))
  (is (= "http://localhost:9200" (es/base-url {:host " localhost " :port ""}))))

(deftest auth-headers-test
  (is (= {} (es/auth-headers {:host "h"})))
  (is (= {"Authorization" "Basic ZWxhc3RpYzpzZWNyZXQ="}
         (es/auth-headers {:host "h" :user "elastic" :password "secret"})))
  (testing "an API key wins over basic auth"
    (is (= {"Authorization" "ApiKey abc=="}
           (es/auth-headers {:host "h" :user "elastic" :password "x" :api-key " abc== "})))))

(deftest sql-body-test
  (is (= {"query" "SELECT 1" "fetch_size" 1000 "time_zone" "Z" "field_multi_value_leniency" true}
         (es/sql-body "SELECT 1" nil 1000)))
  (is (= [5 "x"] (get (es/sql-body "SELECT * FROM t WHERE a > ? AND b = ?" [5 "x"] 10) "params"))))

(deftest fetch-size-test
  (is (= 1000 (es/fetch-size {:host "h"})))
  (is (= 250 (es/fetch-size {:host "h" :fetch-size 250})))
  (is (= 50 (es/fetch-size {:host "h" :fetch-size "50"})))
  (is (= 1000 (es/fetch-size {:host "h" :fetch-size "-3"}))))

(defn- client [log & responses]
  (es/client {:host "es" :user "elastic" :password "pw" :fetch-size 2}
             (tu/fake-transport log responses)))

(deftest query-pages-through-the-cursor
  (let [log    (atom [])
        c      (client log
                       {:status 200 :body (tu/fixture "sql-page-1.json")}
                       {:status 200 :body (tu/fixture "sql-page-2.json")})
        page   (es/query c "SELECT customer, total, placed_at FROM orders" nil)
        rows   (into [] (es/reducible-rows c page))]
    (is (= [{:name "customer" :type "keyword"} {:name "total" :type "double"}
            {:name "placed_at" :type "datetime"}]
           (:columns page)))
    (is (= ["alice" "bob" "carol"] (map first rows)))
    (testing "the first request carries the query and the page size, the second only the cursor"
      (is (= [:post :post] (map :method @log)))
      (is (= ["http://es:9200/_sql?format=json" "http://es:9200/_sql?format=json"] (map :url @log)))
      (is (= 2 (get-in @log [0 :body "fetch_size"])))
      (is (= {"cursor" "c2Vjb25kLXBhZ2U="} (get-in @log [1 :body])))
      (is (= "application/json" (get-in @log [0 :headers "Content-Type"])))
      (is (= "Basic ZWxhc3RpYzpwdw==" (get-in @log [0 :headers "Authorization"]))))))

(deftest early-stop-closes-the-cursor
  (let [log  (atom [])
        c    (client log
                     {:status 200 :body (tu/fixture "sql-page-1.json")}
                     {:status 200 :body "{\"succeeded\":true}"})
        page (es/query c "SELECT * FROM orders" nil)
        rows (into [] (take 1) (es/reducible-rows c page))]
    (is (= 1 (count rows)))
    (is (= "http://es:9200/_sql/close" (:url (last @log))))
    (is (= {"cursor" "c2Vjb25kLXBhZ2U="} (:body (last @log))))
    (is (= 2 (count @log)) "no next page is fetched after the reader stopped")))

(deftest a-failing-next-page-closes-the-cursor-and-throws
  (let [log  (atom [])
        c    (client log
                     {:status 200 :body (tu/fixture "sql-page-1.json")}
                     {:status 500 :body "{\"error\":{\"type\":\"x\",\"reason\":\"node left\"},\"status\":500}"}
                     {:status 200 :body "{\"succeeded\":true}"})
        page (es/query c "SELECT * FROM orders" nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"node left"
                          (into [] (es/reducible-rows c page))))
    (is (= "http://es:9200/_sql/close" (:url (last @log))))))

(deftest errors-carry-the-elasticsearch-reason
  (let [c (client (atom []) {:status 400 :body (tu/fixture "error-verification.json")})]
    (try
      (es/query c "SELECT nope FROM orders" nil)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (re-find #"Unknown column \[nope\]" (ex-message e)))
        (is (= {:status 400 :error-type "verification_exception"} (ex-data e))))))
  (testing "a join is refused with the engine's own words"
    (is (re-find #"JOIN are not yet supported"
                 (es/error-message 400 (tu/fixture "error-join.json")))))
  (testing "a body that is not JSON"
    (is (= "Elasticsearch answered HTTP 502: Bad Gateway" (es/error-message 502 "Bad Gateway")))))

(deftest search-encodes-the-index
  (let [log (atom [])
        c   (client log {:status 200 :body (tu/fixture "search-hits.json")})]
    (es/search c "logs-*,orders" {"query" {"match_all" {}}})
    (is (= "http://es:9200/logs-*%2Corders/_search" (:url (first @log))))
    (is (= {"query" {"match_all" {}}} (:body (first @log))))))
