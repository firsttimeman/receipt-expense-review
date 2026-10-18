#!/usr/bin/env python3
"""동일 이미지의 동시 제출과 완료 후 재전송을 k6·실제 MySQL/Redis로 검증합니다."""
import argparse
import collections
import datetime
import hashlib
import http.cookiejar
import json
import os
import re
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zlib

from run import ROOT, COMPOSE, INFLUX_URL, command, query

GRAFANA = 'http://127.0.0.1:' + os.environ.get('LOADTEST_GRAFANA_PORT', '13000')


def save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def json_rows(sql):
    return [json.loads(line) for line in query(sql).splitlines() if line]


def snapshot(case_id):
    # 비동기 완료 전후의 값이 섞이지 않도록 모든 검증 테이블을 같은 스냅샷에서 읽습니다.
    result = query(f"""
        SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
        START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
        SELECT JSON_OBJECT('collection', 'receipts', 'row', JSON_OBJECT(
            'id', id, 'ownerId', owner_employee_id, 'status', status,
            'version', version, 'sha256', image_sha256, 'size', file_size))
        FROM receipts WHERE original_file_name='lt-{case_id}.png' ORDER BY id;
        SELECT JSON_OBJECT('collection', 'jobs', 'row', JSON_OBJECT(
            'id', j.id, 'receiptId', j.receipt_id, 'status', j.status,
            'attemptCount', j.attempt_count, 'duplicateDetected', CAST(j.duplicate_detected AS UNSIGNED),
            'version', j.version, 'storageKey', j.image_storage_key))
        FROM receipt_extraction_jobs j JOIN receipts r ON r.id=j.receipt_id
        WHERE r.original_file_name='lt-{case_id}.png' ORDER BY j.id;
        SELECT JSON_OBJECT('collection', 'audit', 'row', JSON_OBJECT(
            'id', a.id, 'receiptId', a.receipt_id, 'action', a.action,
            'previousStatus', a.previous_status, 'newStatus', a.new_status))
        FROM audit_events a JOIN receipts r ON r.id=a.receipt_id
        WHERE r.original_file_name='lt-{case_id}.png' ORDER BY a.id;
        SELECT JSON_OBJECT('collection', 'keys', 'row', JSON_OBJECT(
            'receiptId', k.receipt_id, 'key', k.idempotency_key))
        FROM idempotency_records k JOIN receipts r ON r.id=k.receipt_id
        WHERE r.original_file_name='lt-{case_id}.png' ORDER BY k.id;
        COMMIT;
    """)
    tables = dict(receipts=[], jobs=[], audit=[], keys=[])
    for line in result.splitlines():
        item = json.loads(line)
        tables[item['collection']].append(item['row'])
    return tables


def get_detail(base, user, receipt_id):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    def call(path, body=None, headers=None):
        request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                         headers=headers or {})
        try:
            with opener.open(request, timeout=10) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())
    status, token = call('/api/auth/csrf')
    if status != 200:
        raise RuntimeError('검증용 CSRF 요청 실패')
    status, _ = call('/api/auth/login', {'loginId': user['loginId'], 'password': user['password']},
                     {'Content-Type': 'application/json', token['headerName']: token['token']})
    if status != 204:
        raise RuntimeError('검증용 로그인 실패')
    return call(f'/api/receipts/{receipt_id}')


def influx_query(query_text, through_grafana=False):
    address = (GRAFANA + '/api/datasources/proxy/uid/receipt-loadtest-influx'
               if through_grafana else INFLUX_URL)
    url = address + '/query?' + urllib.parse.urlencode({'db': 'receipt_loadtest', 'q': query_text})
    with urllib.request.urlopen(url, timeout=10) as response:
        result = json.load(response)['results'][0]
    if 'error' in result:
        raise RuntimeError(result['error'])
    return result.get('series', [])


def publish(measurement, tags, fields):
    tag_text = ','.join(f'{key}={value}' for key, value in tags.items())
    field_text = ','.join(f'{key}={float(value)}' for key, value in fields.items())
    line = f'{measurement},{tag_text} {field_text} {time.time_ns()}'
    request = urllib.request.Request(INFLUX_URL + '/write?db=receipt_loadtest', data=line.encode(), method='POST')
    with urllib.request.urlopen(request, timeout=10) as response:
        if response.status != 204:
            raise RuntimeError('InfluxDB 결과 저장 실패')


