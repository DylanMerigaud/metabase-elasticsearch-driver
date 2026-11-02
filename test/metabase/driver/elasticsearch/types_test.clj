(ns metabase.driver.elasticsearch.types-test
  (:require [clojure.test :refer [deftest is]]
            [metabase.driver.elasticsearch.test-util]
            [metabase.driver.elasticsearch.types :as types]))

(deftest base-type-test
  (is (= :type/Text (types/base-type "keyword")))
  (is (= :type/Text (types/base-type "TEXT")))
  (is (= :type/BigInteger (types/base-type "long")))
  (is (= :type/Float (types/base-type "scaled_float")))
  (is (= :type/DateTimeWithLocalTZ (types/base-type "datetime")))
  (is (= :type/* (types/base-type "something_new")))
  (is (= :type/* (types/base-type nil))))

(deftest queryable-test
  (is (types/queryable? "keyword"))
  (is (not (types/queryable? "object")))
  (is (not (types/queryable? "nested")))
  (is (not (types/queryable? "unsupported")))
  (is (not (types/queryable? nil))))
