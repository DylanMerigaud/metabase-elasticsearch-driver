(ns metabase.driver.elasticsearch.sync
  "Tables and fields from the answers of `SHOW TABLES` and `SHOW COLUMNS IN <table>`. Pure: the
  driver runs the two statements through the SQL API, these functions read their pages.

  Reading the catalogue through the SQL API, rather than the raw `_mapping`, means sync sees exactly
  the columns the SQL engine can query: multi-fields such as `title.keyword` included, objects
  flattened to their leaves with dotted names."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [metabase.driver.elasticsearch.types :as types]))

(def Page
  "An SQL answer page: column names and rows."
  [:map
   [:columns [:sequential [:map [:name :string]]]]
   [:rows [:sequential [:sequential :any]]]])

(def Field
  "One synced field, in the shape Metabase's describe-table expects."
  [:map
   [:name :string]
   [:database-type :string]
   [:base-type :keyword]
   [:database-position :int]])

(defn- column-index [page col-name]
  (first (keep-indexed (fn [i c] (when (= col-name (:name c)) i)) (:columns page))))

(defn- column-values [page col-name]
  (if-let [i (column-index page col-name)]
    (map #(nth % i) (:rows page))
    (throw (ex-info (str "The SQL API answered without a " col-name " column")
                    {:columns (mapv :name (:columns page))}))))

(defn- glob->regex [glob]
  (re-pattern (str "^" (str/join ".*" (map #(java.util.regex.Pattern/quote %)
                                           (str/split glob #"\*" -1)))
                   "$")))

(defn matches-pattern?
  "Whether an index or alias `name` matches `pattern`: a comma separated list of globs (`*` matches
  anything). A blank pattern keeps every name that does not start with a dot (system and hidden
  indices)."
  [pattern table-name]
  (let [globs (remove str/blank? (map str/trim (str/split (str pattern) #",")))]
    (if (empty? globs)
      (not (str/starts-with? table-name "."))
      (boolean (some #(re-matches (glob->regex %) table-name) globs)))))

(m/=> matches-pattern? [:=> [:cat [:maybe :string] :string] :boolean])

(defn table-names
  "The sorted, distinct table names of a `SHOW TABLES` page kept by `pattern`."
  [page pattern]
  (->> (column-values page "name")
       (map str)
       (filter #(matches-pattern? pattern %))
       distinct
       sort
       vec))

(m/=> table-names [:=> [:cat Page [:maybe :string]] [:vector :string]])

(defn fields
  "The fields of a `SHOW COLUMNS` page: every leaf the SQL API can select, in mapping order. Objects
  are dropped (their leaves stay), and so is everything under a nested field (the SQL API cannot
  select a nested leaf together with other columns)."
  [page]
  (let [names    (map str (column-values page "column"))
        mappings (map #(some-> % str) (column-values page "mapping"))
        nested   (set (keep (fn [[n t]] (when (= "nested" t) n)) (map vector names mappings)))
        under?   (fn [n] (some #(str/starts-with? n (str % ".")) nested))]
    (->> (map vector names mappings)
         (remove (fn [[n t]] (or (not (types/queryable? t)) (under? n))))
         (map-indexed (fn [i [n t]]
                        {:name              n
                         :database-type     t
                         :base-type         (types/base-type t)
                         :database-position i}))
         vec)))

(m/=> fields [:=> [:cat Page] [:vector Field]])

(defn quote-identifier
  "An index name quoted for the SQL API (double quotes, an inner double quote doubled)."
  [s]
  (str \" (str/replace s "\"" "\"\"") \"))

(m/=> quote-identifier [:=> [:cat :string] :string])
