#!/usr/bin/env python3
"""검수 목록 전후 비교의 원본 결과·파생 Influx 지표·Grafana 패널을 읽기 전용으로 대조합니다."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import statistics
import sys


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


C = module("review_comparison", "report-review-comparison.py")
V = module("review_dashboard", "verify-review-dashboard.py")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--experiment", type=Path, required=True)
    args = parser.parse_args()
    folder = args.experiment.resolve()
    report = C.read(folder / "comparison.json")
    comparison = report["comparisonId"]
    if not re.fullmatch(r"[A-Za-z0-9_-]+", comparison):
        parser.error("지원하지 않는 비교 ID")
    failures = []
    result = {"comparisonId": comparison, "checks": {}, "runs": [], "panels": [], "errors": failures}

    def check(name, condition):
        result["checks"][name] = bool(condition)
        if not condition:
            failures.append(name)

    check("completedReport", report.get("completed") is True and report.get("dataIntegrityPassed") is True)
    check("comparisonMatchesFolder", folder.name == comparison)
    for path, digest in report["sourceManifest"].items():
        check("sourceUnchanged:" + path, hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest)
    runs = report["runs"]
    main_runs = [r for r in runs if r["phase"] in ("before", "after")]
    controls = [r for r in runs if r["phase"] == "control"]
    expected = Counter((phase, size, case, repeat) for phase in ("before", "after")
                       for size in (10000, 100000) for case in C.SCENARIOS for repeat in (1, 2, 3))
    check("twentyFourMainRuns", Counter((r["phase"], r["dataset"], r["scenario"], r["repeat"]) for r in main_runs) == expected)
    check("twoSeparateControls", Counter((r["dataset"], r["scenario"]) for r in controls) == Counter(((10000, "all"), (100000, "all"))))
    check("uniqueRunIds", len({r["runId"] for r in runs}) == 26)

    points = C.publication_points(report)
    check("mainSqlKinds", sum(len(r["sql"]) for r in main_runs) == 108)
    expected_point_counts = Counter(p["measurement"] for p in points)
    for measurement in (C.SUMMARY, C.SQL, C.SERIAL):
        stored = V.rows(V.query(f'SELECT * FROM "{measurement}" WHERE "comparison_id"=\'{comparison}\''))
        expected_rows = [p for p in points if p["measurement"] == measurement]
        fields = ("phase", "dataset", "scenario", "page", "kind") if measurement == C.SERIAL else (
            ("run_id", "kind") if measurement == C.SQL else ("run_id",))
        stored_by_key = {tuple(str(row.get(k)) for k in fields): row for row in stored}
        check(measurement + ":noDuplicateKeys", len(stored_by_key) == len(stored))
        check(measurement + ":expectedCount", len(stored) == expected_point_counts[measurement])
        for point in expected_rows:
            key = tuple(str(point["tags"].get(k)) for k in fields)
            row = stored_by_key.get(key, {})
            check(measurement + ":" + "/".join(key),
                  all(str(row.get(k)) == str(v) for k, v in point["tags"].items())
                  and all(V.same(row.get(k), v) for k, v in point["fields"].items()))

    for run in runs:
        run_id = run["runId"]
        if not re.fullmatch(r"[A-Za-z0-9_-]+", run_id):
            raise ValueError("지원하지 않는 run_id")
        source = Path(run["sourceExperiment"])
        original = {k: v for k, v in run.items() if k not in ("phase", "sourceExperiment")}
        check(run_id + ":sourceVerification", C.read(source / run_id / "verification.json") == original)
        latency_count = V.scalar(f'SELECT count("value") AS "count" FROM "receipt_review_ms" WHERE "run_id"=\'{run_id}\'', "count")
        requests = V.scalar(f'SELECT sum("value") AS "count" FROM "receipt_review_requests" WHERE "run_id"=\'{run_id}\'', "count")
        failed = V.scalar(f'SELECT sum("value") AS "count" FROM "receipt_review_failures" WHERE "run_id"=\'{run_id}\'', "count")
        byte_count = V.scalar(f'SELECT sum("value") AS "count" FROM "receipt_review_response_bytes" WHERE "run_id"=\'{run_id}\'', "count")
        check(run_id + ":rawSamples", latency_count == requests == run["requests"])
        check(run_id + ":rawFailuresAndBytes", failed == run["failures"] and V.same(byte_count / requests, run["responseBytesPerRequest"]))
        statuses = {row.get("status"): row["count"] for row in V.rows(V.query(
            f'SELECT count("value") AS "count" FROM "http_req_duration" WHERE "run_id"=\'{run_id}\' GROUP BY "status"'))}
        assessment = report["requestAssessments"][run_id]
        check(run_id + ":statusEvidence", statuses == assessment["httpStatusCounts"]
              and sum(statuses.values()) == requests and statuses.get("200", 0) == requests - failed)
        check(run_id + ":scope", run["rate"] == 20 and run["seconds"] == 60 and run["warmup"] == 30
              and requests + run["dropped"] in (1200, 1201))
        check(run_id + ":evidenceNotPerformance", run["checks"].get("samples") is True
              and run["checks"].get("monitor") is True and run["checks"].get("noSqlErrors") is True)
        result["runs"].append({"phase": run["phase"], "runId": run_id, "requests": requests,
                               "failures": failed, "dropped": run["dropped"], "statusCounts": statuses,
                               "p95Ms": run["latency"]["p(95)"], "originalPassed": run["passed"]})

    dashboard = V.get(V.GRAFANA + "/api/dashboards/uid/" + C.UID)["dashboard"]
    check("dashboardUid", dashboard["uid"] == C.UID)
    check("twentyPanels", len(dashboard["panels"]) == 20)
    variables = dashboard["templating"]["list"]
    check("variables", [v["name"] for v in variables] == ["comparison_id", "run_id"])
    for variable in variables:
        query = variable["query"].replace("$comparison_id", re.escape(comparison))
        actual = {r.get("value") for r in V.rows(V.query(query))}
        expected_values = {comparison} if variable["name"] == "comparison_id" else {r["runId"] for r in runs}
        check("variable:" + variable["name"], expected_values <= actual)
    selected = max((r for r in runs if r["phase"] == "after"), key=lambda r: r["endNs"])
    bounds = f'time >= {selected["startNs"] - 5000000000} AND time <= {selected["endNs"] + 5000000000}'
    targets = 0
    for panel in dashboard["panels"]:
        for target in panel.get("targets", []):
            targets += 1
            check(f"panel{panel['id']}:datasource", target.get("datasource", {}).get("uid") == "receipt-loadtest-influx")
            statement = target["query"].replace("$comparison_id", re.escape(comparison)).replace("$run_id", selected["runId"]).replace("$timeFilter", bounds)
            check(f"panel{panel['id']}:noFailedRunFilter", not re.search(r"\bWHERE\b.*(?:original_passed|\bpassed\b)", statement, re.I))
            series = V.query(statement)
            rows = V.rows(series)
            numeric = [r for r in rows if any(isinstance(v, (int, float)) and math.isfinite(v) for k, v in r.items() if k != "time")]
            check(f"panel{panel['id']}:hasData", bool(numeric))
            if panel["id"] == 2:
                expected_keys = {(phase, str(size), case) for phase in ("before", "after") for size in (10000, 100000) for case in C.SCENARIOS}
                actual_keys = set()
                for s in series:
                    key = (s["tags"]["phase"], s["tags"]["dataset"], s["tags"]["scenario"])
                    actual_keys.add(key)
                    want = report["groups"][key[0]][key[1]][key[2]]["p95Ms"]["median"]
                    check("p95Median:" + "/".join(key), V.same(s["values"][0][1], want))
                check("eightMainP95Groups", actual_keys == expected_keys)
            if panel["id"] in (3, 4, 20):
                visible = ({s.get("tags", {}).get("run_id") for s in series} if panel["id"] == 3
                           else {r.get("실행 ID") for r in rows})
                expected_ids = {r["runId"] for r in (controls if panel["id"] == 20 else main_runs)}
                check(f"panel{panel['id']}:allIntendedRunsAndOnlyThose", visible == expected_ids)
            if panel["id"] in (5, 6):
                field = "ms_per_call" if panel["id"] == 5 else "rows_examined_per_call"
                expected_serial = {tuple(str(p["tags"][k]) for k in ("phase", "dataset", "scenario", "kind")): p["fields"][field]
                                   for p in points if p["measurement"] == C.SERIAL and p["tags"]["page"] == 0}
                seen = set()
                for s in series:
                    key = tuple(s["tags"][k] for k in ("phase", "dataset", "scenario", "kind"))
                    seen.add(key)
                    check(f"serialPanel{panel['id']}:" + "/".join(key), key in expected_serial and V.same(s["values"][0][1], expected_serial[key]))
                check(f"serialPanel{panel['id']}:complete", seen == set(expected_serial))
            result["panels"].append({"id": panel["id"], "title": panel["title"], "series": len(series), "numericRows": len(numeric)})
    check("eighteenPanelQueries", targets == 18)
    result["selectedRun"] = selected["runId"]
    result["evidencePassed"] = not failures
    result["afterPerformanceCriteriaPassed"] = report["afterPerformanceCriteriaPassed"]
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
