import http from 'k6/http';
import exec from 'k6/execution';
import {check, fail, sleep} from 'k6';
import {Counter, Rate, Trend} from 'k6/metrics';
import {uniquePng} from './png.mjs';

const base = (__ENV.BASE_URL || 'http://127.0.0.1:19880').replace(/\/$/, '');
const fixture = JSON.parse(open('../generated/users.json'));
const caseId = __ENV.CASE_ID;
const mode = __ENV.DUPLICATE_MODE;
const phase = __ENV.DUPLICATE_PHASE;
const vus = Number(__ENV.DUPLICATE_VUS);
if (!caseId || !/^[a-z0-9-]{1,75}$/.test(caseId)
    || !['same-key', 'different-key', 'no-key', 'cross-owner'].includes(mode)
    || !['initial', 'after-completion'].includes(phase)
    || !Number.isInteger(vus) || vus < 2 || vus > 100
    || fixture.baseUrl !== base || fixture.users.length < 2) {
    throw new Error('load/run-duplicates.py를 통해 로컬 테스트 환경에서 실행하세요.');
}

// 한 시험의 모든 VU와 재전송 단계가 완전히 동일한 바이트를 제출합니다.
// 다음 시험은 다른 marker를 사용하므로 기존 저장 데이터의 빠른 반환을 측정하지 않습니다.
const image = uniquePng(open('../generated/receipt.png', 'b'), caseId);
const latency = new Trend('duplicate_upload_ms', true);
const submitted = new Counter('duplicate_requests');
const created = new Counter('duplicate_created');
const reused = new Counter('duplicate_reused');
const conflicts = new Counter('duplicate_owner_conflicts');
const unexpected = new Counter('duplicate_unexpected');
const valid = new Rate('duplicate_response_valid');

export const options = {
    setupTimeout: '1m',
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
    scenarios: {burst: {executor: 'per-vu-iterations', vus, iterations: 1, maxDuration: '40s'}},
    thresholds: {
        duplicate_requests: [`count==${vus}`],
        duplicate_response_valid: ['rate==1'],
        duplicate_unexpected: ['count==0']
    }
};

function json(response) {
    try { return response.json(); } catch (_) { return null; }
}

export function setup() {
    const sessions = [];
    for (const user of fixture.users.slice(0, mode === 'cross-owner' ? 2 : 1)) {
        const jar = http.cookieJar();
        jar.clear(base);
        let response = http.get(`${base}/api/auth/csrf`, {tags: {name: 'setup_csrf'}, timeout: '10s'});
        let token = json(response);
        if (response.status !== 200 || !token?.token) fail('CSRF 토큰 조회 실패');
        response = http.post(`${base}/api/auth/login`, JSON.stringify({loginId: user.loginId, password: user.password}), {
            headers: {'Content-Type': 'application/json', [token.headerName]: token.token},
            tags: {name: 'setup_login'}, timeout: '10s'
        });
        if (response.status !== 204) fail(`테스트 로그인 실패: ${response.status}`);
        token = json(http.get(`${base}/api/auth/csrf`, {tags: {name: 'setup_csrf'}, timeout: '10s'}));
        const cookie = jar.cookiesForURL(base).JSESSIONID?.[0];
        if (!cookie || !token?.token) fail('테스트 세션 준비 실패');
        sessions.push({cookie, token, employeeId: user.employeeId});
    }
    return {sessions, startAt: Date.now() + 3000};
}

export default function (data) {
    const vu = exec.vu.idInTest;
    exec.vu.metrics.tags.load_vu = String(vu);
    const session = data.sessions[(vu - 1) % data.sessions.length];
    http.cookieJar().set(base, 'JSESSIONID', session.cookie);
    const headers = {[session.token.headerName]: session.token.token};
    if (mode === 'same-key') headers['Idempotency-Key'] = `lt-${caseId}`;
    if (mode === 'different-key' || mode === 'cross-owner') headers['Idempotency-Key'] = `lt-${caseId}-${vu}`;
    // 이미지 구성/로그인이 끝난 뒤 같은 시각에 보내도록 맞춥니다.
    // 실제 시작 시각 분산은 기록하여 '동시 요청'의 정밀도를 확인합니다.
    const remaining = data.startAt - Date.now();
    if (remaining > 0) sleep(remaining / 1000);
    const startMs = Date.now();
    const response = http.post(`${base}/api/receipts`, {
        file: http.file(image, `lt-${caseId}.png`, 'image/png')
    }, {
        headers, tags: {name: 'duplicate_upload'}, timeout: '20s',
        responseCallback: http.expectedStatuses(...(mode === 'cross-owner' ? [200, 202, 409] : [200, 202]))
    });
    const endMs = Date.now();
    const body = json(response);
    const ok = check(response, {
        '중복 제출 응답 형식': r => ([200, 202].includes(r.status)
                && Number.isInteger(body?.receiptId) && Number.isInteger(body?.jobId)
                && (phase === 'initial' || r.status === 200))
            || (mode === 'cross-owner' && r.status === 409 && !body?.receiptId && !body?.jobId)
    });
    latency.add(endMs - startMs);
    submitted.add(1);
    created.add(response.status === 202 ? 1 : 0);
    reused.add(response.status === 200 ? 1 : 0);
    conflicts.add(response.status === 409 ? 1 : 0);
    unexpected.add(ok ? 0 : 1);
    valid.add(ok);
    // 응답 헤더 전체나 setup_data에는 세션이 포함되므로 식별자/상태만 저장합니다.
    console.log('DUPLICATE_SAMPLE ' + JSON.stringify({
        caseId, mode, phase, vu, employeeId: session.employeeId, startMs, endMs,
        status: response.status, receiptId: body?.receiptId ?? null, jobId: body?.jobId ?? null,
        jobStatus: body?.jobStatus ?? null, replay: response.headers['X-Idempotent-Replay'] ?? null,
        error: response.status >= 400 ? (body?.message ?? body?.detail ?? null) : null
    }));
}

export function handleSummary(data) {
    const report = {
        configuration: {caseId, mode, phase, vus, base, imageBytes: image.byteLength},
        metrics: data.metrics, options: data.options, state: data.state, root_group: data.root_group
    };
    return {
        [__ENV.SUMMARY_FILE]: JSON.stringify(report, null, 2),
        stdout: `\n${caseId} ${phase}: ${JSON.stringify(data.metrics.duplicate_upload_ms?.values || {})}\n`
    };
}
