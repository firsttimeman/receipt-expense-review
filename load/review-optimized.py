#!/usr/bin/env python3
"""검수 목록 개선 후 측정. 기존 k6/측정기를 재사용하고 원본 DB와 개선 전 결과를 보존합니다."""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess

SPEC = importlib.util.spec_from_file_location('review_baseline', Path(__file__).with_name('review-baseline.py'))
B = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(B)
R, ROOT, COMPOSE = B.R, B.ROOT, B.COMPOSE
BASELINE = ROOT / 'load/results/review-baseline-20261018t105936'
APP_TAG = 'receipt-expense-review:loadtest'
TABLES = ('employees', 'receipts', 'receipt_extraction_jobs')
COUNTS = (10000, 100000)
KINDS = ('employee', 'ids', 'entities', 'count', 'jobs')
ORIGINAL_FIXTURE = B.fixture
ACTIVE_KINDS = KINDS
PROOF = {}


def read(path):
    if not path.is_file():
        raise RuntimeError('필요한 기존 실험 파일이 없습니다: ' + str(path)
            + '\n기존 결과 폴더를 복원하거나 --baseline/--experiment/--source-experiment로 해당 결과 경로를 지정하세요. '
            + 'load/results는 Git에 포함되지 않습니다.')
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def persist(experiment, metadata):
    R.save(experiment / 'experiment.json', metadata)
    R.save(experiment / 'comparability.json', PROOF)


def classify(sql):
    text = re.sub(r'\s+', ' ', sql.replace('`', '')).strip().lower()
    text = re.sub(r'\s*\.\s*', '.', text)
    if not text.startswith('select '):
        return None
    if re.search(r'\bfrom employees\b', text) and re.search(r'\blogin_id\s*=', text):
        return 'employee'
    if re.search(r'\bfrom receipt_extraction_jobs\b', text) and re.search(r'\breceipt_id\s+in\s*\(', text):
        return 'jobs'
    if not re.search(r'\bfrom receipts\b', text):
        return None
    if re.search(r'\bwhere\s+(?:\w+\.)?id\s+in\s*\(', text):
        return 'entities'
    if 'owner_employee_id' not in text or ' where ' not in text:
        return None
    if re.match(r'select\s+count\s*\(', text):
        return 'count'
    if re.match(r'select\s+(?:\w+\.)?id\s+from\s+receipts\b', text):
        return 'ids'
    return 'list'


def snapshot(database):
    raw = B.query(f"""SELECT JSON_OBJECT('digest',DIGEST,'sql',DIGEST_TEXT,'count',COUNT_STAR,
        'timerPs',SUM_TIMER_WAIT,'rowsExamined',SUM_ROWS_EXAMINED,'rowsSent',SUM_ROWS_SENT,'errors',SUM_ERRORS)
        FROM performance_schema.events_statements_summary_by_digest
        WHERE SCHEMA_NAME='{database}' AND DIGEST_TEXT LIKE 'SELECT%';""")
    result = {}
    for line in raw.splitlines():
        row = json.loads(line)
        kind = classify(row['sql'])
        if kind:
            row['kind'] = kind
            result[row['digest']] = row
    return result


def delta(before, after):
    result = {kind: dict(count=0, timerMs=0, rowsExamined=0, rowsSent=0, errors=0) for kind in ACTIVE_KINDS}
    for key, row in after.items():
        previous = before.get(key, {})
        if row['count'] == previous.get('count', 0):
            continue
        if row['kind'] not in result:
            raise RuntimeError('예상하지 않은 앱 SQL 종류: ' + row['kind'])
        target = result[row['kind']]
        for field in ('count', 'rowsExamined', 'rowsSent', 'errors'):
            target[field] += row[field] - previous.get(field, 0)
        target['timerMs'] += (row['timerPs'] - previous.get('timerPs', 0)) / 1e9
    return result


