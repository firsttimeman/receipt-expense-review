#!/usr/bin/env python3
"""직원 조회 재사용 전후의 같은 상세 API와 실제 SQL 실행 횟수를 비교합니다."""
import argparse
import datetime
import hashlib
import http.cookiejar
import json
import math
import os
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

from resource_monitor import ResourceMonitor

ROOT = Path(__file__).resolve().parent.parent
BASE = 'http://127.0.0.1:19880'
INFLUX = 'http://127.0.0.1:18086'
COMPOSE = ['docker', 'compose', '--env-file', '/dev/null', '--project-name', 'receipt-loadtest',
           '--file', str(ROOT / 'infra/loadtest/compose.yml')]


def command(args, timeout=60, **kwargs):
    return subprocess.check_output(args, cwd=ROOT, text=True, timeout=timeout, **kwargs).strip()


def query(sql):
    return command(COMPOSE + ['exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root -D "$MYSQL_DATABASE" '
        '--batch --skip-column-names'], input=sql)


def save(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def request(self, path, body=None):
        headers = {}
        if body is not None:
            csrf = json.loads(self.request('/api/auth/csrf'))
            headers = {'Content-Type': 'application/json', csrf['headerName']: csrf['token']}
        request = urllib.request.Request(BASE + path, headers=headers,
            data=None if body is None else json.dumps(body).encode())
        with self.opener.open(request, timeout=20) as response:
            return response.read()

    def login(self, user):
        self.request('/api/auth/login', dict(loginId=user['loginId'], password=user['password']))
        return next(cookie.value for cookie in self.jar if cookie.name == 'JSESSIONID')


def actual_app():
    cid = command(COMPOSE + ['ps', '-q', 'app'])
    container = json.loads(command(['docker', 'inspect', cid]))[0]
    env = dict(value.split('=', 1) for value in container['Config']['Env'])
    required = {'RECEIPT_EXTRACTOR_PROVIDER': 'fake', 'RECEIPT_S3_ENDPOINT': 'http://s3mock:9090',
                'RECEIPT_FAKE_DELAY': '2s', 'RECEIPT_WORKER_CONCURRENCY': '4',
                'RECEIPT_WORKER_BATCH_SIZE': '4'}
    if any(env.get(k) != v for k, v in required.items()):
        raise RuntimeError('Fake 2초 / Worker 4 / S3Mock 환경만 허용합니다.')
    if int(query("SELECT COUNT(*) FROM receipt_extraction_jobs WHERE status IN ('QUEUED','PROCESSING','RETRY_WAIT');")):
        raise RuntimeError('미완료 작업이 있습니다. 완료 후 조회 성능을 측정하세요.')
    return container, required


def sql_snapshot():
    # 정규화한 SQL 집계만 읽습니다. DB 전체 카운터를 초기화하거나 일반 쿼리 로그를 켜지 않습니다.
    raw = query("""SELECT JSON_OBJECT('digest', DIGEST, 'sql', DIGEST_TEXT, 'count', COUNT_STAR,
                  'timerPs', SUM_TIMER_WAIT, 'rowsExamined', SUM_ROWS_EXAMINED,
                  'rowsSent', SUM_ROWS_SENT, 'errors', SUM_ERRORS)
        FROM performance_schema.events_statements_summary_by_digest
        WHERE SCHEMA_NAME='receipt_loadtest' AND DIGEST_TEXT LIKE 'SELECT%';""")
    selected = {}
    for line in raw.splitlines():
        row = json.loads(line)
        sql = row['sql']
        kind = None
        if 'FROM `employees`' in sql and '`login_id` = ?' in sql:
            kind = 'employee'
        elif 'FROM `receipts`' in sql and '`id` = ?' in sql and 'JOIN' not in sql:
            kind = 'receipt'
        elif 'FROM `receipt_extraction_jobs`' in sql and '`receipt_id` = ?' in sql and 'JOIN' not in sql:
            kind = 'job'
        if kind:
            row['kind'] = kind
            selected[row['digest']] = row
    return selected


def sql_delta(before, after):
    totals = {kind: dict(count=0, timerMs=0, rowsExamined=0, rowsSent=0, errors=0)
              for kind in ('employee', 'receipt', 'job')}
    for digest, row in after.items():
        previous = before.get(digest, {})
        target = totals[row['kind']]
        for key in ('count', 'rowsExamined', 'rowsSent', 'errors'):
            target[key] += row[key] - previous.get(key, 0)
        target['timerMs'] += (row['timerPs'] - previous.get('timerPs', 0)) / 1_000_000_000
    return totals


def influx_query(sql):
    url = INFLUX + '/query?' + urllib.parse.urlencode({'db': 'receipt_loadtest', 'q': sql})
    with urllib.request.urlopen(url, timeout=10) as response:
        data = json.load(response)['results'][0]
    if 'error' in data:
        raise RuntimeError(data['error'])
    return data.get('series', [])


def send(measurement, tags, fields, timestamp=None):
    tag_text = ','.join(f'{key}={value}' for key, value in tags.items())
    values = ','.join(f'{key}={float(value)}' for key, value in fields.items())
    line = f'{measurement},{tag_text} {values} {timestamp or time.time_ns()}'
    request = urllib.request.Request(INFLUX + '/write?db=receipt_loadtest', data=line.encode(), method='POST')
    with urllib.request.urlopen(request, timeout=10) as response:
        if response.status != 204:
            raise RuntimeError('InfluxDB 기록 실패')


def prepare_fixture(experiment):
    source = json.loads((ROOT / 'load/generated/users.json').read_text())
    if source['baseUrl'] != BASE or len(source['users']) < 10:
        raise RuntimeError('로컬 테스트 직원 10명이 필요합니다.')
    expected_path = experiment / 'receipts.json'
    expected = json.loads(expected_path.read_text()) if expected_path.exists() else None
    users, public = [], []
    for user in source['users'][:10]:
        client = Client()
        cookie = client.login(user)
        employee_id = int(user['employeeId'])
        previous = next((u for u in expected['users'] if u['employeeId'] == employee_id), None) if expected else None
        if previous:
            ids = [receipt['id'] for receipt in previous['receipts']]
        else:
            ids = [int(row) for row in query(f"""SELECT r.id FROM receipts r
                JOIN receipt_extraction_jobs j ON j.receipt_id=r.id
                WHERE r.owner_employee_id={employee_id} AND j.status='COMPLETED'
                ORDER BY r.id DESC LIMIT 5;""").splitlines()]
        if len(ids) != 5:
            raise RuntimeError('직원별 완료된 영수증 5건이 필요합니다.')
        receipts = []
        for receipt_id in ids:
            raw = client.request(f'/api/receipts/{receipt_id}')
            data = json.loads(raw)
            if data['ownerEmployeeId'] != employee_id or data['jobStatus'] != 'COMPLETED':
                raise RuntimeError('소유자 또는 완료 상태 불일치')
            record = dict(id=receipt_id, sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw))
            if previous and record != next(r for r in previous['receipts'] if r['id'] == receipt_id):
                raise RuntimeError('개선 전후 상세 응답이 달라졌습니다.')
            receipts.append(record)
        public.append(dict(employeeId=employee_id, receipts=receipts))
        users.append(dict(employeeId=employee_id, cookie=cookie, receipts=receipts))
    if not expected:
        save(expected_path, dict(baseUrl=BASE, users=public))
    path = ROOT / 'load/generated' / ('read-sessions-' + uuid.uuid4().hex + '.json')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(dict(baseUrl=BASE, users=users), output)
    return path


