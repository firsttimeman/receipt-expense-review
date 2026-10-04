# 사내 영수증·경비 검수 백엔드

한 회사 내부 ERP를 위한 Java 17 / Spring Boot 3.3.5 백엔드입니다. 직원 계정·세션 인증·영수증 소유권과 역할별 권한, 이미지 접수, 비동기 추출, 규칙 검증, 개별 검수를 구현했습니다. 프런트엔드·이메일 발송·멀티테넌트는 범위 밖입니다.

## 구현 현황

| 영역 | 현재 상태 |
|---|---|
| 계정 | ADMIN 계정 발급·활성 상태 관리, 일회용 비밀번호 설정, BCrypt 해시 |
| 인증 | Spring Security 서버 세션, 로그인·로그아웃·인증 확인, CSRF 보호, 비활성 계정 차단 |
| 권한 | 본인 영수증·감사 이력 조회, 업무 상태에 따른 수정, REVIEWER/ADMIN 승인·반려 |
| 접수 | 이미지 SHA-256, Redis 잠금, MySQL 유니크 제약, 멱등성 키, `202 Accepted` |
| 추출 | Fake 추출기·OpenAI 어댑터, 이미지 품질 검사, 결정론적 경비 규칙 |
| 검수 | 낙관적 락, 추출 원본·수정값·실제 로그인한 행위자의 감사 이력 |
| Worker | MySQL 작업 큐, 동시 실행 제한, 재시도·Lease 만료 복구, Claim Token |
| 검증 | 단위·API 회귀, 실제 MySQL/Redis 권한·동시성 통합, 기존 데이터 Flyway 이관 |
| 후속 개발 | 본인 제출 목록·검수 목록·기간별 검색·월별 집계 |

## 구조와 처리 흐름

코드는 `com.example.receipt.domain` 아래 기능별 구조를 유지합니다.

```text
domain/
├── employee/    # controller, dto, entity, model, repository, service
├── receipt/     # 영수증 접수·검수·감사·소유권·검증
└── extraction/  # 추출기·내구성 작업 큐·Worker
global/          # 공통 설정·Security·잠금·저장·예외·관측
```

영수증·작업·멱등성 기록·업로드 감사 이벤트를 한 DB 트랜잭션으로 저장하고 추출은 Worker에서 실행합니다. `FOR UPDATE SKIP LOCKED`로 작업을 나누며, Claim Token으로 오래된 Worker의 결과 저장을 차단합니다. 외부 AI 호출이 정확히 한 번만 실행된다는 보장은 아닙니다.

접수 후 업무 `status`는 추출 완료 전까지 `null`이며 `jobStatus`를 조회합니다. 작업 상태는 QUEUED / PROCESSING / RETRY_WAIT / COMPLETED / FAILED, 업무 상태는 AUTO_APPROVED / NEEDS_REVIEW / NEEDS_RECAPTURE / UNREADABLE / MANUAL_ENTRY / APPROVED / REJECTED입니다. 추출 재시도는 기본 3회이며 최종 실패 시 MANUAL_ENTRY가 됩니다.

## 실행 환경

JDK 17, Docker Compose, Gradle wrapper를 사용합니다. 저장소 루트에서 실행하세요.

```bash
docker compose up -d --wait mysql redis
RECEIPT_EXTRACTOR_PROVIDER=fake ./gradlew bootRun
```

기본 주소는 앱 `localhost:8080`, MySQL `localhost:3307`, Redis `localhost:6379`입니다. DB는 Flyway와 Hibernate `validate`를 사용합니다. 이미지는 `runtime/receipt-images/`에 저장합니다. Fake 추출기는 합성 데이터로 흐름을 검증하며 실제 OCR을 하지 않습니다. 운영 계정이나 OpenAI 비밀키 없이 모든 자동 테스트를 실행할 수 있습니다.

### 최초 관리자 준비

기본 관리자·공용 비밀번호는 없습니다. **일반 서버를 중지한 상태에서** 아래 별도 모드로 빈 직원 테이블에 관리자 한 명을 생성합니다. DB 잠금으로 동시 실행도 한 번만 성공하며 직원이 이미 있으면 비밀번호를 덮어쓰지 않고 실패합니다.

