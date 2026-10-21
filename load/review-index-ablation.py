#!/usr/bin/env python3
"""동일 인덱스에서 단일 목록 쿼리와 ID 선조회를 교대로 비교합니다. 기존 데이터는 변경하지 않습니다."""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import re

SPEC = importlib.util.spec_from_file_location('review_optimized', Path(__file__).with_name('review-optimized.py'))
O = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(O)
B, R, ROOT, COMPOSE = O.B, O.R, O.ROOT, O.COMPOSE
SOURCE = ROOT / 'load/results/review-optimized-20261019t091357'
IMAGES = {
    'index_only': 'sha256:e59058f262016f11af523489be530d8a6ded36ff5759728c5cbb580d4716a4fc',
    'id_first': 'sha256:77dab6095cad8fb48ce35877c286c10ae3b78ae16c69f51f214098b5d606e1b7',
}
KINDS = {'index_only': ('employee', 'list', 'count', 'jobs'),
         'id_first': ('employee', 'ids', 'entities', 'count', 'jobs')}
TABLES = ('employees', 'receipts', 'receipt_extraction_jobs', 'audit_events', 'idempotency_records', 'account_bootstrap_lock')
ACTIVE = None
PROOF = {'fixtures': {}, 'checks': {}, 'passed': False}


class RequestedStop(Exception):
    pass


def check_stop(path):
    if path.exists():
        raise RequestedStop('중단 파일 발견: ' + str(path))


def save(experiment, metadata):
    R.save(experiment / 'experiment.json', metadata)
    R.save(experiment / 'comparability.json', PROOF)


def schedule():
    result = []
    for count in O.COUNTS:
        for repeat in range(1, 4):
            cases = ('all', 'status_period') if repeat % 2 else ('status_period', 'all')
            for case in cases:
                order = ('index_only', 'id_first') if repeat % 2 else ('id_first', 'index_only')
                for position, variant in enumerate(order, 1):
                    result.append(dict(dataset=count, scenario=case, repeat=repeat, variant=variant,
                        pairId=f'{count}-{case}-r{repeat}', pairOrder=position))
    return result


def fixture(database, count, case, password, directory, page=0):
    session, public, client = O.ORIGINAL_FIXTURE(database, count, case, password, directory, page)
    baseline = O.read(O.BASELINE / str(count) / f'fixture-{case}-{page}.json')
    if public != baseline:
        session.unlink(missing_ok=True)
        raise RuntimeError(f'기존 전체 응답과 불일치: {ACTIVE}/{count}/{case}/{page}')
    PROOF['fixtures'][f'{ACTIVE}/{count}/{case}/{page}'] = dict(equal=True, **public)
    return session, public, client


B.fixture = fixture


def set_variant(variant):
    global ACTIVE
    ACTIVE = variant
    O.ACTIVE_KINDS = KINDS[variant]


def connect(database, variant, override):
    # DB 생성/GRANT/마이그레이션/시딩은 실행하지 않습니다. V7 검증은 사전 점검에서 수행합니다.
    O.use_image(IMAGES[variant])
    override.write_text('services:\n  app:\n    environment:\n'
        f'      DB_URL: jdbc:mysql://mysql:3306/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=utf8\n'
        f'      SESSION_REDIS_NAMESPACE: receipt:ablation:{database}:{variant}\n'
        '      SPRING_FLYWAY_ENABLED: "false"\n')
    R.command(COMPOSE + ['-f', str(override), 'up', '-d', '--no-deps', '--force-recreate', 'app'], timeout=120)
    B.wait_app()
    state = O.app_state()
    if state['imageId'] != IMAGES[variant] or f'/{database}?' not in state['databaseUrl']:
        raise RuntimeError('요청한 이미지/합성 DB에 연결되지 않았습니다.')
    set_variant(variant)
    return state


