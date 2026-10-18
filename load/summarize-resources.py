"""한 실행의 제출 60초 구간 자원 표본을 요약하고 Grafana 저장 건수를 대조합니다."""
import collections
import datetime
import json
import re
import statistics
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def timestamp_ns(value):
    value = re.sub(r'\.(\d+)', lambda m: '.' + m[1][:6].ljust(6, '0'), value).replace('Z', '+00:00')
    return int(datetime.datetime.fromisoformat(value).timestamp() * 1e9)


def stats(values):
    ordered = sorted(values)
    position = (len(ordered) - 1) * .95
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return dict(samples=len(values), avg=statistics.mean(values), min=min(values), max=max(values),
                p95=ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower))


def influx(query):
    url = ('http://127.0.0.1:13000/api/datasources/proxy/uid/receipt-loadtest-influx/query?' +
           urllib.parse.urlencode({'db': 'receipt_loadtest', 'q': query}))
    with urllib.request.urlopen(url, timeout=10) as response:
        result = json.load(response)['results'][0]
    if 'error' in result:
        raise RuntimeError('Grafana 지표 조회 실패')
    return result


def summarize(run_id):
    if not re.fullmatch(r'[a-z0-9-]+', run_id):
        raise ValueError('Invalid run ID')
    path = ROOT / 'load/results' / run_id
    capacity = json.loads((path / 'capacity-analysis.json').read_text())
    start = timestamp_ns(capacity['firstSubmissionAt'])
    end = start + 60 * 10**9
    resources = [json.loads(line) for line in (path / 'resources.jsonl').read_text().splitlines()]
    database = [json.loads(line) for line in (path / 'db-connections.jsonl').read_text().splitlines()]
    raw_counts = collections.Counter(row['service'] for row in resources)
    result = influx(f'SELECT count("cpu_percent") FROM "receipt_resources" WHERE "run_id"=\'{run_id}\' GROUP BY "service"')
    stored_counts = {series['tags']['service']: series['values'][0][1] for series in result['series']}
    assert dict(raw_counts) == stored_counts, (raw_counts, stored_counts)
    result = influx(f'SELECT count("app_connections") FROM "receipt_db_connections" WHERE "run_id"=\'{run_id}\'')
    assert result['series'][0]['values'][0][1] == len(database)
    report = dict(runId=run_id, windowStartNs=start, windowEndNs=end, windowSeconds=60,
                  rawContainerCounts=dict(raw_counts), rawDbCount=len(database),
                  grafanaSampleCountsVerified=True, services={})
    for service in sorted(raw_counts):
        rows = [r for r in resources if r['service'] == service and start <= r['timestamp_ns'] <= end]
        assert len(rows) >= 20, (service, len(rows))
        report['services'][service] = {field: stats([r[field] for r in rows])
                                       for field in ['cpu_percent', 'memory_bytes', 'pids']}
        report['services'][service]['maxSampleGapSeconds'] = max(
            b['timestamp_ns'] - a['timestamp_ns'] for a, b in zip(rows, rows[1:])) / 1e9
    rows = [r for r in database if start <= r['timestamp_ns'] <= end]
    assert len(rows) >= 20, len(rows)
    report['database'] = {field: stats([r[field] for r in rows]) for field in
                           ['app_connections', 'app_query_active', 'app_sleeping',
                            'server_connected', 'server_running', 'max_connections']}
    report['database']['connectionLimitErrorsDelta'] = database[-1]['connection_limit_errors'] - database[0]['connection_limit_errors']
    report['database']['maxSampleGapSeconds'] = max(
        b['timestamp_ns'] - a['timestamp_ns'] for a, b in zip(rows, rows[1:])) / 1e9
    report['method'] = ('Docker stats sampled about every 2s; CPU is core-relative percent; memory excludes file cache. '
                        'Summaries use only the 60s arrival window. SQL-active is not Hikari borrowed. '
                        'Sample peaks can miss shorter activity; CPU averages are arithmetic means of samples.')
    (path / 'resource-summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    print(json.dumps(summarize(sys.argv[1]), indent=2))