```bash
./gradlew bootJar
umask 077
PASSWORD_FILE=$(mktemp)
python3 - "$PASSWORD_FILE" <<'PY'
import getpass, pathlib, sys
password = getpass.getpass("관리자 비밀번호 (12자 이상, UTF-8 72바이트 이하): ")
assert password == getpass.getpass("다시 입력: ")
pathlib.Path(sys.argv[1]).write_text(password)
PY
RECEIPT_WORKER_ENABLED=false java -jar build/libs/receipt-expense-review-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --receipt.bootstrap.enabled=true \
  --receipt.bootstrap.login-id=admin-local \
  --receipt.bootstrap.password-file="$PASSWORD_FILE"
rm -f "$PASSWORD_FILE"
RECEIPT_EXTRACTOR_PROVIDER=fake ./gradlew bootRun
```

파일은 소유자만 접근할 수 있는 `600` 권한이어야 합니다. 비밀번호를 CLI 인수·셸 기록·Git에 넣지 않습니다. 준비 실행은 성공 후 종료하며, 웹 실행에서는 준비 모드를 거절합니다. 평소 서버 시작 인수에는 bootstrap 설정을 두지 않습니다.

## 인증 방식과 계정 사용

동일 출처 웹 ERP를 위한 **서버 세션 + HttpOnly JSESSIONID 쿠키**를 선택했습니다. 브라우저 저장소에 장기 bearer token을 두지 않으며 로그인 시 세션 ID가 바뀝니다. 기본 유휴 만료는 30분, SameSite는 Strict입니다. HTTPS 환경에서는 `SESSION_COOKIE_SECURE=true`를 설정합니다. 세션은 서버 메모리에 있으므로 재시작 시 로그아웃되며 다중 서버 세션 공유는 구현하지 않았습니다.

모든 변경 요청(로그인·비밀번호 설정·로그아웃 포함)에 CSRF 헤더가 필요합니다. `/api/auth/csrf`에서 `headerName`, `token`을 얻어 같은 쿠키 저장소와 함께 전송합니다. 로그인·로그아웃 후에는 토큰을 다시 받습니다. 인증 누락은 `401`, 권한 부족·CSRF 누락은 `403`입니다. 계정 로그인 ID는 소문자 ASCII 영숫자 및 `._-`, 3~64자이며 DB 유니크 제약으로 중복을 막습니다.

### 로그인·계정 발급 예시

Bash, curl, Python 3를 사용합니다. 쿠키·토큰·비밀번호 파일은 `umask 077` 아래에서 만들고 사용 후 삭제합니다.

```bash
BASE=http://localhost:8080
umask 077
COOKIE=$(mktemp)
PASSWORD_FILE=$(mktemp)
python3 - "$PASSWORD_FILE" <<'PY'
import getpass, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(getpass.getpass("로그인 비밀번호: "))
PY
CSRF=$(curl -fsS -c "$COOKIE" "$BASE/api/auth/csrf" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
curl -i -b "$COOKIE" -c "$COOKIE" -H "X-CSRF-TOKEN: $CSRF" \
  --data-urlencode 'loginId=admin-local' --data-urlencode "password@$PASSWORD_FILE" "$BASE/api/auth/login"
rm -f "$PASSWORD_FILE"
CSRF=$(curl -fsS -b "$COOKIE" -c "$COOKIE" "$BASE/api/auth/csrf" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
curl -fsS -b "$COOKIE" "$BASE/api/auth/me"

# ADMIN만 발급 가능. EMPLOYEE / REVIEWER / ADMIN 중 명시적으로 선택합니다.
curl -fsS -b "$COOKIE" -H "X-CSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' \
  -d '{"loginId":"employee-one","name":"테스트 직원","role":"EMPLOYEE"}' \
  "$BASE/api/employees" -o invite.json
```

응답의 `setupToken`은 256비트 난수이고 DB에는 SHA-256 해시만 저장합니다. 직원에게 안전한 별도 경로로 전달합니다(이메일 발송 없음). 토큰은 24시간 후 만료되며 한 번만 사용할 수 있습니다. 비밀번호는 BCrypt 비용 12로 저장하고 응답·로그에 해시를 노출하지 않습니다. 비밀번호 설정 전에는 로그인할 수 없습니다.

### 직원의 최초 비밀번호 설정

직원은 로그인 전에 **자기 쿠키 저장소**와 CSRF 토큰으로 설정합니다. 관리자와 직원의 쿠키 파일을 섞지 않습니다.

