(ns metabase.driver.elasticsearch.values
  "Values in and out of the SQL APIs: query parameters to JSON (Elasticsearch) or to SQL literals
  (OpenSearch), date-time strings to java.time, and date-time literals to SQL. Pure: no Metabase
  dependency."
  (:require [clojure.string :as str]
            [malli.core :as m])
  (:import (java.time Instant LocalDate LocalDateTime OffsetDateTime ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeParseException)))

(def ^:private ^DateTimeFormatter iso-millis
  (DateTimeFormatter/ofPattern "uuuu-MM-dd'T'HH:mm:ss.SSSX"))

(defn- iso-utc ^String [^OffsetDateTime t]
  (.format (.withOffsetSameInstant t ZoneOffset/UTC) iso-millis))

(defn ->offset-date-time
  "A java.time value as an OffsetDateTime (a LocalDateTime and a LocalDate are read in UTC), or nil
  when `t` is not a date-time."
  [t]
  (condp instance? t
    OffsetDateTime t
    ZonedDateTime  (.toOffsetDateTime ^ZonedDateTime t)
    Instant        (.atOffset ^Instant t ZoneOffset/UTC)
    LocalDateTime  (.atOffset ^LocalDateTime t ZoneOffset/UTC)
    LocalDate      (.atOffset (.atStartOfDay ^LocalDate t) ZoneOffset/UTC)
    nil))