def check_dataset(database, count):
    source = O.read(SOURCE / 'experiment.json')
    expected = source.get('datasets', {}).get(str(count), {}).get('database')
    if database != expected or not re.fullmatch(rf'receipt_opt_{count}_[A-Za-z0-9]+', database):
        raise RuntimeError('선택한 --source-experiment에 기록된 receipt_opt 합성 DB에만 연결할 수 있습니다.')
    for table, expected in (('receipts', count), ('receipt_extraction_jobs', count), ('employees', 101)):
        if int(B.query(f'SELECT COUNT(*) FROM {table};', database)) != expected:
            raise RuntimeError('기존 합성 데이터 건수가 다릅니다: ' + table)
    if int(B.query("SELECT COUNT(*) FROM receipt_extraction_jobs WHERE status <> 'COMPLETED';", database)):
        raise RuntimeError('조회 실험에는 완료된 작업만 사용할 수 있습니다.')
    index = B.query("SELECT COLUMN_NAME FROM information_schema.statistics "
        f"WHERE TABLE_SCHEMA='{database}' AND TABLE_NAME='receipts' "
        "AND INDEX_NAME='ix_receipts_review_status_created' ORDER BY SEQ_IN_INDEX;")
    if index.splitlines() != ['status', 'created_at', 'id', 'owner_employee_id']:
        raise RuntimeError('양쪽에 필요한 동일 복합 인덱스가 없습니다.')
    if int(B.query("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=1;", database)) != 1:
        raise RuntimeError('기존 V7 마이그레이션 이력이 없습니다.')


def profile(database, count, password, directory):
    directory.mkdir(parents=True, exist_ok=True)
    expected = ('list', 'count') if ACTIVE == 'index_only' else ('ids', 'entities', 'count')
    consumers = B.query("SELECT NAME,ENABLED FROM performance_schema.setup_consumers "
        "WHERE NAME IN ('events_statements_history_long','events_statements_history');")
    B.query("UPDATE performance_schema.setup_consumers SET ENABLED='YES' "
        "WHERE NAME IN ('events_statements_history_long','events_statements_history');")
    try:
        max_text = int(B.query('SELECT @@performance_schema_max_sql_text_length;'))
        for case, page in [('all', 0), ('status_period', 0), ('all', 50)]:
            serial, plans = directory / f'serial-{case}-{page}.json', directory / f'explain-{case}-{page}.json'
            session, public, client = fixture(database, count, case, password, directory, page)
            try:
                if serial.exists() and plans.exists():
                    if set(O.read(serial)['sql']) != set(KINDS[ACTIVE]) or len(O.read(plans)) != 3 * len(expected):
                        raise RuntimeError('저장된 프로파일 형식이 기대와 다릅니다.')
                    continue
                if serial.exists() or plans.exists():
                    raise RuntimeError('부분 프로파일은 자동 덮어쓰지 않습니다: ' + str(directory))
                marker = int(B.query('SELECT COALESCE(MAX(TIMER_END),0) FROM performance_schema.events_statements_history_long;'))
                before = O.snapshot(database)
                for _ in range(100):
                    if hashlib.sha256(client.request(public['path'])).hexdigest() != public['sha256']:
                        raise RuntimeError('순차 요청의 전체 응답 불일치')
                after = O.snapshot(database)
                totals = O.delta(before, after)
                R.save(serial, dict(variant=ACTIVE, requests=100, sql=totals, before=before, after=after))
                if any(row['count'] != 100 or row['errors'] for row in totals.values()):
                    raise RuntimeError('순차 요청의 SQL 횟수 또는 오류 검증 실패')
                raw = B.query(f"""SELECT DISTINCT JSON_OBJECT('sql',SQL_TEXT)
                    FROM performance_schema.events_statements_history_long
                    WHERE CURRENT_SCHEMA='{database}' AND TIMER_START>{marker}
                    AND LOWER(SQL_TEXT) LIKE 'select %' AND LOWER(SQL_TEXT) LIKE '% from receipts %';""")
                captured = {}
                for line in raw.splitlines():
                    sql = json.loads(line)['sql']
                    kind = O.classify(sql)
                    if kind not in expected or len(sql.encode()) >= max_text - 1:
                        raise RuntimeError('실제 SQL 분류 실패 또는 SQL_TEXT 잘림')
                    if kind in captured and captured[kind] != sql:
                        raise RuntimeError('동일 종류 SQL이 여러 개입니다: ' + kind)
                    captured[kind] = sql
                if set(captured) != set(expected):
                    raise RuntimeError('필요한 실제 SQL을 모두 수집하지 못했습니다.')
                output = []
                for kind in expected:
                    for repeat in range(1, 4):
                        sql = captured[kind]
                        output.append(dict(kind=kind, sql=sql, repeat=repeat,
                            plan=B.query('EXPLAIN ANALYZE ' + sql.rstrip(';') + ';', database)))
                R.save(plans, output)
                print(f'PROFILE {ACTIVE}/{count}/{case}/{page}: ' + str(
                    {key: round(value['timerMs']/100, 3) for key, value in totals.items()}), flush=True)
            finally:
                session.unlink(missing_ok=True)
    finally:
        for line in consumers.splitlines():
            name, value = line.split('\t')
            B.query(f"UPDATE performance_schema.setup_consumers SET ENABLED='{value}' WHERE NAME='{name}';")


