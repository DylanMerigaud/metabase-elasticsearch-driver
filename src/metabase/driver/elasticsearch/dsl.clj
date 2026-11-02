(ns metabase.driver.elasticsearch.dsl
  "The query DSL escape hatch of native queries. A native query whose text starts with `{` is a JSON
  object naming its `index` plus a search body (`query`, `aggs`, `size`, `sort`, `_source`...). It
  runs on `POST /<index>/_search` and its answer becomes rows:

  - with `aggs`, one row per leaf bucket: a column per bucket level (its key) and per metric;
  - without, one row per hit: `_id` then every `_source` field, objects flattened to dotted names.

  Pure: no Metabase and no network."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [malli.core :as m]))

(def Result
  "Rows with their column names."
  [:map
   [:columns [:vector :string]]
   [:rows [:vector [:vector :any]]]])

(defn dsl-query?
  "Whether a native query is a query DSL object rather than SQL."
  [native]
  (str/starts-with? (str/triml (str native)) "{"))

(m/=> dsl-query? [:=> [:cat [:maybe :string]] :boolean])

(defn parse
  "`{:index ..., :body ...}` of a DSL native query. Throws an ex-info saying what is missing when the
  text is not a JSON object with a non-blank `index`."
  [native]
  (let [parsed (try (json/parse-string native)
                    (catch Exception e
                      (throw (ex-info (str "The native query starts with { but is not valid JSON: "
                                           (ex-message e))
                                      {:native native}))))]
    (when-not (map? parsed)
      (throw (ex-info "A query DSL native query must be a JSON object" {:native native})))
    (let [index (get parsed "index")]
      (when (or (not (string? index)) (str/blank? index))
        (throw (ex-info (str "A query DSL native query names its index: "
                             "{\"index\": \"my-index\", \"query\": {...}}")
                        {:native native})))
      {:index index :body (dissoc parsed "index")})))

(m/=> parse [:=> [:cat :string] [:map [:index :string] [:body :map]]])

(defn- flatten-map
  ([m] (flatten-map nil m))
  ([prefix m]
   (reduce-kv (fn [acc k v]
                (let [k (if prefix (str prefix "." k) (str k))]
                  (if (map? v)
                    (into acc (flatten-map k v))
                    (conj acc [k v]))))
              [] m)))

(defn- ordered-union [seqs]
  (let [seen (volatile! #{})]
    (vec (for [s seqs, k s :when (not (@seen k))] (do (vswap! seen conj k) k)))))

(defn hits->result
  "Rows of the hits of a search answer: `_id` then the flattened `_source` fields, columns in the
  order they first appear."
  [response]
  (let [hits    (get-in response ["hits" "hits"])
        sources (mapv (fn [h] (into [["_id" (get h "_id")]]
                                    (flatten-map (get h "_source" {}))))
                      hits)
        columns (ordered-union (map #(map first %) sources))]
    {:columns columns
     :rows    (mapv (fn [pairs]
                      (let [m (into {} pairs)]
                        (mapv #(get m %) columns)))
                    sources)}))

(m/=> hits->result [:=> [:cat :map] Result])

(defn- bucket-agg? [v]
  (and (map? v) (contains? v "buckets")))

(defn- buckets [v]
  (let [b (get v "buckets")]
    ;; keyed buckets (`"keyed": true`, filters aggregations) are a map of key to bucket
    (if (map? b)
      (map (fn [[k bucket]] (assoc bucket "key" k)) b)
      b)))

(defn- metric-value [v]
  (cond
    (contains? v "value_as_string") (get v "value_as_string")
    (contains? v "value")           (get v "value")
    (contains? v "values")          (json/generate-string (get v "values"))
    :else                           (json/generate-string v)))

(defn- bucket-rows
  "Pairs [[column value]...] of every leaf below an aggregation level."
  [level-name agg]
  (for [bucket (buckets agg)
        :let [key-pair  [level-name (or (get bucket "key_as_string") (get bucket "key"))]
              subs      (dissoc bucket "key" "key_as_string" "doc_count" "from" "to"
                                "from_as_string" "to_as_string")
              sub-bucks (filter (fn [[_ v]] (bucket-agg? v)) subs)
              metrics   (->> subs
                             (remove (fn [[_ v]] (bucket-agg? v)))
                             (filter (fn [[_ v]] (map? v)))
                             (sort-by key)
                             (map (fn [[k v]] [k (metric-value v)])))
              own       (concat [key-pair ["doc_count" (get bucket "doc_count")]] metrics)]
        row (if (seq sub-bucks)
              (for [[sub-name sub-agg] (sort-by key sub-bucks)
                    sub-row (bucket-rows sub-name sub-agg)]
                (concat own sub-row))
              [own])]
    row))

(defn aggs->result
  "Rows of the aggregations of a search answer: one row per leaf bucket of each top level bucket
  aggregation, or a single row of the top level metrics when there is no bucket aggregation."
  [response]
  (let [aggs      (get response "aggregations" {})
        top-bucks (filter (fn [[_ v]] (bucket-agg? v)) (sort-by key aggs))
        pairs     (if (seq top-bucks)
                    (vec (for [[n agg] top-bucks, row (bucket-rows n agg)] row))
                    [(->> aggs (filter (fn [[_ v]] (map? v))) (sort-by key)
                          (map (fn [[k v]] [k (metric-value v)])))])
        columns   (ordered-union (map #(map first %) pairs))]
    {:columns columns
     :rows    (mapv (fn [row]
                      (let [m (into {} row)]
                        (mapv #(get m %) columns)))
                    pairs)}))

(m/=> aggs->result [:=> [:cat :map] Result])

(defn response->result
  "The rows of a search answer: its aggregations when it has some, else its hits."
  [response]
  (if (seq (get response "aggregations"))
    (aggs->result response)
    (hits->result response)))

(m/=> response->result [:=> [:cat :map] Result])

(defn- value-base-type [v]
  (cond
    (boolean? v)                             :type/Boolean
    (or (instance? Long v) (instance? Integer v)
        (instance? Short v) (instance? Byte v)) :type/BigInteger
    (integer? v)                             :type/BigInteger
    (number? v)                              :type/Float
    (string? v)                              :type/Text
    :else                                    :type/*))

(defn column-base-types
  "The Metabase base type of each column of a result, read from its non-nil values (`:type/*`
  for a column that is nil everywhere or mixes types)."
  [{:keys [columns rows]}]
  (vec (for [i (range (count columns))]
         (let [types (into #{} (comp (map #(nth % i)) (remove nil?) (map value-base-type)) rows)]
           (cond
             (= 1 (count types))                          (first types)
             (= types #{:type/BigInteger :type/Float})    :type/Float
             :else                                        :type/*)))))

(m/=> column-base-types [:=> [:cat Result] [:vector :keyword]])
