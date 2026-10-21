#!/usr/bin/env python3
"""검수 목록 개선 전후 결과를 검증·집계합니다. --publish만 새 Grafana 파생 지표를 기록합니다."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import statistics
import urllib.parse
import urllib.request


SPEC = importlib.util.spec_from_file_location("review_report", Path(__file__).with_name("report-review.py"))
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)
read, require, stats, table, number = BASE.read, BASE.require, BASE.stats, BASE.table, BASE.number
SCENARIOS = BASE.SCENARIOS
KINDS = {"employee": "직원 인증", "list": "영수증 전체 목록", "ids": "목록 ID 선조회",
         "entities": "선택한 영수증 본문", "count": "전체 건수 COUNT", "jobs": "작업 상태 배치"}
LABELS = {"before": "개선 전", "after": "개선 후", "control": "당일 개선 전 재확인"}
UID = "receipt-review-comparison"
SUMMARY = "receipt_review_comparison_summary"
SQL = "receipt_review_comparison_sql"
SERIAL = "receipt_review_comparison_serial"
INFLUX = "http://127.0.0.1:18086"


def spread(value, unit=""):
    return f"{number(value['median'])}{unit} ({number(value['min'])}~{number(value['max'])})"


def toggle(title, body):
    return '<details>\n<summary>' + title + '</summary>\n' + '\n'.join(
        '\t' + line if line else '' for line in body.splitlines()) + '\n</details>'


def dashboard_url(comparison_id, run):
    return f"http://localhost:13000/d/{UID}/review-comparison?" + urllib.parse.urlencode({
        "orgId": 1, "var-comparison_id": comparison_id, "var-run_id": run["runId"],
        "from": run["startNs"] // 1000000, "to": run["endNs"] // 1000000, "timezone": "browser"})


def validate_runs(folder, meta, phase):
    require(meta.get("completed") is True, f"{phase} 측정이 완료되지 않았습니다.")
    require(meta.get("restoredOriginalDatabase") is True, f"{phase} 원래 DB 복원 확인이 없습니다.")
    require(meta["originalReceiptCount"] == meta.get("originalReceiptCountAfter") == 3233,
            f"{phase} 원래 영수증 3,233건 보존을 확인해야 합니다.")
    require((meta["rate"], meta["seconds"], meta["warmup"], meta["repeats"]) == (20, 60, 30, 3),
            f"{phase} 부하 조건이 다릅니다.")
    runs = meta["runs"]
    expected = Counter((n, c, r) for n in (10000, 100000) for c in SCENARIOS for r in (1, 2, 3))
    require(Counter((r["dataset"], r["scenario"], r["repeat"]) for r in runs) == expected,
            f"{phase} 계획한 12회 조합이 필요합니다.")
    require(len({r["imageId"] for r in runs}) == 1, f"{phase} 실행 중 앱 이미지가 바뀌었습니다.")
    for run in runs:
        validate_run(folder, run)
    return runs


def validate_run(folder, run):
    require(read(folder / run["runId"] / "verification.json") == run, f"원본 verification 불일치: {run['runId']}")
    require((run["rate"], run["seconds"], run["warmup"]) == (20, 60, 30), "실행별 부하 조건 불일치")
    require(run.get("k6ExitCode", 0) in (0, 99), "기준 미달 이외 k6 실행 오류")
    require(all(run["checks"].get(k) is True for k in ("monitor", "samples", "noSqlErrors")),
            f"증거 수집 검증 실패: {run['runId']}")
    require(run["requests"] + run["dropped"] in (1200, 1201), f"예정 도착 수 불일치: {run['runId']}")
    require(set(run["sql"]) <= set(KINDS), "알 수 없는 SQL 종류")
    for kind, item in run["sql"].items():
        require(item["count"] > 0 and item["errors"] == 0, f"SQL 관측 불완전: {run['runId']} {kind}")


def summarize(runs):
    groups = {}
    for phase in ("before", "after"):
        groups[phase] = {}
        for size in (10000, 100000):
            groups[phase][str(size)] = {}
            for case in SCENARIOS:
                selected = [r for r in runs if r["phase"] == phase and r["dataset"] == size and r["scenario"] == case]
                kinds = set(selected[0]["sql"])
                require(all(set(r["sql"]) == kinds for r in selected), "동일 단계 실행의 SQL 종류가 다릅니다.")
                sql = {kind: {"msPerCall": stats(r["sql"][kind]["timerMs"] / r["sql"][kind]["count"] for r in selected),
                              "rowsExaminedPerCall": stats(r["sql"][kind]["rowsExamined"] / r["sql"][kind]["count"] for r in selected)}
                       for kind in sorted(kinds)}
                groups[phase][str(size)][case] = {
                    "runIds": [r["runId"] for r in selected],
                    "p95Ms": stats(r["latency"]["p(95)"] for r in selected),
                    "p99Ms": stats(r["latency"]["p(99)"] for r in selected),
                    "requests": sum(r["requests"] for r in selected),
                    "failures": sum(r["failures"] for r in selected), "dropped": sum(r["dropped"] for r in selected),
                    "sql": sql,
                    "resources": {service: {key: stats(r["resources"][service][key] for r in selected) for key in keys}
                                  for service, keys in {"app": ("cpuMeanPct", "memoryPeakMiB"),
                                                        "mysql": ("cpuMeanPct", "memoryPeakMiB"),
                                                        "db": ("connectionsMax", "queryActiveMax")}.items()},
                }
    return groups


def collect_profiles(before, after):
    profiles = []
    for size in (10000, 100000):
        for case, page in (("all", 0), ("status_period", 0), ("all", 50)):
            name = f"{case}-{page}"
            left = read(before / str(size) / f"fixture-{name}.json")
            right = read(after / str(size) / f"fixture-{name}.json")
            require(all(left[k] == right[k] for k in ("path", "expected", "sha256", "responseBytes")),
                    f"전후 목록 응답이 달라졌습니다: {size} {name}")
            for phase, root in (("before", before), ("after", after)):
                directory = root / str(size)
                serial = read(directory / f"serial-{name}.json")
                require(serial["requests"] == 100, "순차 검증은 100건이어야 합니다.")
                expected_kinds = {"employee", "list", "count", "jobs"} if phase == "before" else {"employee", "ids", "entities", "count", "jobs"}
                require(set(serial["sql"]) == expected_kinds, f"순차 SQL 종류 불일치: {phase} {size} {name}")
                require(all(v["count"] == 100 and v["errors"] == 0 for v in serial["sql"].values()),
                        f"순차 SQL 호출 수 불일치: {phase} {size} {name}")
                plans = read(directory / f"explain-{name}.json")
                buckets = {}
                for entry in plans:
                    kind = entry.get("kind") or ("count" if "count(" in entry["sql"].lower() else "list")
                    require(kind in expected_kinds, "실행 계획의 SQL 종류를 확인하세요.")
                    buckets.setdefault(kind, []).append(entry)
                require({"count", "list" if phase == "before" else "ids"} <= buckets.keys(), "목록/COUNT 실행 계획이 없습니다.")
                summaries = {}
                for kind, entries in buckets.items():
                    require(len(entries) == 3 and {x["repeat"] for x in entries} == {1, 2, 3}, "SQL별 EXPLAIN 3회가 필요합니다.")
                    times = []
                    for entry in entries:
                        match = re.search(r"actual time=[\d.eE+-]+?\.\.([\d.eE+-]+)", entry["plan"].splitlines()[0])
                        require(match is not None, "EXPLAIN 최상위 시간 해석 실패")
                        times.append(float(match.group(1)))
                    summaries[kind] = {"topNodeMs": stats(times), "plans": entries}
                profiles.append({"phase": phase, "dataset": size, "scenario": case, "page": page,
                                 "fixture": left, "serial": {"requests": 100, "sql": serial["sql"]}, "explain": summaries})
    return profiles


def load_comparison(after):
    meta = read(after / "experiment.json")
    before = Path(meta["baselineExperiment"]).resolve()
    require(after != before, "원래 baseline 폴더에는 비교 보고서를 쓰지 않습니다.")
    baseline = read(before / "experiment.json")
    before_runs = validate_runs(before, baseline, "before")
    after_runs = validate_runs(after, meta, "after")
    for key in ("innodbBufferPoolBytes", "dockerCpuCount", "mysqlVersion"):
        require(meta.get(key) == baseline.get(key) and meta.get(key) is not None, f"환경 조건이 다릅니다: {key}")
    proof_path = Path(meta.get("comparabilityProof", "comparability.json"))
    proof_path = proof_path if proof_path.is_absolute() else after / proof_path
    proof = read(proof_path)
    require(proof.get("passed") is True, "데이터/실행 환경 동일성 증거가 통과하지 않았습니다.")
    require(proof.get("checks") and all(v is True for v in proof["checks"].values()), "동일성 검사 항목을 확인하세요.")
    controls = meta.get("beforeControls", [])
    require(Counter((r["dataset"], r["scenario"]) for r in controls) == Counter(((10000, "all"), (100000, "all"))),
            "당일 개선 전 재확인 2회가 필요합니다.")
    control_path = Path(meta["controlExperiment"]).resolve()
    for run in controls:
        validate_run(control_path, run)
        require(run["imageId"] == before_runs[0]["imageId"], "당일 재확인 앱 이미지가 이전 기준선과 다릅니다.")
    runs = [dict(r, phase=phase, sourceExperiment=str(folder))
            for phase, folder, selected in (("before", before, before_runs), ("after", after, after_runs), ("control", control_path, controls))
            for r in selected]
    require(len({r["runId"] for r in runs}) == 26, "비교 실행 ID가 중복됩니다.")
    request_assessments = {}
    for folder, selected in ((before, before_runs), (after, after_runs), (control_path, controls)):
        request_assessments.update(BASE.assess_raw_requests(folder, selected))
    for run in runs:
        assessment = request_assessments[run["runId"]]
        require(set(assessment["httpStatusCounts"]) <= ({"0", "200"} | {str(n) for n in range(500, 600)}),
                "인증 실패 또는 예상하지 못한 HTTP 상태가 있습니다.")
        fixture = read(Path(run["sourceExperiment"]) / run["runId"] / f"fixture-{run['scenario']}-0.json")
        baseline_fixture = read(before / str(run["dataset"]) / f"fixture-{run['scenario']}-0.json")
        require(all(fixture[k] == baseline_fixture[k] for k in ("path", "expected", "sha256", "responseBytes")),
                f"실행별 응답 기준 해시 불일치: {run['runId']}")
    return {"kind": "review-query-before-after", "completed": True, "comparisonId": after.name,
            "baselineExperiment": str(before), "optimizedExperiment": str(after), "controlExperiment": str(control_path),
            "metadata": {"before": baseline, "after": meta}, "comparabilityProof": proof,
            "groups": summarize(runs), "runs": runs, "requestAssessments": request_assessments,
            "profiles": collect_profiles(before, after),
            "baselineNotionUrl": read(before / "publication.json").get("notionUrl"),
            "sourceManifest": {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                               for path in (before / "experiment.json", after / "experiment.json", proof_path)},
            "dataIntegrityPassed": True,
            "afterPerformanceCriteriaPassed": all(r["failures"] == 0 and r["dropped"] == 0 for r in after_runs)}


def publication_points(report):
    points = []
    for run in report["runs"]:
        tags = {"comparison_id": report["comparisonId"], "phase": run["phase"], "run_id": run["runId"],
                "dataset": run["dataset"], "scenario": run["scenario"], "repeat": run["repeat"]}
        assessment = report["requestAssessments"][run["runId"]]
        r = run["resources"]
        fields = {"p95_ms": run["latency"]["p(95)"], "p99_ms": run["latency"]["p(99)"], "mean_ms": run["latency"]["avg"],
                  "requests": run["requests"], "failures": run["failures"], "dropped": run["dropped"],
                  "timeouts": assessment["requestTimeouts"], "response_bytes_per_request": run["responseBytesPerRequest"],
                  "mysql_cpu_mean": r["mysql"]["cpuMeanPct"], "app_cpu_mean": r["app"]["cpuMeanPct"],
                  "app_memory_peak_mib": r["app"]["memoryPeakMiB"], "mysql_memory_peak_mib": r["mysql"]["memoryPeakMiB"],
                  "db_connections_max": r["db"]["connectionsMax"], "db_query_active_max": r["db"]["queryActiveMax"],
                  "observation_seconds": (run["endNs"] - run["startNs"]) / 1e9, "original_passed": int(run["passed"]),
                  "warmup_dropped": assessment["warmupDropped"], "warmup_failures": assessment["warmupFailures"]}
        points.append({"measurement": SUMMARY, "tags": tags, "fields": fields, "timestamp": run["endNs"]})
        for kind, sql in run["sql"].items():
            fields = {"calls": sql["count"], "total_ms": sql["timerMs"], "ms_per_call": sql["timerMs"] / sql["count"],
                      "ms_per_request": sql["timerMs"] / run["requests"], "rows_examined_per_call": sql["rowsExamined"] / sql["count"],
                      "rows_sent_per_call": sql["rowsSent"] / sql["count"]}
            points.append({"measurement": SQL, "tags": dict(tags, kind=kind), "fields": fields, "timestamp": run["endNs"]})
    timestamp = max(r["endNs"] for r in report["runs"])
    for profile in report["profiles"]:
        for kind, sql in profile["serial"]["sql"].items():
            points.append({"measurement": SERIAL, "timestamp": timestamp,
                           "tags": {"comparison_id": report["comparisonId"], "phase": profile["phase"], "dataset": profile["dataset"],
                                    "scenario": profile["scenario"], "page": profile["page"], "kind": kind},
                           "fields": {"calls": sql["count"], "ms_per_call": sql["timerMs"] / sql["count"],
                                      "rows_examined_per_call": sql["rowsExamined"] / sql["count"],
                                      "rows_sent_per_call": sql["rowsSent"] / sql["count"]}})
    return points


def publish(points):
    lines = []
    for point in points:
        require(point["measurement"] in (SUMMARY, SQL, SERIAL), "원본 측정에는 기록하지 않습니다.")
        require(all(re.fullmatch(r"[A-Za-z0-9_-]+", str(v)) for v in point["tags"].values()), "지원하지 않는 Influx tag")
        require(all(math.isfinite(v) for v in point["fields"].values()), "유한하지 않은 지표")
        tags = ','.join(f"{k}={v}" for k, v in point["tags"].items())
        fields = ','.join(f"{k}={float(v)}" for k, v in point["fields"].items())
        lines.append(f"{point['measurement']},{tags} {fields} {point['timestamp']}")
    request = urllib.request.Request(INFLUX + "/write?db=receipt_loadtest", data=('\n'.join(lines)).encode(), method="POST")
    with urllib.request.urlopen(request, timeout=30) as response:
        require(response.status == 204, "비교 파생 지표 게시 실패")


def render(report):
    groups, runs = report["groups"], report["runs"]
    last = next(r for r in reversed(runs) if r["phase"] == "after")
    results = [["데이터 / 조건", "개선 전 p95 중앙값 (범위)", "개선 후 p95 중앙값 (범위)", "중앙값 변화", "실패 전→후", "미실행 전→후"]]
    resources = [["데이터 / 조건", "앱 CPU 평균 전→후", "MySQL CPU 평균 전→후", "앱 메모리 최대 전→후", "DB 실행 중 연결 최대 전→후"]]
    improved_groups = 0
    for size in ("10000", "100000"):
        for case, label in SCENARIOS.items():
            a, b = groups["before"][size][case], groups["after"][size][case]
            reduction = (1 - b["p95Ms"]["median"] / a["p95Ms"]["median"]) * 100
            improved_groups += int(reduction > 0)
            change = number(abs(reduction), 2) + ("% 감소" if reduction >= 0 else "% 증가")
            results.append([f"{int(size):,} / {label}", spread(a["p95Ms"], " ms"), spread(b["p95Ms"], " ms"), change,
                            f"{a['failures']} → {b['failures']}", f"{a['dropped']} → {b['dropped']}"])
            ra, rb = a["resources"], b["resources"]
            resources.append([f"{int(size):,} / {label}"] + [
                f"{number(ra[service][key]['median'])} → {number(rb[service][key]['median'])} {unit}"
                for service, key, unit in (("app", "cpuMeanPct", "%"), ("mysql", "cpuMeanPct", "%"),
                                           ("app", "memoryPeakMiB", "MiB"), ("db", "queryActiveMax", "개"))])
    serial = [["단계 / 데이터 / 조건 / 페이지", "SQL", "100 요청의 실행 수", "SQL 평균 ms", "검사 행 / 실행"]]
    plan_rows = [["단계 / 데이터 / 조건 / 페이지", "SQL", "EXPLAIN 시간 중앙값 (범위)"]]
    plan_details = []
    for p in report["profiles"]:
        label = f"{LABELS[p['phase']]} / {p['dataset']:,} / {SCENARIOS[p['scenario']]} / {p['page']}"
        for kind, q in p["serial"]["sql"].items():
            serial.append([label, KINDS[kind], q["count"], number(q["timerMs"] / q["count"]), number(q["rowsExamined"] / q["count"], 1)])
        for kind, data in p["explain"].items():
            plan_rows.append([label, KINDS[kind], spread(data["topNodeMs"], " ms")])
            if p["dataset"] == 100000 and p["page"] == 0:
                plan_details.append(f"### {label} · {KINDS[kind]}\n\n```text\n{data['plans'][0]['plan']}\n```")
    core_sql = [["10만 건 조건 / 대상", "개선 전 평균 ms", "개선 후 평균 ms", "개선 전 검사 행", "개선 후 검사 행"]]
    for case, label in SCENARIOS.items():
        previous = next(p["serial"]["sql"] for p in report["profiles"]
                        if p["phase"] == "before" and p["dataset"] == 100000 and p["scenario"] == case and p["page"] == 0)
        current = next(p["serial"]["sql"] for p in report["profiles"]
                       if p["phase"] == "after" and p["dataset"] == 100000 and p["scenario"] == case and p["page"] == 0)
        for old_kind, new_kind, purpose in (("list", "ids", "목록 대상 선택"), (None, "entities", "선택한 20건 본문"), ("count", "count", "전체 건수")):
            old = previous[old_kind] if old_kind else None
            new = current[new_kind]
            core_sql.append([f"{label} / {purpose}", number(old["timerMs"] / old["count"]) if old else "이전 목록에 포함",
                             number(new["timerMs"] / new["count"]), number(old["rowsExamined"] / old["count"], 1) if old else "별도 조회 없음",
                             number(new["rowsExamined"] / new["count"], 1)])
    controls = [["데이터", "이전 기준 p95 중앙값", "당일 같은 이전 코드 p95 (1회)", "응답 실패 / 미실행", "관측 기간 초"]]
    for r in runs:
        if r["phase"] == "control":
            controls.append([f"{r['dataset']:,}", number(groups['before'][str(r['dataset'])]['all']['p95Ms']['median']),
                             number(r['latency']['p(95)']), f"{r['failures']} / {r['dropped']}", number((r['endNs']-r['startNs'])/1e9)])
    raw = [["단계 / run_id", "데이터 / 조건 / 반복", "요청 / 실패 / 미실행", "p95 / p99 ms", "관측 기간 초", "원본 passed"]]
    raw_sql = [["단계 / run_id", "SQL", "호출 수", "총 시간 ms", "검사 행", "반환 행"]]
    raw_resources = [["단계 / run_id", "앱 CPU / MySQL CPU 평균 %", "앱 / MySQL 최대 MiB", "DB 열린 / 실행 중 최대", "준비 부하 실패 / 미실행"]]
    links = []
    for r in runs:
        label = f"{LABELS[r['phase']]} · {r['runId']}"
        raw.append([label, f"{r['dataset']:,} / {SCENARIOS[r['scenario']]} / {r['repeat']}",
                    f"{r['requests']} / {r['failures']} / {r['dropped']}", f"{number(r['latency']['p(95)'])} / {number(r['latency']['p(99)'])}",
                    number((r['endNs']-r['startNs'])/1e9), str(r['passed']).lower()])
        resources_run = r["resources"]
        assessment = report["requestAssessments"][r["runId"]]
        raw_resources.append([label, f"{number(resources_run['app']['cpuMeanPct'])} / {number(resources_run['mysql']['cpuMeanPct'])}",
                              f"{resources_run['app']['memoryPeakMiB']} / {resources_run['mysql']['memoryPeakMiB']}",
                              f"{resources_run['db']['connectionsMax']} / {resources_run['db']['queryActiveMax']}",
                              f"{assessment['warmupFailures']} / {assessment['warmupDropped']}"])
        for kind, q in r["sql"].items():
            raw_sql.append([label, KINDS[kind], q["count"], number(q["timerMs"]), q["rowsExamined"], q["rowsSent"]])
        links.append(f"- [{label}]({dashboard_url(report['comparisonId'], r)})")
    before_failed = sum(r["failures"] for r in runs if r["phase"] == "before")
    after_failed = sum(r["failures"] for r in runs if r["phase"] == "after")
    before_dropped = sum(r["dropped"] for r in runs if r["phase"] == "before")
    after_dropped = sum(r["dropped"] for r in runs if r["phase"] == "after")
    same_day_large = next(r for r in runs if r["phase"] == "control" and r["dataset"] == 100000 and r["scenario"] == "all")
    same_day_caution = (
        "**측정 날짜 차이:** 개선 전 본비교는 전날 기준선입니다. "
        f"오늘 같은 이전 코드로 10만 건 전체 목록을 다시 확인했을 때도 p95 **{number(same_day_large['latency']['p(95)'] / 1000, 2)}초**, "
        f"미실행 **{same_day_large['dropped']}건**, 응답 실패 **{same_day_large['failures']}건**으로 병목을 확인했습니다. "
        "같은 로컬 환경에서도 실행 시점의 상태에 따라 수치가 달라지므로, 위 감소율에는 환경 변동의 영향도 포함됩니다.")
    verdict = ("이번 네 조건 모두에서 p95 중앙값이 낮아졌고, 개선 후 응답 실패·미실행이 없었습니다."
               if improved_groups == 4 and after_failed == 0 and after_dropped == 0
               else f"네 조건 중 {improved_groups}개에서 p95 중앙값이 낮아졌습니다. 개선 후에도 실패·미실행이나 지연 증가가 있는지는 표의 각 조건을 확인해야 하며 일괄 개선으로 단정하지 않습니다.")
    baseline_mention = (f'<mention-page url="{report["baselineNotionUrl"]}">영수증 부하테스트 5차 — 검수 목록 개선 전</mention-page>'
                        if report.get("baselineNotionUrl") else "영수증 부하테스트 5차 — 검수 목록 개선 전")
    conditions = table([['항목', '전후 공통 조건'],
        ['데이터', '같은 합성 영수증 1만·10만 건. 데이터 동일성 검사와 기존 6개 fixture의 전체 응답 SHA-256/IDs/전체 건수 대조'],
        ['API', '검수 대기 전체 / NEEDS_REVIEW+2026년 9월, page=0,size=20. page=50은 단독 SQL 참고 측정'],
        ['부하', '각 20 RPS × 도착 주입 60초 × 3회. 매 실행 앱 재시작 후 같은 속도로 30초 준비 부하'],
        ['클라이언트', '한 검수자 세션, preAllocatedVUs=20/maxVUs=100, HTTP timeout=15초, gracefulStop=20초'],
        ['환경', f"같은 Mac, Docker CPU {report['metadata']['after']['dockerCpuCount']}개, MySQL {report['metadata']['after']['mysqlVersion']}, 버퍼풀 {report['metadata']['after']['innodbBufferPoolBytes']//1024**2} MiB. 원래 DB 3,233건 보존·앱 연결 복원 확인"],
        ['외부 호출', 'AI/S3 호출 없음. 추출기 2초 설정은 유지하되 완료된 데이터의 목록 조회이므로 추출 완료 시간은 미측정'],
        ['프론트', '프론트를 거치지 않고 k6가 API를 직접 호출. 자동 조회 제거는 이번 성능 변화와 별도'],
    ])
    return f'''<mention-date start="2026-10-19"/> · 영수증 부하테스트 6차 · 검수 목록 쿼리 개선 전후

이전 기록: {baseline_mention}

## 어떤 문제를 해결하려고 했나

검수자는 최신 영수증 20건만 필요하지만, 이전 조회는 영수증 전체를 읽어 정렬하고 전체 페이지 수를 구하려고 다시 COUNT했습니다. 10만 건 조건에서 SQL 비용이 커지고 요청이 밀렸습니다. 이번에는 **목록의 ID를 먼저 조회한 뒤 선택된 20건의 본문만 PK로 가져오고, 검색·정렬 조건에 맞춘 인덱스를 사용하도록 조회를 변경**했습니다.

본인 영수증·소유자 없는 영수증 제외, 검수 상태·기간, 최신순 정렬, 전체 건수·페이지 응답은 유지했습니다. 이전에 적용한 직원 조회 재사용도 전후 공통입니다.

## 결과부터 보기

{table(results)}

{same_day_caution}

본비교는 개선 전 12회와 개선 후 12회입니다. 응답 실패는 **{before_failed} → {after_failed}건**, k6 미실행은 **{before_dropped} → {after_dropped}건**입니다. 당일 이전 코드 재확인 2회는 아래 보조 표로 따로 두고 이 집계에 섞지 않았습니다.

**{verdict}**

각 p95는 실행별 지연 표본에서 계산했고, 표에는 세 실행 p95의 중앙값과 범위를 표시했습니다. 실제 실행되어 타임아웃된 요청도 지연에 포함하고, 시작하지 못한 dropped는 포함하지 않습니다. 따라서 개선 전후 응답 시간과 실패·미실행을 함께 봐야 합니다. 감소율은 같은 데이터·조건의 중앙값 계산이며 운영 서비스의 개선율로 일반화하지 않습니다.

## 코드는 어떻게 달라졌나

```text
개선 전: 직원 확인 → 영수증 본문 목록 + COUNT → 작업 상태 배치 → 응답
개선 후: 직원 확인 → 영수증 ID 목록 + COUNT → 선택한 본문 PK 배치 → 작업 상태 배치 → 같은 응답
```

추가한 인덱스는 다음과 같습니다. `ReceiptRepository.findReviewQueueIds()`에서 ID 페이지를 고르고, `ReceiptQueryService`에서 본문을 일괄 조회한 뒤 원래 ID 순서로 정렬합니다.

```sql
CREATE INDEX ix_receipts_review_status_created
ON receipts (status, created_at, id, owner_employee_id);
```

SQL을 무조건 적게 실행하는 것이 목적은 아닙니다. 조회 단계가 하나 늘어도 필요한 20건을 먼저 정하고 큰 JSON 본문을 그 대상에만 읽도록 만들었습니다. 인덱스와 실제 실행 계획이 읽는 행·정렬 비용을 얼마나 바꿨는지는 아래 단독 SQL 측정으로 확인합니다. 인덱스와 조회 방식이 함께 변경되었으므로 각각의 기여율을 분리해 측정했다고 말하지 않습니다.

추가 인덱스의 통계상 크기는 10만 건에서 약 {report['comparabilityProof']['indexSpace']['100000']['afterPerIndexBytes']['ix_receipts_review_status_created'] / 1024**2:.2f} MiB입니다. 저장 공간과 쓰기 시 인덱스 갱신 비용이 추가되며, 쓰기 성능의 변화는 이번 읽기 부하에서 측정하지 않았습니다.

## 비교 조건을 어떻게 맞췄나

같은 **1만·10만 건, 20 RPS, 60초, 준비 부하 30초, 각 조건 3회**로 비교했습니다. 전체 검수 목록과 상태·기간 검색을 사용하고, 전후 응답의 ID·전체 건수·본문 해시를 확인했습니다. 서버 설정이나 버퍼풀 크기를 키우지 않았고 AI/S3 호출은 없습니다.

{toggle('상세 측정 조건 펼치기', conditions)}

10만 건 기존 테이블 데이터 영역은 약 195.75 MiB로 128 MiB 버퍼풀보다 컸습니다. 준비 부하를 주어도 전체 데이터가 캐시에 상주한다는 뜻은 아닙니다. 이번 비교는 버퍼풀 크기를 키우지 않고 쿼리·인덱스를 변경했습니다. 추가 인덱스의 공간 비용과 최종 구성은 데이터/환경 증거 및 실행 계획 원본에 남겼습니다.

## 어떤 SQL 비용이 달라졌나

아래는 **10만 건에서 API 100건을 순차 실행**한 결과입니다. 먼저 SQL 한 번의 평균 시간과 검사 행 수로 변경 효과를 확인합니다.

{table(core_sql)}

개선 전에는 목록 SQL 하나가 대상 선택과 큰 본문 읽기를 함께 했고, 개선 후에는 ID 선택과 선택한 본문 조회로 나뉩니다. 따라서 두 개선 후 단계를 함께 봅니다. 각 SQL은 이 검증에서 정확히 100회 실행됐습니다. 검사 행은 논리적 검사량이며 디스크 바이트가 아닙니다. COUNT는 전체 페이지 수를 위해 유지합니다.

**실행 계획에서 확인한 변화는 두 조건이 다릅니다.**

- **전체 검수 목록:** 이전 목록·COUNT는 각각 테이블 10만 행을 읽었습니다. 개선 후 ID 목록·COUNT는 검수 대상 상태에 해당하는 인덱스 2만 행을 읽고, 큰 본문은 PK로 선택한 20건만 가져옵니다. 여러 상태의 결과를 최신순으로 합쳐야 하므로 **목록 정렬은 여전히 남아 있습니다.**
- **상태·기간 검색:** ID 목록은 해당 상태·기간의 인덱스를 역순으로 읽으며 **20건을 찾으면 종료하고 별도 정렬도 하지 않습니다.** COUNT는 같은 인덱스 범위의 411행을 확인하고 본인·소유자 없는 영수증을 제외한 405건을 셉니다. 선택한 본문 20건은 별도로 가져옵니다.

두 경우 모두 본문 전체를 먼저 읽는 비용은 줄였지만, 전체 목록은 아직 2만 인덱스 행과 정렬이 필요합니다. 모든 검색이 인덱스 20행만 읽도록 바뀌었다고 해석하면 안 됩니다. 위 행 수는 이번 10만 건 데이터의 실행 계획에서 관측한 값입니다.

{toggle('1만·10만 건 순차 SQL 전체 54행 펼치기', table(serial))}

EXPLAIN ANALYZE도 실제 SQL마다 3회 실행했습니다. 평균 시간만 보지 않고 어떤 인덱스·정렬을 사용하고 몇 행을 처리했는지 아래 원본 계획으로 확인할 수 있습니다. EXPLAIN에는 자체 계측 비용이 있으므로 API p95와는 다른 지표입니다.

{toggle('EXPLAIN 전체 실행 시간 표 펼치기', table(plan_rows))}

{toggle('10만 건 실행 계획 원본 펼치기', chr(10).join(plan_details))}

## CPU·메모리·DB 연결

{table(resources)}

CPU는 실행별 관측 평균의 세 번 중앙값, 메모리와 연결은 실행별 관측 최대의 세 번 중앙값입니다. 전체 범위와 실행별 원본은 comparison.json과 아래 부록에서 확인할 수 있습니다. 관측 기간은 startNs~endNs로, 60초 도착 주입 뒤 마지막 요청의 종료 대기까지 포함됩니다. Docker CPU 100%는 논리 CPU 하나 수준입니다. DB 실행 중 연결 수만으로 Hikari 대기 시간을 측정했다고 말하지 않습니다.

## 같은 날 이전 코드도 다시 확인

기존 baseline은 10월 18일, 개선 후는 10월 19일 측정입니다. 같은 날 이전 코드도 1만·10만 건 전체 목록을 각 한 번 확인했습니다. 이 두 건은 보조 결과이며 본비교의 3회 중앙값에 섞지 않았습니다. 날짜 차이를 없애거나 전후 무작위 교차 측정을 대신하는 검증은 아닙니다.

{toggle('당일 이전 코드 재확인 2회 펼치기', table(controls))}

## Grafana에서 보는 순서

[검수 목록 개선 전후 비교 대시보드]({dashboard_url(report['comparisonId'], last)})

1. before/after의 데이터·조건별 p95 중앙값과 각 실행을 확인합니다.
2. 실패·미실행과 단독 SQL 100건 시간/검사 행을 함께 봅니다.
3. run_id를 선택하고 해당 실행 시간으로 이동하면 10초 p95/p99와 CPU·메모리·DB 연결을 볼 수 있습니다. 당일 재확인(control)은 별도 표입니다.

동시 부하의 Performance Schema 차분은 집계 경합과 준비 부하/타임아웃 잔여 SQL의 측정 창 경계 영향을 포함할 수 있는 근삿값입니다. 정확한 SQL 횟수 증거는 위 순차 100건 표입니다. 기존 과부하 실행의 원본 passed=false, 타임아웃·미실행을 숨기지 않았습니다.

{toggle('실행별 Grafana 링크 펼치기', chr(10).join(links))}

## 검증 범위와 한계

- 전후 6개 fixture와 각 실행의 응답 기준 SHA-256·IDs·전체 건수가 일치했습니다. HTTP 200 응답은 모두 그 전체 응답 해시 검증을 통과했습니다.
- k6 요약·원시 JSON·InfluxDB 지연 표본 수와 자원 관측 결과를 대조했습니다. HTTP 오류·타임아웃·미실행은 결과에 남기고 응답 내용 불일치·수집 오류는 완료 처리하지 않습니다.
- 20 RPS는 목표 도착률입니다. 최대 100 VU가 모두 대기하면 요청을 시작하지 못할 수 있으므로 미실행을 HTTP 오류나 서버 최대 TPS로 바꾸어 해석하지 않습니다.
- 단기 로컬·읽기 전용·합성 데이터·한 세션 부하입니다. 운영 환경, 실제 다수 직원 세션, 업로드·검수 혼합 트래픽, 장시간 안정성은 별도 검증 대상입니다.
- 쿼리와 인덱스를 함께 변경했습니다. 순차 SQL 및 실행 계획으로 구조 변화를 확인했지만 각각의 최적화 기여율과 물리 디스크 I/O 감소를 독립 측정한 것은 아닙니다.

{toggle('24회 본비교와 2회 보조 확인의 원본 수치 펼치기', table(raw) + chr(10) + table(raw_resources) + chr(10) + table(raw_sql))}

{toggle('데이터·환경 동일성 증거 펼치기', '```json' + chr(10) + json.dumps(report['comparabilityProof'], ensure_ascii=False, indent=2) + chr(10) + '```')}

## 원본 기록

개선 전: `{report['baselineExperiment']}`
개선 후: `{report['optimizedExperiment']}`
당일 이전 코드 재확인: `{report['controlExperiment']}`

`comparison.json`은 원본 실행·집계·증거 참조를 보관합니다. 각 실행의 verification.json, summary.json, metrics.json, SQL 전후 스냅샷, 자원 관측 파일은 수정하지 않았습니다. 개선 전 Grafana/Notion 기록도 보존하고 이번 비교는 별도 문서·대시보드로 작성했습니다.
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("experiment", type=Path)
    parser.add_argument("--publish", action="store_true", help="검증 완료 후 로컬 InfluxDB의 새로운 비교 파생 지표만 기록")
    args = parser.parse_args()
    folder = args.experiment.resolve()
    report = load_comparison(folder)
    report["grafanaUrl"] = dashboard_url(report["comparisonId"], next(r for r in reversed(report["runs"]) if r["phase"] == "after"))
    points = publication_points(report)
    content = render(report)
    (folder / "comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (folder / "notion-content.txt").write_text(content)
    (folder / "comparison-points.json").write_text(json.dumps(points, ensure_ascii=False, indent=2) + "\n")
    if args.publish:
        publish(points)
        print(f"Published {len(points)} derived comparison points; original measurements untouched.")
    print(folder / "comparison.json")
    print(folder / "notion-content.txt")


if __name__ == "__main__":
    main()