def annotate(experiment, metadata, item, row):
    result = dict(row, **{key: item[key] for key in ('variant', 'pairId', 'pairOrder')})
    directory = Path(metadata['variants'][item['variant']]['directory']) / row['runId']
    result['relativeDirectory'] = str(directory)
    if row['imageId'] != IMAGES[item['variant']] or set(row['sql']) != set(KINDS[item['variant']]):
        raise RuntimeError('측정 결과 이미지 또는 SQL 종류가 variant와 다릅니다.')
    if not row['responseIntegrityPassed'] or not all(row['checks'][key] for key in ('monitor', 'samples', 'noSqlErrors')):
        raise RuntimeError('성능 결과가 아닌 측정 무결성 실패입니다.')
    return result


def collect(experiment, metadata):
    result, keys = [], set()
    lookup = {(item['dataset'], item['scenario'], item['repeat'], item['variant']): item for item in metadata['schedule']}
    for variant, config in metadata['variants'].items():
        for path in sorted((experiment / config['directory']).glob('review-*/verification.json')):
            row = O.read(path)
            key = (row['dataset'], row['scenario'], row['repeat'], variant)
            if key not in lookup or key in keys:
                raise RuntimeError('중복/예상 외 측정 조합을 자동 선택하지 않습니다.')
            keys.add(key)
            result.append(annotate(experiment, metadata, lookup[key], row))
    result.sort(key=lambda row: row['startNs'])
    old = {row['runId']: row for row in metadata['runs']}
    for row in result:
        if row['runId'] in old and row != old[row['runId']]:
            raise RuntimeError('저장된 원시 결과가 변경되었습니다.')
    if set(old) - {row['runId'] for row in result}:
        raise RuntimeError('이전 측정 파일이 없어졌습니다.')
    # 완성된 실패 결과는 보존합니다. 결과 없이 중단된 실제 부하도 자동 재측정하지 않습니다.
    active = metadata.get('activeRun')
    if active and not any(all(row[key] == active[key] for key in ('dataset', 'scenario', 'repeat', 'variant')) for row in result):
        raise RuntimeError('결과 없이 중단된 부하가 있습니다. 자동 재측정하지 않습니다: ' + str(active))
    metadata['activeRun'] = None
    return result


def source_hashes():
    files = ('load/review-index-ablation.py', 'load/review-optimized.py', 'load/review-baseline.py',
             'load/run-reads.py', 'load/resource_monitor.py', 'load/k6/review-queue.js', 'infra/loadtest/compose.yml')
    return {name: O.sha(ROOT / name) for name in files}


