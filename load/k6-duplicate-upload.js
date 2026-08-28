import http from 'k6/http';
import {check} from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const companyId = __ENV.COMPANY_ID || 'k6-duplicate-company';
const image = open('./synthetic-receipt-load.jpg', 'b');
const profileName = __ENV.PROFILE || 'normal';
const profiles = {
    smoke: {vus: 5, duration: '10s'},
    normal: {vus: 20, duration: '30s'},
    heavy: {vus: 50, duration: '60s'},
    stress: {vus: 100, duration: '60s'},
    spike: {vus: 200, duration: '30s'},
};
const selectedProfile = profiles[profileName] || profiles.normal;

export const options = {
    scenarios: {
        duplicate_burst: {
            executor: 'constant-vus',
            vus: Number(__ENV.VUS || selectedProfile.vus),
            duration: __ENV.DURATION || selectedProfile.duration,
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    },
};

export default function () {
    const response = http.post(`${baseUrl}/api/receipts`, {
        file: http.file(image, 'synthetic-receipt-load.jpg', 'image/jpeg'),
    }, {
        headers: {'X-Company-Id': companyId},
    });
    const body = response.body ? response.json() : {};

    check(response, {
        'accepted or existing receipt': (result) => result.status === 202 || result.status === 200,
        'receipt id returned': () => Boolean(body.receiptId),
    });
}
