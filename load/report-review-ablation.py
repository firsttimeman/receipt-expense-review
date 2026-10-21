#!/usr/bin/env python3
"""같은 인덱스에서 단일 조회와 ID 선조회를 비교합니다. 원본 기록은 수정하지 않습니다."""
import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import urllib.parse
import urllib.request


SPEC = importlib.util.spec_from_file_location("review_report", Path(__file__).with_name("report-review.py"))
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)
read, require, stats, table, number = BASE.read, BASE.require, BASE.stats, BASE.table, BASE.number
SCENARIOS = BASE.SCENARIOS
VARIANTS = {"index_only": "인덱스 + 한 번 조회", "id_first": "같은 인덱스 + ID 먼저 조회"}
KINDS = {"employee": "직원 인증", "list": "영수증 20건 조회", "ids": "영수증 번호 선택",
         "entities": "선택한 20건 데이터", "count": "조건에 맞는 전체 개수", "jobs": "작업 상태"}
EXPECTED = {"index_only": {"employee", "list", "count", "jobs"},
            "id_first": {"employee", "ids", "entities", "count", "jobs"}}
UID = "receipt-review-index-ablation"
SUMMARY, SQL, SERIAL = ("receipt_review_ablation_" + k for k in ("summary", "sql", "serial"))


def toggle(title, body):
    return '<details>\n<summary>' + title + '</summary>\n' + '\n'.join(
        '\t' + line if line else '' for line in body.splitlines()) + '\n</details>'


def spread(value, unit=""):
    return f"{number(value['median'])}{unit} ({number(value['min'])}~{number(value['max'])})"