def fixture(database, count, case, password, directory, page=0):
    session, public, client = ORIGINAL_FIXTURE(database, count, case, password, directory, page)
    baseline = read(BASELINE / str(count) / f'fixture-{case}-{page}.json')
    if public != baseline:
        session.unlink(missing_ok=True)
        raise RuntimeError(f'개선 전 전체 응답과 불일치: {count}/{case}/{page}')
    PROOF.setdefault('fixtures', {})[f'{count}/{case}/{page}'] = dict(
        equal=True, baseline=str((BASELINE / str(count) / f'fixture-{case}-{page}.json').resolve()),
        **public)
    return session, public, client


B.snapshot, B.delta, B.fixture = snapshot, delta, fixture


def columns(database, table):
    return [line.split('\t')[0] for line in B.query(f'SHOW COLUMNS FROM `{table}`;', database).splitlines()]


def fingerprint(database, tables=('receipts', 'receipt_extraction_jobs')):
    # SQL에서는 각 행의 모든 컬럼을 해시하고 Python에서는 ID 순서대로 행 해시를 다시 합칩니다.
    # 비밀번호를 포함한 employees 값은 읽어 출력하거나 아티팩트에 기록하지 않습니다.
    result = {}
    for table in tables:
        names = columns(database, table)
        selected = ','.join('`' + name + '`' for name in names)
        raw = B.query(f'SELECT SHA2(JSON_ARRAY({selected}),256) FROM `{table}` ORDER BY id;', database)
        hashes = raw.splitlines()
        if any(not re.fullmatch('[0-9a-f]{64}', item) for item in hashes):
            raise RuntimeError('행 해시 생성 실패: ' + table)
        result[table] = dict(columns=names, rows=len(hashes), sha256=hashlib.sha256(
            ''.join(item + '\n' for item in hashes).encode()).hexdigest())
    return result


def app_state():
    info = json.loads(R.command(['docker', 'inspect', 'receipt-loadtest-app-1']))[0]
    env = dict(value.split('=', 1) for value in info['Config']['Env'])
    required = dict(SPRING_PROFILES_ACTIVE='prod,loadtest', RECEIPT_EXTRACTOR_PROVIDER='fake',
        RECEIPT_S3_ENDPOINT='http://s3mock:9090', RECEIPT_FAKE_DELAY='2s',
        RECEIPT_WORKER_CONCURRENCY='4', RECEIPT_WORKER_BATCH_SIZE='4')
    if any(env.get(key) != value for key, value in required.items()):
        raise RuntimeError('로컬 Fake 2초 / Worker 4 / S3Mock 환경만 허용합니다.')
    # 전체 inspect/환경 변수에는 자격 증명이 있으므로 허용한 설정만 반환합니다.
    return dict(imageId=info['Image'], databaseUrl=env['DB_URL'], settings=required,
        javaToolOptions=env.get('JAVA_TOOL_OPTIONS'), memoryBytes=info['HostConfig']['Memory'],
        nanoCpus=info['HostConfig']['NanoCpus'])


def image_id(image):
    try:
        return R.command(['docker', 'image', 'inspect', '--format', '{{.Id}}', image])
    except subprocess.CalledProcessError as exception:
        raise RuntimeError('로컬 Docker 이미지를 확인할 수 없습니다: ' + image
            + '\nDocker가 실행 중인지 확인하고 비교할 코드로 이미지를 먼저 빌드하거나 가져오세요. '
            + '--image 또는 --index-only-image/--id-first-image로 준비한 태그를 지정할 수 있습니다.') from exception


def use_image(image):
    R.command(['docker', 'tag', image, APP_TAG])


def environment():
    info = json.loads(R.command(['docker', 'info', '--format', '{{json .}}']))
    return dict(dockerCpuCount=info.get('NCPU'), dockerMemoryBytes=info.get('MemTotal'),
        innodbBufferPoolBytes=int(B.query('SELECT @@innodb_buffer_pool_size;')),
        mysqlVersion=B.query('SELECT @@version;'), app=app_state())


