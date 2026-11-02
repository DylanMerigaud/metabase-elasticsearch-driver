# Metabase Elasticsearch driver

[![CI](https://github.com/DylanMerigaud/metabase-elasticsearch-driver/actions/workflows/ci.yml/badge.svg)](https://github.com/DylanMerigaud/metabase-elasticsearch-driver/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A Metabase driver for Elasticsearch. Point Metabase at a cluster and build questions, dashboards
and native queries on your indices, the same way as on a SQL database.

It talks to the Elasticsearch [SQL REST API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-sql-query)
(`POST /_sql`), which ships with the free Basic license: no Platinum JDBC driver, no Trino or
Presto cluster in between. For what SQL cannot say, a native query can be raw query DSL.

For whom: teams that keep data in Elasticsearch (orders, events, logs, product catalogues) and
want Metabase on it, which Metabase has been asked for since 2015
([metabase/metabase#1300](https://github.com/metabase/metabase/issues/1300)).

## Install

Download the jar into the `plugins` directory of your Metabase and restart it:

```sh
curl -L -o plugins/elasticsearch.metabase-driver.jar \
  https://github.com/DylanMerigaud/metabase-elasticsearch-driver/releases/download/v0.1.0/elasticsearch.metabase-driver.jar
```

With Docker, mount a directory holding the jar on `/plugins`:

```sh
docker run -d -p 3000:3000 -v "$PWD/plugins:/plugins" -e MB_PLUGINS_DIR=/plugins metabase/metabase:v0.65.2
```

Then **Admin settings > Databases > Add a database > Elasticsearch**.

| Field | What to put |
| --- | --- |
| Host | `localhost`, `es.internal`, or a full URL such as `https://my-deployment.es.io` |
| Port | 9200 by default (9243 or 443 on Elastic Cloud) |
| Username, Password | basic auth, when security is on |
| API key | the `encoded` value Elasticsearch returns when it creates a key; used instead of the user |
| Indices to sync | comma separated index or alias patterns, `logs-*,orders`; empty syncs every index whose name does not start with a dot |
| Use a secure connection (SSL) | HTTPS, when the host carries no scheme |
| Rows per page (advanced) | the `fetch_size` of each SQL page, 1000 by default |

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

### Native SQL

Any statement the [Elasticsearch SQL language](https://www.elastic.co/docs/reference/query-languages/sql)
accepts, with Metabase variables:

```sql
SELECT customer, SUM(total) AS spent
FROM orders
WHERE customer = {{who}}
GROUP BY customer
```

Results stream page by page through the SQL cursor; when Metabase stops reading (its row limit)
the cursor is closed.

### Native query DSL

A native query that starts with `{` is a search: a JSON object naming its `index`, plus any body
the [search API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search)
takes.

```json
{"index": "orders",
 "query": {"match": {"notes": "late delivery"}},
 "size": 50}
```

Without `aggs`, each hit is a row: `_id` then every `_source` field, objects flattened to dotted
names. With `aggs`, each leaf bucket is a row: a column per bucket level holding its key, plus
`doc_count` and each metric of the deepest level.

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
```

## Compatibility

Each push runs the end-to-end suite (`integration/run.py`: sync, table preview, aggregations, date
units, filters, native SQL with a variable, query DSL hits and aggregations, refused joins) inside
a real Metabase against a real cluster, for every pair of:

- Metabase v0.63, v0.64 and v0.65 (v0.63.22, v0.64.4, v0.65.2);
- Elasticsearch 8.19 (security off) and 9.1 (basic auth).

## Limits

- No joins and no nested questions: the SQL API has neither, so Metabase does not offer them. A
  native query with a `JOIN` fails with Elasticsearch's own message.
- Fields under a `nested` mapping are not synced (the SQL API cannot select them with other
  columns); query them with the DSL.
- A multi-valued field returns its first value in SQL results (`field_multi_value_leniency`).
- Custom expressions, binning, percentiles, standard deviation and window functions are off in
  v0.1.0.
- Every date-time is read and returned in UTC.
- OpenSearch is not supported yet: its SQL plugin speaks a different dialect and API.
- No SSH tunnel; for a private CA, add it to the JVM trust store of Metabase.

## Why this driver

| | This driver | [apinstein/metabase-es-sql-driver](https://github.com/apinstein/metabase-es-sql-driver) | [atakdomain/metabase-elasticsearch-driver](https://github.com/atakdomain/metabase-elasticsearch-driver) | Trino in front of Elasticsearch |
| --- | --- | --- | --- | --- |
| License | Apache-2.0 | none stated | none stated | Apache-2.0 (Trino) |
| Elasticsearch license needed | Basic (free) | Platinum (JDBC driver) | Basic | Basic |
| Last change | 2026 | 2020 | July 2026, one weekend | maintained |
| Released jar | yes, per tag | no | no | n/a |
| Metabase versions tested in CI | v0.63, v0.64, v0.65 | none | none | Metabase's Trino driver |
| Extra service to run | none | none | none | a Trino cluster |

Metabase itself ships no Elasticsearch driver; its usual advice is a Trino cluster in between,
which works but is one more distributed system to operate for a read path.

## Development

```sh
clojure -X:test          # unit tests (recorded Elasticsearch answers, no network)
clojure -T:build jar     # target/elasticsearch.metabase-driver.jar
```

The end-to-end suite needs a cluster and a Metabase with the jar in its plugins directory; see
`.github/workflows/ci.yml` and `integration/run.py`. Every public function of the pure namespaces
carries a Malli schema, and the unit tests run with instrumentation on.

## License

Apache License 2.0, see [LICENSE](LICENSE).
