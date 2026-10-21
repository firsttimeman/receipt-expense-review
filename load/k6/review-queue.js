import http from 'k6/http';
import exec from 'k6/execution';
import {check} from 'k6';
import {sha256} from 'k6/crypto';
import {Counter, Rate, Trend} from 'k6/metrics';

const fixture = JSON.parse(open(__ENV.REVIEW_FIXTURE));
if (fixture.baseUrl !== 'http://127.0.0.1:19880') throw new Error('로컬 검수 목록 테스트만 허용합니다.');
const requests = new Counter('receipt_review_requests');
const failures = new Counter('receipt_review_failures');
const bytes = new Counter('receipt_review_response_bytes');
const valid = new Rate('receipt_review_valid');
const latency = new Trend('receipt_review_ms', true);
export const options = {
    noCookiesReset: true,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
    tags: {dataset: __ENV.DATASET, case_name: __ENV.CASE_NAME},
    scenarios: {review: {executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 20),
        timeUnit: '1s', duration: __ENV.DURATION || '60s', preAllocatedVUs: 20,
        maxVUs: 100, gracefulStop: '20s'}},
    thresholds: {receipt_review_valid: ['rate==1'], receipt_review_requests: ['count>0'],
        dropped_iterations: ['count==0']}
};
export default function () {
    exec.vu.metrics.tags.load_vu = String(exec.vu.idInTest);
    http.cookieJar().set(fixture.baseUrl, 'JSESSIONID', fixture.cookie);
    const response = http.get(fixture.baseUrl + fixture.path, {
        tags: {name: 'receipt_review_queue'}, timeout: '15s', responseType: 'binary'});
    const matches = response.status === 200 && sha256(response.body || '', 'hex') === fixture.sha256;
    check(response, {'HTTP 200 및 검증한 목록 전체 응답 일치': () => matches});
    requests.add(1);
    failures.add(matches ? 0 : 1);
    bytes.add(response.body ? response.body.byteLength : 0);
    valid.add(matches);
    latency.add(response.timings.duration);
}
export function handleSummary(data) {
    return {[__ENV.SUMMARY_FILE]: JSON.stringify(data, null, 2)};
}