def initialize(experiment):
    baseline = O.check_baseline()
    source = O.read(SOURCE / 'experiment.json')
    if not source['completed']:
        raise RuntimeError('기존 개선 후 실험이 완료되지 않았습니다.')
    env = O.environment()
    if '/receipt_loadtest?' not in env['app']['databaseUrl']:
        raise RuntimeError('시작 전 앱은 원래 receipt_loadtest DB에 연결되어 있어야 합니다.')
    for variant, image in IMAGES.items():
        if O.image_id(image) != image:
            raise RuntimeError('지정한 앱 이미지가 없습니다: ' + variant)
    experiment.mkdir(parents=True, exist_ok=False)
    R.save(experiment / 'environment-start.json', env)
    R.save(experiment / 'original-fingerprint-before.json', O.fingerprint('receipt_loadtest', TABLES))
    metadata = dict(sourceExperiment=str(SOURCE), baselineExperiment=str(O.BASELINE),
        startedAt=dt.datetime.now(dt.timezone.utc).isoformat(), gitCommit=R.command(['git', 'rev-parse', 'HEAD']),
        rate=20, seconds=60, warmup=30, repeats=3, runs=[], schedule=schedule(), datasets={}, variants={},
        originalImageId=env['app']['imageId'], environment=env, scripts=source_hashes(),
        profileCompleted=False, completed=False, restoredOriginalDatabase=False, activeRun=None,
        workload=dict(preAllocatedVUs=20, maxVUs=100, timeoutSeconds=15, drainSeconds=20),
        notes=['두 variant 모두 V7 인덱스가 이미 존재하는 같은 DB를 사용합니다.',
               '두 variant 모두 Flyway를 끄며 DB 생성/복사/시딩/삭제는 수행하지 않습니다.',
               '1·3회는 index_only → id_first, 2회는 id_first → index_only 순서입니다.',
               '매 회 앱을 다시 시작하고 30초 준비 부하를 주되 DB 캐시는 비우지 않습니다.',
               '10만 건은 합성 데이터 규모 시험이며 예상 실사용량으로 주장하지 않습니다.'])
    for variant, image in IMAGES.items():
        directory = experiment.name + '-' + variant
        (experiment / directory).mkdir()
        metadata['variants'][variant] = dict(imageId=image, experimentId=directory, directory=directory)
    for count in O.COUNTS:
        database = source['datasets'][str(count)]['database']
        check_dataset(database, count)
        directory = experiment / str(count)
        directory.mkdir()
        actual = O.fingerprint(database, TABLES)
        previous = O.read(SOURCE / str(count) / 'fingerprint-after.json')
        if {name: actual[name] for name in previous} != previous:
            raise RuntimeError('기존 개선 후 데이터가 달라졌습니다.')
        R.save(directory / 'fingerprint-before.json', actual)
        metadata['datasets'][str(count)] = dict(source['datasets'][str(count)],
            indexes=B.query('SHOW INDEX FROM receipts;', database))
        PROOF['checks'][f'{count}_matches_previous_data'] = True
        PROOF['checks'][f'{count}_same_v7_index'] = True
    PROOF['checks']['k6_matches_baseline'] = metadata['scripts']['load/k6/review-queue.js'] == baseline['scripts']['load/k6/review-queue.js']
    save(experiment, metadata)
    return metadata


def restore(experiment, metadata):
    # 원본 DB에 대한 SQL 변경 없이 시작 때의 이미지와 Compose 기본 연결만 복원합니다.
    O.use_image(metadata['originalImageId'])
    R.command(COMPOSE + ['up', '-d', '--no-deps', '--force-recreate', 'app'], timeout=120)
    B.wait_app()
    state = O.app_state()
    original = O.fingerprint('receipt_loadtest', TABLES)
    R.save(experiment / 'original-fingerprint-after.json', original)
    PROOF['checks']['original_data_unchanged'] = original == O.read(experiment / 'original-fingerprint-before.json')
    for count, dataset in metadata['datasets'].items():
        after = O.fingerprint(dataset['database'], TABLES)
        R.save(experiment / count / 'fingerprint-after.json', after)
        PROOF['checks'][count + '_data_unchanged'] = after == O.read(experiment / count / 'fingerprint-before.json')
    metadata['restoredOriginalDatabase'] = (state['imageId'] == metadata['originalImageId']
        and '/receipt_loadtest?' in state['databaseUrl'] and PROOF['checks']['original_data_unchanged'])
    PROOF['checks']['original_app_restored'] = metadata['restoredOriginalDatabase']
    PROOF['passed'] = bool(PROOF['checks']) and all(PROOF['checks'].values()) and metadata['profileCompleted']
    metadata['completed'] = (len(metadata['runs']) == len(metadata['schedule'])
        and metadata['profileCompleted'] and PROOF['passed'])
    R.save(experiment / 'environment-end.json', O.environment())
    save(experiment, metadata)
    if not all(PROOF['checks'].values()):
        raise RuntimeError('앱 복원/데이터 보존 검증 실패. comparability.json을 확인하세요.')
    print('RESTORED original app/database; results: ' + str(experiment), flush=True)


