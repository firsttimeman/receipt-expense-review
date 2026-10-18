#!/usr/bin/env python3
"""Grafana API를 통해 대시보드의 실제 질의와 저장된 표본 수를 확인합니다."""
import json
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
experiment = Path((ROOT/'load/generated/employee-read-experiment.txt').read_text().strip())
comparison = json.loads((experiment/'comparison.json').read_text())
run = comparison['runs'][-1]
base = 'http://127.0.0.1:13000'


def fetch(url):
    with urllib.request.urlopen(url,timeout=15) as response:
        return json.load(response)


dashboard = fetch(base+'/api/dashboards/uid/receipt-employee-reads')['dashboard']
checks = []
for panel in dashboard['panels']:
    for target in panel.get('targets',[]):
        query = target['query'].replace('$experiment_id',experiment.name).replace('$run_id',run['runId'])
        query = query.replace('$timeFilter',f'time >= {run["startNs"]-5_000_000_000} AND time <= {run["endNs"]+5_000_000_000}')
        data = fetch(base+'/api/datasources/proxy/uid/receipt-loadtest-influx/query?'+
                     urllib.parse.urlencode(dict(db='receipt_loadtest',q=query)))['results'][0]
        if 'error' in data:
            raise RuntimeError(str(data))
        series = data.get('series',[])
        non_null = sum(sum(v is not None for v in row[1:]) for s in series for row in s['values'])
        if not non_null:
            raise RuntimeError('표시할 데이터가 없습니다: '+panel['title'])
        if panel['id']==7 and len(series)!=6:
            raise RuntimeError('원래 계획의 6회가 표시되지 않습니다.')
        if panel['id']==10 and sum(row[1] or 0 for s in series for row in s['values']) != run['requests']:
            raise RuntimeError('그래프의 지연 표본 수가 실제 요청 수와 다릅니다.')
        checks.append(dict(panel=panel['id'],title=panel['title'],query=query,series=len(series),
                           nonNullValues=non_null,passed=True))
result = dict(dashboardUid=dashboard['uid'],panelCount=len(dashboard['panels']),checks=checks,passed=True)
(experiment/'grafana-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(dict(passed=True,panels=len(checks),url=run['grafanaUrl']),ensure_ascii=False))