def restore(experiment, metadata, image):
    use_image(image)
    R.command(COMPOSE + ['up', '-d', '--no-deps', '--force-recreate', 'app'], timeout=120)
    B.wait_app()
    state = app_state()
    actual = fingerprint('receipt_loadtest')
    R.save(experiment / 'original-fingerprint-after.json', actual)
    metadata['originalReceiptCountAfter'] = actual['receipts']['rows']
    metadata['restoredImageId'] = state['imageId']
    metadata['restoredOriginalDatabase'] = (
        '/receipt_loadtest?' in state['databaseUrl'] and state['imageId'] == image
        and actual == read(experiment / 'original-fingerprint-before.json'))
    persist(experiment, metadata)
    if not metadata['restoredOriginalDatabase']:
        raise RuntimeError('원래 DB/이미지 복원 또는 원래 데이터 해시 검증 실패')
    print('RESTORED receipt_loadtest (data preserved). Results: ' + str(experiment), flush=True)


def source_hashes():
    files = [Path(__file__).resolve(), ROOT / 'load/review-baseline.py', ROOT / 'load/k6/review-queue.js',
             ROOT / 'load/resource_monitor.py', ROOT / 'load/run-reads.py', ROOT / 'infra/loadtest/compose.yml',
             ROOT / 'src/main/resources/application-loadtest.yml',
             ROOT / 'src/main/java/com/example/receipt/domain/receipt/repository/ReceiptRepository.java',
             ROOT / 'src/main/java/com/example/receipt/domain/receipt/service/ReceiptQueryService.java',
             ROOT / 'src/main/resources/db/migration/V7__add_receipt_review_index.sql']
    return {str(path.relative_to(ROOT)): sha(path) for path in files if path.exists()}


def check_baseline():
    metadata = read(BASELINE / 'experiment.json')
    if not metadata.get('completed') or len(metadata['runs']) != 12:
        raise RuntimeError('완료된 개선 전 12회가 필요합니다.')
    if sha(ROOT / 'load/k6/review-queue.js') != metadata['scripts']['load/k6/review-queue.js']:
        raise RuntimeError('k6 시나리오가 개선 전과 다릅니다.')
    return metadata


def collect_runs(directory, existing):
    by_id = {row['runId']: row for row in existing}
    for path in sorted(directory.glob('review-*/verification.json')):
        row = read(path)
        if row['runId'] in by_id and row != by_id[row['runId']]:
            raise RuntimeError('기록된 원시 결과가 변경되었습니다: ' + str(path))
        # dropped/timeout/threshold 실패를 버리고 재측정하지 않습니다.
        if not row.get('responseIntegrityPassed', False) or not all(
                row['checks'][key] for key in ('monitor', 'samples', 'noSqlErrors')):
            raise RuntimeError('성능 실패가 아닌 측정 무결성 오류입니다: ' + str(path))
        by_id[row['runId']] = row
    rows = sorted(by_id.values(), key=lambda row: row['startNs'])
    keys = [(row['dataset'], row['scenario'], row['repeat']) for row in rows]
    if len(keys) != len(set(keys)):
        raise RuntimeError('중복 실행 조합을 자동 선택할 수 없습니다.')
    return rows


