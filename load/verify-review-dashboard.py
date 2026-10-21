#!/usr/bin/env python3
"""저장된 기준선 결과와 로컬 Grafana/InfluxDB를 읽기 전용으로 대조합니다."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re
import statistics
import sys
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parent.parent
GRAFANA = 'http://127.0.0.1:13000'
INFLUX = 'http://127.0.0.1:18086'
UID = 'receipt-review-baseline'


def get(url):
    with urllib.request.urlopen(url, timeout=15) as response:
        return json.load(response)


def query(sql):
    if not re.match(r'^(SELECT|SHOW)\s', sql, re.I):
        raise ValueError('읽기 전용 SQL만 허용합니다.')
    result = get(INFLUX + '/query?' + urllib.parse.urlencode(
        {'db': 'receipt_loadtest', 'q': sql}))
    if result.get('error'):
        raise RuntimeError(result['error'])
    output = result['results'][0]
    if output.get('error'):
        raise RuntimeError(output['error'])
    return output.get('series', [])


def rows(series):
    return [dict(s.get('tags', {}), **dict(zip(s['columns'], values)))
            for s in series for values in s.get('values', [])]


def scalar(sql, column):
    data = rows(query(sql))
    if len(data) != 1 or data[0].get(column) is None:
        raise ValueError('집계 결과가 한 값이 아닙니다: ' + sql)
    return data[0][column]


def same(actual, expected):
    return (isinstance(actual, (int, float)) and math.isfinite(actual)
            and math.isclose(actual, expected, rel_tol=1e-8, abs_tol=1e-8))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--experiment', type=Path, required=True)
    parser.add_argument('--allow-incomplete', action='store_true',
                        help='측정 진행 중 완료된 실행만 검증합니다. 최종 12회 조건은 검사하지 않습니다.')
    args = parser.parse_args()
    path = args.experiment.resolve()
    experiment = path.name
    if not path.is_dir() or not re.fullmatch(r'[A-Za-z0-9_-]+', experiment):
        parser.error('존재하는 실험 폴더를 지정하세요.')
    run_files = sorted(path.glob('review-*/verification.json'))
    runs = [json.loads(p.read_text()) for p in run_files]
    run_directories = {run['runId']: source.parent for run, source in zip(runs, run_files)}
    if not runs:
        parser.error('완료된 verification.json이 없습니다.')
    if any(not re.fullmatch(r'[A-Za-z0-9_-]+', r['runId']) for r in runs):
        parser.error('지원하지 않는 실행 ID')

    failures = []
    report = dict(experiment=experiment, mode='in-progress' if args.allow_incomplete else 'final',
                  completedRunsRead=len(runs), checks={}, runs=[], panels=[], errors=failures)
    report['scope'] = ('측정 증거와 대시보드의 일치를 검증합니다. 요청 누락·응답 실패·성능 기준 실패를 '
                       '숨기거나 원본의 passed 값을 변경하지 않습니다. Performance Schema 횟수는 근사 집계입니다.')

    def check(name, condition):
        report['checks'][name] = bool(condition)
        if not condition:
            failures.append(name)

    dashboard = get(GRAFANA + '/api/dashboards/uid/' + UID)['dashboard']
    check('dashboardUid', dashboard.get('uid') == UID)
    check('dashboardPanels', len(dashboard['panels']) == 19)
    check('cacheScopeExplained', '128 MiB' in dashboard['panels'][0]['options']['content']
          and '195.75 MiB' in dashboard['panels'][0]['options']['content'])
    variables = dashboard['templating']['list']
    check('variables', [v['name'] for v in variables] == ['experiment_id', 'run_id'])
    for variable in variables:
        statement = variable['query'].replace('$experiment_id', re.escape(experiment))
        values = {row.get('value') for row in rows(query(statement))}
        expected = {experiment} if variable['name'] == 'experiment_id' else {r['runId'] for r in runs}
        check('variable_' + variable['name'], expected <= values)

    summary = rows(query('SELECT * FROM "receipt_review_summary" '
                         f'WHERE "experiment_id"=\'{experiment}\''))
    summary_by_id = {row['run_id']: row for row in summary}
    check('summaryNoDuplicateRun', len(summary_by_id) == len(summary))
    run_ids = {r['runId'] for r in runs}
    check('completedRunsStored', run_ids <= set(summary_by_id))
    if not args.allow_incomplete:
        expected_matrix = Counter((n, c, r) for n in (10000, 100000)
                                  for c in ('all', 'status_period') for r in (1, 2, 3))
        actual_matrix = Counter((r['dataset'], r['scenario'], r['repeat']) for r in runs)
        check('twelvePlannedRuns', len(runs) == 12 and actual_matrix == expected_matrix)
        check('summaryExactlyTwelve', len(summary) == 12 and set(summary_by_id) == run_ids)
        metadata = json.loads((path / 'experiment.json').read_text())
        check('experimentComplete', metadata.get('completed') is True)
        check('originalDatabaseRestored', metadata.get('restoredOriginalDatabase') is True)

    sql_rows = rows(query('SELECT * FROM "receipt_review_sql" '
                          f'WHERE "experiment_id"=\'{experiment}\''))
    sql_by_key = {(row['run_id'], row['kind']): row for row in sql_rows}
    check('sqlNoDuplicateRunKind', len(sql_by_key) == len(sql_rows))
    if not args.allow_incomplete:
        check('fortyEightSqlSummaries', len(sql_rows) == 48)

    for run in runs:
        run_id, count = run['runId'], run['requests']
        prefix = run_id + ':'
        raw_count = scalar('SELECT count("value") AS "count" FROM "receipt_review_ms" '
                           f'WHERE "run_id"=\'{run_id}\'', 'count')
        request_sum = scalar('SELECT sum("value") AS "count" FROM "receipt_review_requests" '
                             f'WHERE "run_id"=\'{run_id}\'', 'count')
        failure_sum = scalar('SELECT sum("value") AS "count" FROM "receipt_review_failures" '
                             f'WHERE "run_id"=\'{run_id}\'', 'count')
        byte_sum = scalar('SELECT sum("value") AS "bytes" FROM "receipt_review_response_bytes" '
                          f'WHERE "run_id"=\'{run_id}\'', 'bytes')
        check(prefix + 'latencySamplesAndRequestCounter', raw_count == request_sum == count)
        check(prefix + 'failuresAndBytes', failure_sum == run['failures']
              and same(byte_sum / count, run['responseBytesPerRequest']))
        # 목표 부하를 소화하지 못한 실행도 유효한 측정 증거입니다.
        # monitor/samples는 증거 수집의 건전성, noDropped/k6 등은 시험 기준 통과 여부입니다.
        check(prefix + 'measurementEvidenceComplete', run['checks'].get('samples') is True
              and run['checks'].get('monitor') is True and not run.get('errors'))
        status_series = query('SELECT count("value") AS "count" FROM "http_req_duration" '
                              f'WHERE "run_id"=\'{run_id}\' GROUP BY "status"')
        status_counts = {row.get('status', 'missing'): row['count'] for row in rows(status_series)}
        # k6의 matches 조건은 HTTP 200 AND 전체 응답 해시 일치입니다.
        # 따라서 성공 수가 HTTP 200 수와 같으면 200 응답의 본문 불일치가 없습니다.
        check(prefix + 'httpStatusSamplesComplete', sum(status_counts.values()) == count)
        check(prefix + 'successfulBodiesMatch', count - run['failures'] == status_counts.get('200', 0))
        allowed_statuses = {'0', '200'} | {str(code) for code in range(500, 600)}
        check(prefix + 'noAuthOrUnexpectedHttpErrors', set(status_counts) <= allowed_statuses)
        check(prefix + 'failedRequestsAccountedFor', run['failures'] == sum(
            value for status, value in status_counts.items() if status != '200'))
        if 'responseStatusCounts' in run:
            check(prefix + 'storedStatusClassificationMatches', run['responseStatusCounts'] == status_counts)
        if 'responseIntegrityPassed' in run:
            check(prefix + 'storedResponseIntegrityPassed', run['responseIntegrityPassed'] is True)
        console_path = run_directories[run_id] / 'console.log'
        timeout_warnings = console_path.read_text().count('request timeout') if console_path.exists() else None
        if run['failures']:
            # 실패가 있으면 로컬 원시 HTTP 표본과 checks까지 한 번 더 대조합니다.
            local_status_counts = Counter()
            local_check_counts = Counter()
            with (run_directories[run_id] / 'metrics.json').open() as source:
                for line in source:
                    if '"http_req_duration"' not in line and '"checks"' not in line:
                        continue
                    point = json.loads(line)
                    if point.get('type') != 'Point':
                        continue
                    if point.get('metric') == 'http_req_duration':
                        local_status_counts[point['data']['tags'].get('status', 'missing')] += 1
                    elif point.get('metric') == 'checks':
                        local_check_counts[point['data']['value']] += 1
            check(prefix + 'rawHttpStatusesMatchInflux', dict(local_status_counts) == status_counts)
            check(prefix + 'rawChecksMatchResponses', local_check_counts[1] == count - run['failures']
                  and local_check_counts[0] == run['failures']
                  and sum(local_check_counts.values()) == count)
        check(prefix + 'sqlStatementsWithoutErrors', run['checks'].get('noSqlErrors') is True
              and all(value['errors'] == 0 for value in run['sql'].values()))
        k6_summary = json.loads((run_directories[run_id] / 'summary.json').read_text())
        threshold_results = {
            metric + ':' + threshold: values['ok']
            for metric, data in k6_summary['metrics'].items()
            for threshold, values in data.get('thresholds', {}).items()
        }
        threshold_passed = all(threshold_results.values())
        check(prefix + 'k6ExitMatchesThresholds', bool(threshold_results)
              and run['checks'].get('k6') == threshold_passed)
        warmup_dropped = run.get('warmupDropped')
        warmup_path = run_directories[run_id] / 'warmup' / 'summary.json'
        if warmup_dropped is None and warmup_path.exists():
            warmup_summary = json.loads(warmup_path.read_text())
            warmup_dropped = warmup_summary['metrics'].get('dropped_iterations', {}).get('values', {}).get('count', 0)
        if run['rate'] == 20 and run['seconds'] == 60:
            check(prefix + 'expectedArrivalCount', count + run['dropped'] in (1200, 1201))

        stored = summary_by_id.get(run_id, {})
        expected_summary = dict(p95_ms=run['latency']['p(95)'], p99_ms=run['latency']['p(99)'],
            mean_ms=run['latency']['avg'], requests=count, failures=run['failures'],
            dropped=run['dropped'], response_bytes_per_request=run['responseBytesPerRequest'],
            mysql_cpu_mean=run['resources']['mysql']['cpuMeanPct'],
            app_cpu_mean=run['resources']['app']['cpuMeanPct'],
            app_memory_peak_mib=run['resources']['app']['memoryPeakMiB'],
            db_connections_max=run['resources']['db']['connectionsMax'], passed=int(run['passed']))
        check(prefix + 'summaryValues', all(same(stored.get(k), v) for k, v in expected_summary.items())
              and stored.get('dataset') == str(run['dataset'])
              and stored.get('scenario') == run['scenario']
              and stored.get('repeat') == str(run['repeat']))
        for kind, value in run['sql'].items():
            denominator = max(value['count'], 1)
            expected_sql = dict(calls=value['count'], total_ms=value['timerMs'],
                ms_per_call=value['timerMs'] / denominator,
                ms_per_request=value['timerMs'] / count,
                rows_examined_per_call=value['rowsExamined'] / denominator,
                rows_sent_per_call=value['rowsSent'] / denominator)
            stored_sql = sql_by_key.get((run_id, kind), {})
            check(prefix + 'sql_' + kind, all(same(stored_sql.get(k), v) for k, v in expected_sql.items()))
        report['runs'].append(dict(runId=run_id, dataset=run['dataset'], scenario=run['scenario'],
            repeat=run['repeat'], requests=count, rawLatencySamples=raw_count,
            influxRequestCounter=request_sum, dropped=run['dropped'], failures=run['failures'],
            warmupDropped=warmup_dropped, k6ExitCode=run.get('k6ExitCode'),
            httpStatusCounts=status_counts, consoleTimeoutWarnings=timeout_warnings,
            successfulBodiesMatch=(count - run['failures'] == status_counts.get('200', 0)),
            arrivalsIncludingDropped=count + run['dropped'], p95Ms=run['latency']['p(95)'],
            originalPassed=run['passed'], originalChecks=run['checks'],
            performanceCriteriaPassed=all(run['checks'].get(key) is True
                for key in ('k6', 'noFailures', 'noDropped', 'noSqlErrors')),
            k6Thresholds=threshold_results,
            sqlCountDiagnostics={kind:dict(observed=value['count'],
                differenceFromRequests=value['count'] - count,
                differencePct=(value['count'] - count) / count * 100)
                for kind, value in run['sql'].items()}))

    # 완료된 실행의 시각만 사용해 대시보드의 읽기 전용 쿼리를 확인합니다.
    selected = max(runs, key=lambda r: r['endNs'])
    bounds = f'time >= {selected["startNs"] - 5_000_000_000} AND time <= {selected["endNs"] + 5_000_000_000}'
    target_count = 0
    for panel in dashboard['panels']:
        for target in panel.get('targets', []):
            target_count += 1
            check('datasource_' + str(panel['id']), target.get('datasource', {}).get('uid') == 'receipt-loadtest-influx')
            statement = target['query'].replace('$experiment_id', re.escape(experiment))
            statement = statement.replace('$run_id', re.escape(selected['runId'])).replace('$timeFilter', bounds)
            check('panel_' + str(panel['id']) + '_doesNotHideFailedRuns',
                  not re.search(r'\bWHERE\b.*(?:"passed"|\bpassed\b)', statement, re.I))
            series = query(statement)
            data = rows(series)
            useful = [row for row in data if any(isinstance(value, (int, float))
                      and math.isfinite(value) for key, value in row.items() if key != 'time')]
            check('panel_' + str(panel['id']) + '_hasData', bool(useful))
            report['panels'].append(dict(id=panel['id'], title=panel['title'],
                                         series=len(series), rows=len(data), numericRows=len(useful)))
            if panel['id'] == 3:
                displayed_runs = {s.get('tags', {}).get('run_id') for s in series}
                check('allIndividualRunsVisible', run_ids <= displayed_runs)
            if panel['id'] == 4:
                displayed_runs = {row.get('실행 ID') for row in data}
                check('allTableRunsVisible', run_ids <= displayed_runs)
            if panel['id'] == 2:
                for s in series:
                    key = (int(s['tags']['dataset']), s['tags']['scenario'])
                    expected = statistics.median(row['p95_ms'] for row in summary
                        if (int(row['dataset']), row['scenario']) == key)
                    check('p95Median_' + str(key), same(s['values'][0][1], expected))
    check('seventeenPanelQueries', target_count == 17)
    report['selectedRun'] = selected['runId']
    report['performanceCriteriaPassedForEveryRun'] = all(
        run['performanceCriteriaPassed'] for run in report['runs'])
    report['evidencePassed'] = not failures
    report['passed'] = not failures
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
