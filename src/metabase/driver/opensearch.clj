(ns metabase.driver.opensearch
  "The OpenSearch driver: OpenSearch 2 and 3 through the SQL plugin (`POST /_plugins/_sql`, JDBC
  response format), a child of the Elasticsearch driver.

  It keeps the parent's features, its query DSL native queries and its execution, and changes
  what the two engines do differently:

  - sync reads `_cat/indices`, `_cat/aliases` and the index mappings (the plugin has no SHOW
    COLUMNS of what SQL can select); multi-fields are left out, the plugin cannot select them;
  - identifiers are quoted with backticks (a double quoted text is a string to the plugin);
  - date buckets use DATE_FORMAT, DATE_SUB and the extract functions (the plugin has no
    DATE_TRUNC); weeks start on Sunday;
  - query parameters are written into the SQL as literals (the plugin's new engine takes none);
  - a later page of a paginated question is an OFFSET, which the plugin has;
  - a grouped question sorted on its group keys gets a last sort key, COUNT(*), so the plugin
    sorts the groups itself: when every sort key is a group key it hands the sort to a composite
    aggregation, which orders a computed key (an hour, a day of month) as text, 10 before 7."
  (:require
   [metabase.driver :as driver]
   [metabase.driver.common :as driver.common]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.elasticsearch]
   [metabase.driver.elasticsearch.client :as es]
   [metabase.driver.elasticsearch.sync :as es.sync]
   [metabase.driver.elasticsearch.values :as values]
   [metabase.driver.sql.query-processor :as sql.qp])
  (:import
   (java.net URLEncoder)
   (java.time Instant LocalDate LocalDateTime OffsetDateTime ZonedDateTime)))

(set! *warn-on-reflection* true)

