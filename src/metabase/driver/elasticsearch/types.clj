(ns metabase.driver.elasticsearch.types
  "Elasticsearch and OpenSearch field types to Metabase base types. Pure: no Metabase dependency.

  The same names appear in the `mapping` column of `SHOW COLUMNS` and in index mappings (sync), and
  in the `type` of each column an SQL API returns (query results; OpenSearch says `timestamp` for a
  date field)."
  (:require [malli.core :as m]))

(def ^:private base-types
  {"keyword"          :type/Text
   "constant_keyword" :type/Text
   "wildcard"         :type/Text
   "text"             :type/Text
   "string"           :type/Text
   "match_only_text"  :type/Text
   "version"          :type/Text
   "ip"               :type/IPAddress
   "long"             :type/BigInteger
   "unsigned_long"    :type/BigInteger
   "integer"          :type/Integer
   "short"            :type/Integer
   "byte"             :type/Integer
   "double"           :type/Float
   "float"            :type/Float
   "half_float"       :type/Float
   "scaled_float"     :type/Float
   "boolean"          :type/Boolean
   "datetime"         :type/DateTimeWithLocalTZ
   "timestamp"        :type/DateTimeWithLocalTZ
   "date"             :type/DateTimeWithLocalTZ
   "date_nanos"       :type/DateTimeWithLocalTZ
   "time"             :type/Time
   "geo_point"        :type/Text
   "geo_shape"        :type/Text
   "shape"            :type/Text
   "point"            :type/Text})

(defn base-type
  "The Metabase base type of an Elasticsearch type name (case insensitive), `:type/*` when unknown."
  [es-type]
  (get base-types (some-> es-type str .toLowerCase) :type/*))

(m/=> base-type [:=> [:cat [:maybe :string]] :keyword])

(def ^:private unqueryable
  #{"object" "nested" "unsupported" "null" "binary" "join" "flattened" "dense_vector"
    "sparse_vector" "rank_feature" "rank_features" "aggregate_metric_double" "histogram"
    "percolator" "completion" "search_as_you_type" "alias" "flat_object" "knn_vector"
    "semantic"})

(defn queryable?
  "Whether a column of this Elasticsearch type can be selected through the SQL API. Objects and
  nested fields are not columns themselves (their leaves are), and the SQL API refuses the rest."
  [es-type]
  (and (some? es-type) (not (contains? unqueryable (.toLowerCase ^String es-type)))))

(m/=> queryable? [:=> [:cat [:maybe :string]] :boolean])

(def ^:private temporal #{"datetime" "timestamp" "date" "date_nanos"})

(defn temporal?
  "Whether values of this Elasticsearch type are date-times (returned as ISO-8601 strings)."
  [es-type]
  (contains? temporal (some-> es-type str .toLowerCase)))

(m/=> temporal? [:=> [:cat [:maybe :string]] :boolean])
