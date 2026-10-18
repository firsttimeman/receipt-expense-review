#!/usr/bin/env python3
"""저장된 측정값에서 Notion에 올릴 읽기 쉬운 결과 본문을 생성합니다."""
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
experiment = Path((ROOT/'load/generated/employee-read-experiment.txt').read_text().strip())
report = json.loads((experiment/'comparison.json').read_text())
meta = json.loads((experiment/'experiment.json').read_text())
before, after = report['groups']['before'], report['groups']['after']
runs = report['runs']
last = runs[-1]
grafana = last['grafanaUrl']
anomalies = report.get('measurementNotes', [])


def table(rows):
    return '<table header-row="true">\n' + '\n'.join(
        '<tr>' + ''.join('<td>' + html.escape(str(cell)) + '</td>' for cell in row) + '</tr>'
        for row in rows) + '\n</table>'


def number(x, decimals=2):
    return f'{x:,.{decimals}f}'


def group_range(phase, service, field):
    values = [r['resources'][service][field] for r in runs if r['phase'] == phase]
    return f'{number(min(values))}~{number(max(values))}'


summary = table([
    ['지표', '개선 전', '개선 후', '읽는 방법'],
    ['직원 SELECT / 요청 (순차 검증)', before['serial_employee_sql_per_request'], after['serial_employee_sql_per_request'], '각 버전 100건 순차 요청: 직원 SELECT 200건 → 100건. 50% 감소'],
    ['관련 SELECT / 요청 (순차 검증)', before['serial_detail_sql_per_request'], after['serial_detail_sql_per_request'], '직원+영수증+작업 조회 합계. 4회 → 3회, 25% 감소'],
    ['직원 SELECT / 요청 (동시 부하 집계)', number(before['employee_sql_per_request'],6), number(after['employee_sql_per_request'],6), 'Performance Schema 근삿값. 원본 집계 오차를 보정하지 않음'],
    ['p95 (3회 중앙값)', number(before['p95_median_ms'])+' ms', number(after['p95_median_ms'])+' ms', '각 1분 실행에서 요청 95%가 이 시간 안에 응답. 세 실행의 p95 중 중앙값'],
    ['p95 범위 (3회)', f"{number(before['p95_min_ms'])}~{number(before['p95_max_ms'])} ms", f"{number(after['p95_min_ms'])}~{number(after['p95_max_ms'])} ms", '실행마다 달라진 정도도 함께 확인'],
    ['p99 (3회 중앙값)', number(before['p99_median_ms'])+' ms', number(after['p99_median_ms'])+' ms', '각 실행에서 요청 99%가 완료된 시간의 중앙값'],
    ['측정 요청 합계', int(before['requests']), int(after['requests']), '준비 부하와 로그인은 제외'],
    ['실패 / 누락된 실행', f"{int(before['failures'])} / {int(before['dropped'])}", f"{int(after['failures'])} / {int(after['dropped'])}", 'HTTP 오류·응답 불일치 / k6 dropped_iterations'],
])

resources = table([
    ['지표', '개선 전', '개선 후', '기준'],
    ['MySQL CPU', number(before['mysql_cpu_median'])+'%', number(after['mysql_cpu_median'])+'%', '실행별 평균의 중앙값. 100%는 논리 CPU 1개 수준'],
    ['MySQL CPU 실행별 평균 범위', group_range('before','mysql','cpuMeanPct')+'%', group_range('after','mysql','cpuMeanPct')+'%', '3회 변동 범위'],
    ['앱 CPU', number(before['app_cpu_median'])+'%', number(after['app_cpu_median'])+'%', '실행별 평균의 중앙값'],
    ['앱 CPU 실행별 평균 범위', group_range('before','app','cpuMeanPct')+'%', group_range('after','app','cpuMeanPct')+'%', '3회 변동 범위'],
    ['앱 메모리 최대', number(before['app_memory_peak_mib'])+' MiB', number(after['app_memory_peak_mib'])+' MiB', '3회 관측 최댓값; JVM GC 시점의 영향 포함'],
    ['DB 연결 최대', int(before['db_connections_max']), int(after['db_connections_max']), '앱의 열린 연결 수. 모두 동시에 SQL 실행 중이라는 뜻은 아님'],
    ['DB 실행 중 연결 최대', int(before['db_query_active_max']), int(after['db_query_active_max']), '약 2초 간격으로 관측한 최대치'],
    ['관련 SQL 시간 / 요청', number(before['detail_sql_ms_per_request'],4)+' ms', number(after['detail_sql_ms_per_request'],4)+' ms', 'MySQL performance_schema의 해당 SQL elapsed time 합계 / HTTP 건수. CPU 시간이 아님'],
])