(driver/register! :opensearch, :parent #{:elasticsearch})

(defmethod driver/db-start-of-week :opensearch
  [_driver]
  ;; DAYOFWEEK is 1 for Sunday, and the week bucket below starts on it
  :sunday)

;;; ------------------------------------------------- connection --------------------------------------------------

(defn- client [details]
  (es/client details nil :opensearch))

(defmethod driver/can-connect? :opensearch
  [_driver details]
  (let [c (client details)]
    (es/check-engine! c)
    (= 1 (count (:rows (es/query c "SELECT 1" nil))))))

;;; ---------------------------------------------------- sync -----------------------------------------------------

(defmethod driver/describe-database* :opensearch
  [_driver database]
  (let [details (driver.conn/effective-details database)
        c       (client details)
        indices (es/call! c :get "/_cat/indices?format=json&h=index,status" nil)
        aliases (es/call! c :get "/_cat/aliases?format=json&h=alias" nil)]
    {:tables (set (for [table-name (es.sync/cat-table-names (vec indices) (vec aliases)
                                                            (:index-pattern details))]
                    {:name table-name :schema nil :description nil}))}))

(defmethod driver/describe-table :opensearch
  [_driver database {table-name :name}]
  (let [c        (client (driver.conn/effective-details database))
        mappings (es/call! c :get (str "/" (URLEncoder/encode ^String table-name "UTF-8") "/_mapping")
                           nil)]
    {:name   table-name
     :schema nil
     :fields (set (es.sync/mapping-fields mappings))}))

;;; ----------------------------------------------- SQL compilation -----------------------------------------------

(defmethod sql.qp/quote-style :opensearch
  [_driver]
  :mysql)

(defn- datetime-literal [t]
  [:raw (values/opensearch-datetime-literal t)])

(defmethod sql.qp/->honeysql [:opensearch OffsetDateTime] [_driver t] (datetime-literal t))
(defmethod sql.qp/->honeysql [:opensearch ZonedDateTime]  [_driver t] (datetime-literal t))
(defmethod sql.qp/->honeysql [:opensearch LocalDateTime]  [_driver t] (datetime-literal t))
(defmethod sql.qp/->honeysql [:opensearch LocalDate]      [_driver t] (datetime-literal t))
(defmethod sql.qp/->honeysql [:opensearch Instant]        [_driver t] (datetime-literal t))

(defmethod sql.qp/add-interval-honeysql-form :opensearch
  [_driver hsql-form amount unit]
  [:date_add hsql-form [:interval (if (number? amount) [:inline amount] amount) unit]])

(defn- format-trunc
  "`expr` truncated by writing it with a DATE_FORMAT `pattern` and reading it back as a timestamp."
  [pattern expr]
  [:timestamp [:date_format expr [:inline pattern]]])

(defmethod sql.qp/date [:opensearch :default] [_ _ expr] expr)
(defmethod sql.qp/date [:opensearch :second]  [_ _ expr] (format-trunc "%Y-%m-%d %H:%i:%s" expr))
(defmethod sql.qp/date [:opensearch :minute]  [_ _ expr] (format-trunc "%Y-%m-%d %H:%i:00" expr))
(defmethod sql.qp/date [:opensearch :hour]    [_ _ expr] (format-trunc "%Y-%m-%d %H:00:00" expr))
(defmethod sql.qp/date [:opensearch :day]     [_ _ expr] (format-trunc "%Y-%m-%d 00:00:00" expr))
(defmethod sql.qp/date [:opensearch :month]   [_ _ expr] (format-trunc "%Y-%m-01 00:00:00" expr))
(defmethod sql.qp/date [:opensearch :year]    [_ _ expr] (format-trunc "%Y-01-01 00:00:00" expr))
(defmethod sql.qp/date [:opensearch :quarter]
  [_ _ expr]
  ;; the first day of the month, minus the months since the quarter started
  [:date_sub (format-trunc "%Y-%m-01 00:00:00" expr)
   [:interval [:mod [:- [:month expr] [:inline 1]] [:inline 3]] :month]])
(defmethod sql.qp/date [:opensearch :week]
  [driver _ expr]
  (sql.qp/adjust-start-of-week
   driver
   (fn [e] [:timestamp [:date_sub [:date e] [:interval [:- [:dayofweek e] [:inline 1]] :day]]])
   expr))

(defmethod sql.qp/date [:opensearch :second-of-minute] [_ _ expr] [:second expr])
(defmethod sql.qp/date [:opensearch :minute-of-hour]   [_ _ expr] [:minute expr])
(defmethod sql.qp/date [:opensearch :hour-of-day]      [_ _ expr] [:hour expr])
(defmethod sql.qp/date [:opensearch :day-of-month]     [_ _ expr] [:dayofmonth expr])
(defmethod sql.qp/date [:opensearch :day-of-year]      [_ _ expr] [:dayofyear expr])
(defmethod sql.qp/date [:opensearch :week-of-year-iso] [_ _ expr] [:week expr [:inline 3]])
(defmethod sql.qp/date [:opensearch :month-of-year]    [_ _ expr] [:month expr])
(defmethod sql.qp/date [:opensearch :quarter-of-year]  [_ _ expr] [:quarter expr])
(defmethod sql.qp/date [:opensearch :year-of-era]      [_ _ expr] [:year expr])
(defmethod sql.qp/date [:opensearch :day-of-week]
  [driver _ expr]
  (sql.qp/adjust-day-of-week driver [:dayofweek expr]
                             (driver.common/start-of-week-offset-for-day :sunday)))

(defmethod sql.qp/apply-top-level-clause [:opensearch :order-by]
  [driver clause honeysql-form query]
  (let [form ((get-method sql.qp/apply-top-level-clause [:elasticsearch :order-by])
              driver clause honeysql-form query)]
    (if (and (seq (:breakout query)) (seq (:order-by form)))
      (update form :order-by #(conj (vec %) [[:count :*] :asc]))
      form)))

;; The plugin has LIMIT and OFFSET: every page of a paginated question can be read.
(defmethod sql.qp/apply-top-level-clause [:opensearch :page]
  [driver clause honeysql-form query]
  ((get-method sql.qp/apply-top-level-clause [:sql :page]) driver clause honeysql-form query))