def prepare_controls(experiment, baseline, user):
    global ACTIVE_KINDS
    ACTIVE_KINDS = ('employee', 'list', 'count', 'jobs')
    initial = app_state()
    if '/receipt_loadtest?' not in initial['databaseUrl']:
        raise RuntimeError('시작 전 앱을 원래 receipt_loadtest DB에 연결하세요.')
    original = fingerprint('receipt_loadtest')
    if original['receipts']['rows'] != baseline['originalReceiptCount']:
        raise RuntimeError('원래 DB 건수가 개선 전 기록과 다릅니다.')
    before_images = {row['imageId'] for row in baseline['runs']}
    if len(before_images) != 1:
        raise RuntimeError('개선 전 이미지가 하나여야 합니다.')
    before_image = image_id(before_images.pop())
    tag = 'receipt-expense-review:before-' + experiment.name
    R.command(['docker', 'tag', before_image, tag])
    original_tag = 'receipt-expense-review:original-' + experiment.name
    R.command(['docker', 'tag', initial['imageId'], original_tag])
    control = experiment / (experiment.name + '-controls')
    control.mkdir()
    R.save(experiment / 'original-fingerprint-before.json', original)
    env = environment()
    metadata = dict(baselineExperiment=str(BASELINE.resolve()), controlExperiment=str(control.resolve()),
        comparabilityProof='comparability.json', gitCommit=R.command(['git', 'rev-parse', 'HEAD']),
        rate=20, seconds=60, warmup=30, repeats=3, originalReceiptCount=original['receipts']['rows'],
        originalImageId=initial['imageId'], originalImageTag=original_tag, beforeImageId=before_image,
        beforeImageTag=tag, datasets={}, runs=[], beforeControls=[], scripts=source_hashes(),
        completed=False, restoredOriginalDatabase=False,
        dockerCpuCount=env['dockerCpuCount'], innodbBufferPoolBytes=env['innodbBufferPoolBytes'],
        mysqlVersion=env['mysqlVersion'], environment=env)
    PROOF.update(baselineExperiment=metadata['baselineExperiment'], workload=dict(rate=20, seconds=60,
        warmup=30, repeats=3, preAllocatedVUs=20, maxVUs=100, timeoutSeconds=15, drainSeconds=20),
        k6Sha256=sha(ROOT / 'load/k6/review-queue.js'), fixtures={}, datasets={},
        notes=['30초 준비 부하는 DB 전체 캐시 상주를 의미하지 않습니다.',
               '자원/SQL 측정 구간은 60초 요청 주입과 최대 20초 종료 대기를 포함합니다.',
               '같은 날 all 조건 1회씩은 환경 변화 확인용이며 전날 3회 반복을 대체하지 않습니다.'])
    persist(experiment, metadata)
    try:
        use_image(before_image)
        for count in COUNTS:
            source = baseline['datasets'][str(count)]['database']
            if int(B.query('SELECT COUNT(*) FROM receipts;', source)) != count:
                raise RuntimeError('개선 전 합성 DB가 없습니다: ' + source)
            if int(B.query("SELECT COUNT(*) FROM information_schema.statistics "
                    f"WHERE table_schema='{source}' AND index_name='ix_receipts_review_status_created';")):
                raise RuntimeError('개선 전 합성 DB에 개선 인덱스가 존재합니다.')
            directory = experiment / str(count)
            directory.mkdir()
            R.save(directory / 'fingerprint-before.json', fingerprint(source))
            B.configure_database(source, B.GENERATED / 'review-optimized-compose.yml')
            if app_state()['imageId'] != before_image:
                raise RuntimeError('개선 전 실행 이미지 불일치')
            metadata['beforeControls'].append(B.measure(source, count, 'all', 1, user['password'],
                control, 20, 60, 30))
            persist(experiment, metadata)
        metadata['controlsCompleted'] = len(metadata['beforeControls']) == 2
    finally:
        try:
            metadata['beforeControls'] = collect_runs(control, metadata['beforeControls'])
        finally:
            restore(experiment, metadata, initial['imageId'])


def copy_dataset(source, target, count):
    counts = {table: int(B.query(f'SELECT COUNT(*) FROM `{table}`;', target)) for table in TABLES}
    if any(counts.values()):
        if counts['receipts'] != count or counts['receipt_extraction_jobs'] != count or counts['employees'] != 101:
            raise RuntimeError('불완전한 합성 DB를 자동 삭제/덮어쓰기하지 않습니다: ' + target)
        return
    statements = ['START TRANSACTION;']
    for table in TABLES:
        names = columns(source, table)
        if names != columns(target, table):
            raise RuntimeError('원본과 개선 후 컬럼 불일치: ' + table)
        selected = ','.join('`' + name + '`' for name in names)
        statements.append(f'INSERT INTO `{target}`.`{table}` ({selected}) '
            f'SELECT {selected} FROM `{source}`.`{table}` ORDER BY id;')
    statements += ['COMMIT;', 'ANALYZE TABLE receipts,receipt_extraction_jobs,employees;']
    B.query('\n'.join(statements), target)


