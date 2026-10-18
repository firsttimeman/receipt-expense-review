import http from 'k6/http';
import exec from 'k6/execution';
import {check} from 'k6';
import {sha256} from 'k6/crypto';
import {Counter, Rate, Trend} from 'k6/metrics';

// 이 파일에는 세션이 있어 실행기가 0600 임시 파일로 만들고 실행 후 제거합니다.
const fixture = JSON.parse(open(__ENV.READ_FIXTURE));
const rate = Number(__ENV.RATE || 10);
const duration = __ENV.DURATION || '60s';
if (fixture.baseUrl !== 'http://127.0.0.1:19880' || !fixture.users.length) {
    throw new Error('격리된 로컬 조회 테스트 계정이 필요합니다.');
}

const requests = new Counter('receipt_read_requests');
const successful = new Counter('receipt_read_successful');
const failures = new Counter('receipt_read_failures');
const valid = new Rate('receipt_read_valid');
const latency = new Trend('receipt_read_ms', true);
const responseBytes = new Counter('receipt_read_response_bytes');

export const options = {
    noCookiesReset: true,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
    scenarios: {
        detail: {executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
            preAllocatedVUs: 50, maxVUs: 200, gracefulStop: '15s'}
    },
    thresholds: {
        receipt_read_valid: ['rate==1'],
        receipt_read_requests: ['count>0'],
        dropped_iterations: ['count==0']
    }
};

export default function () {
    exec.vu.metrics.tags.load_vu = String(exec.vu.idInTest);
    const iteration = exec.scenario.iterationInTest;
    const user = fixture.users[iteration % fixture.users.length];
    const receipt = user.receipts[Math.floor(iteration / fixture.users.length) % user.receipts.length];
    http.cookieJar().set(fixture.baseUrl, 'JSESSIONID', user.cookie);
    const response = http.get(`${fixture.baseUrl}/api/receipts/${receipt.id}`, {
        tags: {name: 'receipt_detail_read'}, timeout: '10s', responseType: 'binary'
    });
    requests.add(1);
    latency.add(response.timings.duration);
    // 응답 전체 해시로 API 의미가 바뀌지 않았음을 확인합니다.
    const matches = response.status === 200 && sha256(response.body || '', 'hex') === receipt.sha256;
    check(response, {'HTTP 200 및 기존 상세 응답 유지': () => matches});
    valid.add(matches);
    successful.add(matches ? 1 : 0);
    failures.add(matches ? 0 : 1);
    responseBytes.add(response.body ? response.body.byteLength : 0);
}

export function handleSummary(data) {
    const report = {
        configuration: {rate, duration, accounts: fixture.users.length,
            receiptCount: fixture.users.reduce((n, user) => n + user.receipts.length, 0),
            phase: __ENV.READ_PHASE, runId: __ENV.RUN_ID,
            metric: 'HTTP GET duration; login excluded; completed receipt body unchanged'},
        metrics: data.metrics, options: data.options, state: data.state, root_group: data.root_group
    };
    return {[__ENV.SUMMARY_FILE]: JSON.stringify(report, null, 2)};
}