execution_rows = [['순서', '버전 / 반복', '요청 수', 'p95', 'p99', '직원 SELECT', '관련 SELECT 합계', '검증']]
for i,r in enumerate(runs,1):
    execution_rows.append([i,('개선 전' if r['phase']=='before' else '개선 후')+' / '+r['label'].replace('compare-r',''),
        r['requests'],number(r['latency']['p(95)'])+' ms',number(r['latency']['p(99)'])+' ms',
        r['sql']['employee']['count'],sum(s['count'] for s in r['sql'].values()),'HTTP 정상 / SQL 집계 오차' if any(a['difference'] for a in r['sqlAssessment'].values()) else '정상'])

reduction = report['reductionPercent']['p95_median_ms']
latency_statement = (f"이번 로컬 측정에서 p95 중앙값은 {number(before['p95_median_ms'])} → {number(after['p95_median_ms'])} ms로 {number(reduction,1)}% 낮았습니다."
    if reduction > 0 else f"이번 로컬 측정에서 p95 중앙값은 {number(before['p95_median_ms'])} → {number(after['p95_median_ms'])} ms였습니다. 응답 시간이 개선됐다고 주장할 수 없습니다.")
anomaly_note = ''
if anomalies:
    anomaly_note = '''## 측정 도중 발견한 집계 한계와 처리
처음에는 동시 부하의 SQL 통계를 정확한 카운터로 검사해 2회 실행이 중단됐습니다. 하지만 두 실행 모두 HTTP·응답 해시·지연 표본 검증은 정상이고 SQL 실행 횟수 집계만 반환 행 수와 달랐습니다. 이 한계를 확인한 뒤 **원래 계획의 두 실행을 p95 비교에 다시 포함**했습니다. 더 좋은 응답 시간을 골라 사용하지 않았습니다.
''' + table([['실행','비교 포함','HTTP 실패','p95','확인 내용']] + [[a['runId'],'포함' if a['included'] else '진단용 추가 실행',a['responseFailures'],str(a['latencyP95Ms'])+' ms',a['reason']] for a in anomalies]) + '''
MySQL은 Performance Schema가 수집 시 mutex를 사용하지 않아 통계의 일관성을 보장하지 않는다고 설명합니다. 이번 COUNT_STAR와 반환 행 수의 차이는 집계 경합 가능성을 시사하지만, 근본 원인을 확정한 것은 아닙니다. [MySQL 8.4 공식 문서](https://dev.mysql.com/doc/refman/8.4/en/performance-schema-restrictions.html)
정확한 SQL 횟수는 전후 각각 100건의 순차 요청으로 별도 확인했습니다. 동시 부하의 SQL 원본 수치는 보정하지 않고 그대로 표시하며, 실행기의 0.1% 허용 오차는 큰 이상을 잡기 위한 기준일 뿐 통계적 신뢰구간이 아닙니다. 원래 `verification.json`은 보존하고 평가를 `evaluation-v2.json`에 별도 기록했습니다.
준비 조건을 확인하다 앱 재시작이 누락된 `read-20261016t093624-13a827`은 중도 중단하고 비교에서 제외했습니다. 최종 표의 6회는 모두 앱 재시작 및 30초 준비 부하 조건을 맞췄습니다.
'''
content = f'''<mention-date start="2026-10-16"/> · 로컬 Docker · 개선 전후 각 3회

## 무엇이 문제였나
영수증 상세 화면은 처리 완료 여부를 확인하려고 같은 상세 API를 반복 호출합니다. 기존에는 한 요청에서 **인증 필터가 직원 정보를 조회하고, 서비스가 같은 직원을 다시 조회**했습니다. 인증 검사는 필요하지만 같은 요청에서 동일 정보를 두 번 읽는 비용이 있었습니다.

이번에는 이 중복 조회만 수정했습니다. **순차 SQL 검증에서 직원 SELECT는 요청당 2회에서 1회로, 상세 요청 관련 SELECT 합계는 4회에서 3회로 줄었습니다.** {latency_statement}

## 코드가 어떻게 달라졌나
```text
개선 전: 요청 → 필터의 직원 SELECT → 서비스의 직원 SELECT → 영수증 SELECT → 작업 SELECT → 응답
개선 후: 요청 → 필터의 직원 SELECT → 요청 속성에서 재사용 → 영수증 SELECT → 작업 SELECT → 같은 응답
```
1. 필터는 매 요청마다 DB에서 직원을 읽고 활성 상태·직원 ID·세션 버전을 검증합니다. 역할도 DB 값으로 갱신합니다.
2. 검증한 Employee를 HttpServletRequest의 내부 속성에 담습니다. 클라이언트가 보낸 헤더·파라미터를 사용하는 방식이 아닙니다.
3. CurrentEmployeeService는 그 값을 재사용하고 principal 유효성을 다시 검사합니다. HTTP 요청 밖에서 직접 호출하면 기존처럼 DB를 조회합니다.
4. 필터는 정상 응답과 예외 모두 finally에서 요청 속성을 지웁니다. 다음 요청에서는 다시 DB를 읽습니다. Redis 세션이나 전역 캐시에 Employee를 보관하지 않습니다.

변경 위치: `EmployeeSessionValidationFilter.java:41`, `EmployeeRequestContext.java:15`, `CurrentEmployeeService.java:28`.
역할·소유권 정책, 상세 응답 필드, 프론트의 2초 조회 간격, Worker 설정은 그대로입니다. 계정 변경은 다음 요청의 DB 검사에서 확인합니다. 요청 도중 다른 트랜잭션에서 변경된 계정 정보를 다시 조회하는 구조는 아니며, 요청 전체에 원자적인 권한 검사를 보장한다는 뜻도 아닙니다.

## 어떤 조건으로 비교했나
{table([
['항목','조건'],
['요청','로그인된 GET /api/receipts/{id} 상세 조회만 측정'],
['부하','초당 100건 × 60초 × 버전별 3회'],
['실행 순서','개선 전 1 → 개선 후 1 → 개선 후 2 → 개선 전 2 → 개선 전 3 → 개선 후 3'],
['준비','매 실행 앱 컨테이너를 다시 시작하고 30초 같은 속도로 준비 부하. 로그인과 준비 부하는 집계 제외'],
['데이터','기존 테스트 계정 10개, 계정별 완료 영수증 5개, 총 50개를 전후 동일하게 반복 조회'],
['인증','각 계정은 로그인 후 발급된 Redis 세션 쿠키 사용. 관리자 권한으로 우회하지 않음'],
['추출 설정','Fake 지연 2초, Worker 동시 처리 4개, batch 4 유지. 조회만 하므로 측정 중 추출 작업은 없음'],
['실행 환경',f"같은 Mac의 k6와 Docker Desktop. VM CPU {meta['dockerResources']['NCPU']}개, 메모리 {number(meta['dockerResources']['MemTotal']/1024**3)} GiB. 앱 제한 1 GiB. MySQL 8.4 / Redis 7.2"],
['비교 고정','전후 Docker 이미지 ID, 소스 해시, 측정 스크립트 해시 저장. DB 초기화·데이터 삭제 없음'],
])}

사전 점검은 개선 전 코드로 10·50·100 RPS를 각각 1분 수행했고 모두 통과했습니다. 그중 가장 높은 점검 구간인 100 RPS를 반복 비교에 사용했습니다. 최대 처리량을 찾은 실험은 아닙니다.
실제 화면의 업로드부터 완료까지를 재현하는 대신, 동일한 상세 API의 비용을 분리해서 측정했습니다. 10개 계정의 세션을 공유하는 합성 부하이며, 실제 사용자 100명 또는 200명을 검증했다는 의미는 아닙니다. 완료 데이터는 측정 중 내용이 바뀌지 않게 하기 위한 선택입니다.

## 결과를 숫자로 보기
{summary}
{latency_statement} 이 수치는 개별 실행의 p95 3개 중 중앙값이며, 전후 전체 요청을 합쳐 계산한 p95는 아닙니다. 전후 범위와 아래 개별 실행도 함께 봅니다.

{resources}
MySQL CPU 평균의 중앙값은 {number(before['mysql_cpu_median'])}% → {number(after['mysql_cpu_median'])}%로 낮았지만, 앱 CPU는 {number(before['app_cpu_median'])}% → {number(after['app_cpu_median'])}%, 앱 메모리 최대는 {number(before['app_memory_peak_mib'])} → {number(after['app_memory_peak_mib'])} MiB로 오히려 조금 높았습니다. DB 연결 수는 동일했습니다. 이 측정으로 앱 CPU나 메모리를 개선했다고 주장하지 않습니다.

## 6회 실행을 하나씩 보기
{table(execution_rows)}
constant-arrival-rate의 경계 시점에 따라 1분 요청 수는 6,000 또는 6,001건이 될 수 있습니다. SQL 횟수는 실제 요청 수로 나눠 비교합니다.

{anomaly_note}

## 결과를 어떻게 검증했나
- MySQL performance_schema의 정규화된 SELECT 실행 횟수를 측정 직전·직후에 읽어 차이를 계산했습니다. 로그인과 준비 부하는 제외했습니다.
- 직원·영수증·추출 작업 조회를 구분했습니다. Worker의 대기 작업 조회나 관측 SQL은 관련 SELECT 합계에 포함하지 않았습니다. 따라서 서버 전체 SQL이 25% 줄었다는 표현은 사용하지 않습니다.
- 모든 측정 응답의 HTTP 200과 전체 응답 바이트 SHA-256을 기존 데이터와 비교했습니다. 응답 데이터를 빼거나 소유권 검사를 생략해 얻은 결과가 아닙니다.
- k6 요약 요청 수, 원시 응답 시간 표본 수, InfluxDB 저장 표본 수를 대조했습니다. HTTP 실패·누락 0건을 확인했습니다. SQL은 순차 횟수 검증과 동시 부하 근사 집계를 분리했습니다.
- 회귀 테스트 {meta['regressionTests']}개 통과: 요청 내 재사용/정리/사용자 분리 8개, 직원 권한 20개, 실제 Redis 분산 세션 8개, 상세 조회 일관성 2개, 목록·업무 흐름 11개. 비활성화·세션 폐기·역할 변경·타인 영수증 접근을 확인했습니다.

## Grafana에서 보는 순서
[직원 조회 재사용 전후 비교 대시보드]({grafana})
1. 위쪽 비교 그래프에서 before/after의 직원 SELECT, 관련 SELECT, p95 중앙값을 비교합니다.
2. 실행별 p95 그래프에서 3회 모두 비슷한 경향인지 확인합니다.
3. 상세 실행 run_id를 선택하고 해당 실행 시간으로 이동하면 10초 구간 p95/p99, 요청 수, CPU·메모리·DB 연결 그래프를 확인할 수 있습니다. 각 실행 링크는 아래에 있습니다.
Grafana의 10초 구간 p95는 그 10초 안의 표본으로 계산하므로, 60초 전체 p95와 값이 다를 수 있습니다. localhost 링크는 이 Mac에서 모니터링 컨테이너가 켜져 있어야 열립니다.

{chr(10).join(f'- [{r["phase"]} {r["label"]} · {r["runId"]}]({r["grafanaUrl"]})' for r in runs)}

## 이력서에는 어떻게 설명할까
> 영수증 상세 반복 조회에서 인증 필터와 서비스의 중복 직원 조회를 확인하고, 검증한 직원 정보를 요청 범위에서 재사용하도록 개선했다. 전후 각 100건의 순차 검증에서 직원 SELECT를 요청당 2회→1회(50%), 상세 요청 관련 SELECT를 4회→3회(25%) 줄인 것을 확인했다. 동일한 100 RPS 부하를 각 3회 비교하고 권한·분산 세션 회귀 테스트 49개를 통과했다.

응답 시간은 위의 로컬 조건과 실제 수치를 함께 제시합니다. 단기 로컬 측정이므로 운영 성능, 최대 처리량, 장시간 안정성, 추출 완료 시간의 개선으로 확대해서 쓰지 않습니다. SQL 감소는 명확하지만 지연·CPU는 실행 시점과 JVM 상태에 따라 변동할 수 있습니다.

## 원본 증거와 재현
저장 폴더: `{experiment}`
- `comparison.json`: 전후 집계와 원래 계획의 6회 실행 결과
- `serial-proof-before.json`, `serial-proof-after.json`: 전후 각 100건 순차 요청의 SQL 횟수 검증
- `evaluation-policy.json`, `measurement-anomalies.json`: 통계 집계의 한계, 평가 방법 변경, 진단·중단 실행 기록
- 각 run 폴더: `summary.json`, `verification.json`, `sql-before.json`, `sql-after.json`, `metrics.json`, 자원 관측 JSONL
- `experiment.json`: 이미지 ID, 코드/측정 스크립트 해시, 환경
- `employee-reuse.patch`: 전후 코드 차이
- `regression-tests.json`, `regression-tests.log`: 테스트 결과
- `load/k6/receipt-reads.js`, `load/run-reads.py`, `load/compare-reads.py`: 측정 코드

```bash
# 로컬 테스트 환경과 고정한 전후 이미지가 준비된 상태에서 실행
python3 load/compare-reads.py --rate 100 --seconds 60 --warmup 30
```
로그인 쿠키는 권한 0600 임시 파일로만 전달하고 실행 후 제거했습니다. 공개 결과에는 비밀번호·세션 쿠키·실제 영수증 이미지를 넣지 않았습니다.
'''
lines = []
in_code = False
for line in content.splitlines():
    if line.startswith('```'):
        in_code = not in_code
    if not in_code and line.startswith('- ') and lines and lines[-1] and not lines[-1].startswith('- '):
        lines.append('')
    lines.append(line)
    if not in_code and line.startswith('## '):
        lines.append('')
(experiment/'notion-page.txt').write_text('\n'.join(lines)+'\n')
print(str(experiment/'notion-page.txt'))