def k6_run(fixture, target, run_id, phase, rate, seconds, record=True):
    env = dict(os.environ, READ_FIXTURE=str(fixture), RATE=str(rate), DURATION=f'{seconds}s',
               SUMMARY_FILE=str(target / 'summary.json'), RUN_ID=run_id, READ_PHASE=phase)
    args = ['k6', 'run', '--quiet', '--no-usage-report', '--tag', f'run_id={run_id}']
    if record:
        args += ['--out', f'json={target / "metrics.json"}', '--out', f'influxdb={INFLUX}/receipt_loadtest']
    args.append(str(ROOT / 'load/k6/receipt-reads.js'))
    with (target / 'console.log').open('w') as log:
        return subprocess.run(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                              timeout=seconds + 90).returncode


def summarize_resources(target, start, end):
    rows = [json.loads(line) for line in (target / 'resources.jsonl').read_text().splitlines()]
    data = {}
    for service in ('app', 'mysql', 'redis', 's3mock'):
        samples = [r for r in rows if r['service'] == service and start <= r['timestamp_ns'] <= end]
        if not samples:
            raise RuntimeError('자원 표본 없음: ' + service)
        data[service] = dict(samples=len(samples), cpuMeanPct=statistics.mean(r['cpu_percent'] for r in samples),
            cpuMaxPct=max(r['cpu_percent'] for r in samples),
            memoryPeakMiB=max(r['memory_bytes'] for r in samples) / 1024**2)
    db = [json.loads(line) for line in (target / 'db-connections.jsonl').read_text().splitlines()]
    db = [r for r in db if start <= r['timestamp_ns'] <= end]
    data['db'] = dict(samples=len(db), connectionsMax=max(r['app_connections'] for r in db),
                     queryActiveMax=max(r['app_query_active'] for r in db))
    return data