def image_hash(case_id):
    original = (ROOT / 'load/generated/receipt.png').read_bytes()
    body = b'tEXt' + b'loadtest\0' + case_id.encode()
    chunk = struct.pack('>I', len(body) - 4) + body + struct.pack('>I', zlib.crc32(body))
    payload = original[:-12] + chunk + original[-12:]
    return hashlib.sha256(payload).hexdigest(), len(payload)


def run_phase(experiment, case, phase, directory, base):
    target = directory / phase
    target.mkdir()
    env = dict(os.environ, BASE_URL=base, CASE_ID=case['id'], DUPLICATE_MODE=case['mode'],
               DUPLICATE_PHASE=phase, DUPLICATE_VUS=str(case['vus']), SUMMARY_FILE=str(target / 'summary.json'))
    tags = dict(experiment_id=experiment, run_id=case['id'], mode=case['mode'], phase=phase,
                burst_vus=str(case['vus']), round=str(case['round']))
    args = ['k6', 'run', '--quiet', '--log-format', 'raw', '--no-usage-report']
    for key, value in tags.items():
        args += ['--tag', f'{key}={value}']
    args += ['--out', f'json={target / "metrics.json"}', '--out', f'influxdb={INFLUX_URL}/receipt_loadtest',
             str(ROOT / 'load/k6/duplicates.js')]
    with (target / 'console.log').open('w') as log:
        process = subprocess.run(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=100)
    samples = []
    for line in (target / 'console.log').read_text().splitlines():
        if line.startswith('DUPLICATE_SAMPLE '):
            samples.append(json.loads(line.removeprefix('DUPLICATE_SAMPLE ')))
    save(target / 'responses.json', samples)
    summary_path = target / 'summary.json'
    if not summary_path.exists():
        raise RuntimeError(f'k6 요약 없음: {case["id"]} {phase}; console.log를 확인하세요.')
    metrics = json.loads(summary_path.read_text())['metrics']
    verification = {}
    for metric in ['duplicate_upload_ms', 'duplicate_requests', 'duplicate_created', 'duplicate_reused',
                   'duplicate_owner_conflicts', 'duplicate_unexpected']:
        with (target / 'metrics.json').open() as source:
            raw = [json.loads(line) for line in source]
        points = [r['data']['value'] for r in raw if r.get('type') == 'Point' and r.get('metric') == metric]
        expected = len(points) if metric == 'duplicate_upload_ms' else sum(points)
        function = 'count' if metric == 'duplicate_upload_ms' else 'sum'
        series = influx_query(f'SELECT {function}("value") FROM "{metric}" '
                              f'WHERE "run_id"=\'{case["id"]}\' AND "phase"=\'{phase}\'', True)
        stored = series[0]['values'][0][1] if series else 0
        summary_value = (len(samples) if metric == 'duplicate_upload_ms'
                         else metrics.get(metric, {}).get('values', {}).get('count', 0))
        verification[metric] = dict(raw=expected, grafana=stored, summary=summary_value,
                                    matches=expected == stored == summary_value)
    values = metrics.get('duplicate_upload_ms', {}).get('values', {})
    result = dict(phase=phase, exitCode=process.returncode, responses=samples,
                  latency=values, statusCounts=dict(collections.Counter(str(s['status']) for s in samples)),
                  startSpreadMs=max((s['startMs'] for s in samples), default=0)
                                - min((s['startMs'] for s in samples), default=0),
                  grafanaVerification=verification)
    save(target / 'verification.json', result)
    publish('receipt_duplicate_http', tags, dict(
        requests=len(samples), created=result['statusCounts'].get('202', 0), reused=result['statusCounts'].get('200', 0),
        owner_conflicts=result['statusCounts'].get('409', 0), p95_ms=values.get('p(95)', 0),
        p99_ms=values.get('p(99)', 0), start_spread_ms=result['startSpreadMs'], k6_exit_code=process.returncode))
    return result


