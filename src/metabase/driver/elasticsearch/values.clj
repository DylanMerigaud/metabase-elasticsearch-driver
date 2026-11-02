(ns metabase.driver.elasticsearch.values
  "Values in and out of the SQL API: query parameters to JSON, date-time strings to java.time, and
  date-time literals to SQL. Pure: no Metabase dependency."
  (:require [malli.core :as m])
  (:import (java.time Instant LocalDate LocalDateTime OffsetDateTime ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeParseException)))

(defn- iso-utc ^String [^OffsetDateTime t]
  (.format (.withOffsetSameInstant t ZoneOffset/UTC) DateTimeFormatter/ISO_OFFSET_DATE_TIME))

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
  "An ISO-8601 date-time string of the SQL API (`2024-03-01T10:00:00.000Z`) as an OffsetDateTime; a
  bare date as midnight UTC; anything unreadable is returned unchanged."
  [v]
  (if-not (string? v)
    v
    (try
      (OffsetDateTime/parse v)
      (catch DateTimeParseException _
        (try
          (.atOffset (.atStartOfDay (LocalDate/parse v)) ZoneOffset/UTC)
          (catch DateTimeParseException _
            v))))))

(m/=> parse-datetime [:=> [:cat :any] :any])
