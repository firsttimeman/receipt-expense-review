#!/usr/bin/env python3
"""완료된 검수 목록 기준 측정에서 comparison.json과 Notion 본문을 생성합니다."""
import argparse
import html
import json
import re
import statistics
from pathlib import Path
from urllib.parse import urlencode


ROOT = Path(__file__).resolve().parent.parent
DATASETS = (10000, 100000)
SCENARIOS = {"all": "검수 대기 전체", "status_period": "검수 필요 + 9월 기간"}
KINDS = {"employee": "직원 인증 조회", "list": "목록 20건 조회", "count": "전체 건수 COUNT", "jobs": "20건의 작업 상태 조회"}


def read(path):
    return json.loads(path.read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value, places=3):
    return f"{value:,.{places}f}"


def stats(values):
    values = list(values)
    require(bool(values), "집계 표본이 없습니다.")
    return {"median": statistics.median(values), "min": min(values), "max": max(values)}


def table(rows):
    return '<table header-row="true">\n' + '\n'.join(
        '<tr>' + ''.join('<td>' + html.escape(str(cell)) + '</td>' for cell in row) + '</tr>'
        for row in rows) + '\n</table>'


def spread(value, suffix=""):
    return f"{number(value['median'])}{suffix} ({number(value['min'])}~{number(value['max'])})"


def grafana(experiment, run):
    parameters = {"orgId": 1, "from": run["startNs"] // 1000000,
                  "to": run["endNs"] // 1000000, "timezone": "browser",
                  "var-experiment_id": experiment.name, "var-run_id": run["runId"]}
    return "http://localhost:13000/d/receipt-review-baseline/review-baseline?" + urlencode(parameters)


def validate_complete(meta, experiment):
    require(meta.get("completed") is True, "측정이 아직 완료되지 않았습니다. 보고서를 만들지 않습니다.")
    runs = meta.get("runs", [])
    expected = {(size, case, repeat) for size in DATASETS for case in SCENARIOS for repeat in (1, 2, 3)}
    actual = [(r["dataset"], r["scenario"], r["repeat"]) for r in runs]
    require(len(runs) == 12 and set(actual) == expected, "각 데이터/시나리오의 3회씩 총 12회 측정이 필요합니다.")
    require(len({r["runId"] for r in runs}) == 12, "중복 runId가 있습니다.")
    require(len({r["imageId"] for r in runs}) == 1, "실행 사이 앱 이미지가 바뀌었습니다.")
    require(meta.get("restoredOriginalDatabase") is True, "앱의 원래 DB 복원을 확인하지 못했습니다.")
    require(meta.get("originalReceiptCountAfter") == meta["originalReceiptCount"], "원래 영수증 건수가 달라졌습니다.")
    require((meta["rate"], meta["seconds"], meta["warmup"], meta["repeats"]) == (20, 60, 30, 3),
            "이 보고서는 20 RPS, 60초, 준비 부하 30초, 3회 조건에 맞춰져 있습니다.")
    for run in runs:
        require((run["rate"], run["seconds"], run["warmup"]) == (20, 60, 30), f"실행 조건 불일치: {run['runId']}")
        require({"k6", "noFailures", "noDropped", "monitor", "samples", "noSqlErrors", "sqlCounts"}
                <= run.get("checks", {}).keys(), f"실행 검증 항목 누락: {run['runId']}")
        # 과부하 결과는 제외하지 않습니다. 기록 자체의 완전성과 성능 기준을 분리합니다.
        require(all(run["checks"][key] for key in ("monitor", "samples", "noSqlErrors")),
                f"측정 데이터 완전성 검증 실패: {run['runId']}")
        require(run.get("k6ExitCode", 0) in (0, 99), f"성능 기준 실패 외의 k6 실행 오류: {run['runId']}")
        require(read(experiment / run["runId"] / "verification.json") == run,
                f"experiment와 원본 verification 불일치: {run['runId']}")
        for kind in KINDS:
            require(run["sql"][kind]["count"] > 0 and run["sql"][kind]["errors"] == 0,
                    f"SQL 증거 불완전: {run['runId']} {kind}")
        for service in ("app", "mysql", "db"):
            require(run["resources"][service]["samples"] > 0, f"자원 표본 없음: {run['runId']} {service}")
    return runs


def aggregate(runs):
    groups = {}
    for size in DATASETS:
        groups[str(size)] = {}
        for case in SCENARIOS:
            selected = [r for r in runs if r["dataset"] == size and r["scenario"] == case]
            sql = {}
            for kind in KINDS:
                sql[kind] = {
                    "msPerCall": stats(r["sql"][kind]["timerMs"] / r["sql"][kind]["count"] for r in selected),
                    "rowsExaminedPerCall": stats(r["sql"][kind]["rowsExamined"] / r["sql"][kind]["count"] for r in selected),
                    "callsPerRequest": stats(r["sql"][kind]["count"] / r["requests"] for r in selected),
                }
            resources = {}
            for service in ("app", "mysql", "redis", "s3mock"):
                resources[service] = {
                    field: stats(r["resources"][service][field] for r in selected)
                    for field in ("cpuMeanPct", "cpuMaxPct", "memoryPeakMiB")}
            resources["db"] = {
                field: stats(r["resources"]["db"][field] for r in selected)
                for field in ("connectionsMax", "queryActiveMax")}
            groups[str(size)][case] = {
                "runIds": [r["runId"] for r in selected],
                "requests": sum(r["requests"] for r in selected),
                "failures": sum(r["failures"] for r in selected),
                "dropped": sum(r["dropped"] for r in selected),
                "plannedRequestSlots": sum(r["requests"] + r["dropped"] for r in selected),
                "p95Ms": stats(r["latency"]["p(95)"] for r in selected),
                "p99Ms": stats(r["latency"]["p(99)"] for r in selected),
                "meanMs": stats(r["latency"]["avg"] for r in selected),
                "responseBytesPerRequest": stats(r["responseBytesPerRequest"] for r in selected),
                "sql": sql, "resources": resources,
            }
    return groups


