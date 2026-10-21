#!/usr/bin/env python3
"""검수 목록 두 방식의 원본·Influx·Grafana를 읽기 전용으로 대조합니다. 결과는 stdout만 사용합니다."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import sys


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


C = module("review_ablation_report", "report-review-ablation.py")
V = module("review_dashboard", "verify-review-dashboard.py")
ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--experiment", type=Path, required=True)
    args = parser.parse_args()
    folder = args.experiment.resolve()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", folder.name):
        parser.error("지원하지 않는 실험 ID")
    failures = []
    result = {"comparisonId": folder.name, "checks": {}, "runs": [], "panels": [], "errors": failures}

    def check(name, condition):
        result["checks"][name] = bool(condition)
        if not condition:
            failures.append(name)

    try:
        # 보고서 생성 함수의 읽기 전용 부분만 호출하여 원본으로부터 다시 집계합니다.
        fresh = C.load_report(folder)
        report = C.read(folder / "comparison.json")
        check("reportMatchesOriginalData", all(report.get(k) == v for k, v in fresh.items()))
        check("completedAndRestored", fresh["completed"] and fresh["metadata"]["restoredOriginalDatabase"])
        runs, metadata = fresh["runs"], fresh["metadata"]
        expected = Counter((v, n, c, r) for v in C.VARIANTS for n in (10000, 100000)
                           for c in C.SCENARIOS for r in (1, 2, 3))
        check("exactTwentyFourRuns", Counter((r["variant"], r["dataset"], r["scenario"], r["repeat"]) for r in runs) == expected)
        check("uniqueRunIds", len({r["runId"] for r in runs}) == 24)
        for pair in fresh["pairs"]:
            check(f"threePairs:{pair['dataset']}:{pair['scenario']}", len(pair["idFirstMinusIndexOnlyP95Ms"]) == 3)
        for path, digest in fresh["sourceManifest"].items():
            check("sourceHash:" + path, hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest)
        for relative, digest in metadata["scripts"].items():
            check("measurementToolUnchanged:" + relative, hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() == digest)
        for size in (10000, 100000):
            check(f"datasetUnchanged:{size}", C.read(folder / str(size) / "fingerprint-before.json") ==
                  C.read(folder / str(size) / "fingerprint-after.json"))
        events = C.read(folder / "resume-events.json")
        check("resumeRecordIncluded", bool(events) and fresh.get("resumeEvents") == events)
        check("firstPauseAfterThreeRuns", events[0]["completedRuns"] == 3)
        all_run_ids = {r["runId"] for r in runs}
        for i, event in enumerate(events):
            preserved = set()
            for relative, digest in event["existingRunHashes"].items():
                path = (folder / relative).resolve()
                check(f"preservedPath:{i}:{relative}", folder in path.parents)
                check(f"preservedHash:{i}:{relative}", hashlib.sha256(path.read_bytes()).hexdigest() == digest)
                if path.name == "verification.json":
                    preserved.add(C.read(path)["runId"])
            check(f"preservedCompletedRuns:{i}", len(preserved) == event["completedRuns"] and preserved <= all_run_ids)

        points = C.publication_points(fresh)
        check("savedPointsMatchSource", C.read(folder / "comparison-points.json") == points)
        check("exactDerivedPointCounts", Counter(p["measurement"] for p in points) ==
              Counter({C.SUMMARY: 24, C.SQL: 108, C.SERIAL: 54}))
        for measurement in (C.SUMMARY, C.SQL, C.SERIAL):
            stored = V.rows(V.query(f'SELECT * FROM "{measurement}" WHERE "comparison_id"=\'{folder.name}\''))
            keys = ("variant", "dataset", "scenario", "page", "kind") if measurement == C.SERIAL else (
                ("run_id", "kind") if measurement == C.SQL else ("run_id",))
            indexed = {tuple(str(row.get(k)) for k in keys): row for row in stored}
            wanted = [p for p in points if p["measurement"] == measurement]
            check(measurement + ":exactRows", len(indexed) == len(stored) == len(wanted))
            for point in wanted:
                key = tuple(str(point["tags"][k]) for k in keys)
                row = indexed.get(key, {})
                check(measurement + ":" + "/".join(key),
                      all(str(row.get(k)) == str(v) for k, v in point["tags"].items()) and
                      all(V.same(row.get(k), v) for k, v in point["fields"].items()))

        for run in runs:
            run_id = run["runId"]
            C.require(re.fullmatch(r"[A-Za-z0-9_-]+", run_id), "지원하지 않는 실행 ID")
            directory = C.run_directory(folder, metadata, run)
            latencies = []
            for line in (directory / "metrics.json").open():
                point = json.loads(line)
                if point.get("type") == "Point" and point["metric"] == "receipt_review_ms":
                    latencies.append(point["data"]["value"])
            influx_latencies = [row["value"] for row in V.rows(V.query(
                f'SELECT "value" FROM "receipt_review_ms" WHERE "run_id"=\'{run_id}\''))]
            check(run_id + ":everyLatencySample", len(latencies) == len(influx_latencies) == run["requests"] and
                  all(V.same(a, b) for a, b in zip(sorted(latencies), sorted(influx_latencies))))
            metrics = V.query(f'SELECT sum("value") AS "count" FROM "receipt_review_requests", '
                              f'"receipt_review_failures", "receipt_review_response_bytes" WHERE "run_id"=\'{run_id}\'')
            totals = {s["name"]: s["values"][0][1] for s in metrics}
            check(run_id + ":rawRequestsFailuresBytes", totals.get("receipt_review_requests") == run["requests"] and
                  totals.get("receipt_review_failures") == run["failures"] and
                  V.same(totals.get("receipt_review_response_bytes", -1) / run["requests"], run["responseBytesPerRequest"]))
            statuses = {str(row.get("status")): row["count"] for row in V.rows(V.query(
                f'SELECT count("value") AS "count" FROM "http_req_duration" WHERE "run_id"=\'{run_id}\' GROUP BY "status"'))}
            check(run_id + ":rawStatusCounts", statuses == fresh["requestAssessments"][run_id]["httpStatusCounts"])
            result["runs"].append({"runId": run_id, "variant": run["variant"], "requests": run["requests"],
                                   "failures": run["failures"], "dropped": run["dropped"], "originalPassed": run["passed"]})

        dashboard = V.get(V.GRAFANA + "/api/dashboards/uid/" + C.UID)["dashboard"]
        check("eightPanels", dashboard["uid"] == C.UID and len(dashboard["panels"]) == 8)
        variables = dashboard["templating"]["list"]
        check("variables", [v["name"] for v in variables] == ["comparison_id", "run_id"])
        for variable in variables:
            query = variable["query"].replace("$comparison_id", re.escape(folder.name))
            actual = {row.get("value") for row in V.rows(V.query(query))}
            wanted = {folder.name} if variable["name"] == "comparison_id" else all_run_ids
            check("variable:" + variable["name"], wanted <= actual)
        selected = max(runs, key=lambda r: r["endNs"])
        bounds = f'time >= {selected["startNs"] - 5000000000} AND time <= {selected["endNs"] + 5000000000}'
        query_count = 0
        for panel in dashboard["panels"]:
            for target in panel.get("targets", []):
                query_count += 1
                check(f"panel{panel['id']}:datasource", target.get("datasource", {}).get("uid") == "receipt-loadtest-influx")
                statement = target["query"].replace("$comparison_id", re.escape(folder.name)).replace(
                    "$run_id", selected["runId"]).replace("$timeFilter", bounds)
                check(f"panel{panel['id']}:doesNotHideFailures", not re.search(r"\bWHERE\b.*(?:original_passed|\bpassed\b)", statement, re.I))
                series = V.query(statement)
                rows = V.rows(series)
                numeric = [row for row in rows if any(isinstance(v, (int, float)) and math.isfinite(v)
                           for k, v in row.items() if k != "time")]
                check(f"panel{panel['id']}:hasData", bool(numeric))
                if panel["id"] in (2, 3):
                    metric = "p95Ms" if panel["id"] == 2 else "p99Ms"
                    actual = set()
                    for s in series:
                        key = tuple(s["tags"][k] for k in ("variant", "dataset", "scenario"))
                        actual.add(key)
                        want = fresh["groups"][key[0]][key[1]][key[2]][metric]["median"]
                        check(metric + ":" + "/".join(key), V.same(s["values"][0][1], want))
                    check(f"panel{panel['id']}:allEightGroups", actual ==
                          {(v, str(n), c) for v in C.VARIANTS for n in (10000, 100000) for c in C.SCENARIOS})
                if panel["id"] == 4:
                    check("panel4:allTwentyFourRuns", {row.get("실행 ID") for row in rows} == all_run_ids)
                if panel["id"] == 5:
                    expected_serial = {tuple(str(p["tags"][k]) for k in ("variant", "dataset", "scenario", "kind")):
                                       p["fields"]["ms_per_call"] for p in points if p["measurement"] == C.SERIAL and p["tags"]["page"] == 0}
                    seen = set()
                    for s in series:
                        key = tuple(s["tags"][k] for k in ("variant", "dataset", "scenario", "kind"))
                        seen.add(key)
                        check("serialPanel:" + "/".join(key), key in expected_serial and V.same(s["values"][0][1], expected_serial.get(key)))
                    check("serialPanel:complete", seen == set(expected_serial))
                if panel["id"] == 6:
                    check("resourcePanel:allTwentyFourRuns", len(rows) == 24)
                result["panels"].append({"id": panel["id"], "title": panel["title"], "series": len(series), "numericRows": len(numeric)})
        check("sevenPanelQueries", query_count == 7)
        result["selectedRun"] = selected["runId"]
    except Exception as error:
        failures.append(f"{type(error).__name__}: {error}")
    result["evidencePassed"] = not failures
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
