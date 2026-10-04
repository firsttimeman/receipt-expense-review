# 직원 인증·영수증 권한 구현 검증

## 시작점과 환경

- 시작 커밋: `1e247fc0e89a880db1833b64ddd635a62478bee6` (`codex/cloud-erp-setup`). 이 커밋에서 `codex/employee-auth-ownership`을 생성했으며 main에서 시작하지 않았다.
- 실행 위치: 선택된 Linux 클라우드 환경 `/workspace/receipt-expense-review`. 로컬 Mac이나 운영 계정·비밀키에 의존하지 않았다.
- 실제 확인: Temurin JDK `17.0.16+8`, 저장소 wrapper Gradle `8.14`, Docker client/daemon `28.4.0`, Docker Compose `v2.40.3`.
- 게시된 `/workspace/.cloud-tools/install.sh`와 `activate.sh`를 사용했다. `/workspace/.gradle` 캐시와 `cloud-maven-mirror.gradle`의 Maven Central Google 미러 설정을 확인했다. wrapper/의존성 컴파일도 실제 실행했다.
- 복원·확인한 이미지: `mysql:8.4`, `redis:7.2-alpine`, `alpine:3.17`, `testcontainers/ryuk:0.7.0`. 실제 컨테이너를 기동해 검증했다.
- 추출기는 fake만 사용했다. 기존 OpenAI 어댑터 테스트도 로컬 가짜 HTTP 응답을 사용하며 외부 AI 호출은 없다.

## 최종 자동 테스트

2026-10-02 UTC, 최종 코드에서 다음 명령을 실행했다.

```bash
source /workspace/.cloud-tools/activate.sh
bash scripts/cloud/verify.sh
```

스크립트 내부의 `./gradlew clean test --no-daemon`이 실행되어 캐시된 테스트 결과를 재사용하지 않았다. 종료 코드 0, 마지막 Gradle 실행 시간 1분 55초.

```text
BUILD SUCCESSFUL
6 actionable tasks: 6 executed
Test results: tests=43, failures=0, errors=0, skipped=0
```

| 테스트 묶음 | 테스트 | 실패 | 오류 | 건너뜀 |
|---|---:|---:|---:|---:|
| EmployeeAuthorizationIntegrationTest — 실제 MySQL/Redis | 13 | 0 | 0 | 0 |
| EmployeeMigrationIntegrationTest — 실제 MySQL, 데이터가 있는 V4→V5 | 1 | 0 | 0 | 0 |
| MySqlSchemaIntegrationTest — 기존 MySQL/Redis 회귀 | 3 | 0 | 0 | 0 |
| ReceiptApiIntegrationTest — 인증을 포함한 기존 API 회귀 | 7 | 0 | 0 | 0 |
| 나머지 단위·추출·Worker·경쟁·관측 테스트 | 19 | 0 | 0 | 0 |
| **합계** | **43** | **0** | **0** | **0** |

**미실행 테스트 없음.** 환경 준비 때의 과거 29개 통과는 위 결과에 재사용하지 않았다. 기존 29개에 계정·권한 13개와 이관 1개를 추가했다. 신규 테스트에 Docker 부재 시 건너뛰는 옵션을 사용하지 않았으며 기존 테스트도 하나도 건너뛰지 않았다. `verify.sh`는 신규 두 묶음이 실제 실행됐는지도 검사한다.

[JUnit XML에서 추출한 세부 결과](employee-auth-test-results.json)에 각 테스트 이름·실행 시각·집계·테스트 대상 소스 fingerprint를 기록했다. 원본 XML/HTML은 클라우드 작업 디렉터리 `build/test-results/test/`, `build/reports/tests/test/`에 생성된다. 비밀번호·초대 토큰이 포함될 수 있는 전체 HTTP 디버그 로그는 저장소에 올리지 않았다.

개발 중 첫 41개 실행에서는 기존 Mockito fixture의 소유자 기본값 때문에 단위 테스트 1개가 실패했다. 명시적 소유자 fixture로 수정했고 이후 이관·단위 테스트 및 전체 재실행이 통과했다. 이후 JSON 파싱 오류의 비밀값 반사 방지를 추가하고 위 최종 `clean test`로 다시 검증했다. 실패한 실행이나 컴파일만 한 결과를 통과한 테스트로 세지 않았다.

## 완료 기준별 검증

