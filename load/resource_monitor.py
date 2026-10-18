"""로컬 부하테스트의 Docker 자원과 MySQL 연결을 관측합니다."""
import json
import re
import subprocess
import threading
import time
import urllib.request


def memory_bytes(value):
    match = re.fullmatch(r'([0-9.]+)(B|kB|MB|GB|KiB|MiB|GiB|TiB)', value.strip())
    if not match:
        raise ValueError('알 수 없는 Docker 메모리 단위')
    scale = {'B': 1, 'kB': 1000, 'MB': 1000**2, 'GB': 1000**3,
             'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3, 'TiB': 1024**4}
    return float(match[1]) * scale[match[2]]


class ResourceMonitor:
    def __init__(self, run_id, result_dir, containers, app_ip, query, influx_url, errors):
        self.run_id = run_id
        self.result_dir = result_dir
        self.containers = containers
        self.app_ip = app_ip
        self.query = query
        self.influx_url = influx_url
        self.errors = errors
        self.stop_event = threading.Event()
        self.process = None
        self.threads = []
        self.counts = {'containers': 0, 'db': 0}

    def send(self, measurement, fields, timestamp, service=None):
        if not self.influx_url:
            return
        tags = f'run_id={self.run_id}' + (f',service={service}' if service else '')
        values = ','.join(f'{key}={float(value)}' for key, value in fields.items())
        line = f'{measurement},{tags} {values} {timestamp}'
        request = urllib.request.Request(self.influx_url + '/write?db=receipt_loadtest',
                                         data=line.encode(), method='POST')
        with urllib.request.urlopen(request, timeout=5) as response:
            if response.status != 204:
                raise RuntimeError('자원 지표 기록 실패')

    def start(self):
        self.process = subprocess.Popen(
            ['docker', 'stats', '--no-trunc', '--format', '{{json .}}', *self.containers],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.threads = [threading.Thread(target=self.watch_containers), threading.Thread(target=self.watch_db)]
        for thread in self.threads:
            thread.start()

    def watch_containers(self):
        try:
            last_sample = {}
            with (self.result_dir / 'resources.jsonl').open('w') as output:
                for line in self.process.stdout:
                    if self.stop_event.is_set():
                        break
                    line = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', line).strip()
                    if not line:
                        continue
                    raw = json.loads(line)
                    service = self.containers[raw['Name']]
                    observed = time.monotonic()
                    if observed - last_sample.get(service, 0) < 2:
                        continue
                    last_sample[service] = observed
                    used, limit = raw['MemUsage'].split(' / ')
                    fields = dict(cpu_percent=float(raw['CPUPerc'].rstrip('%')),
                                  memory_bytes=memory_bytes(used), memory_limit_bytes=memory_bytes(limit),
                                  pids=int(raw['PIDs']))
                    now = time.time_ns()
                    output.write(json.dumps(dict(timestamp_ns=now, service=service, **fields)) + '\n')
                    output.flush()
                    self.counts['containers'] += 1
                    self.send('receipt_resources', fields, now, service)
            if not self.stop_event.is_set():
                raise RuntimeError('Docker stats가 예기치 않게 종료됨')
        except Exception as error:
            self.errors.append('ContainerResources:' + type(error).__name__)

    def watch_db(self):
        # 앱 컨테이너 주소의 연결만 집계합니다. 이 관측 연결과 큐 관측 연결은 제외됩니다.
        if not re.fullmatch(r'[0-9.]+', self.app_ip):
            self.errors.append('DatabaseResources:InvalidAddress')
            return
        sql = f"""SELECT COUNT(*), COALESCE(SUM(COMMAND <> 'Sleep'),0),
                   COALESCE(SUM(COMMAND = 'Sleep'),0)
                   FROM information_schema.PROCESSLIST
                   WHERE SUBSTRING_INDEX(HOST,':',1)='{self.app_ip}' AND ID<>CONNECTION_ID();
                   SHOW GLOBAL STATUS WHERE Variable_name IN
                     ('Connection_errors_max_connections','Threads_connected','Threads_running');
                   SELECT @@max_connections;"""
        with (self.result_dir / 'db-connections.jsonl').open('w') as output:
            while not self.stop_event.is_set():
                try:
                    rows = self.query(sql).splitlines()
                    total, active, sleeping = map(int, rows[0].split('\t'))
                    status = dict(row.split('\t') for row in rows[1:-1])
                    fields = dict(app_connections=total, app_query_active=active, app_sleeping=sleeping,
                                  server_connected=int(status['Threads_connected']),
                                  server_running=int(status['Threads_running']),
                                  connection_limit_errors=int(status['Connection_errors_max_connections']),
                                  max_connections=int(rows[-1]))
                    now = time.time_ns()
                    output.write(json.dumps(dict(timestamp_ns=now, **fields)) + '\n')
                    output.flush()
                    self.counts['db'] += 1
                    self.send('receipt_db_connections', fields, now)
                except Exception as error:
                    self.errors.append('DatabaseResources:' + type(error).__name__)
                self.stop_event.wait(2)

    def stop(self):
        self.stop_event.set()
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        for thread in self.threads:
            thread.join(timeout=30)
            if thread.is_alive():
                self.errors.append('Resources:ThreadStopTimeout')
        if not all(self.counts.values()):
            self.errors.append('Resources:MissingSamples')
        return self.counts
