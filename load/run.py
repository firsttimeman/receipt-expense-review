#!/usr/bin/env python3
"""k6 실행 결과와 같은 실행에 속한 DB 작업 수를 함께 기록합니다."""
import argparse
import csv
import datetime
import json
import os
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from resource_monitor import ResourceMonitor

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ['docker', 'compose', '--env-file', '/dev/null', '--project-name', 'receipt-loadtest',
           '--file', str(ROOT / 'infra/loadtest/compose.yml')]
INFLUX_URL = 'http://127.0.0.1:' + os.environ.get('LOADTEST_INFLUX_PORT', '18086')
GRAFANA_ENABLED = os.environ.get('LOADTEST_GRAFANA', '1') != '0'


def command(args, **kwargs):
    return subprocess.check_output(args, cwd=ROOT, text=True, timeout=20, **kwargs).strip()


def query(sql):
    return command(COMPOSE + ['exec', '-T', 'mysql', 'sh', '-c',
                             'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" '
                             '-D "$MYSQL_DATABASE" --batch --skip-column-names'], input=sql)


def snapshot(run_id):
    # run_id는 이 스크립트가 생성한 날짜+UUID만 사용합니다.
    result = query(f"""
        SELECT COUNT(*),
               COALESCE(SUM(j.status='QUEUED'), 0), COALESCE(SUM(j.status='PROCESSING'), 0),
               COALESCE(SUM(j.status='RETRY_WAIT'), 0), COALESCE(SUM(j.status='COMPLETED'), 0),
               COALESCE(SUM(j.status='FAILED'), 0)
        FROM receipt_extraction_jobs j JOIN receipts r ON r.id=j.receipt_id
        WHERE r.original_file_name LIKE 'lt-{run_id}-%';
    """)
    return [int(value) for value in result.split('\t')]


def send_metrics(measurement, run_id, fields):
    if not GRAFANA_ENABLED:
        return
    values = ','.join(f'{key}={float(value)}' for key, value in fields.items())
    line = f'{measurement},run_id={run_id} {values} {time.time_ns()}'
    request = urllib.request.Request(INFLUX_URL + '/write?db=receipt_loadtest', data=line.encode(), method='POST')
    with urllib.request.urlopen(request, timeout=5) as response:
        if response.status != 204:
            raise RuntimeError('InfluxDB 기록 실패')


def verify_grafana_metrics(run_id, result_dir, metrics):
    counts = {'receipt_upload_ms': 0, 'receipt_completion_ms': 0}
    with (result_dir / 'metrics.json').open() as source:
        for line in source:
            item = json.loads(line)
            if item.get('type') == 'Point' and item.get('metric') in counts:
                counts[item['metric']] += 1
    checks = [(name, 'count', count) for name, count in counts.items()]
    for name in ['receipt_submissions', 'receipt_accepted', 'receipt_completed',
                 'receipt_not_accepted', 'receipt_failed', 'receipt_completion_timeouts']:
        checks.append((name, 'sum', metrics.get(name, {}).get('values', {}).get('count', 0)))
    results = {}
    for name, function, expected in checks:
        query_text = f'SELECT {function}("value") FROM "{name}" WHERE "run_id"=\'{run_id}\''
        url = INFLUX_URL + '/query?' + urllib.parse.urlencode({'db': 'receipt_loadtest', 'q': query_text})
        with urllib.request.urlopen(url, timeout=5) as response:
            result = json.load(response)['results'][0]
        if 'error' in result:
            raise RuntimeError('Grafana 지표 검증 쿼리 실패')
        series = result.get('series', [])
        actual = (series[0]['values'][0][1] or 0) if series else 0
        results[name] = {'expected': expected, 'stored': actual, 'matches': actual == expected}
    return results