def dashboard_url(experiment_id, run):
    return f"http://localhost:13000/d/{UID}/review-index-ablation?" + urllib.parse.urlencode({
        "orgId": 1, "var-comparison_id": experiment_id, "var-run_id": run["runId"],
        "from": run["startNs"] // 1000000, "to": run["endNs"] // 1000000, "timezone": "browser"})


def representative_run(report):
    target = report["groups"]["id_first"]["100000"]["all"]["p95Ms"]["median"]
    candidates = [r for r in report["runs"] if
                  (r["variant"], r["dataset"], r["scenario"]) == ("id_first", 100000, "all")]
    return min(candidates, key=lambda r: abs(r["latency"]["p(95)"] - target))


def run_directory(root, meta, run):
    if run.get("relativeDirectory"):
        path = root / run["relativeDirectory"]
    else:
        path = Path(meta["variants"][run["variant"]]["directory"])
        path = (path if path.is_absolute() else root / path) / run["runId"]
    path = path.resolve()
    require(root in path.parents, "실행 원본이 현재 실험 폴더 밖에 있습니다.")
    return path


def collect_profiles(root):
    profiles = []
    for size in (10000, 100000):
        for case, page in (("all", 0), ("status_period", 0), ("all", 50)):
            fixtures = []
            for variant in VARIANTS:
                directory = root / str(size) / variant
                name = f"{case}-{page}"
                fixture = read(directory / f"fixture-{name}.json")
                fixtures.append(fixture)
                serial = read(directory / f"serial-{name}.json")
                require(serial["requests"] == 100, "순차 검증은 조건별 100회여야 합니다.")
                require(set(serial["sql"]) == EXPECTED[variant], "순차 SQL 종류 불일치")
                require(all(q["count"] == 100 and q["errors"] == 0 for q in serial["sql"].values()),
                        "순차 SQL 검증 호출 수 또는 오류를 확인하세요.")
                plans = read(directory / f"explain-{name}.json")
                grouped = {}
                for entry in plans:
                    kind = entry.get("kind") or ("count" if "count(" in entry["sql"].lower() else "list")
                    require(kind in EXPECTED[variant], "알 수 없는 실행 계획 종류")
                    grouped.setdefault(kind, []).append(entry)
                require({"count", "list" if variant == "index_only" else "ids"} <= grouped.keys(),
                        "목록/COUNT 실행 계획이 없습니다.")
                summaries = {}
                for kind, entries in grouped.items():
                    require(len(entries) == 3 and {x["repeat"] for x in entries} == {1, 2, 3}, "EXPLAIN은 SQL별 3회 필요")
                    times = []
                    for entry in entries:
                        match = re.search(r"actual time=[\d.eE+-]+?\.\.([\d.eE+-]+)", entry["plan"].splitlines()[0])
                        require(match is not None, "EXPLAIN 최상위 시간 해석 실패")
                        times.append(float(match.group(1)))
                    summaries[kind] = {"topNodeMs": stats(times), "plans": entries}
                profiles.append({"variant": variant, "dataset": size, "scenario": case, "page": page,
                                 "fixture": fixture, "serial": serial, "explain": summaries})
            require(all(fixtures[0][k] == fixtures[1][k] for k in ("path", "expected", "sha256", "responseBytes")),
                    f"전체 응답 내용이 다릅니다: {size}/{case}/{page}")
    return profiles


def load_report(root):
    meta = read(root / "experiment.json")
    require(meta.get("completed") is True, "24회 실험이 아직 완료되지 않았습니다.")
    require(meta.get("restoredOriginalDatabase") is True, "원래 DB 복원 확인이 없습니다.")
    require(read(root / "original-fingerprint-before.json") == read(root / "original-fingerprint-after.json"),
            "원래 데이터의 전체 해시가 달라졌습니다.")
    require((meta["rate"], meta["seconds"], meta["warmup"], meta["repeats"]) == (20, 60, 30, 3), "실험 조건 불일치")
    runs = meta["runs"]
    require(Counter((r["dataset"], r["scenario"], r["repeat"], r["variant"]) for r in runs) ==
            Counter((n, c, r, v) for n in (10000, 100000) for c in SCENARIOS for r in (1, 2, 3) for v in VARIANTS),
            "계획한 24회 조합이 필요합니다.")
    require(len({r["runId"] for r in runs}) == 24, "실행 ID 중복")
    profiles = collect_profiles(root)
    assessments = {}
    for run in runs:
        directory = run_directory(root, meta, run)
        raw = read(directory / "verification.json")
        require(all(run.get(k) == v for k, v in raw.items()), f"원본 실행 기록과 다릅니다: {run['runId']}")
        require((run["rate"], run["seconds"], run["warmup"]) == (20, 60, 30), "실행별 부하 조건 불일치")
        require(run.get("k6ExitCode", 0) in (0, 99), "기준 미달 이외 k6 오류")
        require(all(run["checks"].get(k) is True for k in ("monitor", "samples", "noSqlErrors")), "증거 수집 검증 실패")
        require(set(run["sql"]) == EXPECTED[run["variant"]], "SQL 종류 불일치")
        require(all(q["count"] > 0 and q["errors"] == 0 for q in run["sql"].values()), "SQL 관측 불완전")
        require(run["requests"] + run["dropped"] in (1200, 1201), "예정 도착 수 불일치")
        for service in ("app", "mysql", "db"):
            require(run["resources"][service]["samples"] > 0, "자원 관측 표본 누락")
        assessments.update(BASE.assess_raw_requests(directory.parent, [run]))
        fixture = read(directory / f"fixture-{run['scenario']}-0.json")
        standard = next(p["fixture"] for p in profiles if p["dataset"] == run["dataset"] and
                        p["scenario"] == run["scenario"] and p["page"] == 0 and p["variant"] == run["variant"])
        require(all(fixture[k] == standard[k] for k in ("path", "expected", "sha256", "responseBytes")), "실행별 응답 불일치")
    for variant in VARIANTS:
        require(len({r["imageId"] for r in runs if r["variant"] == variant}) == 1, "같은 방식의 앱 이미지 변경")
    proof_path = root / meta.get("comparabilityProof", "comparability.json")
    proof = read(proof_path)
    require(proof.get("passed") is True and proof.get("checks") and all(v is True for v in proof["checks"].values()),
            "데이터·인덱스·환경 동일성 검증을 확인하세요.")
    groups = {v: {} for v in VARIANTS}
    pairs = []
    for size in (10000, 100000):
        for variant in VARIANTS:
            groups[variant][str(size)] = {}
            for case in SCENARIOS:
                selected = [r for r in runs if (r["variant"], r["dataset"], r["scenario"]) == (variant, size, case)]
                groups[variant][str(size)][case] = {
                    "runIds": [r["runId"] for r in selected],
                    "p95Ms": stats(r["latency"]["p(95)"] for r in selected),
                    "p99Ms": stats(r["latency"]["p(99)"] for r in selected),
                    "requests": sum(r["requests"] for r in selected), "failures": sum(r["failures"] for r in selected),
                    "dropped": sum(r["dropped"] for r in selected),
                    "resources": {service: {key: stats(r["resources"][service][key] for r in selected) for key in keys}
                                  for service, keys in {"app": ("cpuMeanPct", "memoryPeakMiB"),
                                                        "mysql": ("cpuMeanPct", "memoryPeakMiB"),
                                                        "db": ("connectionsMax", "queryActiveMax")}.items()}}
        for case in SCENARIOS:
            deltas = []
            for repeat in (1, 2, 3):
                matched = {r["variant"]: r for r in runs if (r["dataset"], r["scenario"], r["repeat"]) == (size, case, repeat)}
                a, b = matched["index_only"], matched["id_first"]
                require(a["pairId"] == b["pairId"] and {a["pairOrder"], b["pairOrder"]} == {1, 2}, "짝 비교 순서 불일치")
                deltas.append(b["latency"]["p(95)"] - a["latency"]["p(95)"])
            pairs.append({"dataset": size, "scenario": case, "idFirstMinusIndexOnlyP95Ms": deltas,
                          "deltaStats": stats(deltas)})
    resume_path = root / "resume-events.json"
    resume_events = read(resume_path) if resume_path.exists() else []
    sources = [root / "experiment.json", proof_path] + ([resume_path] if resume_path.exists() else [])
    image_path = root / "image-layer-comparison.json"
    image_comparison = read(image_path) if image_path.exists() else None
    if image_comparison:
        require(image_comparison["images"] == {v: meta["variants"][v]["imageId"] for v in VARIANTS},
                "이미지 계층 증거의 앱 이미지가 실험과 다릅니다.")
        sources.append(image_path)
    return {"kind": "review-index-ablation", "completed": True, "comparisonId": root.name,
            "experimentDirectory": str(root), "metadata": meta, "groups": groups, "pairs": pairs,
            "runs": runs, "profiles": profiles, "requestAssessments": assessments, "comparabilityProof": proof,
            "resumeEvents": resume_events,
            "imageLayerComparison": image_comparison,
            "sourceManifest": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}


def publication_points(report):
    points = []
    for run in report["runs"]:
        tags = {"comparison_id": report["comparisonId"], "variant": run["variant"], "run_id": run["runId"],
                "dataset": run["dataset"], "scenario": run["scenario"], "repeat": run["repeat"]}
        r, assessment = run["resources"], report["requestAssessments"][run["runId"]]
        fields = {"p95_ms": run["latency"]["p(95)"], "p99_ms": run["latency"]["p(99)"], "mean_ms": run["latency"]["avg"],
                  "requests": run["requests"], "failures": run["failures"], "dropped": run["dropped"],
                  "response_bytes_per_request": run["responseBytesPerRequest"], "original_passed": int(run["passed"]),
                  "app_cpu_mean": r["app"]["cpuMeanPct"], "mysql_cpu_mean": r["mysql"]["cpuMeanPct"],
                  "app_memory_peak_mib": r["app"]["memoryPeakMiB"], "mysql_memory_peak_mib": r["mysql"]["memoryPeakMiB"],
                  "db_connections_max": r["db"]["connectionsMax"], "db_query_active_max": r["db"]["queryActiveMax"],
                  "warmup_failures": assessment["warmupFailures"], "warmup_dropped": assessment["warmupDropped"],
                  "pair_order": run["pairOrder"]}
        points.append({"measurement": SUMMARY, "tags": tags, "fields": fields, "timestamp": run["endNs"]})
        for kind, q in run["sql"].items():
            fields = {"calls": q["count"], "total_ms": q["timerMs"], "ms_per_call": q["timerMs"] / q["count"],
                      "ms_per_request": q["timerMs"] / run["requests"], "rows_examined_per_call": q["rowsExamined"] / q["count"],
                      "rows_sent_per_call": q["rowsSent"] / q["count"]}
            points.append({"measurement": SQL, "tags": dict(tags, kind=kind), "fields": fields, "timestamp": run["endNs"]})
    for p in report["profiles"]:
        for kind, q in p["serial"]["sql"].items():
            points.append({"measurement": SERIAL, "timestamp": max(r["endNs"] for r in report["runs"]),
                           "tags": {"comparison_id": report["comparisonId"], "variant": p["variant"], "dataset": p["dataset"],
                                    "scenario": p["scenario"], "page": p["page"], "kind": kind},
                           "fields": {"calls": q["count"], "ms_per_call": q["timerMs"] / q["count"],
                                      "rows_examined_per_call": q["rowsExamined"] / q["count"], "rows_sent_per_call": q["rowsSent"] / q["count"]}})
    return points


def publish(points):
    lines = []
    for p in points:
        require(p["measurement"] in (SUMMARY, SQL, SERIAL), "기존 측정에는 기록하지 않습니다.")
        require(all(re.fullmatch(r"[A-Za-z0-9_-]+", str(v)) for v in p["tags"].values()), "지원하지 않는 Influx tag")
        require(all(math.isfinite(v) for v in p["fields"].values()), "유한하지 않은 수치")
        tags = ','.join(f"{k}={v}" for k, v in p["tags"].items())
        fields = ','.join(f"{k}={float(v)}" for k, v in p["fields"].items())
        lines.append(f"{p['measurement']},{tags} {fields} {p['timestamp']}")
    req = urllib.request.Request("http://127.0.0.1:18086/write?db=receipt_loadtest", data=('\n'.join(lines)).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=30) as response:
        require(response.status == 204, "파생 지표 게시 실패")


def render(report):
    resume_note = ""
    if report.get("resumeEvents"):
        first = report["resumeEvents"][0]
        resume_note = (f"\n**중간 중단과 재개:** 처음 {first['completedRuns']}회 뒤 Codex 사용량 제한 때문에 중단했습니다. "
                       "원래 실행 환경을 복원한 뒤 같은 측정 도구·DB·앱 이미지로 나머지를 재개했고, 완료된 결과는 보존했습니다. "
                       "DB 캐시는 비우지 않았습니다. 일부 짝의 두 실행 사이에 중단이 있었으므로 시간에 따른 호스트·캐시 상태의 영향이 남을 수 있습니다.\n")
    a, b = (report["groups"][v]["100000"]["all"] for v in VARIANTS)
    af, bf = (report["groups"][v]["100000"]["status_period"] for v in VARIANTS)
    reduction = (1 - b["p95Ms"]["median"] / a["p95Ms"]["median"]) * 100
    conclusion = (
        f"**10만 건의 전체 검수 목록에서는 ID를 먼저 고르는 방식의 이점이 확인됐습니다.** "
        f"두 방식에 같은 인덱스가 있는데도 p95 중앙값은 **{number(a['p95Ms']['median'])} → {number(b['p95Ms']['median'])} ms"
        f"(약 {number(reduction, 1)}% 감소)**였습니다. "
        f"반면 상태·기간 검색은 **{number(af['p95Ms']['median'])} → {number(bf['p95Ms']['median'])} ms**로 거의 같았습니다. "
        "따라서 현재의 전체 검수 목록에는 ID 먼저 조회하는 방식을 유지할 근거가 있습니다. 모든 조회에 이 단계가 필수라는 뜻은 아닙니다."
        if reduction > 0 and abs(af["p95Ms"]["median"] - bf["p95Ms"]["median"]) < 1
        else "**조건별 결과를 구분해 판단해야 합니다.** 전체 목록과 상태·기간 검색을 나누어 p95·p99·실패·DB 비용을 비교했습니다.")
    resource_conclusion = (
        f"10만 건 전체 목록에서 MySQL CPU 평균의 중앙값은 **{number(a['resources']['mysql']['cpuMeanPct']['median'], 2)}% → "
        f"{number(b['resources']['mysql']['cpuMeanPct']['median'], 2)}%**였습니다. "
        f"응답 실패는 **{a['failures']} → {b['failures']}건**, 시작하지 못한 요청은 **{a['dropped']} → {b['dropped']}건**입니다. "
        f"열린 DB 연결 최대는 **{a['resources']['db']['connectionsMax']['max']} → {b['resources']['db']['connectionsMax']['max']}개**로 같고, "
        f"그중 SQL 실행 중이던 연결 최대가 **{a['resources']['db']['queryActiveMax']['max']} → {b['resources']['db']['queryActiveMax']['max']}개**였습니다. "
        "연결 자체가 줄었다는 뜻은 아닙니다. 메모리 사용량은 비슷했으며 메모리 개선 성과로 주장하지 않습니다.")
    plans = {p["variant"]: p["explain"] for p in report["profiles"] if p["dataset"] == 100000 and
             p["scenario"] == "all" and p["page"] == 0}
    verified_plan = (all("Table scan" in e["plan"] and "rows=100000 loops=1" in e["plan"] for e in plans["index_only"]["list"]["plans"])
                     and all("Covering index range scan" in e["plan"] and "rows=20000 loops=1" in e["plan"]
                             for e in plans["id_first"]["ids"]["plans"])
                     and all("using PRIMARY" in e["plan"] and "rows=20 loops=1" in e["plan"]
                             for e in plans["id_first"]["entities"]["plans"]))
    plan_explanation = ""
    if verified_plan:
        plan_explanation = '''**왜 전체 목록에서 차이가 났을까요? 실제 DB 실행 과정을 확인했습니다.**

```text
인덱스 + 한 번 조회
영수증 테이블 100,000행 확인
→ 검수 조건에 맞는 19,781건
→ 최신순으로 정렬해 20건 반환

같은 인덱스 + ID 먼저 조회
인덱스에서 검수 상태에 해당하는 20,000항목 확인
→ 검수 조건에 맞는 19,781건
→ 최신순으로 정렬해 ID 20개 선택
→ 기본키(PK)로 그 20건의 전체 데이터 조회
```

인덱스가 있다고 DB가 모든 쿼리에서 그 인덱스를 선택하는 것은 아닙니다. 이번 전체 목록의 단일 조회는 테이블 전체 확인을 선택했고, ID만 고르는 쿼리는 필요한 값이 들어 있는 인덱스만으로 대상을 골랐습니다. **“20건만 읽는다”는 설명은 마지막의 영수증 전체 데이터 조회에 해당합니다.** 그 전에 인덱스 2만 항목 확인과 정렬은 여전히 필요합니다.

반면 상태·기간 검색에서는 기존 단일 조회도 같은 인덱스로 범위를 좁혀 20건을 찾았습니다. 그래서 ID 선조회의 추가 단계가 응답 시간을 더 줄이지 못했습니다. 이 차이를 확인했기 때문에 전체 목록과 검색 결과를 구분해서 설명합니다.
'''
    image_note = ""
    image = report.get("imageLayerComparison")
    if image and image.get("sameBaseLayers") and image.get("onlyLastLayerDiffers"):
        image_note = (f"- 앱 이미지의 앞 {len(image['layers']['index_only']) - 1}개 공통 계층은 같고, "
                      "마지막 `/app/app.jar` 복사 계층만 다른 것을 확인했습니다. 이미지 계층 비교는 실행 환경의 차이를 좁히는 증거이며, JAR 내부 모든 클래스·의존성을 하나씩 대조한 검증은 아닙니다.\n")
    results = [["누적 건수 / 검색 조건", "한 번 조회 p95 ms", "ID 먼저 조회 p95 ms", "한 번 조회 p99 ms", "ID 먼저 조회 p99 ms", "실패 / 미실행 (단일 → ID)"]]
    resources = [["누적 건수 / 검색 조건", "앱 CPU 평균 %", "DB CPU 평균 %", "앱 최대 메모리 MiB", "DB 최대 메모리 MiB", "DB 열린 / 실행 중 연결 최대"]]
    interpretations = []
    for size in (10000, 100000):
        for case, label in SCENARIOS.items():
            a, b = (report["groups"][v][str(size)][case] for v in VARIANTS)
            results.append([f"{size:,} / {label}", spread(a["p95Ms"]), spread(b["p95Ms"]),
                            spread(a["p99Ms"]), spread(b["p99Ms"]), f"{a['failures']} / {a['dropped']} → {b['failures']} / {b['dropped']}"])
            ra, rb = a["resources"], b["resources"]
            values = [f"{size:,} / {label}"]
            for service, key in (("app", "cpuMeanPct"), ("mysql", "cpuMeanPct"), ("app", "memoryPeakMiB"), ("mysql", "memoryPeakMiB")):
                values.append(f"{number(ra[service][key]['median'])} → {number(rb[service][key]['median'])}")
            values.append(f"{ra['db']['connectionsMax']['max']} / {ra['db']['queryActiveMax']['max']} → {rb['db']['connectionsMax']['max']} / {rb['db']['queryActiveMax']['max']}")
            resources.append(values)
            delta = b["p95Ms"]["median"] - a["p95Ms"]["median"]
            overlap = not (a["p95Ms"]["max"] < b["p95Ms"]["min"] or b["p95Ms"]["max"] < a["p95Ms"]["min"])
            interpretations.append(f"- **{size:,}건 · {label}:** ID 먼저 조회한 쪽의 p95 중앙값이 {number(abs(delta))} ms {'낮았습니다' if delta < 0 else '높았습니다'}. " +
                                   ("세 반복의 범위는 서로 겹칩니다. 작은 차이를 확정적인 우위로 해석하지 않습니다." if overlap else "이번 세 반복의 범위는 서로 겹치지 않았습니다. 이 결과는 해당 조건에서의 관측입니다."))
    serial = [["규모 / 조건 / 페이지(1부터) / 방식", "SQL", "호출 수", "SQL 평균 ms", "DB가 확인한 행 / SQL"]]
    core = [["규모 / 검색 조건", "한 번 조회의 목록 SQL ms", "ID 선택 + 데이터 조회 SQL ms", "전체 개수 SQL ms (단일 → ID)"]]
    plan_details = []
    for size in (10000, 100000):
        for case, label in SCENARIOS.items():
            a, b = [next(p["serial"]["sql"] for p in report["profiles"] if (p["dataset"], p["scenario"], p["page"], p["variant"]) == (size, case, 0, v)) for v in VARIANTS]
            avg = lambda q: q["timerMs"] / q["count"]
            core.append([f"{size:,} / {label}", number(avg(a["list"])),
                         f"{number(avg(b['ids']))} + {number(avg(b['entities']))} = {number(avg(b['ids']) + avg(b['entities']))}",
                         f"{number(avg(a['count']))} → {number(avg(b['count']))}"])
    for p in report["profiles"]:
        label = f"{p['dataset']:,} / {SCENARIOS[p['scenario']]} / {p['page'] + 1}번째(page={p['page']}) / {VARIANTS[p['variant']]}"
        for kind, q in p["serial"]["sql"].items():
            serial.append([label, KINDS[kind], q["count"], number(q["timerMs"] / q["count"]), number(q["rowsExamined"] / q["count"], 1)])
        if p["dataset"] == 100000 and p["page"] == 0:
            for kind, values in p["explain"].items():
                plan_details.append(f"### {label} · {KINDS[kind]}\n\n최상위 실행 시간 중앙값: {spread(values['topNodeMs'], ' ms')}\n\n```text\n{values['plans'][0]['plan']}\n```")
    raw = [["방식 / 실행 ID", "규모 / 조건 / 반복 / 짝 내 순서", "p95 / p99 ms", "요청 / 실패 / 미실행", "준비 부하 실패 / 미실행", "원본 기준 통과"]]
    links = []
    for r in report["runs"]:
        assessment = report["requestAssessments"][r["runId"]]
        raw.append([f"{VARIANTS[r['variant']]} / {r['runId']}", f"{r['dataset']:,} / {SCENARIOS[r['scenario']]} / {r['repeat']} / {r['pairOrder']}",
                    f"{number(r['latency']['p(95)'])} / {number(r['latency']['p(99)'])}", f"{r['requests']} / {r['failures']} / {r['dropped']}",
                    f"{assessment['warmupFailures']} / {assessment['warmupDropped']}", str(r['passed']).lower()])
        links.append(f"- [{r['runId']} · {VARIANTS[r['variant']]}]({dashboard_url(report['comparisonId'], r)})")
    day = datetime.fromtimestamp(max(r["endNs"] for r in report["runs"]) / 1e9, timezone(timedelta(hours=9))).strftime("%Y-%m-%d")
    return f'''<mention-date start="{day}"/> · 한국 시간 기준 · 같은 인덱스에서 조회 방식만 비교

## 이번에 확인한 질문

**“인덱스만 추가해도 빨라질 텐데, 굳이 ID를 먼저 읽는 단계가 필요한가?”**를 확인했습니다. 이전 비교에서는 인덱스와 조회 방식을 함께 바꿨기 때문에 효과를 구분할 수 없었습니다. 이번에는 두 방식 모두 **같은 복합 인덱스**를 사용했습니다.

```text
한 번 조회: 조건에 맞는 영수증 20건을 바로 조회
ID 먼저 조회: 조건에 맞는 영수증 번호 20개 선택 → 그 20건의 데이터 조회
```

위 횟수는 목록 데이터를 가져오는 SQL만 센 것입니다. 로그인 확인·전체 개수·작업 상태 조회도 있어 API 요청 전체 SQL은 각각 4개와 5개입니다. 전체 개수를 세는 COUNT는 두 방식 모두 유지했습니다.

## 결과부터 보기

{conclusion}

{table(results)}

**p95**는 실행된 요청 100개 중 약 95개의 대기 시간이 이 값 이하였다는 뜻입니다. **p99**는 약 99개입니다. 먼저 각 60초 실행의 요청 표본으로 p95·p99를 각각 구하고, 표에는 **세 실행의 p95 중 중앙값과 세 실행의 p99 중 중앙값**을 표시했습니다. 괄호는 세 값의 최솟값~최댓값입니다. 세 실행의 요청을 모두 합쳐 다시 계산한 p95·p99가 아닙니다. 실제 실행된 요청의 실패·타임아웃 대기도 포함하며, 시작하지 못한 미실행(dropped)은 지연 계산에 넣지 않고 별도로 표시했습니다.

{chr(10).join(interpretations)}

**단순한 구조로도 실제 요구 시간을 충분히 만족하면 한 번 조회하는 방식이 합리적입니다.** 추가 단계의 이점은 위 응답 시간뿐 아니라 아래 DB 작업량과 함께 판단합니다. 아직 운영 응답 목표나 실제 예상 데이터 규모가 확정되지 않았으므로, 모든 회사에서 ID 선조회가 필수라고 결론 내리지 않습니다.

## 10만 건까지 시험할 필요가 있었나

**매번 10만 건으로 시험할 필요는 없습니다.** 실제 직원 수, 한 달 제출량, 보관 기간으로 예상 규모를 먼저 잡는 것이 맞습니다. 예를 들어 직원 100명이 매달 영수증 10건씩 제출한다는 가정이면 한 달 1,000건, 1년 12,000건입니다. 이 가정에서 10만 건은 약 8.3년치입니다. 이 숫자는 프로젝트의 실제 사용량이 아니라 계산 예시입니다. **이번 시험 데이터는 2025년 10월부터 2026년 9월까지 1년 안에 날짜를 분포시켰습니다.** 8.3년이라는 설명은 데이터 규모를 가늠하는 별도 예시이며, 실제로 8.3년치 날짜를 생성했다는 뜻은 아닙니다.

이번 1만 건은 작은 데이터에서의 비용을 함께 보기 위한 조건이고, 10만 건은 **데이터가 많이 쌓였을 때 조회 방식의 약점이 드러나는지 보는 확장 조건**입니다. 10만 건이라는 숫자 자체가 이력서의 성과가 되지는 않습니다. 실제 사용 범위에서는 차이가 없다면 복잡한 최적화를 유지할 이유도 줄어듭니다.

**저장된 영수증 수와 초당 요청 수는 다른 조건입니다.** DB에 10만 건이 있어도 요청은 적을 수 있고, DB가 작아도 여러 직원이 동시에 요청할 수 있습니다. 이번 초당 20회 요청은 두 구현을 같은 조건에서 비교하기 위한 값이며 실사용 트래픽 예측이나 최대 처리량을 의미하지 않습니다.

## DB에서는 어떤 차이가 났나

아래는 부하와 별도로 API를 **한 건씩 100번 실행**한 결과입니다. 응답 전체 시간 대신 목록 SQL과 전체 개수 SQL에 든 시간을 나눠 보았습니다.

{table(core)}

{plan_explanation}

ID 먼저 조회하는 방식의 비용은 번호 조회와 데이터 조회를 합쳐 봐야 합니다. 한 번 조회도 인덱스를 잘 사용하면 빠를 수 있습니다. 실제로 어느 인덱스·정렬을 사용했는지는 실행 계획에 남겼으며, 모든 조건에서 ID 먼저 조회가 더 적은 행을 읽는다고 미리 가정하지 않았습니다. “확인한 행”은 DB의 논리적 검사 횟수로, 실제 디스크 읽기 바이트나 서로 다른 영수증 수와 같지 않습니다.

**전체 개수를 세는 COUNT는 두 방식에서 같은 쿼리와 같은 인덱스 접근을 유지했습니다.** 위 순차 100회 측정에서 COUNT 평균 시간이 조금 달라도, COUNT 구조를 개선해서 얻은 효과로 해석하지 않습니다. 캐시와 실행 시점의 상태가 영향을 줄 수 있습니다.

{toggle('SQL별 100회 측정 수치 자세히 보기', table(serial))}

{toggle('10만 건의 SQL 실행 과정(EXPLAIN ANALYZE) 보기', chr(10).join(plan_details))}

## CPU·메모리·DB 연결도 함께 확인

{resource_conclusion}

모든 화살표는 **한 번 조회 → ID 먼저 조회** 순서입니다.

{table(resources)}

CPU는 실행별 평균의 세 번 중앙값, 메모리는 실행별 최대 관측값의 중앙값입니다. 연결은 세 실행 전체에서 관측한 최대를 표시합니다. Docker CPU 100%는 논리 CPU 하나 수준입니다. 메모리는 컨테이너 전체 값이고 JVM 힙 전용 값이 아닙니다. 2초 간격 관측이므로 순간 피크를 놓칠 수 있습니다. DB 연결 수는 Hikari 풀 대기 시간을 직접 측정한 값이 아닙니다.

## 같은 조건으로 비교한 방법

- 합성 영수증 1만 건과 10만 건에서 검수 대상 비율은 20%, 날짜 범위는 같은 1년으로 맞췄습니다. 각 규모에서 두 조회 방식은 같은 DB 데이터를 사용했습니다.
- 전체 검수 목록과 상태·기간 검색을 각각 시험했습니다. 첫 페이지 20건 조회가 본시험입니다. API의 페이지 번호는 0부터 시작하므로 **page=50은 앞의 1,000건을 건너뛴 51번째 페이지**이며 SQL 참고 측정으로만 사용했습니다.
- **같은 날**, 규모·검색 조건·반복 회차가 같은 두 방식을 한 쌍으로 묶었습니다. 실행 순서를 번갈아 사용하며 매 실행 앱을 재시작했습니다. 무작위 실험이 아니며, 중간 중단으로 일부 짝의 두 실행이 연속해서 진행되지는 않았습니다.
- 초당 20회 요청, 준비 30초 뒤 측정 60초, 각 조건 3회로 **총 24회**입니다. DB 캐시는 강제로 비우지 않았습니다.
- 전체 응답 내용의 해시·ID·전체 개수가 일치하는지 확인했습니다. 원래 로컬 영수증 데이터와 기존 실험 기록을 보존했습니다.
- AI나 실제 S3를 호출하지 않습니다. 추출기 2초 설정은 유지하지만 이미 처리된 목록 조회만 시험하므로 AI 처리 시간을 측정한 것이 아닙니다.
{image_note}

{resume_note}

{toggle('24회 실행 원본 수치와 순서 보기', table(raw))}

{toggle('데이터·인덱스·환경 동일성 검증 보기', '```json' + chr(10) + json.dumps(report['comparabilityProof'], ensure_ascii=False, indent=2) + chr(10) + '```')}

## Grafana와 원본 기록

[같은 인덱스에서 조회 방식 비교 대시보드]({report['grafanaUrl']})

먼저 두 방식의 p95·p99와 실패·미실행을 확인하고, SQL 시간·DB CPU를 함께 보세요. 기본 링크의 시계열은 핵심 조건인 **10만 건 전체 목록·ID 먼저 조회의 세 실행 중 p95가 중앙값인 실행**을 보여줍니다. 위 비교 표에는 24회를 모두 포함했습니다. 아래쪽 시계열은 실행 ID와 시간 범위를 바꾸면 다른 실행의 10초 구간 p95·p99 및 자원 사용량도 보여줍니다. 각 10초 p95를 평균 낸 값은 전체 실행 p95와 다릅니다.

{toggle('실행별 Grafana 링크 보기', chr(10).join(links))}

원본 폴더: `{report['experimentDirectory']}`

`comparison.json`에 실행별 값·세 반복의 편차·짝 비교·원본 경로를 보관했습니다. 동시 부하 중 SQL 집계는 측정 창 경계 영향을 받는 근삿값입니다. 정확한 SQL 횟수는 순차 100회 측정으로 확인했습니다. 이 결과는 같은 Mac에서 앱·DB·부하 발생기가 자원을 공유하는 단기 읽기 시험이며, 운영 환경·장시간 안정성·쓰기 비용·혼합 트래픽의 성능으로 일반화하지 않습니다.

관련 기록: [5차 — 1만·10만 건 검수 목록 개선 전 결과](https://app.notion.com/p/3fd3191bf3f0813fb2abfb5cbb617041), [6차 — 인덱스·조회 방식 개선 전후 비교](https://app.notion.com/p/3fe3191bf3f081138558ccde2a3e19e4). 이전 결과는 참고 기록이며 이번 두 방식의 당일 비교 집계에 합치지 않았습니다.
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("experiment", type=Path)
    parser.add_argument("--publish", action="store_true", help="새 비교 전용 Influx 측정값만 게시")
    args = parser.parse_args()
    root = args.experiment.resolve()
    report = load_report(root)
    report["grafanaUrl"] = dashboard_url(root.name, representative_run(report))
    points = publication_points(report)
    (root / "comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (root / "notion-content.txt").write_text(render(report))
    (root / "comparison-points.json").write_text(json.dumps(points, ensure_ascii=False, indent=2) + "\n")
    if args.publish:
        publish(points)
        print(f"Published {len(points)} new comparison points.")
    print(root / "comparison.json")
    print(root / "notion-content.txt")


if __name__ == "__main__":
    main()