def profile(database, count, password, directory):
    consumers = B.query("SELECT NAME,ENABLED FROM performance_schema.setup_consumers "
        "WHERE NAME IN ('events_statements_history_long','events_statements_history');")
    B.query("UPDATE performance_schema.setup_consumers SET ENABLED='YES' "
        "WHERE NAME IN ('events_statements_history_long','events_statements_history');")
    try:
        max_text = int(B.query('SELECT @@performance_schema_max_sql_text_length;'))
        for case, page in [('all', 0), ('status_period', 0), ('all', 50)]:
            serial_file, plan_file = directory / f'serial-{case}-{page}.json', directory / f'explain-{case}-{page}.json'
            session, public, client = fixture(database, count, case, password, directory, page)
            try:
                if serial_file.exists() and plan_file.exists():
                    if set(read(serial_file)['sql']) != set(KINDS) or len(read(plan_file)) != 9:
                        raise RuntimeError('기존 프로파일이 기대한 5종 SQL / 9개 실행 계획과 다릅니다.')
                    continue
                if serial_file.exists() or plan_file.exists():
                    raise RuntimeError('부분 프로파일 파일을 보존합니다. 원인을 확인한 뒤 별도 보관하세요.')
                marker = int(B.query('SELECT COALESCE(MAX(TIMER_END),0) FROM performance_schema.events_statements_history_long;'))
                before = snapshot(database)
                for _ in range(100):
                    if hashlib.sha256(client.request(public['path'])).hexdigest() != public['sha256']:
                        raise RuntimeError('순차 요청의 전체 응답 불일치')
                after = snapshot(database)
                totals = delta(before, after)
                R.save(serial_file, dict(requests=100, sql=totals, before=before, after=after))
                if any(row['count'] != 100 or row['errors'] for row in totals.values()):
                    raise RuntimeError('순차 100회의 SQL 종류별 호출 수 불일치')
                raw = B.query(f"""SELECT DISTINCT JSON_OBJECT('sql',SQL_TEXT)
                    FROM performance_schema.events_statements_history_long
                    WHERE CURRENT_SCHEMA='{database}' AND TIMER_START>{marker}
                    AND LOWER(SQL_TEXT) LIKE 'select %' AND LOWER(SQL_TEXT) LIKE '% from receipts %';""")
                captured = {}
                for line in raw.splitlines():
                    sql = json.loads(line)['sql']
                    kind = classify(sql)
                    if kind not in ('ids', 'entities', 'count'):
                        raise RuntimeError('실제 영수증 SQL 분류 실패: ' + str(kind))
                    if len(sql.encode()) >= max_text - 1:
                        raise RuntimeError('SQL_TEXT가 잘렸을 수 있습니다. 수집 길이 설정을 확인하세요.')
                    if kind in captured and captured[kind] != sql:
                        raise RuntimeError('같은 종류의 실제 SQL이 여러 개입니다: ' + kind)
                    captured[kind] = sql
                if set(captured) != {'ids', 'entities', 'count'}:
                    raise RuntimeError('ID / 본문 / COUNT 실제 SQL 3종이 모두 필요합니다.')
                plans = []
                for kind in ('ids', 'entities', 'count'):
                    for repeat in range(1, 4):
                        sql = captured[kind]
                        plans.append(dict(kind=kind, sql=sql, repeat=repeat,
                            plan=B.query('EXPLAIN ANALYZE ' + sql.rstrip(';') + ';', database)))
                R.save(plan_file, plans)
                print(f'PROFILE {count} {case} page={page}: ' + str(
                    {kind: round(row['timerMs'] / 100, 3) for kind, row in totals.items()}), flush=True)
            finally:
                session.unlink(missing_ok=True)
    finally:
        for line in consumers.splitlines():
            name, value = line.split('\t')
            B.query(f"UPDATE performance_schema.setup_consumers SET ENABLED='{value}' WHERE NAME='{name}';")


