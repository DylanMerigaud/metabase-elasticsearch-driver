#!/usr/bin/env python3
"""End-to-end test of the driver inside a running Metabase, against a seeded Elasticsearch or
OpenSearch cluster.

Starts nothing: the CI starts the cluster (a service container) and Metabase (with the built jar
in its plugins directory). This script seeds an index, sets Metabase up, adds the database, waits
for the sync, then runs questions and native queries through Metabase's REST API and checks each
answer. Standard library only.

Environment: ENGINE (elasticsearch or opensearch, the Metabase driver to use), ES_URL
(http://localhost:9200), ES_USER and ES_PASSWORD (optional), MB_URL (http://localhost:3000).
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

ES_URL = os.environ.get("ES_URL", "http://localhost:9200")
ES_USER = os.environ.get("ES_USER") or None
ES_PASSWORD = os.environ.get("ES_PASSWORD") or None
MB_URL = os.environ.get("MB_URL", "http://localhost:3000")
ENGINE = os.environ.get("ENGINE", "elasticsearch")
OPENSEARCH = ENGINE == "opensearch"
OTHER = "elasticsearch" if OPENSEARCH else "opensearch"

DOCS = [
    {"customer": "alice", "notes": "first order", "total": 12.5, "quantity": 1,
     "placed_at": "2024-03-01T10:00:00Z", "paid": True, "address": {"city": "Lyon"}},
    {"customer": "alice", "notes": "repeat", "total": 7.5, "quantity": 2,
     "placed_at": "2024-04-02T09:00:00Z", "paid": False, "address": {"city": "Lyon"}},
    {"customer": "bob", "notes": "gift", "total": 30.0, "quantity": 3,
     "placed_at": "2024-03-15T18:30:00Z", "paid": True, "address": {"city": "Paris"}},
    {"customer": "carol", "notes": "late delivery", "total": 4.0, "quantity": 1,
     "placed_at": "2024-05-20T07:45:00Z", "paid": True, "address": {"city": "Nantes"}},
    {"customer": "bob", "notes": "second", "total": 11.0, "quantity": 1,
     "placed_at": "2024-05-21T12:00:00Z", "paid": False, "address": {"city": "Paris"}},
    {"customer": "dave", "notes": "bulk", "total": 100.0, "quantity": 10,
     "placed_at": "2024-03-31T23:59:00Z", "paid": True, "address": {"city": "Lille"}},
]

MAPPING = {"mappings": {"properties": {
    "customer": {"type": "keyword"},
    "notes": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
    "total": {"type": "double"},
    "quantity": {"type": "integer"},
    "placed_at": {"type": "date"},
    "paid": {"type": "boolean"},
    "address": {"properties": {"city": {"type": "keyword"}}},
}}}

FAILURES = []


def http(method, url, body=None, headers=None, timeout=120):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def es(method, path, body=None):
    headers = {}
    if ES_USER:
        token = base64.b64encode(f"{ES_USER}:{ES_PASSWORD}".encode()).decode()
        headers["Authorization"] = "Basic " + token
    status, out = http(method, ES_URL + path, body, headers)
    if status >= 300:
        sys.exit(f"Elasticsearch {method} {path}: HTTP {status} {out}")
    return out


def seed():
    es("PUT", "/orders", MAPPING)
    for i, doc in enumerate(DOCS, 1):
        es("PUT", f"/orders/_doc/{i}", doc)
    es("POST", "/orders/_refresh")
    es("PUT", "/events-2024", {"mappings": {"properties": {"kind": {"type": "keyword"}}}})
    es("PUT", "/events-2024/_doc/1?refresh=true", {"kind": "signup"})


class Metabase:
    def __init__(self):
        self.session = None

    def call(self, method, path, body=None, ok=(200, 202)):
        headers = {"X-Metabase-Session": self.session} if self.session else {}
        status, out = http(method, MB_URL + path, body, headers, timeout=300)
        if status not in ok:
            sys.exit(f"Metabase {method} {path}: HTTP {status} {str(out)[:2000]}")
        return out

    def wait_healthy(self, seconds=300):
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                status, out = http("GET", MB_URL + "/api/health", timeout=5)
                if status == 200 and isinstance(out, dict) and out.get("status") == "ok":
                    return
            except OSError:
                pass
            time.sleep(3)
        sys.exit("Metabase did not become healthy")

    def setup(self):
        token = self.call("GET", "/api/session/properties")["setup-token"]
        out = self.call("POST", "/api/setup", {
            "token": token,
            "user": {"email": "ci@example.com", "password": "Ci-passw0rd-2024!",
                     "first_name": "CI", "last_name": "Run", "site_name": "ci"},
            "prefs": {"site_name": "ci", "allow_tracking": False},
        })
        self.session = out["id"]


def failure(out):
    """The error of a failed query and the SQL Metabase compiled, for the log."""
    native = (out.get("native") or {}).get("query") if isinstance(out.get("native"), dict) else None
    return f"{out.get('error')} | SQL: {native}"


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f": {detail}"))
    if not ok:
        FAILURES.append(name)


def main():
    seed()
    mb = Metabase()
    mb.wait_healthy()
    mb.setup()

    details = {"host": ES_URL.split("://", 1)[1].rsplit(":", 1)[0],
               "port": int(ES_URL.rsplit(":", 1)[1]), "ssl": False,
               "index-pattern": "orders,events-*"}
    if ES_USER:
        details.update(user=ES_USER, password=ES_PASSWORD)
    out = mb.call("POST", "/api/database", {"engine": OTHER, "name": "wrong engine",
                                            "details": details, "is_full_sync": True},
                  ok=(200, 400, 500))
    check(f"the {OTHER} driver refuses this cluster and names the right one",
          "This cluster is" in str(out) and "id" not in out, str(out)[:500])

    db = mb.call("POST", "/api/database", {"engine": ENGINE, "name": "cluster",
                                           "details": details, "is_full_sync": True})
    db_id = db["id"]
    check(f"the plugin registers the {ENGINE} engine",
          mb.call("GET", f"/api/database/{db_id}").get("engine") == ENGINE, db)

    # OpenSearch's SQL plugin cannot select a multi-field such as notes.keyword: not synced there
    expected = {"customer", "notes", "total", "quantity", "placed_at", "paid", "address.city"}
    if not OPENSEARCH:
        expected.add("notes.keyword")

    # wait for the sync to see the orders fields
    deadline = time.time() + 240
    tables = {}
    while time.time() < deadline:
        meta = mb.call("GET", f"/api/database/{db_id}/metadata")
        tables = {t["name"]: t for t in meta.get("tables", [])}
        if "orders" in tables and len(tables["orders"].get("fields", [])) >= len(expected):
            break
        time.sleep(3)
    check("sync finds the indices the pattern keeps", set(tables) == {"orders", "events-2024"},
          sorted(tables))
    orders = tables["orders"]
    fields = {f["name"]: f for f in orders["fields"]}
    check("sync finds every column, object leaves and multi-fields included",
          set(fields) == expected, sorted(fields))
    check("sync maps the types",
          fields["total"]["base_type"] == "type/Float"
          and fields["placed_at"]["base_type"] == "type/DateTimeWithLocalTZ"
          and fields["paid"]["base_type"] == "type/Boolean"
          and fields["quantity"]["base_type"] == "type/Integer",
          {k: f["base_type"] for k, f in fields.items()})
    tid = orders["id"]

    def field(name, **opts):
        return ["field", fields[name]["id"], opts or None]

    def mbql(query, expect_ok=True):
        q = {"source-table": tid}
        q.update(query)
        out = mb.call("POST", "/api/dataset", {"database": db_id, "type": "query", "query": q},
                      ok=(200, 202, 500))
        if expect_ok and out.get("status") != "completed":
            return None, failure(out)
        return out["data"]["rows"], out.get("error")

    def native(text, extra=None, parameters=None):
        body = {"database": db_id, "type": "native", "native": {"query": text, **(extra or {})}}
        if parameters:
            body["parameters"] = parameters
        out = mb.call("POST", "/api/dataset", body, ok=(200, 202, 500))
        if out.get("status") != "completed":
            return None, failure(out)
        return out["data"]["rows"], None

    rows, err = mbql({"limit": 10})
    check("table preview", rows is not None and len(rows) == 6, err or rows)

    rows, err = mbql({"aggregation": [["count"]]})
    check("count", rows == [[6]], err or rows)

    rows, err = mbql({"aggregation": [["sum", field("total")]]})
    check("sum", rows is not None and abs(rows[0][0] - 165.0) < 1e-9, err or rows)

    rows, err = mbql({"aggregation": [["avg", field("total")], ["min", field("total")],
                                      ["max", field("total")], ["distinct", field("customer")]]})
    check("average, min, max, distinct count",
          rows is not None and abs(rows[0][0] - 27.5) < 1e-9 and rows[0][1:] == [4.0, 100.0, 4],
          err or rows)

    rows, err = mbql({"aggregation": [["count"]], "breakout": [field("customer")],
                      "order-by": [["asc", field("customer")]]})
    check("count by a keyword", rows == [["alice", 2], ["bob", 2], ["carol", 1], ["dave", 1]],
          err or rows)

    rows, err = mbql({"aggregation": [["count"]],
                      "breakout": [field("placed_at", **{"temporal-unit": "month"})],
                      "order-by": [["asc", field("placed_at", **{"temporal-unit": "month"})]]})
    months = [(r[0][:7], r[1]) for r in rows] if rows else None
    check("count by month", months == [("2024-03", 3), ("2024-04", 1), ("2024-05", 2)],
          err or rows)

    # placed_at: 03-01 Fri 10:00, 04-02 Tue 09:00, 03-15 Fri 18:30, 05-20 Mon 07:45,
    # 05-21 Tue 12:00, 03-31 Sun 23:59 (all UTC)
    expected_units = {
        "day-of-week": [[1, 1], [2, 1], [3, 2], [6, 2]],
        "hour-of-day": [[7, 1], [9, 1], [10, 1], [12, 1], [18, 1], [23, 1]],
        "day-of-month": [[1, 1], [2, 1], [15, 1], [20, 1], [21, 1], [31, 1]],
        "month-of-year": [[3, 3], [4, 1], [5, 2]],
        "quarter-of-year": [[1, 3], [2, 3]],
        "year": None,
        "week": None,
        "week-of-year": None,
        "day-of-year": None,
        "day": None,
        "hour": None,
        "quarter": None,
    }
    for unit, expected in expected_units.items():
        f = field("placed_at", **{"temporal-unit": unit})
        rows, err = mbql({"aggregation": [["count"]], "breakout": [f],
                          "order-by": [["asc", f]]})
        if expected is None:
            ok = rows is not None and sum(r[1] for r in rows) == 6
        else:
            ok = rows == expected
        check(f"count by {unit}", ok, err or rows)

    rows, err = mbql({"fields": [field("customer")], "order-by": [["desc", field("total")]],
                      "page": {"page": 1, "items": 2}})
    check("first page of a paginated question", rows == [["dave"], ["bob"]], err or rows)

    if OPENSEARCH:
        rows, err = mbql({"fields": [field("customer")], "order-by": [["desc", field("total")]],
                          "page": {"page": 2, "items": 2}})
        check("a later page of a paginated question", rows == [["alice"], ["bob"]], err or rows)
    else:
        rows, err = mbql({"fields": [field("customer")], "page": {"page": 2, "items": 2}})
        check("a later page is refused clearly", rows is None and "OFFSET" in str(err), err)

    rows, err = mbql({"aggregation": [["count"]], "filter": [">", field("total"), 10]})
    check("numeric filter", rows == [[4]], err or rows)

    rows, err = mbql({"aggregation": [["count"]],
                      "filter": ["between", field("placed_at", **{"temporal-unit": "day"}),
                                 "2024-03-01", "2024-03-31"]})
    check("date range filter", rows == [[3]], err or rows)

    rows, err = mbql({"aggregation": [["count"]],
                      "filter": ["time-interval", field("placed_at"), -5000, "day"]})
    check("relative date filter", rows == [[6]], err or rows)

    rows, err = mbql({"aggregation": [["count"]],
                      "filter": ["=", field("customer"), "o'brien"]})
    check("a quote in a filter value", rows == [[0]], err or rows)

    rows, err = mbql({"aggregation": [["count"]], "filter": ["=", field("address.city"), "Lyon"]})
    check("filter on an object leaf", rows == [[2]], err or rows)

    rows, err = mbql({"aggregation": [["count"]], "filter": ["=", field("paid"), True]})
    check("boolean filter", rows == [[4]], err or rows)

    rows, err = mbql({"aggregation": [["count"]],
                      "filter": ["contains", field("customer"), "ar", {"case-sensitive": True}]})
    check("contains filter", rows == [[1]], err or rows)

    rows, err = native("SELECT customer, SUM(total) AS spent FROM orders "
                       "GROUP BY customer ORDER BY customer")
    check("native SQL", rows == [["alice", 20.0], ["bob", 41.0], ["carol", 4.0], ["dave", 100.0]],
          err or rows)

    rows, err = native("SELECT COUNT(*) FROM orders WHERE customer = {{who}}",
                       {"template-tags": {"who": {"id": "who-tag", "name": "who",
                                                  "display-name": "Who", "type": "text"}}},
                       [{"type": "category", "target": ["variable", ["template-tag", "who"]],
                         "value": "bob"}])
    check("native SQL with a parameter", rows == [[2]], err or rows)

    rows, err = native('{"index": "orders", "query": {"term": {"customer": "carol"}}}')
    check("native query DSL, hits",
          rows is not None and len(rows) == 1 and "carol" in rows[0], err or rows)

    rows, err = native('{"index": "orders", "size": 0, "aggs": {"by_city": '
                       '{"terms": {"field": "address.city", "order": {"_key": "asc"}}, '
                       '"aggs": {"revenue": {"sum": {"field": "total"}}}}}}')
    check("native query DSL, aggregations",
          rows == [["Lille", 1, 100.0], ["Lyon", 2, 20.0], ["Nantes", 1, 4.0],
                   ["Paris", 2, 41.0]], err or rows)

    if not OPENSEARCH:
        # OpenSearch 2 and 3 hand a JOIN to the SQL plugin's legacy engine, which runs it
        rows, err = native("SELECT o.customer FROM orders o JOIN orders p "
                           "ON o.customer = p.customer")
        check("a join fails with the engine's message",
              rows is None and "JOIN" in str(err).upper(), err)

    rows, err = native("SELECT nope FROM orders")
    check("an unknown column fails with the engine's message",
          rows is None and "nope" in str(err), err)

    if FAILURES:
        sys.exit(f"{len(FAILURES)} check(s) failed: {', '.join(FAILURES)}")
    print("all checks passed")


if __name__ == "__main__":
    main()