```bash
EMPLOYEE_COOKIE=$(mktemp)
SETUP_REQUEST=$(mktemp)
python3 - "$SETUP_REQUEST" <<'PY'
import getpass, json, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({
    "token": getpass.getpass("전달받은 설정 토큰: "),
    "password": getpass.getpass("새 비밀번호: ")}))
PY
EMPLOYEE_CSRF=$(curl -fsS -c "$EMPLOYEE_COOKIE" "$BASE/api/auth/csrf" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
curl -i -b "$EMPLOYEE_COOKIE" -H "X-CSRF-TOKEN: $EMPLOYEE_CSRF" \
  -H 'Content-Type: application/json' --data-binary "@$SETUP_REQUEST" "$BASE/api/auth/password"
rm -f "$SETUP_REQUEST" invite.json
```

이후 로그인 예시에서 `loginId=employee-one`과 직원의 비밀번호·쿠키 파일을 사용합니다. 비활성 계정은 로그인·비밀번호 설정이 거절되고 기존 세션도 다음 요청에서 폐기됩니다. ADMIN은 `PATCH /api/employees/{id}/active`에 `{"active":false}`로 비활성화할 수 있으며 자기 자신은 비활성화할 수 없습니다.

## 영수증 API와 접근 정책

`X-Company-Id`는 인증 근거가 아니며 HTTP 접수에서는 **무시**합니다. 신규 회사 값은 서버에서 `internal`로 고정합니다. 제출자·검토자는 로그인한 직원으로 결정하며 감사 행위자는 `employee:<직원 ID>`로 기록합니다. 클라이언트의 미지원 `reviewerId`/`ownerEmployeeId` 필드는 권한이나 행위자에 영향을 주지 않습니다.

| 역할 | 조회·감사 이력 | 필드 수정 | 승인·반려 |
|---|---|---|---|
| EMPLOYEE | 본인만 | 본인 + NEEDS_REVIEW / NEEDS_RECAPTURE / UNREADABLE / MANUAL_ENTRY | 불가 |
| REVIEWER | 소유자가 있는 모든 영수증 | 추출 완료 후, 최종 처리 전 | 추출 완료 후, 최종 처리 전 |
| ADMIN | 전체 + 격리된 과거 자료 | REVIEWER와 동일, 소유자 없는 과거 자료는 불가 | REVIEWER와 동일, 소유자 없는 과거 자료는 불가 |

다른 직원의 영수증·감사 이력은 일반 직원에게 `404`를 반환합니다. 추출 대기 중이거나 APPROVED/REJECTED이면 수정할 수 없습니다. NEEDS_RECAPTURE/UNREADABLE은 필드를 보완해야 결정할 수 있습니다. 수정·결정은 최신 `version`을 요구하며 충돌하면 `409`입니다.

아래 `COOKIE`, `CSRF`는 **로그인 후 갱신한** 해당 직원의 값입니다. 응답 ID와 최신 version을 실제 값으로 바꿔 사용합니다.

```bash
curl -i -b "$COOKIE" -H "X-CSRF-TOKEN: $CSRF" \
  -H 'Idempotency-Key: upload-001' \
  -F 'file=@samples/synthetic-receipt.png;type=image/png' "$BASE/api/receipts"
curl -fsS -b "$COOKIE" "$BASE/api/receipts/1"
curl -fsS -b "$COOKIE" "$BASE/api/receipts/1/audit-events"
curl -i -X PATCH -b "$COOKIE" -H "X-CSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' \
  -d '{"version":1,"merchant":"수정 상점"}' "$BASE/api/receipts/1/fields"
# REVIEWER 또는 ADMIN 세션으로만 가능. reviewerId를 전달하지 않습니다.
curl -i -b "$COOKIE" -H "X-CSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' \
  -d '{"version":2,"decision":"APPROVE","note":"증빙 확인"}' "$BASE/api/receipts/1/decision"
curl -i -b "$COOKIE" -H "X-CSRF-TOKEN: $CSRF" -X POST "$BASE/api/auth/logout"
rm -f "$COOKIE" "$EMPLOYEE_COOKIE"
```

신규 접수는 `202`와 receiptId/jobId/jobStatus/acceptedAt을 반환하고, 소유자의 재전송은 `200`입니다. 이미지/멱등성 키 유니크 제약은 회사 전체에 유지합니다. **다른 소유자와 충돌하면 식별자·감사 정보를 반환하거나 원본을 변경하지 않고 `409`로 거절**합니다. 빠른 조회, 잠금 내부, DB 유니크 충돌 복구 경로 모두 같은 소유권 검사를 수행합니다.

### 기존 소유자 없는 자료 이관

