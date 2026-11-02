# Changelog

## 0.2.0 (2026-11-02)

- OpenSearch 2 and 3: a second driver, **OpenSearch**, in the same plugin jar. Queries go to the
  SQL plugin (`POST /_plugins/_sql`, JDBC format); tables and fields sync from `_cat/indices`,
  `_cat/aliases` and the index mappings; identifiers are quoted with backticks; date buckets
  minute to year and the date extracts compile to `DATE_FORMAT`, `DATE_SUB` and the plugin's
  extract functions; parameters are written into the SQL as literals; every page of a
  paginated question can be read (`OFFSET`); native query DSL works as on Elasticsearch.
- A grouped OpenSearch question sorted on its group keys gets `COUNT(*)` as a last sort key, so
  the plugin sorts the groups itself instead of ordering a computed key (an hour, a day of
  month) as text.
- Each driver reads `GET /` and refuses a cluster of the other engine, naming the driver to pick.
- Error messages carry OpenSearch's `details` line (`Invalid SQL query: can't resolve
  Symbol(... name=nope)`).
- CI: the end-to-end suite runs on OpenSearch 2.19 and 3.9 (security plugin and basic auth on
  3.9), next to Elasticsearch 8.19 and 9.1, on Metabase v0.63.22, v0.64.4 and v0.65.2.

## 0.1.0 (2026-11-02)

- Elasticsearch 8 and 9 through the SQL REST API: sync from `SHOW TABLES` and `SHOW COLUMNS`,
  questions compiled to Elasticsearch SQL, cursor paging, native SQL with variables, native
  query DSL, CI on Metabase v0.63, v0.64 and v0.65.