def check_responses(case, phase, snapshot_data):
    errors = []
    samples = phase['responses']
    if phase['exitCode'] or len(samples) != case['vus']:
        errors.append('k6 threshold/요청 수 실패')
    if not all(v['matches'] for v in phase['grafanaVerification'].values()):
        errors.append('Grafana 원시 표본 불일치')
    if len(snapshot_data['receipts']) != 1 or len(snapshot_data['jobs']) != 1:
        return errors + ['영수증 또는 Job이 1개가 아님']
    receipt = snapshot_data['receipts'][0]
    job = snapshot_data['jobs'][0]
    created_count = sum(s['status'] == 202 for s in samples)
    if created_count != (1 if phase['phase'] == 'initial' else 0):
        errors.append('신규 접수 202 개수 불일치')
    saved_keys = {k['key'] for k in snapshot_data['keys']}
    for s in samples:
        if s['employeeId'] != receipt['ownerId']:
            if s['status'] != 409 or s['receiptId'] is not None or s['jobId'] is not None:
                errors.append(f'다른 직원 차단 실패: VU {s["vu"]}')
            continue
        if s['status'] not in (200, 202) or s['receiptId'] != receipt['id'] or s['jobId'] != job['id']:
            errors.append(f'소유자 응답/식별자 불일치: VU {s["vu"]}, HTTP {s["status"]}')
        if s['status'] == 200:
            key = (f'lt-{case["id"]}' if case['mode'] == 'same-key'
                   else None if case['mode'] == 'no-key' else f'lt-{case["id"]}-{s["vu"]}')
            if s['replay'] != str(key in saved_keys).lower():
                errors.append(f'재전송 헤더 불일치: VU {s["vu"]}')
    return errors


