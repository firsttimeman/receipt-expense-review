#!/usr/bin/env python3
"""독립 합성 DB 1만/10만 건의 검수 목록 개선 전 측정. 원래 DB를 보존하고 종료 시 앱 연결을 복원합니다."""
import argparse
from collections import Counter
import datetime as dt
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import time
import urllib.error
import uuid

SPEC = importlib.util.spec_from_file_location('read_tools', Path(__file__).with_name('run-reads.py'))
R = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(R)
ROOT, COMPOSE = R.ROOT, R.COMPOSE
GENERATED = ROOT / 'load/generated'
BASE_TIME = dt.datetime(2025, 10, 1)
PENDING = ['NEEDS_REVIEW', 'MANUAL_ENTRY', 'NEEDS_RECAPTURE', 'UNREADABLE']
CASES = {'all': '/api/receipts/review-queue?page=0&size=20',
         'status_period': '/api/receipts/review-queue?page=0&size=20&status=NEEDS_REVIEW&from=2026-09-01&to=2026-09-30'}


def query(sql, database='receipt_loadtest'):
    if not database.replace('_', '').isalnum():
        raise ValueError('Invalid database')
    return R.command(COMPOSE + ['exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root --batch --raw --skip-column-names -D ' + database],
        input=sql, timeout=300)


def wait_app():
    for _ in range(90):
        try:
            R.Client().request('/api/auth/csrf')
            return
        except (OSError, urllib.error.HTTPError):
            time.sleep(1)
    raise RuntimeError('앱 준비 실패')


def configure_database(database, override):
    query(f"CREATE DATABASE IF NOT EXISTS {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
          f"GRANT ALL ON {database}.* TO 'receipt_loadtest'@'%';")
    override.write_text('services:\n  app:\n    environment:\n'
        f'      DB_URL: jdbc:mysql://mysql:3306/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=utf8\n'
        f'      SESSION_REDIS_NAMESPACE: receipt:review:{database}\n')
    R.command(COMPOSE + ['-f', str(override), 'up', '-d', '--no-deps', '--force-recreate', 'app'], timeout=120)
    wait_app()


def seed(database, count, source_employee):
    if int(query('SELECT COUNT(*) FROM receipts;', database)):
        raise RuntimeError('새 합성 DB에만 데이터를 생성합니다.')
    query("INSERT INTO employees(id,login_id,password_hash,name,role,active) "
          f"SELECT 1,'review-baseline',password_hash,'Synthetic reviewer','REVIEWER',true "
          f"FROM receipt_loadtest.employees WHERE id={int(source_employee)};", database)
    values = ','.join(f"({i},'review-owner-{i}','Synthetic owner {i}','EMPLOYEE',true)" for i in range(2, 102))
    query('INSERT INTO employees(id,login_id,name,role,active) VALUES ' + values + ';', database)
    data = dict(shopName='Synthetic Shop', date='2026-09-01', totalAmount=36000,
        businessRegistrationNumber='1234567890', paymentMethod='CARD',
        lineItems=[dict(name=f'Item {i}', quantity=1, unitPrice=i * 1000, amount=i * 1000) for i in range(1, 9)])
    rules = [dict(code='SYNTHETIC_CHECK', outcome='PASS', message='Synthetic fixture')]
    data_sql = json.dumps(data, ensure_ascii=True).replace("'", "''")
    rules_sql = json.dumps(rules).replace("'", "''")
    digits = ' UNION ALL '.join(f'SELECT {i} AS n' for i in range(10))
    numbers = f'SELECT 1+a.n+10*b.n+100*c.n+1000*d.n+10000*e.n n FROM ({digits}) a CROSS JOIN ({digits}) b CROSS JOIN ({digits}) c CROSS JOIN ({digits}) d CROSS JOIN ({digits}) e'
    created = "TIMESTAMPADD(SECOND,MOD(n,86400),TIMESTAMPADD(DAY,MOD(n*97,365),'2025-10-01 00:00:00'))"
    sql = f"""INSERT INTO receipts(id,version,company_id,image_sha256,original_file_name,content_type,file_size,
       original_data,current_data,status,rule_results,created_at,updated_at,owner_employee_id)
       SELECT n,0,'internal',SHA2(CONCAT('review-baseline-',n),256),CONCAT('synthetic-',n,'.jpg'),'image/jpeg',200000,
       CAST('{data_sql}' AS JSON),CAST('{data_sql}' AS JSON),
       CASE MOD(n,20) WHEN 0 THEN 'NEEDS_REVIEW' WHEN 1 THEN 'MANUAL_ENTRY'
          WHEN 2 THEN 'NEEDS_RECAPTURE' WHEN 3 THEN 'UNREADABLE' ELSE 'AUTO_APPROVED' END,
       CAST('{rules_sql}' AS JSON),{created},{created},
       IF(MOD(n,997)=0,NULL,1+MOD(FLOOR(n/100),101)) FROM ({numbers}) s WHERE n <= {count} ORDER BY n;
       INSERT INTO receipt_extraction_jobs(receipt_id,version,status,image_storage_key,duplicate_detected,attempt_count,
           available_at,started_at,completed_at,created_at,updated_at)
       SELECT id,0,'COMPLETED',CONCAT('synthetic-only/',id,'.jpg'),false,1,created_at,created_at,
          TIMESTAMPADD(SECOND,2,created_at),created_at,TIMESTAMPADD(SECOND,2,created_at) FROM receipts;
       ANALYZE TABLE receipts,receipt_extraction_jobs,employees;"""
    query(sql, database)
    return dict(count=count, companyCount=1, ownerAccounts=101, nullableOwnerEvery=997,
        pendingFraction=0.2, dates='2025-10-01 through 2026-09-30 UTC', lineItems=8,
        dataJsonBytes=len(data_sql), jobStatus='COMPLETED', images='metadata only; no S3 objects or AI calls')


