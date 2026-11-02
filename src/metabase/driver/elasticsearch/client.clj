(ns metabase.driver.elasticsearch.client
  "The HTTP layer: requests to the SQL and search APIs of Elasticsearch and OpenSearch, cursor
  paging and error messages. No Metabase dependency: a client is a map of connection details, the
  engine and a transport function, so tests replace the network with recorded responses.

  Elasticsearch SQL API: https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-sql-query
  OpenSearch SQL plugin: https://docs.opensearch.org/latest/search-plugins/sql/sql-ppl-api/"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [metabase.driver.elasticsearch.values :as values])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util Base64)))

(def Details
  "Connection details, as Metabase stores them from the connection form."
  [:map
   [:host :string]
   [:port {:optional true} [:maybe [:or :int :string]]]
   [:ssl {:optional true} [:maybe :boolean]]
   [:user {:optional true} [:maybe :string]]
   [:password {:optional true} [:maybe :string]]
   [:api-key {:optional true} [:maybe :string]]
   [:index-pattern {:optional true} [:maybe :string]]
   [:fetch-size {:optional true} [:maybe [:or :int :string]]]])

(def Request
  "One HTTP request, as a transport receives it."
  [:map
   [:method [:enum :get :post]]
   [:url :string]
   [:headers [:map-of :string :string]]
   [:body {:optional true} [:maybe :string]]])

(def Response
  "One HTTP response, as a transport returns it."
  [:map [:status :int] [:body [:maybe :string]]])

(def Engine
  "Which SQL API the cluster speaks."
  [:enum :elasticsearch :opensearch])

(def Client
  "Connection details, the engine (Elasticsearch when absent) and the function that performs a
  request."
  [:map [:details Details] [:transport fn?] [:engine {:optional true} Engine]])

(def Page
  "One page of an SQL answer: the columns (only on the first page), the rows and the cursor of the
  next page, if any."
  [:map
   [:columns [:sequential [:map [:name :string] [:type [:maybe :string]]]]]
   [:rows [:sequential [:sequential :any]]]
   [:cursor [:maybe :string]]])

;;; ------------------------------------------------- requests ----------------------------------------------------

(defn base-url
  "The URL of the cluster: `host` may carry its scheme (`https://es.example.com`), else `ssl` picks it;
  the port defaults to 9200."
  [{:keys [host port ssl]}]
  (let [host   (str/replace (str/trim (str host)) #"/+$" "")
        scheme (when-let [[_ s] (re-find #"^(https?)://" host)] s)
        bare   (str/replace host #"^https?://" "")
        port   (if (str/blank? (str port)) 9200 port)]
    (if (re-find #":\d+$" bare)
      (str (or scheme (if ssl "https" "http")) "://" bare)
      (str (or scheme (if ssl "https" "http")) "://" bare ":" port))))

(m/=> base-url [:=> [:cat Details] :string])

(defn auth-headers
  "The Authorization header: `ApiKey <encoded key>` when an API key is set, else basic auth when a
  user is set, else none."
  [{:keys [user password api-key]}]
  (cond
    (not (str/blank? api-key))
    {"Authorization" (str "ApiKey " (str/trim api-key))}

    (not (str/blank? user))
    {"Authorization" (str "Basic " (.encodeToString (Base64/getEncoder)
                                                    (.getBytes (str user ":" password)
                                                               StandardCharsets/UTF_8)))}

    :else {}))

(m/=> auth-headers [:=> [:cat Details] [:map-of :string :string]])

(defn request
  "The request for `method` on `path` with an optional JSON `body` (a Clojure map)."
  [details method path body]
  (cond-> {:method  method
           :url     (str (base-url details) path)
           :headers (merge {"Accept" "application/json"} (auth-headers details))}
    (some? body) (-> (assoc :body (json/generate-string body))
                     (assoc-in [:headers "Content-Type"] "application/json"))))

(m/=> request [:=> [:cat Details [:enum :get :post] :string [:maybe :map]] Request])

;;; ------------------------------------------------- transport ---------------------------------------------------

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 15))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(def ^:private request-timeout (Duration/ofMinutes 10))

(defn http-transport
  "The real transport: java.net.http, nothing else on the classpath."
  [{:keys [method url headers body]}]
  (let [builder   (-> (HttpRequest/newBuilder (URI/create url))
                      (.timeout request-timeout))
        publisher (if body
                    (HttpRequest$BodyPublishers/ofString body)
                    (HttpRequest$BodyPublishers/noBody))]
    (doseq [[k v] headers]
      (.header builder k v))
    (.method builder (str/upper-case (name method)) publisher)
    (let [^HttpResponse resp (.send ^HttpClient @http-client (.build builder)
                                    (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(m/=> http-transport [:=> [:cat Request] Response])

(defn client
  "A client on `details` with the real transport, or the one given, for `engine` (Elasticsearch
  by default)."
  [details & [transport engine]]
  {:details details :transport (or transport http-transport) :engine (or engine :elasticsearch)})

(m/=> client [:=> [:cat Details [:? [:maybe fn?]] [:? [:maybe Engine]]] Client])

(defn- engine-of [client]
  (:engine client :elasticsearch))

(defn engine-name
  "The display name of an engine."
  [engine]
  (if (= :opensearch engine) "OpenSearch" "Elasticsearch"))

(m/=> engine-name [:=> [:cat [:maybe Engine]] :string])

;;; --------------------------------------------------- errors ----------------------------------------------------

(defn- first-line [s]
  (some->> (str/split-lines (str s)) (map str/trim) (remove str/blank?) first))

(defn error-message
  "The message to show for a failed response: the engine's own `reason` (the SQL parser and
  verifier messages say what is wrong with the query), followed by the first line of its
  `details` when it has some (OpenSearch puts the cause there: `Invalid SQL query: can't resolve
  Symbol(... name=nope)`), else the status and the start of the body."
  [status body & [engine]]
  (let [parsed  (try (json/parse-string (str body)) (catch Exception _ nil))
        err     (when (map? parsed) (get parsed "error"))
        reason  (cond (map? err)    (or (get-in err ["root_cause" 0 "reason"]) (get err "reason"))
                      (string? err) err)
        details (when (map? err) (first-line (get err "details")))]
    (cond
      (str/blank? reason)
      (str (engine-name engine) " answered HTTP " status ": "
           (subs (str body) 0 (min 300 (count (str body)))))

      (and details (not (str/includes? reason details)))
      (str reason ": " details)

      :else reason)))

(m/=> error-message [:=> [:cat :int [:maybe :string] [:? [:maybe Engine]]] :string])

(defn call!
  "Perform one request and return the parsed JSON body. A status over 299 throws an ex-info carrying
  Elasticsearch's message, the status and the error type."
  [{:keys [details transport] :as client} method path body]
  (let [{:keys [status] :as resp} (transport (request details method path body))]
    (if (<= 200 status 299)
      (some-> (:body resp) not-empty json/parse-string)
      (let [parsed (try (json/parse-string (str (:body resp))) (catch Exception _ nil))]
        (throw (ex-info (error-message status (:body resp) (engine-of client))
                        {:status     status
                         :error-type (when (map? parsed) (get-in parsed ["error" "type"]))}))))))

(m/=> call! [:=> [:cat Client [:enum :get :post] :string [:maybe :map]] :any])

;;; ----------------------------------------------------- SQL -----------------------------------------------------

(defn fetch-size
  "The page size of the SQL API from the details (1000 when unset or invalid)."
  [{:keys [fetch-size]}]
  (let [n (cond (int? fetch-size) fetch-size
                (string? fetch-size) (parse-long (str/trim fetch-size)))]
    (if (and n (pos? n)) n 1000)))

(m/=> fetch-size [:=> [:cat Details] pos-int?])

(defn sql-body
  "The body of a first SQL request. `params` are the values of the `?` placeholders, in order.
  Multi-valued fields return their first value (`field_multi_value_leniency`) instead of failing the
  whole query, and every date-time is read and returned in UTC."
  [sql params page-size]
  (cond-> {"query"                      sql
           "fetch_size"                 page-size
           "time_zone"                  "Z"
           "field_multi_value_leniency" true}
    (seq params) (assoc "params" (vec params))))

(m/=> sql-body [:=> [:cat :string [:maybe [:sequential :any]] pos-int?] :map])

(defn ->page
  "A page of an SQL answer: Elasticsearch's `columns` and `rows`, or the OpenSearch JDBC format's
  `schema` (a column named by its alias when it has one) and `datarows`."
  [body]
  {:columns (mapv (fn [c] {:name (str (or (get c "alias") (get c "name"))) :type (get c "type")})
                  (or (get body "columns") (get body "schema")))
   :rows    (vec (or (get body "rows") (get body "datarows")))
   :cursor  (not-empty (get body "cursor"))})

(m/=> ->page [:=> [:cat :map] Page])

(defn- sql-path [client]
  (if (= :opensearch (engine-of client)) "/_plugins/_sql" "/_sql"))

(defn opensearch-sql-body
  "The body of an OpenSearch SQL request: the query with its parameters written in as literals.
  No `fetch_size`: the plugin pages only plain SELECTs and hands any other paged query to its
  legacy engine, which reads the same SQL differently; without it the answer comes whole (up to
  the index's `max_result_window`, 10,000 rows by default, unless the query says LIMIT)."
  [sql params]
  {"query" (if (seq params) (values/splice-params sql params) sql)})

(m/=> opensearch-sql-body [:=> [:cat :string [:maybe [:sequential :any]]] :map])

(defn query
  "Run `sql` with its `params` and return the first page."
  [client sql params]
  (->page (if (= :opensearch (engine-of client))
            (call! client :post "/_plugins/_sql?format=jdbc" (opensearch-sql-body sql params))
            (call! client :post "/_sql?format=json"
                   (sql-body sql params (fetch-size (:details client)))))))

(m/=> query [:=> [:cat Client :string [:maybe [:sequential :any]]] Page])

(defn next-page
  "The page after `cursor`."
  [client cursor]
  (->page (call! client :post (str (sql-path client)
                                   (if (= :opensearch (engine-of client)) "?format=jdbc" "?format=json"))
                 {"cursor" cursor})))

(m/=> next-page [:=> [:cat Client :string] Page])

(defn close-cursor!
  "Release a cursor the reader stopped before its end. Never throws: a cursor that cannot be closed
  expires on its own (`page_timeout`)."
  [client cursor]
  (try
    (call! client :post (str (sql-path client) "/close") {"cursor" cursor})
    true
    (catch Exception _ false)))

(m/=> close-cursor! [:=> [:cat Client :string] :boolean])

(defn reducible-rows
  "Every row of the answer that starts with `first-page`, fetching the next pages lazily. A reader
  that stops early (a row limit) or fails closes the open cursor."
  [client first-page]
  (reify clojure.lang.IReduceInit
    (reduce [_ rf init]
      (let [open (volatile! (:cursor first-page))]
        (try
          (loop [acc init
                 {:keys [rows cursor]} first-page]
            (vreset! open cursor)
            (let [acc (reduce (fn [a row]
                                (let [r (rf a row)]
                                  (if (reduced? r) (reduced r) r)))
                              acc rows)]
              (cond
                (reduced? acc) (do (when cursor (close-cursor! client cursor))
                                   (vreset! open nil)
                                   @acc)
                cursor         (recur acc (next-page client cursor))
                :else          acc)))
          (catch Throwable t
            (when-let [c @open] (close-cursor! client c))
            (throw t)))))))

(m/=> reducible-rows [:=> [:cat Client Page] [:fn #(instance? clojure.lang.IReduceInit %)]])

(defn all-rows
  "Run `sql` (no parameters) and read every page: the catalogue statements (`SHOW TABLES`, `SHOW
  COLUMNS`) page like any query, and an index with thousands of fields spans several pages."
  [client sql]
  (let [page (query client sql nil)]
    (assoc page :rows (into [] (reducible-rows client page)) :cursor nil)))

(m/=> all-rows [:=> [:cat Client :string] Page])

;;; --------------------------------------------------- search ----------------------------------------------------

(defn info
  "The cluster's own description (`GET /`): its name, version and, on OpenSearch,
  `version.distribution`."
  [client]
  (call! client :get "/" nil))

(m/=> info [:=> [:cat Client] :any])

(defn distribution
  "The engine a `GET /` answer describes: OpenSearch says so in `version.distribution`."
  [info-body]
  (if (= "opensearch" (some-> (get-in info-body ["version" "distribution"]) str str/lower-case))
    :opensearch
    :elasticsearch))

(m/=> distribution [:=> [:cat :any] Engine])

(defn check-engine!
  "Throw when the cluster is not the engine the client speaks to, saying which driver to pick. A
  cluster that does not describe itself (`GET /` refused to a restricted user or API key) passes:
  the SQL request that follows says what is wrong, if anything."
  [client]
  (let [want  (engine-of client)
        found (try (distribution (info client)) (catch Exception _ nil))]
    (when (and found (not= found want))
      (throw (ex-info (str "This cluster is " (engine-name found) ", not " (engine-name want)
                           ": add it as an " (engine-name found) " database.")
                      {:engine found :expected want})))
    true))

(m/=> check-engine! [:=> [:cat Client] :boolean])

(defn search
  "Run a query DSL `body` on `index` (`POST /<index>/_search`) and return the parsed answer."
  [client index body]
  (call! client :post (str "/" (java.net.URLEncoder/encode ^String index "UTF-8") "/_search") body))

(m/=> search [:=> [:cat Client :string :map] :map])