def optimized(experiment, baseline, user, args):
    global ACTIVE_KINDS
    ACTIVE_KINDS = KINDS
    metadata = read(experiment / 'experiment.json')
    recorded_baseline = metadata.get('baselineExperiment')
    if not recorded_baseline or Path(recorded_baseline).resolve() != BASELINE.resolve():
        raise RuntimeError('--baseline은 기존 실험의 baselineExperiment와 같은 경로여야 합니다. '
            '다른 개선 전 결과와 비교하려면 새 실험을 준비하세요.')
    PROOF.update(read(experiment / 'comparability.json'))
    control = experiment / metadata['controlExperiment']
    metadata['beforeControls'] = collect_runs(control, metadata['beforeControls'])
    if len(metadata['beforeControls']) != 2:
        raise RuntimeError('먼저 같은 날 개선 전 비교 2회를 완료하세요.')
    previous_runs = collect_runs(experiment, metadata['runs'])
    if previous_runs and not args.resume:
        raise RuntimeError('기존 결과를 보존합니다. --resume으로 남은 조합만 실행하세요.')
    metadata['runs'] = previous_runs
    after_image = image_id(args.image)
    if after_image == metadata['beforeImageId']:
        raise RuntimeError('개선 후 이미지가 개선 전 이미지와 같습니다.')
    if metadata.get('afterImageId', after_image) != after_image:
        raise RuntimeError('재개 시 개선 후 이미지를 바꿀 수 없습니다.')
    initial = app_state()
    if '/receipt_loadtest?' not in initial['databaseUrl']:
        raise RuntimeError('시작 전 앱을 원래 receipt_loadtest DB에 연결하세요.')
    if fingerprint('receipt_loadtest') != read(experiment / 'original-fingerprint-before.json'):
        raise RuntimeError('원래 DB 데이터가 변경되었습니다.')
    current_env = environment()
    if any(current_env[key] != metadata['environment'][key] for key in
            ('dockerCpuCount', 'dockerMemoryBytes', 'innodbBufferPoolBytes', 'mysqlVersion')):
        raise RuntimeError('동일한 CPU/메모리/DB 환경에서만 비교할 수 있습니다.')
    current_hashes = source_hashes()
    for name in ('load/review-baseline.py', 'load/run-reads.py', 'load/resource_monitor.py',
                 'load/k6/review-queue.js', 'infra/loadtest/compose.yml'):
        if current_hashes[name] != metadata['scripts'][name]:
            raise RuntimeError('controls 이후 공통 측정 코드/설정 변경: ' + name)
    metadata.update(afterImageId=after_image, afterImageTag=args.image, completed=False,
        restoredOriginalDatabase=False, optimizedScripts=current_hashes, afterEnvironment=current_env)
    metadata.setdefault('resumeHistory', []).append(dict(at=dt.datetime.now(dt.timezone.utc).isoformat(),
        completedRuns=len(previous_runs), runnerSha256=sha(Path(__file__).resolve()), profileOnly=args.profile_only))
    persist(experiment, metadata)
    restore_image = initial['imageId'] if args.profile_only else after_image
    try:
        use_image(after_image)
        for count in COUNTS:
            source = baseline['datasets'][str(count)]['database']
            database = f'receipt_opt_{count}_' + experiment.name.split('-')[-1].lower()
            if not database.replace('_', '').isalnum():
                raise RuntimeError('잘못된 합성 DB 이름')
            B.configure_database(database, B.GENERATED / 'review-optimized-compose.yml')
            state = app_state()
            if state['imageId'] != after_image:
                raise RuntimeError('개선 후 실행 이미지 불일치')
            if any(state[key] != metadata['environment']['app'][key] for key in
                    ('settings', 'memoryBytes', 'nanoCpus', 'javaToolOptions')):
                raise RuntimeError('개선 전후 앱 실행 설정이 다릅니다.')
            if int(B.query("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=1;", database)) != 1:
                raise RuntimeError('V7 인덱스 마이그레이션이 적용되지 않았습니다.')
            copy_dataset(source, database, count)
            directory = experiment / str(count)
            before, after = fingerprint(source), fingerprint(database)
            if before != read(directory / 'fingerprint-before.json'):
                raise RuntimeError('개선 전 합성 데이터가 변경되었습니다.')
            R.save(directory / 'fingerprint-after.json', after)
            if before != after:
                raise RuntimeError('개선 전후 전체 필드 논리 데이터가 다릅니다.')
            PROOF['datasets'][str(count)] = dict(equal=True, method='SHA256 of ordered SHA2(JSON_ARRAY(all columns),256) rows',
                sourceDatabase=source, optimizedDatabase=database, before='fingerprint-before.json', after='fingerprint-after.json')
            dataset = dict(baseline['datasets'][str(count)], database=database, copiedFrom=source,
                copyMethod='explicit identical columns INSERT SELECT ORDER BY id; source tables unchanged',
                indexes=B.query('SHOW INDEX FROM receipts;', database),
                size=B.query(f"SELECT DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables "
                    f"WHERE table_schema='{database}' AND table_name='receipts';"))
            R.save(directory / 'dataset.json', dataset)
            metadata['datasets'][str(count)] = dataset
            persist(experiment, metadata)
            profile(database, count, user['password'], directory)
            persist(experiment, metadata)
            if args.profile_only:
                continue
            done = {(row['dataset'], row['scenario'], row['repeat']) for row in metadata['runs']}
            for repeat in range(1, 4):
                for case in (('all', 'status_period') if repeat % 2 else ('status_period', 'all')):
                    if (count, case, repeat) in done:
                        continue
                    metadata['runs'].append(B.measure(database, count, case, repeat, user['password'], experiment, 20, 60, 30))
                    persist(experiment, metadata)
        metadata['profileCompleted'] = len(PROOF['fixtures']) == 6
        metadata['completed'] = not args.profile_only and len(metadata['runs']) == 12 and metadata['profileCompleted']
    finally:
        try:
            metadata['runs'] = collect_runs(experiment, metadata['runs'])
        finally:
            restore(experiment, metadata, restore_image)


