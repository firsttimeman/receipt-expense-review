#!/usr/bin/env python3
"""관리자 발급 → 일회용 토큰으로 비밀번호 설정까지 실제 API로 테스트 직원을 만듭니다."""
import argparse
import http.cookiejar
import json
import secrets
import urllib.error
import urllib.request
import uuid
from pathlib import Path

GENERATED = Path(__file__).resolve().parent / 'generated'


class Client:
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, body=None):
        headers = {}
        if body is not None:
            csrf = self.call('/api/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(self.base + path, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try:
            with self.opener.open(request, timeout=20) as response:
                data = response.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'{path}: HTTP {error.code}. 계정 준비 상태를 확인하세요.') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:19880')
    parser.add_argument('--count', type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.count <= 200:
        parser.error('직원 수는 1~200명이어야 합니다.')
    base = args.base_url.rstrip('/')
    admin = Client(base)
    admin.call('/api/auth/login', json.loads((GENERATED / 'admin.json').read_text()))
    target = GENERATED / 'users.json'
    fixture = json.loads(target.read_text()) if target.exists() else dict(baseUrl=base, users=[])
    if fixture['baseUrl'] != base:
        raise RuntimeError('users.json의 대상 주소가 다릅니다. 환경별로 계정 파일을 분리하세요.')
    for user in fixture['users']:
        Client(base).call('/api/auth/login', dict(loginId=user['loginId'], password=user['password']))
    for index in range(len(fixture['users']), args.count):
        login_id = 'lt-' + uuid.uuid4().hex[:16]
        issued = admin.call('/api/employees', dict(loginId=login_id, name=f'부하테스트 직원 {index + 1}', role='EMPLOYEE'))
        password = secrets.token_urlsafe(24)
        Client(base).call('/api/auth/password', dict(token=issued['setupToken'], password=password))
        fixture['users'].append(dict(loginId=login_id, password=password, employeeId=issued['employee']['id']))
        # 중간 실패 때 이미 발급한 계정을 다시 만들지 않도록 한 건씩 저장합니다.
        with target.open('w') as file:
            target.chmod(0o600)
            json.dump(fixture, file, indent=2)
    print(f'직원 {len(fixture["users"])}명 준비 완료. 비밀번호는 Git 제외된 load/generated/users.json에만 저장했습니다.')


if __name__ == '__main__':
    main()
