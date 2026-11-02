# Metabase Elasticsearch and OpenSearch driver

[![CI](https://github.com/DylanMerigaud/metabase-elasticsearch-driver/actions/workflows/ci.yml/badge.svg)](https://github.com/DylanMerigaud/metabase-elasticsearch-driver/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Metabase drivers for Elasticsearch and OpenSearch, in one plugin jar. Point Metabase at a cluster
and build questions, dashboards and native queries on your indices, the same way as on a SQL
database.

- **Elasticsearch** 8 and 9 through its [SQL REST API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-sql-query)
  (`POST /_sql`), which ships with the free Basic license: no Platinum JDBC driver.
- **OpenSearch** 2 and 3 through its [SQL plugin](https://docs.opensearch.org/latest/search-plugins/sql/sql-ppl-api/)
  (`POST /_plugins/_sql`), bundled with every OpenSearch distribution, Amazon OpenSearch Service
  included.

No Trino or Presto cluster in between. For what SQL cannot say, a native query can be raw query
DSL, on either engine.

For whom: teams that keep data in Elasticsearch or OpenSearch (orders, events, logs, product
catalogues) and want Metabase on it, which Metabase has been asked for since 2015
([metabase/metabase#1300](https://github.com/metabase/metabase/issues/1300)).

## Install

Download the jar into the `plugins` directory of your Metabase and restart it:

```sh
curl -L -o plugins/elasticsearch.metabase-driver.jar \
  https://github.com/DylanMerigaud/metabase-elasticsearch-driver/releases/download/v0.2.0/elasticsearch.metabase-driver.jar
```

With Docker, mount a directory holding the jar on `/plugins`:

```sh
docker run -d -p 3000:3000 -v "$PWD/plugins:/plugins" -e MB_PLUGINS_DIR=/plugins metabase/metabase:v0.65.2
```

Then **Admin settings > Databases > Add a database > Elasticsearch** (or **OpenSearch**). Each
driver checks the cluster it reaches: an OpenSearch cluster added as Elasticsearch, or the other
way round, is refused with a message naming the driver to pick.

| Field | What to put |
| --- | --- |
| Host | `localhost`, `es.internal`, or a full URL such as `https://my-deployment.es.io` |
| Port | 9200 by default (9243 or 443 on Elastic Cloud) |
| Username, Password | basic auth, when security is on |
| API key | the `encoded` value Elasticsearch returns when it creates a key; used instead of the user (sent as `Authorization: ApiKey <key>`, on OpenSearch too) |
| Indices to sync | comma separated index or alias patterns, `logs-*,orders`; empty syncs every index whose name does not start with a dot |
| Use a secure connection (SSL) | HTTPS, when the host carries no scheme |
| Rows per page (advanced, Elasticsearch) | the `fetch_size` of each SQL page, 1000 by default |

## Usage

### Questions (the query builder)

Each index and alias is a table; each field is a column, object fields by their dotted name
(`address.city`) and multi-fields too (`notes.keyword`). Filters, summaries (count, sum, average,
min, max, distinct count) and group-bys, including by date unit (minute to year, day of week,
month of year...), compile to Elasticsearch SQL. With a table `orders`, "count of orders by
month of `placed_at` where `total` > 10" runs as SQL of this shape:

```sql
SELECT DATE_TRUNC('month', "placed_at") AS "placed_at", COUNT(*) AS "count"
FROM "orders"
WHERE "total" > 10
GROUP BY DATE_TRUNC('month', "placed_at")
ORDER BY DATE_TRUNC('month', "placed_at") ASC
```

On OpenSearch the same question compiles to the SQL plugin's dialect (backtick identifiers, no
`DATE_TRUNC`):

```sql
SELECT TIMESTAMP(DATE_FORMAT(`placed_at`, '%Y-%m-01 00:00:00')) AS `placed_at`, COUNT(*) AS `count`
FROM `orders`
WHERE `total` > 10
GROUP BY TIMESTAMP(DATE_FORMAT(`placed_at`, '%Y-%m-01 00:00:00'))
ORDER BY `placed_at` ASC, COUNT(*) ASC
```

### Native SQL

Any statement the [Elasticsearch SQL language](https://www.elastic.co/docs/reference/query-languages/sql)
or the [OpenSearch SQL plugin](https://docs.opensearch.org/latest/search-plugins/sql/index/)
accepts, with Metabase variables:

```sql
SELECT customer, SUM(total) AS spent
FROM orders
WHERE customer = {{who}}
GROUP BY customer
```

On Elasticsearch, results stream page by page through the SQL cursor; when Metabase stops reading
(its row limit) the cursor is closed. On OpenSearch the answer comes in one response: the plugin
pages only plain SELECTs, and asking it to page anything else hands the query to its legacy
engine, which reads the same SQL differently.

### Native query DSL

A native query that starts with `{` is a search: a JSON object naming its `index`, plus any body
the [search API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search)
takes.

```json
{"index": "orders",
 "query": {"match": {"notes": "late delivery"}},
 "size": 50}
```

The same JSON runs on OpenSearch. Without `aggs`, each hit is a row: `_id` then every `_source` field, objects flattened to dotted
names. With `aggs`, each leaf bucket is a row: a column per bucket level holding its key, a column per
metric of any level, and `doc_count` (the deepest level's count).

```json
{"index": "orders", "size": 0,
 "aggs": {"by_city": {"terms": {"field": "address.city"},
                      "aggs": {"revenue": {"sum": {"field": "total"}}}}}}
```

| by_city | doc_count | revenue |
| --- | --- | --- |
| Lyon | 2 | 20.0 |
| Paris | 2 | 41.0 |

### The HTTP client on its own

The namespaces under `metabase.driver.elasticsearch.*` other than the driver itself do not depend
on Metabase. From a checkout, `clojure -M:test` gives a REPL where:

```clojure
(require '[metabase.driver.elasticsearch.client :as es])

(def c (es/client {:host "localhost" :port 9200 :user "elastic" :password "changeme"}))
(def page (es/query c "SELECT customer, total FROM orders ORDER BY total DESC" nil))
(:columns page)                      ; [{:name "customer", :type "keyword"} {:name "total", :type "double"}]
(into [] (es/reducible-rows c page)) ; every row, following the cursor

;; OpenSearch: the same calls on a client for that engine
(def os (es/client {:host "localhost" :port 9200} nil :opensearch))
(es/query os "SELECT customer FROM orders WHERE total > ?" [10]) ; the parameter is written in as 10
```

## Compatibility

Each push runs the end-to-end suite (`integration/run.py`: sync, table preview, aggregations, date
units, filters, native SQL with a variable, query DSL hits and aggregations, refused joins) inside
a real Metabase against a real cluster, for every pair of:

- Metabase v0.63, v0.64 and v0.65 (v0.63.22, v0.64.4, v0.65.2);
- Elasticsearch 8.19 (security off) and 9.1 (basic auth);
- OpenSearch 2.19 (security plugin off) and 3.9 (security plugin on, basic auth).

| | Elasticsearch 8, 9 | OpenSearch 2, 3 |
| --- | --- | --- |
| SQL endpoint | `POST /_sql` (JSON) | `POST /_plugins/_sql` (JDBC format) |
| Sync | `SHOW TABLES`, `SHOW COLUMNS` | `_cat/indices`, `_cat/aliases`, `_mapping` |
| Object leaves (`address.city`) | yes | yes |
| Multi-fields (`notes.keyword`) | yes | no (the plugin uses the keyword sub-field by itself) |
| Filters, summaries, group-bys | yes | yes |
| Date units, minute to year, and extracts | yes | yes |
| Later pages of a paginated question | no (no `OFFSET`) | yes |
| Native SQL with variables | yes (sent as `params`) | yes (written in as literals) |
| Native query DSL | yes | yes |
| Paged results | SQL cursor | one response, up to `max_result_window` (10,000) rows unless the SQL says `LIMIT` |
| API key | yes | yes |

## Limits

- No joins and no nested questions: the Elasticsearch SQL API has neither, so Metabase does not
  offer them on either engine. A native query with a `JOIN` fails with Elasticsearch's own message;
  on OpenSearch the SQL plugin hands it to its legacy engine, which runs simple joins.
- Fields under a `nested` mapping are not synced (the SQL API cannot select them with other
  columns); query them with the DSL.
- A multi-valued field returns its first value in SQL results (`field_multi_value_leniency`).
- Custom expressions, binning, percentiles, standard deviation and window functions are off.
- Every date-time is read and returned in UTC.
- OpenSearch: a parameter value holding a backslash is refused (OpenSearch 2 keeps a backslash as
  typed in SQL text, OpenSearch 3 reads it as an escape, so no spelling means the same on both);
  a `%` or `_` in the text of a contains, starts-with or ends-with filter matches as a wildcard
  (the plugin has no `ESCAPE`);
  a native SQL query without `LIMIT` returns at most the index's `max_result_window` rows; an
  `OFFSET` past it fails with the engine's message.
- No SSH tunnel; for a private CA, add it to the JVM trust store of Metabase.

## Why this driver

| | This driver | [apinstein/metabase-es-sql-driver](https://github.com/apinstein/metabase-es-sql-driver) | [atakdomain/metabase-elasticsearch-driver](https://github.com/atakdomain/metabase-elasticsearch-driver) | Trino in front of Elasticsearch |
| --- | --- | --- | --- | --- |
| License | Apache-2.0 | none stated | none stated | Apache-2.0 (Trino) |
| Elasticsearch license needed | Basic (free) | Platinum (JDBC driver) | Basic | Basic |
| OpenSearch | 2 and 3, tested in CI | no | no | through Trino's OpenSearch connector |
| Last change | 2026 | 2020 | July 2026, one weekend | maintained |
| Released jar | yes, per tag | no | no | n/a |
| Metabase versions tested in CI | v0.63, v0.64, v0.65 | none | none | Metabase's Trino driver |
| Extra service to run | none | none | none | a Trino cluster |

Metabase itself ships no Elasticsearch or OpenSearch driver; its usual advice is a Trino cluster in between,
which works but is one more distributed system to operate for a read path.

## Development

```sh
clojure -X:test          # unit tests (recorded Elasticsearch and OpenSearch answers, no network)
clojure -T:build jar     # target/elasticsearch.metabase-driver.jar
```

The end-to-end suite needs a cluster and a Metabase with the jar in its plugins directory; see
`.github/workflows/ci.yml` and `integration/run.py`. Every public function of the pure namespaces
carries a Malli schema, and the unit tests run with instrumentation on.

## License

Apache License 2.0, see [LICENSE](LICENSE).