def verify_case(case, before, after, phases, base, fixture, container_id, started_at, directory, s3_prefix):
    errors = []
    for phase in phases:
        errors += check_responses(case, phase, before)
    if len(after['receipts']) != 1 or len(after['jobs']) != 1:
        return dict(passed=False, errors=errors + ['영수증/Job 수 불일치'])
    receipt, job = after['receipts'][0], after['jobs'][0]
    expected_hash, expected_size = image_hash(case['id'])
    if receipt['sha256'] != expected_hash or receipt['size'] != expected_size:
        errors.append('전송 이미지 SHA-256/크기 불일치')
    if before != after:
        errors.append('완료 후 재전송이 DB 상태/버전/감사 이력을 변경함')
    if job['status'] != 'COMPLETED' or job['attemptCount'] != 1:
        errors.append('추출 완료/시도 횟수 불일치')
    duplicate = case['mode'] != 'same-key' and not (case['mode'] == 'cross-owner' and case['vus'] == 2)
    if bool(job['duplicateDetected']) != duplicate:
        errors.append('중복 표시 불일치')
    expected_status = 'NEEDS_REVIEW' if duplicate else 'AUTO_APPROVED'
    if receipt['status'] != expected_status:
        errors.append('최종 업무 상태 불일치')
    audit = collections.Counter(a['action'] for a in after['audit'])
    expected_audit = dict(UPLOADED=1, EXTRACTION_JOB_CLAIMED=1, EXTRACTION_COMPLETED=1, VALIDATION_COMPLETED=1)
    if duplicate:
        expected_audit['DUPLICATE_DETECTED'] = 1
    if dict(audit) != expected_audit:
        errors.append('감사 이력 횟수 불일치')
    if len(after['keys']) != (0 if case['mode'] == 'no-key' else 1):
        errors.append('요청 키 저장 수 불일치')
    owner = next(user for user in fixture['users'] if user['employeeId'] == receipt['ownerId'])
    status, detail = get_detail(base, owner, receipt['id'])
    save(directory / 'owner-detail.json', dict(httpStatus=status, body=detail))
    data = (detail or {}).get('currentData') or {}
    rules = (detail or {}).get('ruleResults') or []
    if status != 200 or detail.get('jobStatus') != 'COMPLETED' or data.get('shopName') != '테스트상점' or data.get('totalAmount') != 12000:
        errors.append('API 최종 추출 데이터 불일치')
    duplicate_rule = any(r['code'] == 'DUPLICATE_SUBMISSION' and r['outcome'] == 'FAIL' for r in rules)
    if duplicate_rule != duplicate:
        errors.append('API 중복 규칙 불일치')
    foreign_status = None
    if case['mode'] == 'cross-owner':
        foreign_user = next(u for u in fixture['users'][:2] if u['employeeId'] != receipt['ownerId'])
        foreign_status, _ = get_detail(base, foreign_user, receipt['id'])
        if foreign_status != 404:
            errors.append('다른 직원의 영수증 조회 차단 실패')
    logs = command(['docker', 'logs', '--since', started_at, container_id], stderr=subprocess.STDOUT)
    starts = [line for line in logs.splitlines()
              if f'Fake receipt extraction started. fileName=lt-{case["id"]}.png' in line]
    (directory / 'extraction-starts.log').write_text('\n'.join(starts) + '\n')
    if len(starts) != 1:
        errors.append(f'실제 Fake 추출 진입 횟수: {len(starts)}')
    # 객체를 공개하거나 다운로드하지 않고, 로컬 S3Mock의 정확한 키만 조회합니다.
    object_key = '/'.join(part for part in [s3_prefix.strip('/'), job['storageKey']] if part)
    address = 'http://s3mock:9090/receipt-loadtest?' + urllib.parse.urlencode({'list-type': 2, 'prefix': object_key})
    listing = command(COMPOSE + ['exec', '-T', 'app', 'curl', '--fail', '--silent', address])
    contents = ET.fromstring(listing).findall('{*}Contents')
    keys = [entry.find('{*}Key').text for entry in contents]
    if keys != [object_key]:
        errors.append('S3Mock 객체 수/키 불일치')
    return dict(passed=not errors, errors=errors, receiptCount=len(after['receipts']), jobCount=len(after['jobs']),
                receiptId=receipt['id'], jobId=job['id'], extractionCalls=len(starts), attempts=job['attemptCount'],
                auditCounts=dict(audit), duplicateDetected=duplicate, finalStatus=receipt['status'],
                foreignReadHttpStatus=foreign_status, s3Objects=len(keys), imageSha256=expected_hash,
                replayStateUnchanged=before == after)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:19880')
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--sizes', type=int, nargs='+', default=[10, 50])
    parser.add_argument('--modes', nargs='+', choices=['same-key', 'different-key', 'no-key', 'cross-owner'],
                        default=['same-key', 'different-key', 'no-key', 'cross-owner'])
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    if not 1 <= args.repeats <= 10 or any(not 2 <= n <= 100 or n % 2 for n in args.sizes):
        parser.error('반복 1~10회, 요청 수는 2~100 사이의 짝수를 사용하세요.')
    container_id = command(COMPOSE + ['ps', '-q', 'app'])
    container = json.loads(command(['docker', 'inspect', container_id]))[0]
    actual = dict(item.split('=', 1) for item in container['Config']['Env'])
    port = container['NetworkSettings']['Ports']['8080/tcp'][0]
    if (base != f'http://127.0.0.1:{port["HostPort"]}' or port['HostIp'] != '127.0.0.1'
            or actual.get('RECEIPT_EXTRACTOR_PROVIDER') != 'fake'
            or actual.get('RECEIPT_S3_ENDPOINT') != 'http://s3mock:9090'
            or 'loadtest' not in actual.get('SPRING_PROFILES_ACTIVE', '').split(',')):
        raise SystemExit('격리된 로컬 Fake + S3Mock 서버에서만 실행합니다.')
    if int(query("SELECT COUNT(*) FROM receipt_extraction_jobs WHERE status IN ('QUEUED','PROCESSING','RETRY_WAIT');")):
        raise SystemExit('이전 미완료 작업을 먼저 기다려 주세요.')
    fixture = json.loads((ROOT / 'load/generated/users.json').read_text())
    if fixture['baseUrl'] != base or len(fixture['users']) < 2:
        raise SystemExit('테스트 직원 2명 이상을 먼저 준비하세요.')
    experiment = 'dup-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dt%H%M%S') + '-' + uuid.uuid4().hex[:6]
    directory = ROOT / 'load/results' / experiment
    directory.mkdir(parents=True)
    started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
    metadata = dict(experimentId=experiment, startedAt=started_at, appImageId=container['Image'],
                    baseUrl=base, appMemoryLimitBytes=container['HostConfig']['Memory'],
                    appSettings={key: actual.get(key) for key in ['SPRING_PROFILES_ACTIVE', 'RECEIPT_EXTRACTOR_PROVIDER',
                                  'RECEIPT_FAKE_DELAY', 'RECEIPT_WORKER_CONCURRENCY', 'RECEIPT_WORKER_BATCH_SIZE']},
                    k6Version=command(['k6', 'version']), gitCommit=command(['git', 'rev-parse', 'HEAD']),
                    cases=[], limitations=['단일 앱 인스턴스, 실제 MySQL/Redis, S3Mock/Fake',
                        '동시 VU 해제 방식이며 서버 도착 시각이 완전히 같다는 의미는 아님',
                        '재시작/네트워크 장애를 넣지 않았으므로 장애 시 exactly-once 보장이 아님'])
    save(directory / 'experiment.json', metadata)
    print(f'Experiment: {experiment}\n결과: {directory}', flush=True)
    for round_no in range(1, args.repeats + 1):
        for size in args.sizes:
            for mode in args.modes:
                # 권한 교차 검사는 큰 요청 묶음에서 반복합니다.
                if mode == 'cross-owner' and size != max(args.sizes):
                    continue
                case = dict(id=f'{experiment}-r{round_no}-n{size}-{mode}', round=round_no, vus=size, mode=mode)
                target = directory / case['id']
                target.mkdir()
                print(f'시작 {case["id"]}', flush=True)
                initial = run_phase(experiment, case, 'initial', target, base)
                deadline = time.monotonic() + 40
                while True:
                    before = snapshot(case['id'])
                    if before['jobs'] and all(j['status'] in ('COMPLETED', 'FAILED') for j in before['jobs']):
                        break
                    if time.monotonic() >= deadline:
                        save(target / 'before-replay.json', before)
                        raise RuntimeError('작업이 40초 안에 완료되지 않았습니다. 원본 결과를 남기고 중단합니다.')
                    time.sleep(0.25)
                save(target / 'before-replay.json', before)
                replay = run_phase(experiment, case, 'after-completion', target, base)
                after = snapshot(case['id'])
                save(target / 'after-replay.json', after)
                validation = verify_case(case, before, after, [initial, replay], base, fixture,
                                         container_id, started_at, target, actual.get("RECEIPT_S3_PREFIX", ""))
                case.update(validation=validation, phases=[initial, replay])
                metadata['cases'].append(case)
                save(target / 'validation.json', validation)
                save(directory / 'experiment.json', metadata)
                publish('receipt_duplicate_validation',
                        dict(experiment_id=experiment, mode=mode, burst_vus=size, round=round_no, run_id=case['id']),
                        dict(passed=validation['passed'], receipts=len(after['receipts']), jobs=len(after['jobs']),
                             extraction_calls=validation.get('extractionCalls', -1), attempts=validation.get('attempts', -1),
                             duplicate_events=validation.get('auditCounts', {}).get('DUPLICATE_DETECTED', 0),
                             replay_unchanged=validation.get('replayStateUnchanged', False)))
                print(f'완료 {mode} {size}개 {round_no}회: passed={validation["passed"]}, '
                      f'initial={initial["statusCounts"]}, replay={replay["statusCounts"]}, '
                      f'p95={initial["latency"].get("p(95)", 0):.1f}ms; {validation["errors"]}', flush=True)
    metadata['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    metadata['passed'] = all(c['validation']['passed'] for c in metadata['cases'])
    metadata['totalRequests'] = sum(len(p['responses']) for c in metadata['cases'] for p in c['phases'])
    start_ms = int(datetime.datetime.fromisoformat(started_at).timestamp() * 1000) - 5000
    metadata['grafanaUrl'] = (f'http://localhost:{os.environ.get("LOADTEST_GRAFANA_PORT", "13000")}'
                              f'/d/receipt-duplicates/duplicates?var-experiment_id={experiment}'
                              f'&from={start_ms}&to={int(time.time() * 1000) + 5000}')
    save(directory / 'experiment.json', metadata)
    print(f'전체 완료: {len(metadata["cases"])} cases / {metadata["totalRequests"]} requests / passed={metadata["passed"]}', flush=True)
    print(metadata['grafanaUrl'], flush=True)
    raise SystemExit(0 if metadata['passed'] else 1)


if __name__ == '__main__':
    main()
