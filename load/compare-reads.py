#!/usr/bin/env python3
"""고정한 개선 전후 이미지로 교차 반복 측정하고 Grafana 비교 지표를 기록합니다."""
import argparse
import importlib.util
import json
import statistics
from pathlib import Path

spec = importlib.util.spec_from_file_location('reads', Path(__file__).with_name('run-reads.py'))
reads = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reads)


def summarize(experiment, run_ids):
    runs = [json.loads((experiment / run_id / 'verification.json').read_text()) for run_id in run_ids]
    for run in runs:
        run['originalPassed'] = run['passed']
        assessment = reads.assess_sql(run['sql'],run['requests'],run['phase'])
        non_sql_checks = {k:v for k,v in run['checks'].items() if k not in ('employeeSql','receiptSql','jobSql','sqlWithinTolerance')}
        run['sqlAssessment'] = assessment
        run['passed'] = all(non_sql_checks.values()) and all(r['withinTolerance'] for r in assessment.values())
        reads.save(experiment/run['runId']/'evaluation-v2.json',
                   dict(originalPassed=run['originalPassed'],measurementValid=run['passed'],sqlAssessment=assessment,
                        note='원본 verification.json 변경 없음. 동시 Performance Schema 집계는 0.1% 이상 차이를 탐지하며 정확한 횟수 검증은 별도 순차 요청으로 수행.'))
    if len(runs) != 6 or not all(run['passed'] for run in runs):
        raise RuntimeError('전후 각 3회의 성공한 측정이 필요합니다.')
    for run in runs:
        reads.send('receipt_read_selected',dict(experiment_id=experiment.name,run_id=run['runId'],
                   phase=run['phase'],label=run['label']),dict(p95_ms=run['latency']['p(95)'],
                   failures=run['failed'],dropped=run['dropped'],
                   sql_count_difference=sum(a['difference'] for a in run['sqlAssessment'].values())),
                   timestamp=run['endNs'])
    groups = {}
    for phase in ('before', 'after'):
        selected = [run for run in runs if run['phase'] == phase]
        if len(selected) != 3:
            raise RuntimeError('전후 측정 횟수가 다릅니다.')
        total = sum(run['requests'] for run in selected)
        proof = json.loads((experiment/f'serial-proof-{phase}.json').read_text())
        if not proof['passed']:
            raise RuntimeError('순차 SQL 횟수 검증이 필요합니다.')
        p95s = [run['latency']['p(95)'] for run in selected]
        fields = dict(
            p95_median_ms=statistics.median(p95s), p95_min_ms=min(p95s), p95_max_ms=max(p95s),
            p99_median_ms=statistics.median(run['latency']['p(99)'] for run in selected),
            employee_sql_per_request=sum(run['sql']['employee']['count'] for run in selected) / total,
            detail_sql_per_request=sum(sum(s['count'] for s in run['sql'].values()) for run in selected) / total,
            mysql_cpu_median=statistics.median(run['resources']['mysql']['cpuMeanPct'] for run in selected),
            app_cpu_median=statistics.median(run['resources']['app']['cpuMeanPct'] for run in selected),
            app_memory_peak_mib=max(run['resources']['app']['memoryPeakMiB'] for run in selected),
            db_connections_max=max(run['resources']['db']['connectionsMax'] for run in selected),
            db_query_active_max=max(run['resources']['db']['queryActiveMax'] for run in selected),
            employee_sql_ms_per_request=sum(run['sql']['employee']['timerMs'] for run in selected) / total,
            detail_sql_ms_per_request=sum(sum(s['timerMs'] for s in run['sql'].values()) for run in selected) / total,
            requests=total, failures=sum(run['failed'] for run in selected),
            dropped=sum(run['dropped'] for run in selected),
            response_bytes_per_request=sum(run['responseBytes'] for run in selected) / total)
        fields['serial_employee_sql_per_request'] = proof['sql']['employee']['count'] / proof['requests']
        fields['serial_detail_sql_per_request'] = sum(s['count'] for s in proof['sql'].values()) / proof['requests']
        groups[phase] = fields
        reads.send('receipt_read_comparison', dict(experiment_id=experiment.name, phase=phase), fields)
    changes = {field: (1 - groups['after'][field] / groups['before'][field]) * 100
               for field in ('p95_median_ms', 'employee_sql_per_request', 'detail_sql_per_request',
                             'mysql_cpu_median', 'app_cpu_median', 'detail_sql_ms_per_request')}
    report = dict(experimentId=experiment.name, order=run_ids, groups=groups, reductionPercent=changes,
                  runs=runs, interpretation='3회 각각의 p95 중앙값. 전체 표본을 합친 p95가 아님. '
                  '동일 로컬 호스트의 단기 관측이며 최대 처리량이나 운영 성능을 증명하지 않음.')
    anomalies = experiment/'measurement-anomalies.json'
    report['measurementNotes'] = json.loads(anomalies.read_text()) if anomalies.exists() else []
    reads.save(experiment / 'comparison.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--experiment', type=Path)
    parser.add_argument('--rate', type=int, default=100)
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--warmup', type=int, default=30)
    parser.add_argument('--resume', action='store_true', help='검증을 통과해 저장한 실행 다음부터 계속합니다.')
    args = parser.parse_args()
    if not 1 <= args.rate <= 200 or args.seconds < 5 or args.warmup < 5:
        parser.error('1~200 RPS, 5초 이상의 시간을 지정하세요.')
    experiment = args.experiment or Path((reads.ROOT/'load/generated/employee-read-experiment.txt').read_text().strip())
    metadata = json.loads((experiment/'experiment.json').read_text())
    override = experiment/'compose-override.json'
    order = [('before',1),('after',1),('after',2),('before',2),('before',3),('after',3)]
    progress = experiment/'comparison-progress.json'
    run_ids = json.loads(progress.read_text())['order'] if args.resume else []
    for index, run_id in enumerate(run_ids):
        prior = json.loads((experiment/run_id/'verification.json').read_text())
        expected_phase, expected_repeat = order[index]
        if (not prior['passed'] or prior['phase'] != expected_phase
                or prior['label'] != f'compare-r{expected_repeat}'
                or prior['rate'] != args.rate or prior['duration'] != args.seconds):
            raise RuntimeError('이어갈 측정의 조건이 다릅니다.')
    for phase, repeat in order[len(run_ids):]:
        reads.save(override, dict(services=dict(app=dict(image=metadata[phase+'Image']))))
        print(f'비교 {repeat}/3 {phase}: 앱 재시작 후 같은 준비 부하 적용', flush=True)
        reads.command(reads.COMPOSE + ['--file', str(override), 'up', '-d', '--no-deps',
                      '--force-recreate', '--wait', '--wait-timeout', '180', 'app'], timeout=240)
        result = reads.run(experiment, phase, args.rate, args.seconds, args.warmup, f'compare-r{repeat}')
        run_ids.append(result['runId'])
        reads.save(experiment/'comparison-progress.json', dict(order=run_ids, complete=False))
    # 동시 부하 측정이 모두 끝난 뒤 별도로 순차 SQL 횟수를 확인합니다.
    for phase in ('before','after'):
        reads.save(override, dict(services=dict(app=dict(image=metadata[phase+'Image']))))
        reads.command(reads.COMPOSE + ['--file', str(override), 'up', '-d', '--no-deps',
                      '--force-recreate', '--wait', '--wait-timeout', '180', 'app'], timeout=240)
        reads.serial_probe(experiment,phase)
    report = summarize(experiment, run_ids)
    reads.save(experiment/'comparison-progress.json', dict(order=run_ids, complete=True))
    print(json.dumps(dict(groups=report['groups'], reductionPercent=report['reductionPercent']),
                     ensure_ascii=False, indent=2), flush=True)


if __name__ == '__main__':
    main()
