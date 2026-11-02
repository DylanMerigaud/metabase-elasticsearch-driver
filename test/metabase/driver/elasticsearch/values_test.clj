(ns metabase.driver.elasticsearch.values-test
  (:require [clojure.test :refer [deftest is]]
            [metabase.driver.elasticsearch.test-util]
            [metabase.driver.elasticsearch.values :as values])
  (:import (java.time Instant LocalDate LocalDateTime OffsetDateTime ZoneOffset)))

(deftest datetime-literal-test
  (is (= "CAST('2024-03-01T10:00:00.000Z' AS DATETIME)"
         (values/datetime-literal (OffsetDateTime/of 2024 3 1 11 0 0 0 (ZoneOffset/ofHours 1)))))
  (is (= "CAST('2024-03-01T00:00:00.000Z' AS DATETIME)" (values/datetime-literal (LocalDate/of 2024 3 1))))
  (is (= "CAST('2024-03-01T08:30:00.000Z' AS DATETIME)"
         (values/datetime-literal (LocalDateTime/of 2024 3 1 8 30))))
  (is (= "CAST('1970-01-01T00:00:00.000Z' AS DATETIME)" (values/datetime-literal Instant/EPOCH)))
  (is (nil? (values/datetime-literal "2024-03-01"))))

(deftest param-test
  (is (= [nil "x" 1 2.5 true "2024-03-01T00:00:00.000Z" "kw" "00000000-0000-0000-0000-000000000042"]
         (mapv values/param [nil "x" 1 2.5 true (LocalDate/of 2024 3 1) :kw (java.util.UUID/fromString "00000000-0000-0000-0000-000000000042")]))))

(deftest parse-datetime-test
  (is (= (OffsetDateTime/of 2024 3 1 10 0 0 0 ZoneOffset/UTC)
         (values/parse-datetime "2024-03-01T10:00:00.000Z")))
  (is (= (OffsetDateTime/of 2024 3 1 0 0 0 0 ZoneOffset/UTC) (values/parse-datetime "2024-03-01")))
  (is (= "not a date" (values/parse-datetime "not a date")))
  (is (nil? (values/parse-datetime nil))))