def assess_raw_requests(experiment, runs):
    """원본 표본/준비 부하를 읽어 해석만 추가하며 verification은 변경하지 않습니다."""
    result = {}
    for run in runs:
        folder = experiment / run["runId"]
        statuses = {}
        checks_passed = checks_failed = latency_samples = failure_counter = request_counter = 0
        for line in (folder / "metrics.json").open():
            point = json.loads(line)
            if point.get("type") != "Point":
                continue
            metric, data = point["metric"], point["data"]
            if metric == "http_req_duration":
                status = str(data.get("tags", {}).get("status", "unknown"))
                statuses[status] = statuses.get(status, 0) + 1
            elif metric == "checks":
                if data["value"] == 1:
                    checks_passed += 1
                else:
                    checks_failed += 1
            elif metric == "receipt_review_ms":
                latency_samples += 1
            elif metric == "receipt_review_failures":
                failure_counter += data["value"]
            elif metric == "receipt_review_requests":
                request_counter += data["value"]
        require(sum(statuses.values()) == latency_samples == request_counter == run["requests"],
                f"HTTP 상태/지연/요청 원시 표본 불일치: {run['runId']}")
        require(failure_counter == checks_failed == run["failures"], f"응답 실패 원시 표본 불일치: {run['runId']}")
        require(statuses.get("200", 0) == checks_passed == run["requests"] - run["failures"],
                f"HTTP 200 응답 중 해시 불일치가 있거나 성공 표본이 다릅니다: {run['runId']}")
        timeout_logs = (folder / "console.log").read_text().count("request timeout")
        status_zero = statuses.get("0", 0)
        require(timeout_logs <= status_zero, f"타임아웃 로그와 상태 0 표본을 다시 확인하세요: {run['runId']}")
        warm = read(folder / "warmup" / "summary.json")["metrics"]
        warmup_dropped = warm.get("dropped_iterations", {}).get("values", {}).get("count", 0)
        require(run.get("warmupDropped", warmup_dropped) == warmup_dropped,
                f"준비 부하 미실행 집계 불일치: {run['runId']}")
        result[run["runId"]] = {
            "httpStatusCounts": statuses, "http200ResponseHashMatches": checks_passed,
            "successfulResponseContentMatched": True, "latencySamples": latency_samples,
            "requestTimeouts": timeout_logs, "otherStatusZeroFailures": status_zero - timeout_logs,
            "warmupDropped": warmup_dropped,
            "warmupFailures": warm.get("receipt_review_failures", {}).get("values", {}).get("count", 0),
            "warmupDroppedSource": f"{run['runId']}/warmup/summary.json",
        }
    return result


def profiles(experiment):
    result = []
    for size in DATASETS:
        for case, page in (("all", 0), ("status_period", 0), ("all", 50)):
            prefix = experiment / str(size)
            serial = read(prefix / f"serial-{case}-{page}.json")
            require(serial["requests"] == 100, "순차 검증은 각 조건 100건이어야 합니다.")
            for kind in KINDS:
                require(serial["sql"][kind]["count"] == 100 and serial["sql"][kind]["errors"] == 0,
                        f"순차 검증 SQL 횟수 불일치: {size} {case} page={page} {kind}")
            plans = read(prefix / f"explain-{case}-{page}.json")
            parsed = {}
            for kind in ("list", "count"):
                subset = [p for p in plans if ("count(" in p["sql"].lower()) == (kind == "count")]
                require(len(subset) == 3, "EXPLAIN ANALYZE는 SQL별 3회가 필요합니다.")
                times, scan_rows = [], []
                for entry in subset:
                    top = re.search(r"actual time=[\d.]+\.\.([\d.]+)", entry["plan"].splitlines()[0])
                    require(top is not None, "EXPLAIN 최상위 실행 시간 해석 실패")
                    times.append(float(top.group(1)))
                    scans = re.findall(r"Table scan[^\n]*actual time=[\d.]+\.\.[\d.]+ rows=(\d+)", entry["plan"])
                    require(len(scans) == 1, "실행 계획이 바뀌었습니다. 전수 스캔이라는 보고서 해석을 다시 검토하세요.")
                    scan_rows.append(int(scans[0]))
                require(all(v == size for v in scan_rows), "실제 스캔 행 수가 데이터 건수와 다릅니다.")
                if kind == "list":
                    require(all("-> Sort:" in p["plan"] for p in subset), "목록 정렬 실행 계획을 다시 검토하세요.")
                parsed[kind] = {"topNodeElapsedMs": stats(times), "scanRows": scan_rows,
                                "tableScan": True, "sort": kind == "list", "raw": subset}
            result.append({"dataset": size, "scenario": case, "page": page,
                           "fixture": read(prefix / f"fixture-{case}-{page}.json"),
                           "serial": {"requests": serial["requests"], "sql": serial["sql"]}, "explain": parsed})
    return result


