(ns metabase.driver.elasticsearch
  "The Metabase driver: Elasticsearch through its SQL REST API, on the `:sql` parent.

  Metabase compiles questions to SQL (HoneySQL) as for any SQL database; this namespace runs that
  SQL on `POST /_sql`, pages through the cursor, syncs indices and their columns through `SHOW
  TABLES` and `SHOW COLUMNS`, and runs native queries that start with `{` as query DSL searches.
  Joins, subqueries and the features the SQL API lacks are declared unsupported, so Metabase does
  not offer them."
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.common :as driver.common]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.elasticsearch.client :as es]
   [metabase.driver.elasticsearch.dsl :as dsl]
   [metabase.driver.elasticsearch.sync :as es.sync]
   [metabase.driver.elasticsearch.types :as types]
   [metabase.driver.elasticsearch.values :as values]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.honey-sql-2 :as h2x])
  (:import
   (java.time Instant LocalDate LocalDateTime OffsetDateTime ZonedDateTime)))

(set! *warn-on-reflection* true)

(driver/register! :elasticsearch, :parent #{:sql})

;;; ------------------------------------------------- features ----------------------------------------------------

(doseq [[feature supported?] {;; the SQL API has no joins and no subquery in FROM
                              :left-join                          false
                              :right-join                         false
                              :inner-join                         false
                              :full-join                          false
                              :nested-queries                     false
                              :native-parameter-card-reference    false
                              :foreign-keys                       false
                              :metadata/key-constraints           false
                              :parameters/table-reference         false
                              ;; an index has no schema
                              :schemas                            false
                              ;; not offered by the SQL API, or not wired yet
                              :window-functions/cumulative        false
                              :window-functions/offset            false
                              :percentile-aggregations            false
                              :standard-deviation-aggregations    false
                              :advanced-math-expressions          false
                              :expression-aggregations            false
                              :distinct-where                     false
                              :binning                            false
                              :regex                              false
                              :expressions                        false
                              :date-arithmetics                   false
                              :datetime-diff                      false
                              :convert-timezone                   false
                              :set-timezone                       false
                              :dependencies/native                false
                              :database-routing                   false
                              :upload-with-auto-pk                false
                              :saved-question-sandboxing          false
                              ;; supported
                              :basic-aggregations                 true
                              :temporal-extract                   true
                              :native-parameters                  true}]
  (defmethod driver/database-supports? [:elasticsearch feature] [_driver _feature _db] supported?))

(defmethod driver/db-start-of-week :elasticsearch
  [_driver]
  ;; DATE_TRUNC('week', ...) starts weeks on Monday
  :monday)

(defmethod driver/db-default-timezone :elasticsearch
  [_driver _database]
  ;; every request asks for UTC (`time_zone: Z`)
  "UTC")

;;; ------------------------------------------------- connection --------------------------------------------------

(defn- details-of [database]
  (driver.conn/effective-details database))

(defmethod driver/can-connect? :elasticsearch
  [_driver details]
  ;; a trivial statement proves the cluster answers, the credentials work and the SQL API is enabled
  (let [{:keys [rows]} (es/query (es/client details) "SELECT 1" nil)]
    (= 1 (count rows))))

;;; ---------------------------------------------------- sync -----------------------------------------------------

(defmethod driver/describe-database* :elasticsearch
  [_driver database]
  (let [details (details-of database)
        page    (es/all-rows (es/client details) "SHOW TABLES")]
    {:tables (set (for [table-name (es.sync/table-names page (:index-pattern details))]
                    {:name table-name :schema nil :description nil}))}))

(defmethod driver/describe-table :elasticsearch
  [_driver database {table-name :name}]
  (let [page (es/all-rows (es/client (details-of database))
                          (str "SHOW COLUMNS IN " (es.sync/quote-identifier table-name)))]
    {:name   table-name
     :schema nil
     :fields (set (es.sync/fields page))}))

;;; ----------------------------------------------- SQL compilation -----------------------------------------------

;; There is one table per query (no joins, no nested queries), so a field is its bare name. The SQL
;; API resolves a dotted name such as "user.name" to the object leaf, quoted as one identifier.
(defmethod sql.qp/->honeysql [:elasticsearch ::h2x/identifier]
  [_driver [tag identifier-type components :as identifier]]
  (if (and (= :field identifier-type) (> (count components) 1))
    [tag identifier-type [(last components)]]
    identifier))

(defn- datetime-literal [t]
  [:raw (values/datetime-literal t)])

(defmethod sql.qp/->honeysql [:elasticsearch OffsetDateTime] [_driver t] (datetime-literal t))
(defmethod sql.qp/->honeysql [:elasticsearch ZonedDateTime]  [_driver t] (datetime-literal t))
(defmethod sql.qp/->honeysql [:elasticsearch LocalDateTime]  [_driver t] (datetime-literal t))
(defmethod sql.qp/->honeysql [:elasticsearch LocalDate]      [_driver t] (datetime-literal t))
(defmethod sql.qp/->honeysql [:elasticsearch Instant]        [_driver t] (datetime-literal t))

(defmethod sql.qp/current-datetime-honeysql-form :elasticsearch
  [_driver]
  [:raw "NOW()"])

(defmethod sql.qp/add-interval-honeysql-form :elasticsearch
  [_driver hsql-form amount unit]
  [:date_add [:inline (name unit)] (if (number? amount) [:inline amount] amount) hsql-form])

(defn- trunc [unit expr]
  [:date_trunc [:inline (name unit)] expr])

(defmethod sql.qp/date [:elasticsearch :default]         [_ _ expr] expr)
(defmethod sql.qp/date [:elasticsearch :second]          [_ _ expr] (trunc :second expr))
(defmethod sql.qp/date [:elasticsearch :minute]          [_ _ expr] (trunc :minute expr))
(defmethod sql.qp/date [:elasticsearch :hour]            [_ _ expr] (trunc :hour expr))
(defmethod sql.qp/date [:elasticsearch :day]             [_ _ expr] (trunc :day expr))
(defmethod sql.qp/date [:elasticsearch :month]           [_ _ expr] (trunc :month expr))
(defmethod sql.qp/date [:elasticsearch :quarter]         [_ _ expr] (trunc :quarter expr))
(defmethod sql.qp/date [:elasticsearch :year]            [_ _ expr] (trunc :year expr))
(defmethod sql.qp/date [:elasticsearch :week]
  [driver _ expr]
  (sql.qp/adjust-start-of-week driver (partial trunc :week) expr))

(defmethod sql.qp/date [:elasticsearch :second-of-minute] [_ _ expr] [:second_of_minute expr])
(defmethod sql.qp/date [:elasticsearch :minute-of-hour]   [_ _ expr] [:minute_of_hour expr])
(defmethod sql.qp/date [:elasticsearch :hour-of-day]      [_ _ expr] [:hour_of_day expr])
(defmethod sql.qp/date [:elasticsearch :day-of-month]     [_ _ expr] [:day_of_month expr])
(defmethod sql.qp/date [:elasticsearch :day-of-year]      [_ _ expr] [:day_of_year expr])
(defmethod sql.qp/date [:elasticsearch :week-of-year-iso] [_ _ expr] [:iso_week_of_year expr])
(defmethod sql.qp/date [:elasticsearch :month-of-year]    [_ _ expr] [:month_of_year expr])
(defmethod sql.qp/date [:elasticsearch :quarter-of-year]  [_ _ expr] [:quarter expr])
(defmethod sql.qp/date [:elasticsearch :year-of-era]      [_ _ expr] [:year expr])
(defmethod sql.qp/date [:elasticsearch :day-of-week]
  [driver _ expr]
  ;; DAY_OF_WEEK is 1 for Sunday
  (sql.qp/adjust-day-of-week driver [:day_of_week expr]
                             (driver.common/start-of-week-offset-for-day :sunday)))

;; In ORDER BY the SQL API binds a name to a SELECT alias before a field: with a group-by on
;; DAY_OF_WEEK("placed_at") AS "placed_at", an ORDER BY DAY_OF_WEEK("placed_at") would read the
;; integer alias. Sorting by the column alias itself is what was meant.
(defmethod sql.qp/apply-top-level-clause [:elasticsearch :order-by]
  [driver clause honeysql-form query]
  ((get-method sql.qp/apply-top-level-clause [:sql :order-by])
   driver clause honeysql-form
   (update query :order-by sql.qp/rewrite-fields-to-force-using-column-aliases)))

;; The SQL API has LIMIT but no OFFSET: the first page is a LIMIT, a later one is refused clearly.
(defmethod sql.qp/apply-top-level-clause [:elasticsearch :page]
  [driver _clause honeysql-form {{:keys [items page]} :page}]
  (if (= 1 page)
    ((get-method sql.qp/apply-top-level-clause [:sql :limit])
     driver :limit honeysql-form {:limit items})
    (throw (ex-info (str "Elasticsearch SQL has no OFFSET: only the first page of a paginated "
                         "question can be read")
                    {:page page :items items}))))

;;; -------------------------------------------------- execution --------------------------------------------------

(defn- column-metadata [{:keys [name type]}]
  {:name          name
   :base_type     (types/base-type type)
   :database_type (str type)})

(defn- row-parser [columns]
  (let [temporal (set (keep-indexed (fn [i c] (when (types/temporal? (:type c)) i)) columns))]
    (if (empty? temporal)
      identity
      (fn [row]
        (into [] (map-indexed (fn [i v] (if (temporal i) (values/parse-datetime v) v))) row)))))

(defn- execute-sql [client sql params respond]
  (let [page (es/query client sql (mapv values/param params))]
    (respond {:cols (mapv column-metadata (:columns page))}
             (eduction (map (row-parser (:columns page)))
                       (es/reducible-rows client page)))))

(defn- execute-dsl [client native respond]
  (let [{:keys [index body]} (dsl/parse native)
        {:keys [columns rows] :as result} (dsl/response->result (es/search client index body))]
    (respond {:cols (mapv (fn [col-name base-type] {:name col-name :base_type base-type})
                          columns (dsl/column-base-types result))}
             rows)))

(defmethod driver/execute-reducible-query :elasticsearch
  [_driver {{native :query, :keys [params]} :native} _context respond]
  (let [database (driver-api/database (driver-api/metadata-provider))
        client   (es/client (details-of database))]
    (if (dsl/dsl-query? native)
      (execute-dsl client native respond)
      (execute-sql client (str/trim native) params respond))))