def run(experiment, resume, stop_file):
    if resume:
        metadata = O.read(experiment / 'experiment.json')
        for field, current, option in (('baselineExperiment', O.BASELINE, '--baseline'),
                                       ('sourceExperiment', SOURCE, '--source-experiment')):
            recorded = metadata.get(field)
            if not recorded or Path(recorded).resolve() != current.resolve():
                raise RuntimeError(option + '는 기존 실험의 ' + field + '와 같은 경로여야 합니다. '
                    '다른 입력으로 비교하려면 새 실험을 시작하세요.')
        recorded_images = {variant: details.get('imageId')
            for variant, details in metadata.get('variants', {}).items()}
        if recorded_images != IMAGES:
            raise RuntimeError('--index-only-image/--id-first-image가 기존 실험의 이미지 ID와 다릅니다. '
                '재개할 때는 같은 이미지를 지정하세요.')
        PROOF.update(O.read(experiment / 'comparability.json'))
        if metadata['scripts'] != source_hashes():
            raise RuntimeError('측정 도구가 변경되어 같은 실험을 재개할 수 없습니다.')
        if metadata['completed']:
            raise RuntimeError('이미 완료된 실험은 다시 실행하지 않습니다.')
        metadata['runs'] = collect(experiment, metadata)
        current = O.environment()
        if current['app'] != metadata['environment']['app']:
            raise RuntimeError('재개 전 앱이 시작 때의 원래 환경으로 복원되어 있어야 합니다.')
        if O.fingerprint('receipt_loadtest', TABLES) != O.read(experiment / 'original-fingerprint-before.json'):
            raise RuntimeError('원본 데이터가 변경되어 재개할 수 없습니다.')
    else:
        metadata = initialize(experiment)
    user = O.read(B.GENERATED / 'users.json')['users'][0]
    override = B.GENERATED / (experiment.name + '-compose.yml')
    metadata.update(restoredOriginalDatabase=False, activeRun=None, stopped=False, stopFile=str(stop_file))
    save(experiment, metadata)
    try:
        env = O.environment()
        for key in ('dockerCpuCount', 'dockerMemoryBytes', 'innodbBufferPoolBytes', 'mysqlVersion'):
            if env[key] != metadata['environment'][key]:
                raise RuntimeError('실험 환경이 변경되었습니다: ' + key)
        for count, dataset in metadata['datasets'].items():
            check_dataset(dataset['database'], int(count))
            if O.fingerprint(dataset['database'], TABLES) != O.read(experiment / count / 'fingerprint-before.json'):
                raise RuntimeError('합성 데이터가 변경되었습니다.')
        done = {(row['dataset'], row['scenario'], row['repeat'], row['variant']) for row in metadata['runs']}
        for item in metadata['schedule']:
            check_stop(stop_file)
            key = (item['dataset'], item['scenario'], item['repeat'], item['variant'])
            if key in done:
                continue
            database = metadata['datasets'][str(item['dataset'])]['database']
            state = connect(database, item['variant'], override)
            for field in ('settings', 'javaToolOptions', 'memoryBytes', 'nanoCpus'):
                if state[field] != metadata['environment']['app'][field]:
                    raise RuntimeError('앱 자원 설정이 다릅니다: ' + field)
            check_stop(stop_file)
            metadata['activeRun'] = item
            save(experiment, metadata)
            directory = experiment / metadata['variants'][item['variant']]['directory']
            row = B.measure(database, item['dataset'], item['scenario'], item['repeat'],
                user['password'], directory, 20, 60, 30)
            metadata['runs'].append(annotate(experiment, metadata, item, row))
            metadata['activeRun'] = None
            save(experiment, metadata)
        # SQL 단독 시간과 실행 계획은 부하 24회 이후 수집하여 첫 반복의 캐시를 미리 데우지 않습니다.
        for count in O.COUNTS:
            database = metadata['datasets'][str(count)]['database']
            for variant in IMAGES:
                check_stop(stop_file)
                connect(database, variant, override)
                profile(database, count, user['password'], experiment / str(count) / variant)
                save(experiment, metadata)
        metadata['profileCompleted'] = len(PROOF['fixtures']) == 12
        PROOF['checks']['all_response_fixtures_match'] = metadata['profileCompleted'] and all(
            row['equal'] for row in PROOF['fixtures'].values())
    except RequestedStop as exception:
        metadata.update(stopped=True, stopReason=str(exception))
        print(str(exception) + '; 새 측정을 시작하지 않고 원래 앱을 복원합니다.', flush=True)
    except KeyboardInterrupt:
        metadata.update(stopped=True, stopReason='KeyboardInterrupt; interrupted workload is not automatically rerun')
        raise
    finally:
        try:
            metadata['runs'] = collect(experiment, metadata)
        finally:
            restore(experiment, metadata)


