# PLAN: metabase-elasticsearch-driver

A Metabase driver plugin, Apache-2.0, that lets Metabase query Elasticsearch and OpenSearch
through their SQL REST endpoints, with a raw query DSL escape hatch. Written 2026-11-02.

## 0. Why, and what exists today

- Need: metabase/metabase issue 1300 "Driver: ElasticSearch", open since 2015, 1509 reactions.
  Metabase ships no Elasticsearch or OpenSearch driver.
- apinstein/metabase-es-sql-driver: last push 2020-12-25, built on the Platinum JDBC driver,
  unmaintained.
- atakdomain/metabase-elasticsearch-driver: one weekend of commits (2026-07-25 to 07-26), no
  license file, no release, pinned to Metabase 0.63.1. Not adoptable.
- Comparable community drivers (release downloads read 2026-11-02): ClickHouse 485k (then made
  official), Athena 230k, Starburst 185k, DuckDB 58k. They ship as a jar attached to a GitHub
  release per Metabase version, which is also how downloads get counted. This driver does the
  same.

## 1. Scope

- Two engines behind one driver, chosen per connection by an `engine` connection property:
  - Elasticsearch 8 and 9: `POST /_sql?format=json` (free Basic licence, no Platinum JDBC),
    cursor pagination with `POST /_sql` `{"cursor": ...}` and `POST /_sql/close`.
    Spec: https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-sql-query
  - OpenSearch 2 and 3: `POST /_plugins/_sql?format=jdbc` (SQL plugin), cursor with
    `{"cursor": ...}` and `POST /_plugins/_sql/close`.
    Spec: https://docs.opensearch.org/latest/search-plugins/sql/sql-ppl-api/
- Metadata sync from `GET /<index>/_mapping` (both engines): tables = indices and aliases
  (system and hidden indices excluded, an optional index pattern filter), fields = mapping
  properties flattened with dotted names, mapped to Metabase base types (keyword/text to
  `:type/Text`, long/integer/short/byte to `:type/Integer`, double/float/half_float/scaled_float
  to `:type/Float`, date/date_nanos to `:type/DateTime`, boolean to `:type/Boolean`,
  ip to `:type/IPAddress`, geo_point to `:type/*` with lat/long, nested/object flattened).
- Query processor: the driver sits on the `:sql` parent, so MBQL compiles to SQL through
  HoneySQL with a dialect of what both SQL endpoints accept (no joins, no subqueries in FROM,
  `LIMIT`, `GROUP BY`, `HISTOGRAM` / `DATE_TRUNC` for date bucketing per engine).
- Native queries: plain SQL, or the raw DSL escape hatch: a JSON body
  `{"index": "...", "query": {...}, "aggs": {...}}` detected by its leading `{`, sent to
  `POST /<index>/_search`, hits flattened into rows (aggregations into bucket rows).
- Honest errors: `database-supports?` returns false for `:left-join`, `:right-join`,
  `:inner-join`, `:full-join`, `:nested-queries`, `:foreign-keys`, so Metabase hides those
  options; a native SQL join gets the engine's own error message passed through unchanged.
- Auth: basic auth, API key (`Authorization: ApiKey ...`), TLS with an optional CA certificate;
  host, port, scheme. No SSH tunnel in v0.1.0.

## 2. Public surface

- Connection properties (`resources/metabase-plugin.yaml`): `engine` (elasticsearch |
  opensearch), `host`, `port` (9200), `ssl`, `user`, `password`, `api-key`, `index-pattern`
  (default `*`), `page-size` (default 1000).
- Namespaces:
  - `metabase.driver.elasticsearch.client`: pure HTTP layer (request building, cursor loop,
    error mapping). Engine differences live in one map, `engines`.
  - `metabase.driver.elasticsearch.sync`: mapping to fields, pure functions over mapping JSON.
  - `metabase.driver.elasticsearch.sql`: HoneySQL dialect and the `sql.qp` multimethods.
  - `metabase.driver.elasticsearch.dsl`: native DSL detection and hit/bucket flattening.
  - `metabase.driver.elasticsearch`: driver registration and the multimethod glue.