Flyway V5는 기존 영수증·회사 값·추출 작업·감사 이력·멱등성 키를 보존하고 `owner_employee_id=NULL`로 남깁니다. 이 자료는 **관리자 읽기 전용 격리 보관**으로 이관합니다. 일반 직원과 REVIEWER는 접근할 수 없고 중복 업로드로 소유권을 얻을 수도 없습니다. 미완료 Worker 추출은 계속 처리될 수 있지만 사람이 수정·승인·반려할 수는 없습니다.

회사 헤더나 과거 문자열 actor로 소유자를 추정하지 않습니다. 소유권 변경 API는 없습니다. 실제 소유자가 확인되어 재배정이 필요하면 증빙·대상 ID·작업자·전후 감사 기록·백업·충돌 확인을 포함하는 별도 검토된 데이터 이관을 수행해야 합니다. 확인되지 않은 자료는 격리 보존합니다.

## API 목록

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/api/auth/csrf` | 익명 CSRF 토큰 발급 |
| POST | `/api/auth/login` | form-urlencoded loginId/password 로그인 |
| POST | `/api/auth/logout` | 세션 폐기 |
| GET | `/api/auth/me` | 현재 직원·역할·활성 상태 |
| POST | `/api/auth/password` | token/password로 최초 비밀번호 설정 |
| POST | `/api/employees` | ADMIN 계정 발급, 설정 토큰 한 번 반환 |
| PATCH | `/api/employees/{id}/active` | ADMIN 활성 상태 변경 |
| POST | `/api/receipts` | 이미지 접수 |
| GET | `/api/receipts/{id}` | 영수증·규칙·작업 상태 조회 |
| GET | `/api/receipts/{id}/audit-events` | 감사 이력 조회 |
| PATCH | `/api/receipts/{id}/fields` | 필드 수정·재검증 |
| POST | `/api/receipts/{id}/decision` | APPROVE / REJECT |

Actuator health는 익명 접근 가능하며 다른 관측 엔드포인트는 ADMIN 인증이 필요합니다. 기존 Prometheus/k6 설정은 인증 전 예시이므로 현재 API를 사용하려면 세션·CSRF 처리를 추가해야 합니다.

## 테스트와 남은 제한

```bash
bash scripts/cloud/verify.sh
```

실제 MySQL 8.4/Redis 7.2를 Testcontainers로 실행합니다. 이 스크립트는 Docker daemon, 기존 MySQL 회귀·신규 계정 권한·데이터 이관 테스트의 실행 여부, 실패·오류·건너뜀 0을 확인합니다. Docker 부재로 기존 테스트 일부가 건너뛰면 검증 실패이며 신규 권한·이관 테스트는 Docker 없이 실행할 수 없습니다. 과거 환경 준비 당시의 29개 통과를 새 구현의 검증 결과로 재사용하지 않습니다. 실제 실행 결과는 [검증 기록](docs/EMPLOYEE_AUTH_VERIFICATION.md)에 남깁니다.

현재 범위에 포함되지 않은 항목:

- 이메일·프런트엔드·멀티테넌트, 목록·집계, 다중 서버 세션 공유.
- 비밀번호 분실 재설정, 만료된 초대 재발급, 로그인 시도 속도 제한. 실제 운영 전 계정 복구 절차를 추가해야 합니다.
- 필수 데이터 누락 영수증의 승인 규칙, 같은 멱등성 키에 다른 파일을 보내는 요청 계약 등 기존 업무 규칙 보완.
- 추출 오류별 재시도 구분·Lease 복구 전체 시도 상한, 로컬 이미지 저장소의 운영 구성.

실제 AI 추출을 별도로 사용하려면 `.env.example`에 설명된 `RECEIPT_EXTRACTOR_PROVIDER=openai`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_BASE_URL`을 실행 환경에 제공합니다. 이 구현·검증에서는 외부 AI를 호출하지 않습니다. OpenAI 어댑터 회귀 테스트도 로컬 가짜 HTTP 서버만 사용합니다.

## 관련 문서

- [클라우드 작업 범위](docs/CLOUD_TASK.md)
- [구현 검증 기록](docs/EMPLOYEE_AUTH_VERIFICATION.md)
- [기존 README](docs/README_LEGACY.md)
- [기존 성능 기록](docs/PERFORMANCE_TEST_RESULTS.md)
- [기존 프로젝트 진단](docs/PROJECT_REVIEW.md)

과거 문서는 당시의 구현·측정 기록입니다. Fake 환경의 수치를 실제 AI 성능으로 해석하지 않습니다.
