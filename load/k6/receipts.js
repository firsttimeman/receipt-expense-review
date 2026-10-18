import http from 'k6/http';
import exec from 'k6/execution';
import {check, fail, sleep} from 'k6';
import {Counter, Rate, Trend} from 'k6/metrics';
import {uniquePng} from './png.mjs';

const base = (__ENV.BASE_URL || 'http://127.0.0.1:19880').replace(/\/$/, '');
const fixture = JSON.parse(open('../generated/users.json'));
const imageMetadata = JSON.parse(open('../generated/image.json'));
const image = open('../generated/receipt.png', 'b');
const runId = __ENV.RUN_ID;
const rate = Number(__ENV.RATE || 1);
const timeUnit = __ENV.TIME_UNIT || '1s';
const duration = __ENV.DURATION || '60s';
const completionTimeout = Number(__ENV.COMPLETION_TIMEOUT_SECONDS || 60);
const pollInterval = Number(__ENV.POLL_SECONDS || 1);
const preVus = Number(__ENV.PRE_VUS || 8);
const maxVus = Number(__ENV.MAX_VUS || 64);
if (!runId || !/^[a-z0-9-]{1,60}$/.test(runId)) throw new Error('scripts/loadtest.sh run으로 고유 RUN_ID를 만들어 실행하세요.');
if (fixture.baseUrl !== base || !fixture.users.length) throw new Error('먼저 대상 서버의 테스트 직원을 준비하세요.');
if (!Number.isInteger(rate) || rate < 1 || !(completionTimeout > 0) || !(pollInterval > 0)
    || !Number.isInteger(preVus) || !Number.isInteger(maxVus) || preVus < 1 || maxVus < preVus) {
    throw new Error('RATE, 완료 제한 시간, 폴링 간격, VU 설정을 확인하세요.');
}

const submissions = new Counter('receipt_submissions');
const accepted = new Counter('receipt_accepted');
const completed = new Counter('receipt_completed');
const rejected = new Counter('receipt_not_accepted');
const failed = new Counter('receipt_failed');
const timedOut = new Counter('receipt_completion_timeouts');
const success = new Rate('receipt_success');
const uploadLatency = new Trend('receipt_upload_ms', true);
const completionLatency = new Trend('receipt_completion_ms', true);

export const options = {
    setupTimeout: '3m',
    noCookiesReset: true,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    // URL에 개별 receiptId가 들어가도 시계열을 무한히 늘리지 않습니다.
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
    scenarios: {
        submit: {
            executor: 'constant-arrival-rate', rate, timeUnit, duration,
            preAllocatedVUs: preVus, maxVUs: maxVus,
            gracefulStop: `${completionTimeout + 20}s`
        }
    },
    thresholds: {
        // 기능 검증 기본값입니다. 성능 목표 p95는 현재 성능을 측정한 다음 정합니다.
        receipt_submissions: ['count>0'],
        receipt_success: ['rate==1'],
        dropped_iterations: ['count==0'],
        ...( __ENV.UPLOAD_P95_MS ? {receipt_upload_ms: [`p(95)<${Number(__ENV.UPLOAD_P95_MS)}`]} : {} ),
        ...( __ENV.COMPLETION_P95_MS ? {receipt_completion_ms: [`p(95)<${Number(__ENV.COMPLETION_P95_MS)}`]} : {} )
    }
};

function json(response) {
    try { return response.json(); } catch (_) { return null; }
}

export function setup() {
    const sessions = [];
    for (const user of fixture.users) {
        const jar = http.cookieJar();
        jar.clear(base);
        let response = http.get(`${base}/api/auth/csrf`, {tags: {name: 'setup_csrf'}, timeout: '10s'});
        let token = json(response);
        if (response.status !== 200 || !token?.token) fail('CSRF 토큰을 가져오지 못했습니다.');
        response = http.post(`${base}/api/auth/login`, JSON.stringify({loginId: user.loginId, password: user.password}), {
            headers: {'Content-Type': 'application/json', [token.headerName]: token.token},
            tags: {name: 'setup_login'}, timeout: '10s'
        });
        if (response.status !== 204) fail(`테스트 직원 로그인 실패: HTTP ${response.status}`);
        response = http.get(`${base}/api/auth/csrf`, {tags: {name: 'setup_csrf'}, timeout: '10s'});
        token = json(response);
        const cookie = jar.cookiesForURL(base).JSESSIONID?.[0];
        if (response.status !== 200 || !cookie || !token?.token) fail('로그인 후 세션 또는 CSRF 토큰이 없습니다.');
        sessions.push({cookie, token, employeeId: user.employeeId});
    }
    return {sessions};
}