def render(report, experiment):
    meta, groups, runs = report["metadata"], report["groups"], report["runs"]
    assessments = report["requestAssessments"]
    last = runs[-1]
    response_rows = [["시나리오", "데이터", "p95 중앙값 (3회 범위)", "p99 중앙값", "실행 요청 / 예정 도착", "응답 실패 / 미실행"]]
    sql_rows = [["시나리오", "데이터", "SQL 종류", "실행 1회 평균 ms의 중앙값", "실행 1회 검사 행 수의 중앙값"]]
    resource_rows = [["시나리오", "데이터", "앱 CPU 평균", "MySQL CPU 평균", "앱 최대 메모리", "MySQL 최대 메모리", "DB 연결 최대", "실행 중 연결 최대"]]
    findings = []
    for case, label in SCENARIOS.items():
        small, large = groups["10000"][case], groups["100000"][case]
        findings.append(f"- {label}: p95 중앙값 **{number(small['p95Ms']['median'])} → {number(large['p95Ms']['median'])} ms** "
                        f"({number(large['p95Ms']['median'] / small['p95Ms']['median'], 2)}배). 데이터가 1만 건에서 10만 건으로 늘어난 비교입니다.")
        for size in DATASETS:
            group = groups[str(size)][case]
            response_rows.append([label, f"{size:,}건", spread(group["p95Ms"], " ms"), number(group["p99Ms"]["median"])+" ms",
                                  f"{group['requests']} / {group['plannedRequestSlots']}", f"{group['failures']} / {group['dropped']}"])
            for kind, name in KINDS.items():
                query = group["sql"][kind]
                sql_rows.append([label, f"{size:,}건", name, spread(query["msPerCall"], " ms"), number(query["rowsExaminedPerCall"]["median"], 1)])
            r = group["resources"]
            resource_rows.append([label, f"{size:,}건", spread(r["app"]["cpuMeanPct"], "%"), spread(r["mysql"]["cpuMeanPct"], "%"),
                                  spread(r["app"]["memoryPeakMiB"], " MiB"), spread(r["mysql"]["memoryPeakMiB"], " MiB"),
                                  spread(r["db"]["connectionsMax"]), spread(r["db"]["queryActiveMax"])])

    serial_rows = [["데이터 / 조건 / 페이지", "직원 SELECT", "목록 SELECT", "COUNT", "작업 SELECT", "목록 평균 ms / 호출", "COUNT 평균 ms / 호출", "목록 검사 행 / 호출", "COUNT 검사 행 / 호출"]]
    plan_rows = [["데이터 / 조건 / 페이지", "목록 시간 중앙값 (범위)", "COUNT 시간 중앙값 (범위)", "각 SQL의 실제 스캔 행", "실행 계획"]]
    for p in report["profiles"]:
        name = f"{p['dataset']:,} / {SCENARIOS[p['scenario']]} / page={p['page']}"
        serial = p["serial"]["sql"]
        serial_rows.append([name] + [serial[k]["count"] for k in KINDS] +
                           [number(serial[k]["timerMs"] / serial[k]["count"]) for k in ("list", "count")] +
                           [number(serial[k]["rowsExamined"] / serial[k]["count"], 0) for k in ("list", "count")])
        plan_rows.append([name, spread(p["explain"]["list"]["topNodeElapsedMs"], " ms"),
                          spread(p["explain"]["count"]["topNodeElapsedMs"], " ms"), f"{p['dataset']:,}", "목록: 전체 스캔 + 정렬 / COUNT: 전체 스캔"])

    run_rows = [["실행 순서 / run_id", "데이터 / 조건 / 반복", "관측 기간 초", "실행 요청 / 예정 도착", "p95 ms", "p99 ms", "평균 ms", "응답 실패 / 미실행", "준비 부하 실패 / 미실행", "원본 passed", "응답 bytes/요청"]]
    raw_resource_rows = [["run_id", "앱 CPU 평균/최대 %", "MySQL CPU 평균/최대 %", "앱/MySQL 메모리 최대 MiB", "DB 연결/실행 중 최대"]]
    raw_sql_rows = [["run_id", "SQL", "호출 수", "총 실행 시간 ms", "검사 행 수", "반환 행 수"]]
    for position, run in enumerate(runs, 1):
        run_rows.append([f"{position}. {run['runId']}", f"{run['dataset']:,} / {SCENARIOS[run['scenario']]} / {run['repeat']}",
                         number((run["endNs"] - run["startNs"]) / 1000000000),
                         f"{run['requests']} / {run['requests'] + run['dropped']}", number(run["latency"]["p(95)"]), number(run["latency"]["p(99)"]),
                         number(run["latency"]["avg"]), f"{run['failures']} / {run['dropped']}",
                         f"{assessments[run['runId']]['warmupFailures']} / {assessments[run['runId']]['warmupDropped']}",
                         str(run["passed"]).lower(), number(run["responseBytesPerRequest"])])
        r = run["resources"]
        raw_resource_rows.append([run["runId"], f"{number(r['app']['cpuMeanPct'])} / {number(r['app']['cpuMaxPct'])}",
                                  f"{number(r['mysql']['cpuMeanPct'])} / {number(r['mysql']['cpuMaxPct'])}",
                                  f"{r['app']['memoryPeakMiB']} / {r['mysql']['memoryPeakMiB']}",
                                  f"{r['db']['connectionsMax']} / {r['db']['queryActiveMax']}"])
        for kind, name in KINDS.items():
            sql = run["sql"][kind]
            raw_sql_rows.append([run["runId"], name, sql["count"], number(sql["timerMs"]), sql["rowsExamined"], sql["rowsSent"]])

    appendix_body = "\n\n".join([
        "아래는 실행별 값을 그대로 옮긴 표이며, 표시 소수점은 3자리까지입니다. 전체 정밀도의 값과 나머지 자원 관측값은 comparison.json의 runs 및 각 실행 verification.json에 보존했습니다.",
        table(run_rows), table(raw_resource_rows), table(raw_sql_rows),
    ])
    appendix = '<details>\n<summary>12회 원본 수치 펼치기</summary>\n' + '\n'.join(
        '\t' + line for line in appendix_body.splitlines()) + '\n</details>'

    query_share = groups["100000"]["all"]["sql"]
    largest = sorted(KINDS, key=lambda k: query_share[k]["msPerCall"]["median"], reverse=True)
    bottleneck = " → ".join(KINDS[k] for k in largest)
    size_rows = [["데이터", "receipts DATA_LENGTH", "receipts INDEX_LENGTH", "DB 버퍼풀"]]
    for size in DATASETS:
        data_bytes, index_bytes = [int(n) for n in report["datasets"][str(size)]["size"].split()]
        size_rows.append([f"{size:,}건", number(data_bytes / 1024**2, 2)+" MiB", number(index_bytes / 1024**2, 2)+" MiB", number(report["bufferPoolBytes"] / 1024**2, 0)+" MiB"])
    links = '\n'.join(f"- [{r['dataset']:,}건 · {SCENARIOS[r['scenario']]} · {r['repeat']}회 · {r['runId']}]({grafana(experiment, r)})" for r in runs)
    performance_failures = [r for r in runs if r["failures"] or r["dropped"]]
    overload_note = (f"**총 {len(performance_failures)}회에서 응답 실패 또는 예정 요청의 미실행이 발생했습니다. "
                     f"응답 실패 {sum(r['failures'] for r in runs):,}건, 미실행(dropped_iterations) {sum(r['dropped'] for r in runs):,}건입니다. "
                     "HTTP 응답이 정상이어도 목표 부하를 모두 실행하지 못했다면 성능 기준을 충족했다고 말할 수 없습니다.**"
                     if performance_failures else "총 12회에서 응답 실패와 예정 요청의 미실행은 발생하지 않았습니다.")
    anomaly_rows = [["run_id", "p95 ms", "응답 실패 / 미실행", "HTTP 상태별 표본", "15초 타임아웃", "직원/목록/COUNT/작업 SQL 횟수", "HTTP 요청", "원본 passed / sqlCounts"]]
    for run in runs:
        if not run["passed"] or not run["checks"]["sqlCounts"]:
            assessment = assessments[run["runId"]]
            anomaly_rows.append([run["runId"], number(run["latency"]["p(95)"]), f"{run['failures']} / {run['dropped']}",
                                 " / ".join(f"{status}: {count}" for status, count in sorted(assessment["httpStatusCounts"].items())), assessment["requestTimeouts"],
                                 " / ".join(str(run["sql"][kind]["count"]) for kind in KINDS), run["requests"],
                                 f"{str(run['passed']).lower()} / {str(run['checks']['sqlCounts']).lower()}"])
    anomaly_table = table(anomaly_rows) if len(anomaly_rows) > 1 else "별도의 과부하/SQL 집계 불일치 실행이 없습니다."
    resume_history = meta.get("resumeHistory")
    resume_text = json.dumps(resume_history if resume_history else meta.get("resumeReason", "재개 기록 없음"),
                             ensure_ascii=False, indent=2)
    return f'''<mention-date start="2026-10-18"/> · 로컬 Docker · 검수 목록 개선 전 기준 측정

## 먼저 결과부터

**검수 목록에서 20건을 보여주기 위해 영수증 테이블 전체를 읽고 정렬하며, 총 건수를 계산하려고 같은 테이블 전체를 다시 읽는 실행 계획을 확인했습니다.** 데이터가 많을수록 이 두 SQL의 비용이 커집니다. 10만 건 전체 검수 조건의 SQL 평균 시간 중앙값 순서는 **{bottleneck}**입니다.

{chr(10).join(findings)}

{overload_note}

이번 측정은 **같은 백엔드 코드에서 데이터만 1만 건·10만 건으로 바꾼 개선 전 기준 측정**입니다. 쿼리·인덱스를 수정한 개선 후 결과가 아니므로 성능 개선율로 표현하지 않습니다. 이전에 적용한 요청 내 직원 정보 재사용은 두 데이터 규모에 모두 포함되어 있습니다.

## 무엇을 확인하려고 했나

검수자는 다른 직원의 검수 대기 영수증을 최신순으로 20건씩 확인합니다. 데이터가 누적돼도 이 목록이 빠르게 열리는지, 느려진다면 어떤 SQL이 비용을 만드는지 확인했습니다.

```text
GET /api/receipts/review-queue
→ 로그인 직원 확인 SELECT
→ 검수 대상 목록 20건 SELECT
→ 총 페이지 계산용 COUNT
→ 목록 20건의 추출 작업 상태 SELECT
→ 목록 응답
```

목록과 COUNT는 목적이 다른 SQL입니다. 같은 정보를 실수로 두 번 읽는 이전 직원 조회 사례와는 다릅니다. 이번에는 필요한 조회라도 많은 행을 읽는 실행 계획이 비용을 만드는지 살펴봤습니다.

## 데이터와 실행 조건

{table([['항목', '조건'],
['대상', '로그인된 GET /api/receipts/review-queue, page=0, size=20'],
['전체 조건', 'NEEDS_REVIEW·MANUAL_ENTRY·NEEDS_RECAPTURE·UNREADABLE 중 본인 제출 건·소유자 없는 건 제외'],
['상태+기간 조건', 'status=NEEDS_REVIEW, from=2026-09-01, to=2026-09-30'],
['합성 데이터', '1만 건·10만 건 별도 DB. 승인 80%, 나머지 검수 대상 상태 각 5%. 회사 1개, 제출 계정 101개, 일부 소유자 없는 행 포함'],
['데이터 범위', '2025-10-01~2026-09-30 UTC에 분포. 각 영수증 구매 항목 8개. 이미지 메타데이터만 사용'],
['부하', '목표 20 RPS × 60초 × 각 조건 3회 = 총 12회. 검수자 1계정의 세션 1개 공유. k6 VU는 미리 20개, 최대 100개'],
['준비', '매 실행 앱 재시작 후 20 RPS로 30초 준비 부하. 로그인·준비 부하는 본측정 제외. DB 캐시는 강제로 비우지 않음'],
['실행 환경', f"같은 Mac에서 k6와 Docker Desktop 실행. Docker 논리 CPU {meta['dockerCpuCount']}개. 실제 사용자 20명이라는 뜻은 아님"],
['외부 처리', '조회만 실행. AI·S3 호출 없음. Fake 추출기 2초 설정은 유지하지만 완료 데이터 조회이므로 추출 지연은 이번 응답 시간에 포함되지 않음'],
['코드', f"기준 commit {meta['gitCommit']}. 같은 앱 이미지·백엔드 쿼리·인덱스로 비교. 프론트 수정은 아래 별도 항목"],
['원래 데이터', f"합성 데이터는 별도 DB 사용. 원래 영수증 {meta['originalReceiptCount']:,}건 보존, 종료 후 앱의 원래 DB 복원 및 {meta['originalReceiptCountAfter']:,}건 확인"],
])}

실행 순서는 아래 12회 원본 표에 기록했습니다. 1만 건 세트를 먼저 끝낸 뒤 10만 건 세트를 실행했고, 각 데이터 안에서는 전체/상태+기간 순서를 반복마다 번갈아 배치했습니다. 데이터 규모 순서를 교차한 실험은 아닙니다.

## API 응답 결과

{table(response_rows)}

p95는 **실제로 실행해 응답 또는 오류까지의 시간을 기록한 요청**의 95번째 백분위수입니다. 15초 제한에서 타임아웃된 실행은 그 관측 시간을 지연 표본에 포함하며, 성공 응답만의 p95가 아닙니다. 실행조차 하지 못한 dropped_iterations는 지연 표본에 포함되지 않습니다. 따라서 p95와 실패·미실행 건수를 함께 봐야 합니다. 위 중앙값은 각 실행에서 계산한 p95 3개 중 중앙값이며 전체 요청을 합쳐 만든 p95가 아닙니다. 괄호는 세 실행의 최솟값~최댓값입니다.

예정 도착은 이 스크립트의 실행 요청 수와 dropped_iterations를 더한 값입니다. 목표는 1분에 약 1,200건이며 시작/종료 경계에 따라 1,201건이 될 수 있습니다. 최대 100개 VU가 모두 응답을 기다리면 다음 예정 요청을 시작하지 못할 수 있습니다. k6는 60초 이후 이미 실행 중인 요청에 최대 20초의 종료 대기(gracefulStop)를 줍니다. 지연 표본과 자원 관측에는 그 완료 대기 구간도 포함될 수 있습니다.

## 과부하 결과와 측정 완전성을 분리해서 보기

{anomaly_table}

원본 `passed`와 검사 항목은 수정하지 않았습니다. 초기 실행기는 목표 부하 미달 또는 SQL 집계 차이를 만나면 중단했지만, 그 실행도 계획한 12회에 그대로 포함합니다. 더 좋은 값으로 대체하지 않습니다. 보고서 생성 시에는 **k6·원시 JSON·InfluxDB 응답 표본 수 일치, 자원 관측 성공, SQL 오류 없음**을 확인합니다. 여기에 원시 HTTP 상태별 표본 수와 check 성공 수를 대조해 **HTTP 200으로 응답한 건은 모두 전체 응답 해시가 일치함**을 검증했습니다. 타임아웃·HTTP 오류·미실행은 성능 결과로 별도 표시하고, HTTP 200인데 응답 내용이 다른 경우는 보고서 생성을 중단합니다. 원본의 `passed=false`를 삭제하거나 성공으로 고치지 않았습니다.

HTTP 상태 `0`은 HTTP 응답을 받지 못한 요청입니다. 이 중 console.log에 `request timeout`으로 기록된 건을 타임아웃으로 분리했습니다. k6의 요청 제한은 15초입니다. 타임아웃된 요청에 서버가 나중에 응답했을 가능성까지 확인한 것은 아닙니다.

10만 건 과부하 과정에서 측정기의 중단 조건을 만나 결과를 원본 그대로 보존하고, 남은 실행을 동일 조건으로 재개했습니다. 이후 측정기는 성능 기준 미달과 데이터 수집 실패를 구분하게 했습니다. 목표 20 RPS·60초·준비 부하 30초·최대 100 VU·앱 이미지·DB·인덱스는 유지했습니다. 최초 실행기 코드는 `initial-scripts/review-baseline.py`, 변경 사유·재개 이력·실행기 해시는 experiment.json에 남겼습니다. 이전 실행을 포함해 준비 부하의 dropped_iterations는 각 실행의 원본 warmup/summary.json에서 읽어 부록에 표시했습니다.

```json
{resume_text}
```

## 실제로 어떤 SQL이 느렸나

{table(sql_rows)}

각 실행의 MySQL Performance Schema 차분으로 `총 SQL 실행 시간 ÷ 호출 수`, `검사 행 수 ÷ 호출 수`를 계산한 뒤 세 실행의 중앙값과 범위를 구했습니다. **동시 부하에서 COUNT_STAR와 HTTP 요청·반환 행 집계가 어긋난 실행이 있어 이 표는 측정 창 안에 집계된 SQL의 호출당 시간/행 수 근삿값입니다.** 원본 분모·분자를 임의로 맞추지 않았습니다.

동시 집계 갱신뿐 아니라 측정 창 경계도 영향을 줄 수 있습니다. 준비 부하에서 타임아웃된 요청의 서버 작업이 본측정 시작 후 끝나거나, 본측정 요청이 타임아웃된 뒤 SQL이 집계 종료 후까지 남을 수 있습니다. 이번 기록으로 각각의 비중이나 원인을 확정하지 않았습니다. HTTP 요청 수와 p95는 k6 원본 표본으로 확인한 값이고, 정확한 SQL 횟수와 개별 SQL 시간의 근거는 **단독 순차 100건 측정·EXPLAIN ANALYZE**를 별도로 사용합니다.

SQL 시간은 MySQL이 관측한 경과 시간이며 CPU 시간이나 API 전체 시간과 같지 않습니다. 검사 행 수는 처리 단계에서 검사된 행의 합계로, 디스크에서 실제 읽은 바이트·서로 다른 행 개수와도 다릅니다.

직원 조회는 요청당 1회, 작업 상태 조회는 페이지의 20건을 한 번에 조회합니다. 목록/COUNT가 많은 행을 읽는 문제를 먼저 개선 후보로 삼을 수 있습니다. 인증 검사를 없애거나 필터 조건을 빼는 방향은 사용하지 않았습니다.

## EXPLAIN ANALYZE로 확인한 원인

실제 앱이 실행한 목록 SELECT와 COUNT 문장을 저장하고 각각 3회 EXPLAIN ANALYZE를 실행했습니다. SQL을 임의의 간단한 조회로 바꿔 분석하지 않았습니다.

{table(plan_rows)}

```text
목록: 전체 Table scan → 검수 대상 필터 → created_at DESC, id DESC 정렬 → 20건 반환
COUNT: 전체 Table scan → 같은 검수 대상 필터 → 전체 건수 계산
```

1만 건에서는 두 SQL이 각각 1만 행, 10만 건에서는 각각 10만 행을 스캔했습니다. 상태·기간 조건으로 결과를 줄여도 이번 실행 계획에서는 먼저 읽는 전체 행 수가 줄지 않았습니다. LIMIT 20은 반환하는 수를 제한할 뿐, 현재 실행 계획의 전체 스캔을 없애지 못합니다.

현재 관련 복합 인덱스는 `(company_id, status, created_at)`과 `(owner_employee_id, created_at)`입니다. 검수 목록은 company_id 동등 조건 없이 여러 상태, 소유자 제외 조건, 제출일·ID 정렬을 사용합니다. **이번 실행에서 옵티마이저가 이 인덱스로 범위를 좁히지 않고 전체 스캔을 선택한 것이 확인된 사실**입니다. 인덱스만 추가하면 어떤 정도로 개선되는지는 다음 전후 비교에서 검증해야 합니다.

page=50은 OFFSET 1,000인 참고 조건입니다. 별도 순차 검증·실행 계획만 수집했으며 20 RPS 본측정 12회에는 포함하지 않았습니다. EXPLAIN 시간에는 계측 비용이 있고 동시 부하도 없으므로 위 API p95와 직접 같은 지표로 비교하지 않습니다.

## 순차 100건으로 SQL 횟수 재확인

{table(serial_rows)}

각 행은 API 100건을 순차 실행한 결과입니다. 각 요청은 직원·목록·COUNT·작업 상태 SELECT를 한 번씩 실행했습니다. 본측정과 별도로 정확히 100회씩 실행된 것을 확인했습니다. 표의 평균 시간은 순차 요청의 SQL 총 시간/100이며 동시 부하의 지연과 구분합니다. 동시 부하의 SQL 집계는 위 불일치를 공개하고 근삿값으로 사용합니다. 원본 값을 보정하거나 더 좋은 실행만 골라내지 않았습니다.

목록의 검사 행 수가 데이터 건수보다 20개, page=50에서는 1,020개 더 크게 집계된 것도 원본 그대로 기록했습니다. 전체 테이블 스캔의 실제 행 수는 위 실행 계획으로 별도 확인했습니다.

## CPU·메모리·DB 연결

{table(resource_rows)}

CPU는 각 실행의 **측정 시작(startNs)부터 k6 요청 종료(endNs)까지** 관측 평균 3개에 대한 중앙값(범위), 메모리는 같은 구간의 관측 최대 3개에 대한 중앙값(범위)입니다. DB 연결도 같은 구간의 관측 최대를 요약했습니다. 60초는 요청 도착을 주입하는 기간이며, 자원 관측은 종료 대기를 포함해 더 길 수 있습니다. 각 실행의 실제 관측 기간은 부록 초 단위 열에 표시했습니다.

Docker CPU 100%는 논리 CPU 한 개 수준이며 전체 Mac CPU 100%와 다릅니다. 관측은 약 2초 간격이므로 짧은 피크를 놓칠 수 있습니다. DB 연결은 열린 연결 수/관측 순간 실행 중 SQL 연결 수이며, Hikari의 대기 스레드나 borrowed connection 계측값이 아닙니다. 연결 10개가 모두 실행 중이었다는 사실만으로 연결 풀 대기 시간을 직접 측정했다고 말하지 않습니다.

{table(size_rows)}

**10만 건 receipts 테이블 DATA_LENGTH는 약 195.75 MiB로 128 MiB 버퍼풀보다 큽니다.** 인덱스와 다른 테이블도 같은 버퍼풀을 사용합니다. 준비 부하를 주었지만 10만 건 전체가 메모리에 들어간 상태라고 볼 수 없습니다. 따라서 데이터 규모에 따른 지연 차이를 행 수 증가 또는 인덱스 부재 하나만의 효과로 분리해 주장하지 않습니다. 전체 스캔과 이 캐시 용량 조건이 함께 있는 기준 결과입니다. 크기는 information_schema의 할당 크기이며 실제 각 응답에서 읽은 크기는 아닙니다.

## Grafana에서는 이렇게 확인

[검수 목록 기준 측정 대시보드]({grafana(experiment, last)})

1. experiment_id가 `{experiment.name}`인지 확인합니다.
2. 데이터 건수와 시나리오별 실행 p95, SQL별 평균 실행 시간/검사 행을 비교합니다.
3. 아래 링크의 run_id와 측정 시간 범위를 열어 해당 실행의 p95 추이·CPU·메모리·DB 연결을 확인합니다.

짧은 시간 구간의 p95 그래프와 60초 전체 p95는 표본 범위가 달라 값이 다를 수 있습니다. localhost 주소는 이 Mac에서 Grafana·InfluxDB가 실행 중일 때 사용할 수 있습니다.

{links}

## 프론트 자동 조회는 별도로 정리

사용자가 요청한 단순화에 따라 **상세 2초·목록 3초 자동 재조회를 제거**했습니다. 업로드 직후 접수 결과를 한 번 보여주고, 이후에는 사용자가 기존 새로고침 버튼을 눌러 현재 상태를 확인합니다. 상세에서 처리 완료를 확인하면 목록도 한 번 갱신됩니다. 서버의 비동기 추출은 화면을 새로고침하지 않아도 계속됩니다.

설명 문장: “서버가 영수증을 처리하고, 사용자는 새로고침을 눌러 현재 처리 결과를 확인합니다.”

실제 static HTML/JS를 임시 Chrome에 로드하고 모든 API를 가짜 응답으로 검증했습니다. 처리 중 화면에서 **6.5초 대기해도 목록·상세 추가 호출 0건**, 각 새로고침 버튼은 해당 API 1회 호출, 완료 시 목록 갱신/입력 활성화, JavaScript 오류 0건을 확인했습니다. 실제 부하 서버로 요청하지 않은 UI 검증입니다.

이번 k6는 프론트를 거치지 않고 API에 고정 20 RPS를 보냈습니다. 따라서 자동 폴링 제거를 이 DB 응답 시간 결과의 개선 원인으로 설명하거나, 실제 사용자 부하 감소율로 환산하지 않습니다. 자동 완료 알림은 제공하지 않으며 사용자가 결과를 직접 갱신해야 한다는 사용성 변화가 있습니다.

## 다음 개선에서 확인할 것과 이번 한계

- 우선 목록/COUNT 쿼리의 조건과 정렬에 맞는 인덱스·조회 방식을 후보로 만들고 EXPLAIN으로 검사 행이 줄어드는지 확인합니다.
- 전체 페이지 수가 필요한 현재 응답을 유지할지 검토한 뒤 COUNT 최적화를 비교합니다. COUNT를 제거하는 것은 단순한 내부 성능 변경이 아니라 API 응답/화면 요구의 변경이 될 수 있습니다.
- 동일 데이터·필터·정렬·반환 ID·전체 건수·20 RPS 조건으로 개선 후 각 3회를 측정해야 성능 개선 수치를 말할 수 있습니다. 이번에는 백엔드 쿼리나 인덱스를 변경하지 않았습니다.
- 단기 로컬 조회 테스트입니다. 업로드와 검수를 섞은 업무 부하, 서로 다른 검수자 다수의 세션, 장시간 안정성, 최대 처리량, 운영 EC2/RDS 성능은 검증하지 않았습니다.
- dropped_iterations는 설정한 최대 100 VU 안에서 예정 요청을 시작하지 못했다는 뜻입니다. 서버 지연과 함께 해석해야 하며, 이 수치만으로 서버의 실제 최대 TPS를 확정하거나 모든 미실행을 HTTP 오류로 세지 않습니다.
- 앱/k6/DB가 같은 Mac의 자원을 공유합니다. 데이터 세트를 1만→10만 순서로 실행했고 DB 캐시는 매번 비우지 않았습니다. CPU·메모리·JVM·버퍼풀 상태가 시간에 영향을 줍니다.

## 부록 — 12회 실행의 원본 수치

{appendix}

## 원본 증거

저장 폴더: `{experiment}`

- `experiment.json`: 완료/원래 DB 복원 확인, 데이터·코드·이미지·실행 순서
- `comparison.json`: 이 문서 집계, 원본 12회 결과, 순차/실행 계획 해석
- `10000/`, `100000/`: dataset.json, fixture, 각 조건 100건 serial, SQL별 3회 explain
- 각 run 폴더: summary.json, verification.json, metrics.json, sql-before/after.json, 자원 관측 원본
- `frontend-verification.json`, `frontend-smoke.cjs`: 자동 폴링 제거의 별도 UI 검증
- `load/review-baseline.py`, `load/k6/review-queue.js`: 합성 데이터·실행·검증 코드
- `load/report-review.py`: 완료 결과만 읽어 이 보고서를 다시 생성하는 코드

로그인·세션 값은 결과 본문에 포함하지 않았습니다. 측정 원본은 수정하지 않고 보고서 두 파일만 생성했습니다.
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("experiment", nargs="?", type=Path, help="완료된 실행 폴더. 생략하면 generated의 마지막 경로 사용")
    parser.add_argument("--buffer-pool-mib", type=int, help="실제 확인한 버퍼풀 크기. metadata에 없을 때 필수")
    args = parser.parse_args()
    experiment = args.experiment or Path((ROOT / "load/generated/review-baseline-experiment.txt").read_text().strip())
    experiment = experiment.resolve()
    meta = read(experiment / "experiment.json")
    runs = validate_complete(meta, experiment)
    buffer_bytes = meta.get("innodbBufferPoolBytes")
    if args.buffer_pool_mib is not None:
        require(buffer_bytes is None or buffer_bytes == args.buffer_pool_mib * 1024**2, "메타데이터와 CLI 버퍼풀 크기가 다릅니다.")
        buffer_bytes = args.buffer_pool_mib * 1024**2
    require(buffer_bytes == 128 * 1024**2, "이번 보고서의 캐시 해석에는 실제 확인된 128 MiB 버퍼풀이 필요합니다.")
    datasets = {str(size): read(experiment / str(size) / "dataset.json") for size in DATASETS}
    require(int(datasets["100000"]["size"].split()[0]) == 205258752,
            "10만 건 테이블 크기가 달라졌습니다. 캐시 관련 본문 수치를 다시 검토하세요.")
    frontend = read(experiment / "frontend-verification.json")
    require(frontend.get("passed") is True and frontend.get("liveServerRequests") == 0
            and frontend.get("idlePendingWindowSeconds", 0) >= 6.5, "별도 프론트 검증이 완료되지 않았습니다.")
    report = {"kind": "baseline-data-volume-comparison; no backend optimization", "completed": True,
              "experimentId": experiment.name, "metadata": meta, "datasets": datasets,
              "bufferPoolBytes": buffer_bytes, "groups": aggregate(runs), "runs": runs,
              "assessment": {
                  "dataIntegrityPassed": True,
                  "performanceCriteriaPassed": all(r["failures"] == 0 and r["dropped"] == 0 for r in runs),
                  "responseFailures": sum(r["failures"] for r in runs),
                  "droppedIterations": sum(r["dropped"] for r in runs),
                  "sqlApproximateRunIds": [r["runId"] for r in runs if not r["checks"]["sqlCounts"]],
                  "originalFailedRunIds": [r["runId"] for r in runs if not r["passed"]],
                  "latencyPopulation": "executed requests only; dropped iterations have no latency sample",
              },
              "requestAssessments": assess_raw_requests(experiment, runs),
              "profiles": profiles(experiment), "frontendVerification": frontend,
              "grafanaUrl": grafana(experiment, runs[-1])}
    content = render(report, experiment)
    # 핵심 쿼리 결과를 먼저 읽고, 계측기 검증과 재개 이력은 필요할 때 펼쳐 봅니다.
    detail_start = content.index('## 과부하 결과와 측정 완전성을 분리해서 보기')
    detail_end = content.index('## 실제로 어떤 SQL이 느렸나', detail_start)
    detail = content[detail_start:detail_end].strip()
    content = (content[:detail_start] + '<details>\n<summary>측정 검증과 재개 기록 펼치기</summary>\n'
               + '\n'.join('\t' + line if line else '' for line in detail.splitlines())
               + '\n</details>\n\n' + content[detail_end:])
    (experiment / "comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (experiment / "notion-content.txt").write_text(content)
    print(experiment / "comparison.json")
    print(experiment / "notion-content.txt")


if __name__ == "__main__":
    main()