def main():
    global BASELINE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, default=BASELINE,
        help='완료된 개선 전 실험 결과 폴더 (기본값: %(default)s). 원본 결과와 당시 Docker 이미지가 필요합니다.')
    parser.add_argument('--prepare-controls', action='store_true')
    parser.add_argument('--experiment', type=Path)
    parser.add_argument('--image', help='개선 후 Docker 이미지 태그')
    parser.add_argument('--profile-only', action='store_true')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    BASELINE = args.baseline.expanduser().resolve()
    if args.prepare_controls and (args.image or args.profile_only or args.resume):
        parser.error('--prepare-controls와 --image/--profile-only/--resume을 함께 사용할 수 없습니다.')
    if not args.prepare_controls and (not args.experiment or not args.image):
        parser.error('--experiment와 --image가 필요합니다.')
    experiment = (args.experiment or ROOT / 'load/results' / ('review-optimized-' +
        dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dt%H%M%S'))).resolve()
    results = (ROOT / 'load/results').resolve()
    if experiment.parent != results or experiment == BASELINE.resolve() or not re.fullmatch(r'review-optimized-[a-zA-Z0-9]+', experiment.name):
        parser.error('load/results/review-optimized-<timestamp> 경로만 사용할 수 있습니다.')
    baseline = check_baseline()
    user = read(B.GENERATED / 'users.json')['users'][0]
    if args.prepare_controls:
        experiment.mkdir(parents=True, exist_ok=False)
        (B.GENERATED / 'review-optimized-experiment.txt').write_text(str(experiment))
        prepare_controls(experiment, baseline, user)
    else:
        optimized(experiment, baseline, user, args)


if __name__ == '__main__':
    main()