def watch_queue(run_id, target, stop, errors):
    with target.open('w', newline='') as file:
        writer = csv.writer(file)
        writer.writerow(['timestamp_utc', 'elapsed_seconds', 'total', 'queued', 'processing', 'retry_wait',
                         'completed', 'failed', 'unfinished', 'completed_per_second'])
        started = previous_time = time.monotonic()
        previous_completed = 0
        while True:
            final_sample = stop.is_set()
            try:
                counts = snapshot(run_id)
                now = time.monotonic()
                throughput = (counts[4] - previous_completed) / max(0.001, now - previous_time)
                writer.writerow([datetime.datetime.now(datetime.timezone.utc).isoformat(), round(now - started, 3),
                                 *counts, sum(counts[1:4]), round(throughput, 3)])
                file.flush()
                previous_time, previous_completed = now, counts[4]
                fields = dict(zip(['total', 'queued', 'processing', 'retry_wait', 'completed', 'failed'], counts))
                fields.update(unfinished=sum(counts[1:4]), completed_per_second=throughput)
                send_metrics('receipt_job_queue', run_id, fields)
            except (subprocess.SubprocessError, ValueError, urllib.error.URLError, TimeoutError, RuntimeError) as error:
                errors.append(type(error).__name__)
                print('작업 수 조회 또는 Grafana 지표 전송 실패. 결과의 관측 오류를 확인하세요.', flush=True)
            if final_sample:
                return
            stop.wait(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    args = parser.parse_args()
    if not shutil.which('k6'):
        raise SystemExit('k6가 필요합니다. macOS: brew install k6')
    if not (ROOT / 'load/generated/users.json').exists():
        raise SystemExit('먼저 ./scripts/loadtest.sh prepare를 실행하세요.')
    if GRAFANA_ENABLED:
        try:
            with urllib.request.urlopen(INFLUX_URL + '/ping', timeout=5) as response:
                response.read()
        except (urllib.error.URLError, TimeoutError):
            raise SystemExit('InfluxDB가 준비되지 않았습니다. ./scripts/loadtest.sh up을 먼저 실행하세요.')
    pending = int(query("SELECT COUNT(*) FROM receipt_extraction_jobs WHERE status IN ('QUEUED','PROCESSING','RETRY_WAIT');"))
    if pending:
        raise SystemExit(f'이전 작업 {pending}건이 아직 처리 중입니다. 완료 후 다시 실행하세요.')

    run_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S').lower() + '-' + uuid.uuid4().hex[:8]
    result_dir = ROOT / 'load/results' / run_id
    result_dir.mkdir(parents=True)
    configuration = json.loads(command(COMPOSE + ['config', '--format', 'json']))
    app = configuration['services']['app']
    tracked_settings = ['SPRING_PROFILES_ACTIVE', 'RECEIPT_EXTRACTOR_PROVIDER', 'RECEIPT_FAKE_DELAY',
                        'RECEIPT_WORKER_CONCURRENCY', 'RECEIPT_WORKER_BATCH_SIZE']
    # Compose 선언보다 실제 실행 컨테이너의 환경을 기록합니다. 변경 뒤 up을 잊은 경우도 구분합니다.
    container_id = command(COMPOSE + ['ps', '-q', 'app'])
    container = json.loads(command(['docker', 'inspect', container_id]))[0]
    actual_env = dict(entry.split('=', 1) for entry in container['Config']['Env'])
    if actual_env.get('RECEIPT_EXTRACTOR_PROVIDER') != 'fake' or actual_env.get('RECEIPT_S3_ENDPOINT') != 'http://s3mock:9090':
        raise SystemExit('이 실행기는 Fake 추출기와 로컬 S3Mock 환경에서만 사용합니다.')
    metadata = dict(runId=run_id, baseUrl=args.base_url, environment='local MySQL + Redis + S3Mock; fake extraction',
                    gitCommit=command(['git', 'rev-parse', 'HEAD']),
                    gitWorkingTree=command(['git', 'status', '--short']),
                    k6Version=command(['k6', 'version']), appImageId=container['Image'],
                    appMemoryLimitBytes=container['HostConfig']['Memory'],
                    appSettings={key: actual_env.get(key) for key in tracked_settings},
                    composeSettingsMatch=all(actual_env.get(key) == app['environment'].get(key) for key in tracked_settings),
                    queueSampleSeconds=2, grafanaEnabled=GRAFANA_ENABLED,
                    sampleIdentityTag='load_vu',
                    startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    (result_dir / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    env = dict(os.environ, RUN_ID=run_id, BASE_URL=args.base_url, SUMMARY_FILE=str(result_dir / 'summary.json'))
    stop = threading.Event()
    errors = []
    resources = None
    if os.environ.get('LOADTEST_RESOURCES', '0') == '1':
        service_names = ['app', 'mysql', 'redis', 's3mock']
        containers = {}
        for service in service_names:
            cid = command(COMPOSE + ['ps', '-q', service])
            name = command(['docker', 'inspect', '--format', '{{.Name}}', cid]).lstrip('/')
            containers[name] = service
        networks = container['NetworkSettings']['Networks']
        app_ip = networks['receipt-loadtest_default']['IPAddress']
        resources = ResourceMonitor(run_id, result_dir, containers, app_ip, query,
                                    INFLUX_URL if GRAFANA_ENABLED else None, errors)
        metadata['resources'] = dict(enabled=True, containerSource='docker stats stream', containerSampleSeconds=2,
                                     dbSampleSeconds=2, appAddress=app_ip,
                                     dbConnections='MySQL connections from app IP; query-active is not Hikari borrowed')
        resources.start()
    monitor = threading.Thread(target=watch_queue, args=(run_id, result_dir / 'queue.csv', stop, errors))
    monitor.start()
    print(f'Run: {run_id}\n결과 폴더: {result_dir}', flush=True)
    return_code = 1
    started_millis = int(time.time() * 1000)
    k6_command = ['k6', 'run', '--no-usage-report', '--tag', f'run_id={run_id}',
                  '--out', f'json={result_dir / "metrics.json"}']
    if GRAFANA_ENABLED:
        k6_command += ['--out', 'influxdb=' + INFLUX_URL + '/receipt_loadtest']
    k6_command.append(str(ROOT / 'load/k6/receipts.js'))
    try:
        with (result_dir / 'console.log').open('w') as log:
            with subprocess.Popen(k6_command, cwd=ROOT, env=env, text=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
                try:
                    for line in process.stdout:
                        print(line, end='', flush=True)
                        log.write(line)
                    return_code = process.wait()
                except KeyboardInterrupt:
                    process.terminate()
                    process.wait(timeout=15)
                    return_code = 130
    finally:
        stop.set()
        monitor.join()
        if resources:
            metadata['resources']['samples'] = resources.stop()
        # k6와 Grafana의 최종 p95가 같은 계산값을 사용하도록 요약값도 저장합니다.
        summary_file = result_dir / 'summary.json'
        if GRAFANA_ENABLED and summary_file.exists():
            try:
                metrics = json.loads(summary_file.read_text())['metrics']
                fields = {}
                for metric, name in [('receipt_upload_ms', 'upload'), ('receipt_completion_ms', 'completion')]:
                    values = metrics.get(metric, {}).get('values', {})
                    for statistic, suffix in [('avg', 'avg'), ('p(95)', 'p95'), ('p(99)', 'p99'), ('max', 'max')]:
                        if statistic in values:
                            fields[f'{name}_{suffix}_ms'] = values[statistic]
                fields['dropped_iterations'] = metrics.get('dropped_iterations', {}).get('values', {}).get('count', 0)
                fields['k6_exit_code'] = return_code
                fields['success_rate'] = metrics.get('receipt_success', {}).get('values', {}).get('rate', 0)
                fields['unsuccessful'] = sum(metrics.get(name, {}).get('values', {}).get('count', 0)
                                             for name in ['receipt_not_accepted', 'receipt_failed', 'receipt_completion_timeouts'])
                send_metrics('receipt_run_summary', run_id, fields)
                metadata['grafanaMetricVerification'] = verify_grafana_metrics(run_id, result_dir, metrics)
                if not all(check['matches'] for check in metadata['grafanaMetricVerification'].values()):
                    errors.append('GrafanaMetricMismatch')
            except (urllib.error.URLError, TimeoutError, RuntimeError, ValueError, KeyError) as error:
                errors.append(type(error).__name__)
        metadata.update(k6ExitCode=return_code, queueSampleErrors=errors,
                        finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
        if GRAFANA_ENABLED:
            metadata['grafanaUrl'] = (f'http://localhost:{os.environ.get("LOADTEST_GRAFANA_PORT", "13000")}'
                                      f'/d/receipt-loadtest/receipt-loadtest?var-run_id={run_id}'
                                      f'&from={started_millis - 5000}&to={int(time.time() * 1000) + 5000}')
        (result_dir / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    if errors:
        print('DB/Grafana 관측 또는 지표 검증에 문제가 있습니다. environment.json을 확인하세요.')
    print(f'결과 저장 완료: {result_dir}')
    if GRAFANA_ENABLED:
        print('Grafana: ' + metadata['grafanaUrl'])
    raise SystemExit(return_code or (1 if errors else 0))


if __name__ == '__main__':
    main()
