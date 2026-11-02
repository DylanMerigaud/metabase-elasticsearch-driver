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

- (Revised, section 7: Elasticsearch only in v0.1.0.) Two engines behind one driver, chosen per connection by an `engine` connection property:
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

## 7. Revisions made while building (2026-11-02)

- OpenSearch is out of v0.1.0. Its SQL plugin has its own date functions (no `DATE_TRUNC`) and
  its own API; supporting it blind would have doubled the risk of the release. The README says
  so under Limits; it is the first candidate for v0.2.0.
- One jar for every supported Metabase: the plugin ships Clojure sources only (no AOT), and the
  driver API it uses (`describe-database*`, `describe-table`, `execute-reducible-query`,
  `database-supports?`, `sql.qp/date`, `sql.qp/->honeysql`, `driver-api/metadata-provider`,
  `driver.conn/effective-details`) is identical in v0.63.22, v0.64.4 and v0.65.2 (read from
  their sources). The CI matrix proves the one jar on each.
- Sync reads `SHOW TABLES` and `SHOW COLUMNS` through the SQL API instead of `_mapping`, so it
  sees exactly what SQL can select (object leaves, multi-fields), and skips nested fields.
- Date-time literals are inlined as `CAST('<ISO in UTC>' AS DATETIME)`, formatted by the driver
  from java.time values; the other values travel as the SQL API's `params`.
- Integration runs through Metabase's REST API (`integration/run.py`): a real Metabase jar with
  the plugin, a real cluster as a service container, Elasticsearch 9 with basic auth.
- The build host has no container runtime; unit tests run locally (a user JDK and the Clojure
  CLI), integration only on GitHub Actions, free once the repository is public.

## 8. v0.2.0: OpenSearch (2026-11-02)

- A second driver, `opensearch`, a child of `elasticsearch` in the same plugin manifest (Metabase
  reads a list under `driver:`), rather than an `engine` property: Metabase dispatches on the
  driver, and OpenSearch then shows by name in the database list.
- The dialect was read from the engines before any code: throwaway probe runs on GitHub Actions
  against OpenSearch 2.19.7 and 3.9.0 (and Elasticsearch 8 and 9 for the day-of-week bug). What
  they showed, and what the driver does about it:
  - `fetch_size` hands any query but a plain SELECT to the legacy engine (other types, functions
    refused): never sent; one response, `max_result_window` rows unless LIMIT;
  - a double quoted text is a string: backtick identifiers;
  - no DATE_TRUNC: TIMESTAMP(DATE_FORMAT(...)), DATE_SUB for quarter and week;
  - a multi-field cannot be selected: not synced;
  - a group key computed by a function sorts as text when every sort key is a group key: COUNT(*)
    as a last sort key;
  - OpenSearch 3 refuses deeply nested computed group keys: a shallow week-of-year formula;
  - no ESCAPE in LIKE, and a backslash reads differently on 2 and 3: patterns unescaped,
    backslash values refused;
  - parameters work only on the legacy path: written in as literals.
- The new start-of-week checks found two Elasticsearch bugs of v0.1.0 (week of year one week
  early, day of week from Monday failing); fixed in the same release.