(m/=> ->offset-date-time [:=> [:cat :any] [:maybe [:fn #(instance? OffsetDateTime %)]]])

(defn datetime-literal
  "The SQL of a date-time value: `CAST('<ISO-8601 in UTC>' AS DATETIME)`. The text is formatted here
  from a java.time value, never taken from user input."
  [t]
  (when-let [odt (->offset-date-time t)]
    (str "CAST('" (iso-utc odt) "' AS DATETIME)")))

(m/=> datetime-literal [:=> [:cat :any] [:maybe :string]])

(defn param
  "A `?` placeholder value as the SQL API takes it in `params`: JSON scalars as they are, date-times
  as ISO-8601 strings in UTC, anything else as its string."
  [v]
  (cond
    (or (nil? v) (string? v) (number? v) (boolean? v)) v
    (->offset-date-time v) (iso-utc (->offset-date-time v))
    (keyword? v) (name v)
    :else (str v)))

(m/=> param [:=> [:cat :any] [:or :nil :string :boolean number?]])

(defn parse-datetime
  "A date-time string of an SQL API as an OffsetDateTime: ISO-8601 with an offset (Elasticsearch,
  `2024-03-01T10:00:00.000Z`), or without one, read in UTC (OpenSearch, `2024-03-01 10:00:00`); a
  bare date as midnight UTC; anything unreadable is returned unchanged."
  [v]
  (if-not (string? v)
    v
    (try
      (OffsetDateTime/parse v)
      (catch DateTimeParseException _
        (try
          (.atOffset (LocalDateTime/parse (str/replace-first v " " "T")) ZoneOffset/UTC)
          (catch DateTimeParseException _
            (try
              (.atOffset (.atStartOfDay (LocalDate/parse v)) ZoneOffset/UTC)
              (catch DateTimeParseException _
                v))))))))

(m/=> parse-datetime [:=> [:cat :any] :any])

;;; ------------------------------------------------- OpenSearch --------------------------------------------------
;; The OpenSearch SQL plugin takes no `params` on the queries its new engine runs, so values are
;; written into the SQL as literals, by this code only, from typed values.

(def ^:private ^DateTimeFormatter os-millis
  (DateTimeFormatter/ofPattern "uuuu-MM-dd HH:mm:ss.SSS"))

(defn opensearch-datetime-literal
  "The OpenSearch SQL of a date-time value: `TIMESTAMP('<yyyy-MM-dd HH:mm:ss.SSS in UTC>')`."
  [t]
  (when-let [odt (->offset-date-time t)]
    (str "TIMESTAMP('" (.format (.withOffsetSameInstant ^OffsetDateTime odt ZoneOffset/UTC) os-millis)
         "')")))

(m/=> opensearch-datetime-literal [:=> [:cat :any] [:maybe :string]])

(defn- string-literal [^String s]
  ;; OpenSearch 2 keeps a backslash as typed, OpenSearch 3 reads it as an escape: no spelling of a
  ;; backslash means the same on both, so a value carrying one is refused rather than altered.
  (when (str/includes? s "\\")
    (throw (ex-info (str "OpenSearch SQL cannot take a value containing a backslash as a "
                         "parameter: " (pr-str s))
                    {:value s})))
  (str "'" (str/replace s "'" "''") "'"))

(defn opensearch-literal
  "A query parameter as an OpenSearch SQL literal: NULL, TRUE and FALSE, numbers in plain notation,
  date-times as TIMESTAMP literals in UTC, anything else as a quoted string (a quote doubled; a
  backslash refused)."
  [v]
  (cond
    (nil? v)                  "NULL"
    (boolean? v)              (if v "TRUE" "FALSE")
    (integer? v)              (str v)
    (ratio? v)                (.toPlainString (bigdec v))
    (decimal? v)              (.toPlainString ^BigDecimal v)
    (number? v)               (if (or (Double/isNaN (double v)) (Double/isInfinite (double v)))
                                (throw (ex-info (str "Not a finite number: " v) {:value v}))
                                (.toPlainString (BigDecimal/valueOf (double v))))
    (->offset-date-time v)    (opensearch-datetime-literal v)
    (keyword? v)              (string-literal (name v))
    :else                     (string-literal (str v))))

(m/=> opensearch-literal [:=> [:cat :any] :string])

(defn splice-params
  "`sql` with each `?` placeholder replaced by the literal of the next of `params`. A `?` inside a
  quoted string, a quoted identifier or a comment is left alone. Throws when the counts differ."
  [sql params]
  (let [n   (count sql)
        out (StringBuilder.)]
    (loop [i 0, state :code, ps (seq params)]
      (if (>= i n)
        (do (when (seq ps)
              (throw (ex-info (str "The query has fewer ? placeholders than parameters ("
                                   (count params) ")")
                              {:sql sql :params (count params)})))
            (str out))
        (let [c  (.charAt ^String sql i)
              c2 (when (< (inc i) n) (.charAt ^String sql (inc i)))]
          (case state
            :code
            (cond
              (= c \?)  (if ps
                          (do (.append out (opensearch-literal (first ps)))
                              (recur (inc i) :code (next ps)))
                          (throw (ex-info (str "The query has more ? placeholders than parameters ("
                                               (count params) ")")
                                          {:sql sql :params (count params)})))
              (= c \')  (do (.append out c) (recur (inc i) :single ps))
              (= c \")  (do (.append out c) (recur (inc i) :double ps))
              (= c \`)  (do (.append out c) (recur (inc i) :backtick ps))
              (and (= c \-) (= c2 \-)) (do (.append out "--") (recur (+ i 2) :line ps))
              (and (= c \/) (= c2 \*)) (do (.append out "/*") (recur (+ i 2) :block ps))
              :else     (do (.append out c) (recur (inc i) :code ps)))

            (:single :double)
            (let [q (if (= state :single) \' \")]
              (cond
                (and (= c \\) c2)   (do (.append out c) (.append out c2) (recur (+ i 2) state ps))
                (and (= c q) (= c2 q)) (do (.append out c) (.append out c2) (recur (+ i 2) state ps))
                (= c q)              (do (.append out c) (recur (inc i) :code ps))
                :else                (do (.append out c) (recur (inc i) state ps))))

            :backtick
            (do (.append out c) (recur (inc i) (if (= c \`) :code :backtick) ps))

            :line
            (do (.append out c) (recur (inc i) (if (= c \newline) :code :line) ps))

            :block
            (if (and (= c \*) (= c2 \/))
              (do (.append out "*/") (recur (+ i 2) :code ps))
              (do (.append out c) (recur (inc i) :block ps)))))))))

(m/=> splice-params [:=> [:cat :string [:maybe [:sequential :any]]] :string])