def expected(count, case, page=0):
    rows = []
    start, end = dt.datetime(2026,8,31,15), dt.datetime(2026,9,30,15)
    for n in range(1, count+1):
        owner = None if n % 997 == 0 else 1 + (n // 100) % 101
        created = BASE_TIME + dt.timedelta(days=n*97 % 365, seconds=n % 86400)
        if owner in (None, 1) or n % 20 >= 4:
            continue
        if case == 'status_period' and not (n % 20 == 0 and start <= created < end):
            continue
        rows.append((created,n))
    rows.sort(reverse=True)
    return dict(ids=[n for _,n in rows[page*20:(page+1)*20]], total=len(rows), page=page)


def fixture(database, count, case, password, directory, page=0):
    client = R.Client()
    cookie = client.login(dict(loginId='review-baseline',password=password))
    path = CASES[case].replace('page=0',f'page={page}')
    raw = client.request(path)
    actual = json.loads(raw)
    wanted = expected(count,case,page)
    if ([r['id'] for r in actual['content']] != wanted['ids'] or actual['totalElements'] != wanted['total']
        or actual['totalPages'] != math.ceil(wanted['total']/20) or actual['page'] != page
        or actual['hasNext'] != ((page+1)*20 < wanted['total'])
        or any(r['ownerEmployeeId'] in (None,1) or r['jobStatus'] != 'COMPLETED' for r in actual['content'])):
        raise RuntimeError('독립 생성기와 API 응답 불일치')
    public = dict(path=path,expected=wanted,sha256=hashlib.sha256(raw).hexdigest(),responseBytes=len(raw))
    R.save(directory / f'fixture-{case}-{page}.json',public)
    target = GENERATED / ('review-session-' + uuid.uuid4().hex + '.json')
    fd = os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:
        json.dump(dict(baseUrl=R.BASE,cookie=cookie,**public),f)
    return target,public,client


def snapshot(database):
    raw=query(f"""SELECT JSON_OBJECT('digest',DIGEST,'sql',DIGEST_TEXT,'count',COUNT_STAR,
       'timerPs',SUM_TIMER_WAIT,'rowsExamined',SUM_ROWS_EXAMINED,'rowsSent',SUM_ROWS_SENT,'errors',SUM_ERRORS)
       FROM performance_schema.events_statements_summary_by_digest
       WHERE SCHEMA_NAME='{database}' AND DIGEST_TEXT LIKE 'SELECT%';""")
    result={}
    for line in raw.splitlines():
        row=json.loads(line); sql=row['sql']; kind=None
        if 'FROM `employees`' in sql and '`login_id` = ?' in sql: kind='employee'
        elif 'FROM `receipts`' in sql: kind='count' if 'COUNT (' in sql.upper() or 'COUNT(' in sql.upper() else 'list'
        elif 'FROM `receipt_extraction_jobs`' in sql and '`receipt_id` IN' in sql: kind='jobs'
        if kind:
            row['kind']=kind;result[row['digest']]=row
    return result


def delta(before,after):
    result={k:dict(count=0,timerMs=0,rowsExamined=0,rowsSent=0,errors=0) for k in ('employee','list','count','jobs')}
    for key,row in after.items():
        previous=before.get(key,{})
        target=result[row['kind']]
        for field in ('count','rowsExamined','rowsSent','errors'):target[field]+=row[field]-previous.get(field,0)
        target['timerMs']+=(row['timerPs']-previous.get('timerPs',0))/1e9
    return result


def profile(database,count,password,directory):
    # 테스트 스키마의 영수증 SELECT만 저장하며 인증/세션 값은 저장하지 않습니다.
    consumers=query("SELECT NAME,ENABLED FROM performance_schema.setup_consumers WHERE NAME IN ('events_statements_history_long','events_statements_history');")
    query("UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME IN ('events_statements_history_long','events_statements_history');")
    try:
        for case,page in [('all',0),('status_period',0),('all',50)]:
            session,public,client=fixture(database,count,case,password,directory,page)
            try:
                marker=int(query('SELECT COALESCE(MAX(TIMER_END),0) FROM performance_schema.events_statements_history_long;'))
                before=snapshot(database)
                for _ in range(100):
                    raw=client.request(public['path'])
                    if hashlib.sha256(raw).hexdigest()!=public['sha256']:raise RuntimeError('순차 응답 불일치')
                after=snapshot(database)
                sql=delta(before,after)
                R.save(directory/f'serial-{case}-{page}.json',dict(requests=100,sql=sql,before=before,after=after))
                if any(row['count']!=100 or row['errors'] for row in sql.values()):
                    raise RuntimeError('순차 요청의 SQL 종류별 횟수 불일치')
                # SQL_TEXT는 실제 앱이 보낸 전체 SELECT입니다. literal을 보존해 같은 쿼리에 EXPLAIN합니다.
                raw=query(f"""SELECT DISTINCT SQL_TEXT FROM performance_schema.events_statements_history_long
                    WHERE CURRENT_SCHEMA='{database}' AND SQL_TEXT LIKE 'select %'
                    AND TIMER_START>{marker}
                    AND SQL_TEXT LIKE '% from receipts %' AND SQL_TEXT LIKE '%owner_employee_id%' LIMIT 10;""")
                plan=[]
                if len(raw.splitlines()) != 2:
                    raise RuntimeError('목록과 COUNT 실제 SQL 2개가 필요합니다: '+str(len(raw.splitlines())))
                for sql_text in raw.splitlines():
                    if not sql_text.endswith('20') and 'count(' not in sql_text.lower():
                        # 전체 텍스트가 잘렸으면 실행하지 않습니다.
                        if len(sql_text)>=1024: raise RuntimeError('SQL_TEXT truncated; need larger capture size')
                    for repeat in range(3):
                        plan.append(dict(sql=sql_text,repeat=repeat+1,plan=query('EXPLAIN ANALYZE '+sql_text+';',database)))
                R.save(directory/f'explain-{case}-{page}.json',plan)
                print(f'PROFILE {count} {case} page={page}: '+str({k:round(v['timerMs']/100,3) for k,v in sql.items()}),flush=True)
            finally: session.unlink(missing_ok=True)
    finally:
        for line in consumers.splitlines():
            name,value=line.split('\t')
            query(f"UPDATE performance_schema.setup_consumers SET ENABLED='{value}' WHERE NAME='{name}';")


def k6(session,target,run_id,case,count,rate,seconds,record):
    env=dict(os.environ,REVIEW_FIXTURE=str(session),RATE=str(rate),DURATION=f'{seconds}s',
        SUMMARY_FILE=str(target/'summary.json'),DATASET=str(count),CASE_NAME=case)
    args=['k6','run','--quiet','--no-usage-report','--tag',f'run_id={run_id}']
    if record:args+=['--out',f'json={target/"metrics.json"}','--out',f'influxdb={R.INFLUX}/receipt_loadtest']
    args+=[str(ROOT/'load/k6/review-queue.js')]
    with (target/'console.log').open('w') as log:
        return subprocess.run(args,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=seconds+90).returncode


def measure(database,count,case,repeat,password,experiment,rate,seconds,warmup):
    # 앱을 매 실행 재시작하고 준비 부하를 주어 JVM 실행 조건을 맞춥니다. DB 캐시는 비우지 않습니다.
    R.command(COMPOSE+['restart','app'],timeout=120);wait_app()
    run_id=f'review-{count}-{case}-r{repeat}-'+uuid.uuid4().hex[:6]
    target=experiment/run_id;target.mkdir();warm=target/'warmup';warm.mkdir()
    session,public,_=fixture(database,count,case,password,target)
    try:
        print(f'{run_id}: warmup {warmup}s, measurement {seconds}s at {rate} RPS',flush=True)
        warmup_code=k6(session,warm,run_id+'-warmup',case,count,rate,warmup,False)
        warm_metrics=json.loads((warm/'summary.json').read_text())['metrics']
        # 도착 요청 누락은 측정할 과부하 현상입니다. 응답 검증 실패/실행 오류와 구별합니다.
        if warmup_code not in (0,99):raise RuntimeError('준비 부하 실행 오류')
        container=json.loads(R.command(['docker','inspect','receipt-loadtest-app-1']))[0]
        containers={f'receipt-loadtest-{s}-1':s for s in ('app','mysql','redis','s3mock')}
        ip=container['NetworkSettings']['Networks']['receipt-loadtest_default']['IPAddress']
        errors=[];monitor=R.ResourceMonitor(run_id,target,containers,ip,query,R.INFLUX,errors)
        before=snapshot(database);monitor.start();start=time.time_ns()
        try:
            code=k6(session,target,run_id,case,count,rate,seconds,True)
            end=time.time_ns();after=snapshot(database)
        finally:monitor.stop()
        R.save(target/'sql-before.json',before);R.save(target/'sql-after.json',after)
        metrics=json.loads((target/'summary.json').read_text())['metrics']
        requests=metrics['receipt_review_requests']['values']['count']
        failures=metrics['receipt_review_failures']['values']['count']
        dropped=metrics.get('dropped_iterations',{}).get('values',{}).get('count',0)
        latency=metrics['receipt_review_ms']['values'];sql=delta(before,after)
        raw_count=0;statuses=Counter()
        for line in (target/'metrics.json').open():
            point=json.loads(line)
            if point.get('type')!='Point':continue
            if point.get('metric')=='receipt_review_ms':raw_count+=1
            if point.get('metric')=='http_req_duration':statuses[point['data']['tags']['status']]+=1
        # 정상 응답의 내용 불일치는 검증 오류, timeout/5xx는 그대로 남길 성능 결과입니다.
        response_integrity=(sum(statuses.values())==requests and statuses.get('200',0)==requests-failures
            and all(s in ('200','0') or s.startswith('5') for s in statuses))
        series=R.influx_query(f'SELECT count("value") FROM "receipt_review_ms" WHERE "run_id"=\'{run_id}\'')
        stored=series[0]['values'][0][1] if series else 0
        checks=dict(k6=code==0,noFailures=failures==0,noDropped=dropped==0,monitor=not errors,
            samples=requests==raw_count==stored,noSqlErrors=all(v['errors']==0 for v in sql.values()),
            sqlCounts=all(abs(v['count']-requests)<=max(1,math.ceil(requests*.001)) for v in sql.values()))
        resources=R.summarize_resources(target,start,end)
        result=dict(runId=run_id,dataset=count,scenario=case,repeat=repeat,rate=rate,seconds=seconds,warmup=warmup,
            startNs=start,endNs=end,requests=requests,failures=failures,dropped=dropped,latency=latency,sql=sql,
            resources=resources,responseBytesPerRequest=metrics['receipt_review_response_bytes']['values']['count']/requests,
            checks=checks,errors=errors,passed=all(checks.values()),imageId=container['Image'],
            k6ExitCode=code,warmupDropped=warm_metrics.get('dropped_iterations',{}).get('values',{}).get('count',0),
            responseStatusCounts=dict(statuses),responseIntegrityPassed=response_integrity)
        tags=dict(experiment_id=experiment.name,run_id=run_id,dataset=count,scenario=case,repeat=repeat)
        R.send('receipt_review_summary',tags,dict(p95_ms=latency['p(95)'],p99_ms=latency['p(99)'],mean_ms=latency['avg'],
            requests=requests,failures=failures,dropped=dropped,response_bytes_per_request=result['responseBytesPerRequest'],
            mysql_cpu_mean=resources['mysql']['cpuMeanPct'],app_cpu_mean=resources['app']['cpuMeanPct'],
            app_memory_peak_mib=resources['app']['memoryPeakMiB'],db_connections_max=resources['db']['connectionsMax'],passed=int(result['passed'])))
        for kind,row in sql.items():
            R.send('receipt_review_sql',dict(**tags,kind=kind),dict(calls=row['count'],total_ms=row['timerMs'],
                ms_per_call=row['timerMs']/max(row['count'],1),ms_per_request=row['timerMs']/requests,
                rows_examined_per_call=row['rowsExamined']/max(row['count'],1),rows_sent_per_call=row['rowsSent']/max(row['count'],1)))
        R.save(target/'verification.json',result)
        print(f'FINISHED {run_id}: p95={latency["p(95)"]:.3f} ms passed={result["passed"]}',flush=True)
        if code not in (0,99) or not response_integrity or not all(checks[k] for k in ('monitor','samples','noSqlErrors')):
            raise RuntimeError('측정 응답 또는 수집 오류: '+str(target))
        return result
    finally:session.unlink(missing_ok=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rate',type=int,default=20)
    parser.add_argument('--seconds',type=int,default=60)
    parser.add_argument('--warmup',type=int,default=30)
    parser.add_argument('--repeats',type=int,default=3)
    parser.add_argument('--profile-only',action='store_true')
    parser.add_argument('--experiment',type=Path,help='이미 사전 검증한 합성 DB를 그대로 사용합니다.')
    parser.add_argument('--resume',action='store_true',help='기존 결과를 보존하고 아직 측정하지 않은 조합만 실행합니다.')
    args=parser.parse_args()
    if not (1<=args.rate<=100 and args.seconds>=5 and args.warmup>=5 and 1<=args.repeats<=3):
        parser.error('rate 1~100, seconds/warmup >=5, repeats 1~3')
    experiment=args.experiment or ROOT/'load/results'/('review-baseline-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dt%H%M%S'))
    experiment.mkdir(parents=True,exist_ok=bool(args.experiment))
    (GENERATED/'review-baseline-experiment.txt').write_text(str(experiment))
    user=json.loads((GENERATED/'users.json').read_text())['users'][0]
    override=GENERATED/'review-baseline-compose.yml'
    original_count=query('SELECT COUNT(*) FROM receipts;')
    previous=json.loads((experiment/'experiment.json').read_text()) if args.experiment else None
    if args.resume and not previous:parser.error('--resume에는 기존 --experiment가 필요합니다.')
    if previous and previous.get('runs') and not args.resume:raise RuntimeError('기존 부하 결과를 덮어쓰지 않습니다. --resume을 사용하세요.')
    metadata=dict(gitCommit=R.command(['git','rev-parse','HEAD']),rate=args.rate,seconds=args.seconds,
        warmup=args.warmup,repeats=args.repeats,originalReceiptCount=int(original_count),datasets={},runs=[],
        kind='baseline only; backend queries/indexes unchanged; 30s warmup, DB cache not cleared, full residency not guaranteed',
        dockerCpuCount=json.loads(R.command(['docker','info','--format','{{json .}}'])).get('NCPU'),
        scripts={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in
                 [Path(__file__).resolve(),ROOT/'load/k6/review-queue.js']})
    if args.resume:
        metadata=previous
        if (metadata['rate'],metadata['seconds'],metadata['warmup'],metadata['repeats']) != (args.rate,args.seconds,args.warmup,args.repeats):
            raise RuntimeError('기존 측정과 같은 부하 조건으로만 재개할 수 있습니다.')
        if metadata['originalReceiptCount'] != int(original_count):raise RuntimeError('원래 DB 건수가 달라졌습니다.')
        known={r['runId'] for r in metadata['runs']}
        for path in sorted(experiment.glob('review-*/verification.json')):
            result=json.loads(path.read_text())
            if result['runId'] not in known:
                metadata['runs'].append(result);known.add(result['runId'])
        metadata['runs'].sort(key=lambda r:r['startNs'])
        keys=[(r['dataset'],r['scenario'],r['repeat']) for r in metadata['runs']]
        if len(set(keys))!=len(keys):raise RuntimeError('중복 측정 조합이 있어 자동 재개할 수 없습니다.')
        metadata['completed']=False
        metadata['restoredOriginalDatabase']=False
        metadata['kind']='baseline only; backend queries/indexes unchanged; 30s warmup, DB cache not cleared, full residency not guaranteed'
        metadata['resumeReason']='Keep the first overloaded 100k run; continue remaining runs without changing load or application settings.'
        metadata['resumedRunnerSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        metadata.setdefault('resumeHistory',[]).append(dict(at=dt.datetime.now(dt.timezone.utc).isoformat(),
            completedRunCount=len(metadata['runs']),runnerSha256=metadata['resumedRunnerSha256'],
            reason='Preserve overloaded runs including timeouts; continue remaining combinations with unchanged workload.'))
    metadata['innodbBufferPoolBytes']=int(query('SELECT @@innodb_buffer_pool_size;'))
    metadata['mysqlVersion']=query('SELECT @@version;')
    R.save(experiment/'experiment.json',metadata)
    try:
        for count in (10000,100000):
            done={(r['dataset'],r['scenario'],r['repeat']) for r in metadata['runs']}
            if not args.profile_only and all((count,case,repeat) in done for case in CASES for repeat in range(1,args.repeats+1)):
                continue
            database=f'receipt_review_{count}_'+experiment.name.split('-')[-1].lower()
            configure_database(database,override)
            seeded=previous['datasets'][str(count)] if previous else seed(database,count,user['employeeId'])
            if int(query('SELECT COUNT(*) FROM receipts;',database)) != count:
                raise RuntimeError('데이터 건수 불일치')
            seeded.update(database=database,distribution=query('SELECT status,COUNT(*) FROM receipts GROUP BY status;',database),
                indexes=query('SHOW INDEX FROM receipts;',database),
                size=query(f"SELECT DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables WHERE table_schema='{database}' AND table_name='receipts';"))
            directory=experiment/str(count);directory.mkdir(exist_ok=bool(previous))
            R.save(directory/'dataset.json',seeded);metadata['datasets'][str(count)]=seeded
            R.save(experiment/'experiment.json',metadata)
            print(f'SEEDED {count}: {database}',flush=True)
            if not previous:profile(database,count,user['password'],directory)
            if not args.profile_only:
                for repeat in range(1,args.repeats+1):
                    for case in (('all','status_period') if repeat%2 else ('status_period','all')):
                        if (count,case,repeat) in done:continue
                        metadata['runs'].append(measure(database,count,case,repeat,user['password'],experiment,args.rate,args.seconds,args.warmup))
                        R.save(experiment/'experiment.json',metadata)
        metadata['completed']=True
    finally:
        R.command(COMPOSE+['up','-d','--no-deps','--force-recreate','app'],timeout=120);wait_app()
        metadata['originalReceiptCountAfter']=int(query('SELECT COUNT(*) FROM receipts;'))
        metadata['restoredOriginalDatabase']=metadata['originalReceiptCountAfter']==metadata['originalReceiptCount']
        R.save(experiment/'experiment.json',metadata)
        print('RESTORED original app database. Results: '+str(experiment),flush=True)


if __name__=='__main__':main()