def assess_sql(sql, count, phase):
    # Performance Schema는 동시 집계의 원자성을 보장하지 않습니다.
    # 오차를 없애거나 보정하지 않고 원본을 보존합니다. 0.1%는 이상 탐지 기준이며 신뢰구간이 아닙니다.
    expected = dict(employee=count * (2 if phase == 'before' else 1), receipt=count, job=count)
    return {kind: dict(expected=value, observed=sql[kind]['count'],
                       difference=sql[kind]['count'] - value,
                       allowedDifference=max(1, math.ceil(value * 0.001)),
                       withinTolerance=abs(sql[kind]['count'] - value) <= max(1, math.ceil(value * 0.001)))
            for kind, value in expected.items()}


def serial_probe(experiment, phase):
    container, _ = actual_app()
    if container['Image'] != json.loads((experiment/'experiment.json').read_text())[phase+'ImageId']:
        raise RuntimeError('순차 검증 이미지 불일치')
    fixture_path = prepare_fixture(experiment)
    try:
        fixture = json.loads(fixture_path.read_text())
        before = sql_snapshot()
        for i in range(100):
            user = fixture['users'][i % 10]
            receipt = user['receipts'][(i // 10) % 5]
            request = urllib.request.Request(BASE + f'/api/receipts/{receipt["id"]}',
                       headers={'Cookie': 'JSESSIONID=' + user['cookie']})
            with urllib.request.urlopen(request,timeout=10) as response:
                if response.status != 200 or hashlib.sha256(response.read()).hexdigest() != receipt['sha256']:
                    raise RuntimeError('순차 검증 응답 불일치')
        after = sql_snapshot()
        sql = sql_delta(before,after)
        expected = dict(employee=200 if phase=='before' else 100, receipt=100, job=100)
        passed = all(sql[k]['count']==v and sql[k]['rowsSent']==v for k,v in expected.items())
        result = dict(phase=phase,requests=100,sql=sql,expected=expected,passed=passed,
                      imageId=container['Image'],before=before,after=after)
        save(experiment/f'serial-proof-{phase}.json',result)
        print(f'순차 검증 {phase}: requests=100, employee SELECT={sql["employee"]["count"]}, passed={passed}',flush=True)
        if not passed:
            raise RuntimeError('순차 SQL 검증 실패')
        return result
    finally:
        fixture_path.unlink(missing_ok=True)


def run(experiment, phase, rate, seconds, warmup, label):
    container, settings = actual_app()
    expected_image = json.loads((experiment / 'experiment.json').read_text())[phase + 'ImageId']
    if container['Image'] != expected_image:
        raise RuntimeError('측정 단계와 실행 중인 앱 이미지가 다릅니다.')
    run_id = 'read-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dt%H%M%S') + '-' + uuid.uuid4().hex[:6]
    target = experiment / run_id
    target.mkdir()
    metadata = dict(runId=run_id, phase=phase, label=label, rate=rate, duration=seconds, warmupSeconds=warmup,
        imageId=container['Image'], appMemoryLimitBytes=container['HostConfig']['Memory'], settings=settings,
        gitCommit=command(['git','rev-parse','HEAD']), startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    save(target / 'environment.json', metadata)
    fixture = prepare_fixture(experiment)
    try:
        warm = target / 'warmup'
        warm.mkdir()
        print(f'{run_id} {phase} {rate}/s: warmup {warmup}s', flush=True)
        if k6_run(fixture, warm, run_id + '-warmup', phase, rate, warmup, False):
            raise RuntimeError('준비 부하 실패: ' + str(warm / 'console.log'))
        errors = []
        containers = {}
        for service in ('app','mysql','redis','s3mock'):
            cid = command(COMPOSE + ['ps','-q',service])
            name = command(['docker','inspect','--format','{{.Name}}',cid]).lstrip('/')
            containers[name] = service
        app_ip = container['NetworkSettings']['Networks']['receipt-loadtest_default']['IPAddress']
        monitor = ResourceMonitor(run_id, target, containers, app_ip, query, INFLUX, errors)
        before = sql_snapshot()
        save(target / 'sql-before.json', before)
        monitor.start()
        start = time.time_ns()
        print(f'{run_id}: measuring {seconds}s', flush=True)
        try:
            exit_code = k6_run(fixture, target, run_id, phase, rate, seconds)
            end = time.time_ns()
            after = sql_snapshot()
        finally:
            monitor.stop()
        save(target / 'sql-after.json', after)
        metrics = json.loads((target/'summary.json').read_text())['metrics']
        count = metrics['receipt_read_requests']['values']['count']
        successful = metrics['receipt_read_successful']['values']['count']
        failed = metrics['receipt_read_failures']['values']['count']
        dropped = metrics.get('dropped_iterations', {}).get('values',{}).get('count',0)
        latency = metrics['receipt_read_ms']['values']
        sql = sql_delta(before, after)
        expected_employee = count * (2 if phase == 'before' else 1)
        checks = dict(k6=exit_code==0, noFailures=failed==0 and successful==count, noDropped=dropped==0,
            employeeSql=sql['employee']['count']==expected_employee,
            receiptSql=sql['receipt']['count']==count, jobSql=sql['job']['count']==count,
            noSqlErrors=all(row['errors']==0 for row in sql.values()), monitor=not errors)
        exact_sql_checks = {kind:checks.pop(kind+'Sql') for kind in ('employee','receipt','job')}
        assessment = assess_sql(sql,count,phase)
        checks['sqlWithinTolerance'] = all(row['withinTolerance'] for row in assessment.values())
        raw_count = sum(1 for line in (target/'metrics.json').open()
            if (lambda point: point.get('type')=='Point' and point.get('metric')=='receipt_read_ms')(json.loads(line)))
        stored = influx_query(f'SELECT count("value") FROM "receipt_read_ms" WHERE "run_id"=\'{run_id}\'')
        stored_count = stored[0]['values'][0][1] if stored else 0
        checks['latencySamples'] = raw_count == stored_count == count
        for metric, expected_count in [('receipt_read_requests',count),('receipt_read_successful',successful),('receipt_read_failures',failed)]:
            series = influx_query(f'SELECT sum("value") FROM "{metric}" WHERE "run_id"=\'{run_id}\'')
            actual = series[0]['values'][0][1] if series else 0
            checks[metric] = actual == expected_count
        resources = summarize_resources(target,start,end)
        result = dict(runId=run_id,phase=phase,label=label,rate=rate,duration=seconds,requests=count,
            successful=successful,failed=failed,dropped=dropped,latency=latency,sql=sql,
            employeeSqlPerRequest=sql['employee']['count']/count,
            detailSqlPerRequest=sum(row['count'] for row in sql.values())/count,
            responseBytes=metrics['receipt_read_response_bytes']['values']['count'],
            resources=resources,checks=checks,errors=errors,startNs=start,endNs=end)
        result.update(sqlAssessment=assessment, exactSqlChecks=exact_sql_checks)
        result['passed'] = all(checks.values())
        fields = dict(p95_ms=latency['p(95)'],p99_ms=latency['p(99)'],mean_ms=latency['avg'],requests=count,
            failures=failed,dropped=dropped,employee_sql_per_request=result['employeeSqlPerRequest'],
            detail_sql_per_request=result['detailSqlPerRequest'],employee_sql_count=sql['employee']['count'],
            detail_sql_count=sum(row['count'] for row in sql.values()),
            employee_sql_ms=sql['employee']['timerMs'],detail_sql_ms=sum(row['timerMs'] for row in sql.values()),
            app_cpu_mean=resources['app']['cpuMeanPct'],mysql_cpu_mean=resources['mysql']['cpuMeanPct'],
            app_memory_peak_mib=resources['app']['memoryPeakMiB'],db_connections_max=resources['db']['connectionsMax'],
            response_bytes=result['responseBytes'],passed=int(result['passed']))
        send('receipt_read_summary',dict(experiment_id=experiment.name,run_id=run_id,phase=phase,label=label,rate=rate),fields)
        result['grafanaUrl'] = (f'http://localhost:13000/d/receipt-employee-reads/employee-reads?var-experiment_id={experiment.name}'
            f'&var-run_id={run_id}&from={start//1000000-5000}&to={end//1000000+5000}')
        save(target/'verification.json',result)
        metadata.update(k6ExitCode=exit_code,finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                        checks=checks,errors=errors)
        save(target/'environment.json',metadata)
        print(f'{run_id}: passed={result["passed"]}, requests={count}, employee SQL/request={result["employeeSqlPerRequest"]}, '
              f'p95={latency["p(95)"]:.3f}ms',flush=True)
        if not result['passed']:
            raise RuntimeError('측정 또는 기능 검증 실패: ' + str(target/'verification.json'))
        return result
    finally:
        fixture.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--experiment', type=Path)
    parser.add_argument('--phase', choices=['before','after'],required=True)
    parser.add_argument('--rates', default='10,50,100')
    parser.add_argument('--seconds', type=int,default=60)
    parser.add_argument('--warmup', type=int,default=30)
    parser.add_argument('--label', default='scout')
    args = parser.parse_args()
    experiment = args.experiment or Path((ROOT/'load/generated/employee-read-experiment.txt').read_text().strip())
    rates = [int(rate) for rate in args.rates.split(',')]
    if not experiment.is_dir() or any(not 1 <= rate <= 200 for rate in rates) or args.seconds < 5 or args.warmup < 5:
        parser.error('기존 실험 폴더와 1~200 RPS, 5초 이상의 시간을 지정하세요.')
    for rate in rates:
        run(experiment,args.phase,rate,args.seconds,args.warmup,args.label)


if __name__ == '__main__':
    main()