- Every public function carries a docstring; Malli schemas on the pure layers.

## 3. Build and release

- `deps.edn` with Metabase as a provided dependency, built with the Metabase driver build
  (`clojure -X:build:drivers:build/driver :driver :elasticsearch` from a Metabase checkout, or
  the standalone `:build` alias compiling against `metabase.jar` on the classpath).
- First risk to check, before any other task: the churn of the driver API across the last
  three Metabase releases (`metabase.driver.sql.query-processor`, `execute-reducible-query`,
  `describe-database`, `describe-table`, the 0.5x move to `describe-fields`). The CI builds the
  jar against each supported Metabase tag and the release attaches one jar per tag:
  `elasticsearch.metabase-driver-<version>-mb<metabase tag>.jar`.

## 4. Test strategy (test first)

- Unit, no network: recorded responses of both engines under `test/fixtures/` (an `_sql`
  page plus cursor page, an OpenSearch jdbc page, a `_mapping` with nested and multi-fields, a
  `_search` with aggregations, error bodies: verification_exception, parsing_exception, a join
  refusal). Contract tests: request bodies per engine, cursor loop closes its cursor on early
  stop, type mapping table, DSL flattening, join features false.
- Integration (CI matrix): Metabase {last three tags} x {Elasticsearch 8.x, 9.x, OpenSearch
  2.x, 3.x} as service containers, a seeded index, then sync and run: a table preview, a
  count, a group by on a keyword, a date bucket by month, a filter, a native SQL query, a
  native DSL query, a join attempt that fails with the engine's message.
- `clj-kondo` and `cljfmt` checks in CI.

## 5. The library harness (growth-cockpit scripts/oss_lib_harness.py), criterion by criterion

- E1 install-built, E2 quickstart-runs, E3 tests-green, E4 api-typed, E5 examples-run: the
  harness builds only a wheel or an npm tarball (`detect` reads pyproject.toml or
  package.json). A Clojure jar has neither, so `check` fails at E0 manifest today. The harness
  needs a JVM ecosystem (build the jar, load it in Metabase, run the README quickstart as a
  native query against a seeded engine) before this library can PASS. Not faked with a dummy
  package.json.
- Scored criteria: a README code block using the driver (the quickstart: drop the jar in
  `plugins/`, add a database, a native SQL and a DSL query), a CI workflow, typed (Malli
  schemas; the harness has no JVM reading of it yet), a lint config (`.clj-kondo/`,
  `cljfmt.edn`), J3 why this one (the comparison table of section 0 in the README).
- Launch plan: only gestures on repositories Dylan owns. Never a PR, issue or comment on
  metabase/metabase or any other third party.

## 6. Done criteria

1. Unit tests green for both engines; integration matrix green on the three Metabase tags x
   four engines.
2. One jar per Metabase tag attached to the v0.1.0 GitHub release.
3. README: what, install, a usage example per feature (SQL, DSL, OpenSearch connection), the
   comparison of section 0, the limits (no joins), license.
4. Independent review passed, findings fixed.
5. `python3 scripts/oss_lib_harness.py check ... --repo https://github.com/DylanMerigaud/metabase-elasticsearch-driver`
   says PASS. Until then the repository stays private.

## 7. State on 2026-11-02 (where a resume starts)

Blocked before any code, on two things:
- The build box (gc-hub) has 1.8 GB free (0.27 GB an hour earlier) and no JDK, no Clojure CLI,
  no container runtime. A JDK, the Metabase jar and its dependencies need about 2 GB, and the
  integration engines need more. The disk door (growth-cockpit scripts/disk_reclaim.py) frees
  nothing more; what remains is classed as Dylan's to decide.
- The harness has no JVM ecosystem (section 5), so no Metabase driver can reach PASS yet.