export default function (data) {
    // 기본 vu 메타데이터와 별도로, InfluxDB에도 보존되는 사용자 태그로 표본을 구분합니다.
    exec.vu.metrics.tags.load_vu = String(exec.vu.idInTest);
    const session = data.sessions[(exec.vu.idInTest - 1) % data.sessions.length];
    http.cookieJar().set(base, 'JSESSIONID', session.cookie);
    const marker = `${runId}-${exec.scenario.iterationInTest}`;
    const payload = uniquePng(image, marker);
    submissions.add(1);
    // 실패 0건도 요약에 나타나게 합니다.
    rejected.add(0); failed.add(0); timedOut.add(0); completed.add(0); accepted.add(0);
    const start = Date.now();
    const response = http.post(`${base}/api/receipts`, {file: http.file(payload, `lt-${marker}.png`, 'image/png')}, {
        headers: {[session.token.headerName]: session.token.token, 'Idempotency-Key': `lt-${marker}`},
        tags: {name: 'upload'}, timeout: '15s'
    });
    uploadLatency.add(Date.now() - start);
    const receiptId = json(response)?.receiptId;
    const isNew = check(response, {'신규 접수 202': r => r.status === 202 && Number.isInteger(receiptId)});
    if (!isNew) {
        rejected.add(1); success.add(false);
        return;
    }
    accepted.add(1);
    while (Date.now() - start < completionTimeout * 1000) {
        const remaining = completionTimeout * 1000 - (Date.now() - start);
        const detailResponse = http.get(`${base}/api/receipts/${receiptId}`, {
            tags: {name: 'poll_detail'}, timeout: `${Math.max(1, Math.min(5000, remaining))}ms`
        });
        const detail = json(detailResponse);
        if (detailResponse.status !== 200 || detail?.id !== receiptId || detail?.ownerEmployeeId !== session.employeeId) {
            failed.add(1); success.add(false);
            return;
        }
        if (detail.jobStatus === 'COMPLETED') {
            // 품질 거절도 Job은 COMPLETED이므로 추출 데이터까지 확인합니다.
            const valid = detail.currentData?.shopName === '테스트상점' && Number(detail.currentData?.totalAmount) === 12000;
            check(detail, {'추출 데이터 확인': () => valid});
            success.add(valid);
            if (valid) {
                completed.add(1);
                completionLatency.add(Date.now() - start);
            } else failed.add(1);
            return;
        }
        if (detail.jobStatus === 'FAILED') {
            failed.add(1); success.add(false);
            return;
        }
        sleep(Math.min(pollInterval, Math.max(0, (completionTimeout * 1000 - (Date.now() - start)) / 1000)));
    }
    timedOut.add(1); success.add(false);
}

export function handleSummary(data) {
    const names = ['receipt_submissions', 'receipt_accepted', 'receipt_completed', 'receipt_not_accepted',
        'receipt_failed', 'receipt_completion_timeouts', 'receipt_success', 'receipt_upload_ms',
        'receipt_completion_ms', 'dropped_iterations'];
    const metrics = names.map(name => `${name}: ${JSON.stringify(data.metrics[name]?.values || {})}`).join('\n');
    const report = {
        configuration: {runId, base, rate, timeUnit, duration, completionTimeout, pollInterval, preVus, maxVus,
            employeeCount: fixture.users.length, image: imageMetadata,
            completionMetric: 'client-observed upload-to-completion; includes polling delay; successful samples only'},
        // setup_data에는 로그인 세션이 들어 있으므로 측정 결과만 저장합니다.
        metrics: data.metrics,
        options: data.options,
        state: data.state,
        root_group: data.root_group
    };
    return {
        [__ENV.SUMMARY_FILE || 'summary.json']: JSON.stringify(report, null, 2),
        stdout: `\nRun: ${runId}\n${metrics}\n완료 p95는 성공 건만 집계하며 실패/시간 초과와 함께 확인하세요.\n`
    };
}