def main():
    global SOURCE, IMAGES
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, default=O.BASELINE,
        help='완료된 개선 전 실험 결과 폴더 (기본값: %(default)s)')
    parser.add_argument('--source-experiment', type=Path, default=SOURCE,
        help='동일 인덱스와 합성 DB를 준비한 개선 후 실험 폴더 (기본값: %(default)s)')
    parser.add_argument('--index-only-image', default=IMAGES['index_only'],
        help='인덱스만 추가한 단일 목록 조회 이미지의 로컬 태그 또는 ID')
    parser.add_argument('--id-first-image', default=IMAGES['id_first'],
        help='같은 인덱스와 ID 선조회를 사용하는 이미지의 로컬 태그 또는 ID')
    parser.add_argument('--experiment', type=Path)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--stop-file', type=Path, help='존재하면 다음 run/프로파일 시작 전 중단 (기본값: 실험폴더/STOP)')
    args = parser.parse_args()
    O.BASELINE = args.baseline.expanduser().resolve()
    SOURCE = args.source_experiment.expanduser().resolve()
    if args.resume and not args.experiment:
        parser.error('--resume에는 --experiment가 필요합니다.')
    experiment = (args.experiment or ROOT / 'load/results' / ('review-index-ablation-' +
        dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dt%H%M%S'))).resolve()
    if experiment.parent != (ROOT / 'load/results').resolve() or not re.fullmatch(r'review-index-ablation-[a-zA-Z0-9]+', experiment.name):
        parser.error('load/results/review-index-ablation-<timestamp> 경로만 사용할 수 있습니다.')
    # 결과 파일과 이미지가 없는 새 환경에서는 DB/앱을 변경하기 전에 준비사항을 안내합니다.
    O.check_baseline()
    source = O.read(SOURCE / 'experiment.json')
    if not source.get('completed'):
        parser.error('--source-experiment는 완료된 개선 후 실험 결과여야 합니다.')
    IMAGES = {'index_only': O.image_id(args.index_only_image), 'id_first': O.image_id(args.id_first_image)}
    if IMAGES['index_only'] == IMAGES['id_first']:
        parser.error('두 조회 방식은 서로 다른 Docker 이미지로 준비하세요.')
    print('EXPERIMENT ' + str(experiment), flush=True)
    run(experiment, args.resume, (args.stop_file or experiment / 'STOP').resolve())


if __name__ == '__main__':
    main()
