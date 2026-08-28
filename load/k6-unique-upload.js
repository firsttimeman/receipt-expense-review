import http from 'k6/http';
import {check} from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const companyId = __ENV.COMPANY_ID || 'k6-unique-200-company';
const users = Number(__ENV.USERS || 200);
const baseImage = open('./synthetic-receipt-load.jpg', 'b');

export const options = {
    scenarios: {
        unique_receipts: {
            executor: 'per-vu-iterations',
            vus: users,
            iterations: 1,
            maxDuration: '60s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<2000', 'p(99)<3000'],
    },
};

export default function () {
    const image = uniqueJpeg(baseImage, __VU);
    const response = http.post(`${baseUrl}/api/receipts`, {
        file: http.file(image, `synthetic-receipt-${__VU}.jpg`, 'image/jpeg'),
    }, {
        headers: {'X-Company-Id': companyId},
        tags: {load_scenario: 'unique_receipts'},
    });
    const body = response.body ? response.json() : {};

    check(response, {
        'new receipt accepted': (result) => result.status === 202,
        'receipt id returned': () => Boolean(body.receiptId),
        'job initially queued': () => body.jobStatus === 'QUEUED',
    });
}

// JPEG 종료 마커 뒤의 바이트는 이미지 디코딩에 영향을 주지 않지만 SHA-256은 달라진다.
// 저장소에는 하나의 비민감 합성 이미지만 유지하면서 VU마다 고유한 영수증을 만든다.
function uniqueJpeg(imageBytes, vuNumber) {
    const original = new Uint8Array(imageBytes);
    const unique = new Uint8Array(original.length + 8);
    unique.set(original);
    const suffix = new DataView(unique.buffer);
    suffix.setUint32(original.length, vuNumber);
    suffix.setUint32(original.length + 4, 0x52435054);
    return unique.buffer;
}