| 요구사항 | 자동 검증 |
|---|---|
| 관리자만 계정 발급, 로그인 ID 유니크 | `adminIssuesAccountsAndDatabaseRejectsDuplicateLogin` 및 MySQL 이관 제약 검사 |
| 비밀번호 설정·로그인·로그아웃·오류·비활성 차단 | `setupLoginLogoutAndInvalidCredentials`, `inactiveAccountCannotLoginUseSessionOrSetPassword` |
| CSRF·세션 고정 방지·만료/재사용 토큰·비밀값 비노출 | `csrfIsRequiredIncludingLoginAndPasswordAndSessionRotatesOnLogin`, `expiredInvalidAndShortPasswordTokensDoNotSetPasswordOrLeakSecrets`, `passwordSetupIsConsumedOnlyOnceUnderConcurrency` |
| 최초 관리자 동시 준비·기존 계정 보호 | `bootstrapIsOneTimeAndConcurrentInvocationsCannotCreateTwoAdmins` |
| 동시 계정 변경으로 비밀번호를 덮어쓰지 않음 | `staleAccountUpdateCannotEraseAJustSetPassword` |
| 제출자가 서버 인증 정보로 결정됨, 다른 직원의 조회·감사·수정 차단 | `employeeOwnsSubmissionAndOtherEmployeeCannotReadAuditOrModify` — 위조된 owner/직원/회사 입력도 검증 |
| 직원의 승인·반려 차단, 실제 검토자 감사 | 위 테스트 및 `actualReviewerIsAuditedForCorrectionApprovalAndRejection` |
| 동일 이미지·멱등성 키·경쟁 경로 소유권 | `crossOwnerDuplicateAndIdempotencyNeverReturnOrMutateOriginalReceipt`, `concurrentCrossOwnerIdempotencyRaceHasOneWinnerWithoutDisclosure` |
| 기존 자료 격리·관리자 읽기 전용 | `legacyOwnerlessReceiptIsQuarantinedForAdminReadOnly` |
| 기존/새 마이그레이션·추출·잠금·Worker 회귀 | `EmployeeMigrationIntegrationTest`, `MySqlSchemaIntegrationTest` 및 기존 테스트 전체 |

## 별도 실행 점검

JUnit 43개와 별도로 Docker Compose MySQL/Redis, 빌드한 실행 JAR에서 다음을 확인했다.

- 파일 권한 `600`의 임시 난수 비밀번호로 최초 관리자 준비: 웹 서버 없이 성공 후 종료.
- 같은 DB에서 관리자 준비 재실행: 비정상 종료하며 기존 계정 덮어쓰기 거부.
- `127.0.0.1:18080`에만 바인딩한 임시 테스트 서버에서 실제 HttpOnly 쿠키·CSRF 토큰으로 계정 발급 → 비밀번호 설정 → 로그인·인증 확인 → 로그아웃 → 익명 접근 거절.
- HTTP 응답 상태 검사 13개 통과. 이는 JUnit 테스트 총수에 더하지 않는다.

## 구현 정책과 제한

- 서버 세션, BCrypt 비용 12, 해시로만 보관하는 256비트 설정 토큰(24시간/일회용), 매 요청 활성 상태·현재 역할 확인을 사용한다.
- 업로드의 서버 인증 소유자와 수정·검토의 감사 행위자를 사용한다. 직원은 본인만, REVIEWER/ADMIN은 검토 가능한 자료만 접근한다.
- 기존 회사 전체 이미지/멱등성 제약을 유지한다. 다른 소유자와 충돌하면 정보를 돌려주거나 원본을 변경하지 않고 `409`를 반환한다. 직원 간 별도 복제 제출은 구현하지 않았다.
- V5의 NULL 소유자 자료는 관리자 읽기 전용 격리 보관이다. 회사 헤더·과거 actor로 추정 배정하지 않는다. 확인 후 재배정은 별도 검토된 이관 작업이 필요하다.
- X-Company-Id는 인증에 사용하지 않고 신규 HTTP 접수에서는 무시한다. 신규 회사 값은 서버의 `internal`이다.
- 계정 복구·만료 초대 재발급·로그인 속도 제한·다중 서버 세션 공유는 후속 작업이다. 이메일·프런트엔드·멀티테넌트·운영 배포는 수행하지 않았다.
- 기존 Flyway의 MySQL 지원 버전 안내와 Gradle 9 호환성 경고는 남아 있으나 이번 실제 MySQL 8.4 실행과 마이그레이션은 통과했다.
