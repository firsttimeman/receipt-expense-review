# 변경 기록

이 문서는 프로젝트의 의미 있는 변경사항을 누적해서 기록합니다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)의 `Added`, `Changed`, `Fixed`, `Security` 분류를 참고합니다.

아직 배포하지 않은 작업은 `Unreleased`에 먼저 기록하고, 릴리스할 때 날짜가 있는 버전 항목으로 이동합니다. DB 마이그레이션, API 계약, 상태 전이, 동시성·정합성, 보안, 외부 AI 연동의 변경에는 변경 이유와 검증 방법도 함께 남깁니다.

## Unreleased

### Simplified

- 4단계 재시도 구조를 학습하고 설명하기 쉬운 고정 지연 방식으로 단순화했습니다.
  - `ExtractionFailureType`, Circuit Breaker와 관련 설정·어댑터 제거
  - `ExtractionException`을 세부 상태 없는 단순한 추출 실패 예외로 변경
  - `ReceiptExtractionProcessor`에서 시도 횟수만 확인해 모든 추출 실패를 10초 뒤 재시도
  - 최초 호출을 포함해 최대 3회 실패하면 Job `FAILED`, Receipt `MANUAL_ENTRY`로 전환
  - 401·잘못된 요청도 최대 3회 호출될 수 있다는 단순화의 비용을 README와 로드맵에 기록
- `ReceiptPersistenceService.markDuplicate`의 처리 전·후 분기에 단계별 설명 주석을 추가해 중복 감지 지연, 멱등 처리, 최종 상태 보존과 JPA 변경 감지 의도를 명확히 했습니다.
- 학습 시 클래스 역할을 빠르게 찾을 수 있도록 패키지를 역할별로 재구성했습니다.
  - JPA 엔티티: `entity`
  - 업무 서비스: `service`
  - 서비스 내부 모델: `service.model`
  - 업무 예외와 API 예외 처리: `exception`
  - API 요청·응답 DTO: `api.dto`
  - enum과 값 객체는 기존 `domain`에 유지
- 패키지 이동 후 비어 있던 `application` 디렉터리를 제거했습니다.
- `ReviewDecision`과 `RuleOutcome`에 역할 차이를 설명하는 Javadoc을 추가했습니다. 규칙 실패는 최종 반려가 아니라 검수 신호이며, 최종 승인·반려는 검수자 결정이라는 점을 명시했습니다.
- `ReceiptUploadService`의 `details(Object...)`도 업로드·품질 검사·추출·검증·실패별 명시적 감사 상세정보 메서드로 분리했습니다. 서비스 계층에서 키·값 인덱스 기반 Map 생성 패턴을 제거했습니다.
- `ReceiptCommandService`의 `Object...` 기반 범용 감사 상세정보 생성기를 필드 수정용·승인 결정용 명시적 메서드로 분리했습니다. 키·값 인덱스 규칙과 홀수 인자 오류 가능성을 제거했습니다.
- `FieldCorrections`를 record와 제네릭 `value()` 병합 메서드에서 일반 클래스와 필드별 `if` 문으로 변경했습니다. 수정값 적용, 기존값 유지, 명시적 삭제 순서가 코드에 직접 드러나도록 단순화했습니다.
- Spring Bean의 반복적인 생성자 주입 코드를 Lombok `@RequiredArgsConstructor`로 변경했습니다. 별도 초기화 로직이 필요한 OpenAI 어댑터와 JPA Entity 생성자는 명시적 생성자를 유지합니다.
- `idempotencyKey`가 등장하는 Entity, Controller, Service, Repository, DB 스키마와 테스트에 “동일 API 요청 재전송의 중복 처리를 막는 요청 키”라는 설명 주석을 추가했습니다.
- 프로젝트 학습 복잡도를 낮추기 위해 `Receipt`, `AuditEvent`, `IdempotencyRecord`의 식별자를 UUID에서 MySQL `BIGINT AUTO_INCREMENT`로 통일했습니다.
- 관련 외래 키, JPA Repository 제네릭, API 경로 변수와 테스트 식별자 타입도 `Long`으로 단순화했습니다.
- 영수증 저장 후 생성된 ID를 감사 이벤트와 멱등성 기록에 연결하도록 트랜잭션 저장 순서를 명확히 했습니다.

### Added

- 8단계 성능 개선의 회귀 방지 테스트와 Grafana 전후 비교 패널을 추가했습니다.
  - 처리 완료된 동일 이미지 100건이 추가 Redis 락 호출 없이 같은 Receipt를 반환하고 중복 감사 이벤트는 한 번만 남는지 검증
  - 정기 polling 한 번 이후에도 Worker가 빈 슬롯을 즉시 보충해 후속 Job을 연속 처리하는지 검증
  - 동일 이미지 처리량·P95와 고유 이미지 200 Job 완료 시간의 개선 전후를 대시보드에 표시
- `receipt_upload_duplicate_fast_path_total` Counter를 추가해 중복 빠른 반환 경로 사용량을 확인할 수 있게 했습니다.
- DB 적재 없이 운영 상태를 확인하는 최소 Micrometer/Prometheus 메트릭을 추가했습니다.
  - 업로드 결과·중복, 추출 성공·재시도·최종 실패, Lease 복구 Counter
  - MySQL 기준 미완료 Job 수 Gauge
  - 영수증·회사·Worker 식별자는 태그에서 제외해 고카디널리티 방지
- Docker Prometheus 서비스를 추가하고 Spring Boot의 `/actuator/prometheus`를 5초마다 수집하도록 구성했습니다.
- 동일 합성 이미지 중복 업로드용 k6 스크립트에 Smoke·Normal·Heavy 부하 프로필을 추가했습니다.
  - Compose의 `k6` 프로필 서비스로 로컬 k6 설치 없이 실행할 수 있습니다.
  - 현재 한계 측정과 8단계 전후 비교를 위해 100 VU Stress와 200 VU Spike 프로필을 추가했습니다.
- 200명이 서로 다른 합성 영수증을 한 번씩 제출하는 `k6-unique-upload.js`와 독립 성능 기록 문서를 추가했습니다.
- 로컬 전용 Grafana 12.1과 자동 프로비저닝 대시보드를 추가했습니다.
  - Prometheus 데이터 소스와 API 처리량·응답 시간·HikariCP 부하·미완료 Job 그래프 자동 등록
  - 관리자 비밀번호는 Git 제외 경로의 Docker secret으로만 주입하고 포트는 `127.0.0.1:3000`으로 제한
- 원본 2.4MB 이미지 대신 65KB·600x900 비민감 합성 JPEG를 사용해 파일 전송량이 API 로직 측정을 과도하게 지배하지 않도록 했습니다.
- 최소 Counter와 미완료 Job Gauge 단위 테스트를 추가했습니다.
- MySQL 작업 큐의 `RETRY_WAIT`와 `available_at`을 이용한 내구성 재시도를 구현했습니다.
  - 기본 최대 3회, 매번 10초 후 다시 처리하는 고정 지연
  - Worker 스레드에서 `sleep`하지 않고 다음 polling 시점 이후 다시 선점
  - 재시도 소진 시 Job은 `FAILED`, Receipt는 `MANUAL_ENTRY`로 전환
- `EXTRACTION_RETRY_SCHEDULED` 감사 이벤트를 추가해 재시도 횟수와 다음 실행 시각을 기록합니다.
- 고정 지연 재시도 성공과 최대 3회 실패 수명주기를 검증하는 통합 테스트를 추가했습니다.
- MySQL 내구성 작업 큐를 실행하는 다중 Worker와 Lease 복구를 추가했습니다.
  - `SELECT ... FOR UPDATE SKIP LOCKED`로 실행 가능한 Job을 짧은 트랜잭션 안에서 선점
  - 고정 크기 Executor와 Semaphore로 인스턴스별 동시 처리 수 제한
  - `workerId`, `claimToken`, `leaseUntil`을 이용해 현재 Worker의 결과만 DB에 반영
  - 만료된 `PROCESSING` Job을 `QUEUED`로 회수하고 이전 Worker의 늦은 완료 결과 차단
  - Worker 선점과 Lease 복구를 감사 이벤트로 기록
- Flyway V3에 선점 세대를 구분하는 `claim_token`, V4에 만료 Lease 조회 인덱스를 추가했습니다.
- 자동 Worker 처리, MySQL 다중 Worker 작업 분배, Lease 만료 복구와 stale Worker 차단 통합 테스트를 추가했습니다.
- `ExtractionJobStatus`에 각 기술 처리 상태의 한글 `description`을 추가해 대기·처리·재시도·완료·실패의 의미를 코드에서 바로 확인할 수 있게 했습니다.
- 업로드 접수와 외부 AI 처리를 분리하는 MySQL 기반 내구성 작업 큐를 추가했습니다.
  - Flyway V2로 `receipt_extraction_jobs` 테이블과 `receipt_id` Unique Constraint 추가
  - 기술 상태 `QUEUED`, `PROCESSING`, `RETRY_WAIT`, `COMPLETED`, `FAILED`를 영수증 업무 상태와 분리
  - Receipt, ExtractionJob, IdempotencyRecord, UPLOADED 감사 이벤트를 하나의 DB 트랜잭션으로 저장
  - 이미지 품질 검사·추출·검증을 후속 실행하는 `ReceiptExtractionProcessor`와 트랜잭션 수명주기 서비스 추가
- 비동기 처리 시 이미지 바이트를 다시 읽을 수 있도록 `ReceiptImageStorage` 인터페이스를 추가했습니다.
  - 로컬 실행은 `runtime/receipt-images`에 임시 파일 작성 후 원자적 이동
  - test 프로필은 메모리 구현을 사용해 실제 영수증이나 파일 잔여물 없이 검증
- 최초 접수 결과 전용 `ReceiptAcceptedResponse`를 추가했습니다.
  - `receiptId`, `jobId`, `jobStatus`, `acceptedAt` 반환
- 동일 이미지 동시 업로드에서 외부 AI 호출이 중복되는 현재 장애를 수치로 재현하는 특성화 테스트를 추가했습니다.
  - `CountingReceiptExtractor`로 추출기 실행 횟수 측정
  - `DelayedReceiptExtractor`로 100건이 외부 추출 구간에 함께 진입하는 상황을 결정적으로 재현
  - 개선 전 기준값: 동시 요청 100건, Receipt 1건, 추출 호출 100회, 테스트 구간 499ms
  - 실제 API 키와 실제 영수증 없이 결정론적 Fake 추출기로 실행
- 외부 AI 장애와 동시 요청에도 영수증을 유실하지 않는 시스템으로 고도화하기 위한 `RESILIENCE_ROADMAP.md`를 추가했습니다.
  - AI 중복 호출 재현부터 MySQL 내구성 작업 큐, Worker Lease 복구, 오류 분류와 장애 격리까지 1~6단계로 구성
  - 회사 정책 DB화와 Redis Cache-Aside는 운영 필요가 생길 때 진행할 선택 과제로 분리
  - 각 단계의 구현 범위, 완료 조건, 권장 테스트와 커밋 메시지를 명시
- IntelliJ HTTP Client에서 Fake 추출기의 전체 업무 흐름을 순서대로 실행할 수 있도록 `http/receipt-api.http`를 추가했습니다.
  - 헬스 체크, 합성 이미지 업로드, 조회, 감사 로그 확인
  - 같은 멱등성 키 재전송과 동일 이미지 중복 제출 검증
  - 응답의 ID와 낙관적 잠금 버전을 자동 연결한 필드 수정 및 승인
- 실제 영수증이나 개인정보 없이 Fake 및 OpenAI 추출 흐름을 시험할 수 있도록 `samples/synthetic-receipt.png` 합성 샘플을 추가했습니다.
  - 평일 거래일, 상호, 두 개 품목과 일치하는 총액, 카드 결제수단 포함
  - 실제 사업자번호, 주소, 전화번호, 카드번호, 승인번호는 포함하지 않음
- `ReceiptStatus`에 각 처리 상태의 한글 `description`을 추가하고 기존 인라인 주석을 제거했습니다.
- `AuditAction`에 각 감사 행위의 한글 `description`을 추가해 코드와 향후 API·관리자 화면에서 의미를 재사용할 수 있도록 했습니다.
- 누락되어 있던 `REVIEW_REJECTED` 감사 행위를 복원했습니다.
- Java 17, Spring Boot 3.3, Gradle 8 기반 프로젝트 골격
- MySQL 8.4, Spring Data JPA, Flyway 기반 영속화
- `receipts`, `audit_events`, `idempotency_records` 초기 스키마
- 단일 영수증 이미지 업로드 API
- 이미지 디코딩 및 최소 해상도 품질 검사
- `ReceiptExtractor` 인터페이스와 결정론적 Fake 추출기
- OpenAI Responses API 이미지 입력 및 strict JSON Schema 기반 추출 어댑터
- 필수 필드, 날짜, 금액, 품목 합계, 사업자등록번호 체크섬, 중복 제출 검증
- 경비 한도, 주말 사용, 금지 업종 키워드 정책 검사
- 안전 상태 라우팅과 사람 검수 필드 수정·승인·반려 API
- 원본 추출값, 수정 전후 값, 규칙 결과, 상태 변경을 보존하는 감사 로그
- `Idempotency-Key` 기반 재요청 결과 재사용
- JPA `@Version` 기반 검수자 수정 충돌 감지 및 `409 Conflict` 응답
- Spring Boot Actuator, Micrometer, Prometheus 레지스트리
- Docker Compose MySQL 개발 환경
- H2 기반 빠른 통합 테스트와 Testcontainers MySQL 8.4 검증

### Changed

- 처리 완료된 동일 이미지 재제출은 `duplicateDetected`를 확인해 Redis 락을 다시 기다리지 않고 기존 Receipt와 Job을 반환하도록 개선했습니다.
- Worker가 작업을 완료하면 다음 1초 polling을 기다리지 않고 빈 슬롯에 후속 Job 한 건을 즉시 보충하도록 변경했습니다.
  - Executor 대기열은 Worker 간 짧은 인계 용도로만 `concurrency` 크기만큼 허용
  - Semaphore가 실행 중·대기 중 작업 합계를 설정된 동시성 이하로 제한
- 이력서용 MVP 범위를 장애 복구와 관측성까지로 확정하고, 현재 동작에 필요하지 않은 규칙 심각도·품목 정책·합성 AI 평가 코드를 제거했습니다.
- 호출마다 DB 행을 누적하던 미커밋 `ExtractionAttempt` 설계를 제거했습니다.
  - 엔티티·Repository·조회 API·Flyway V5·OpenAI 토큰/비용 추정 제거
  - 개별 재시도와 최종 실패는 기존 감사 로그에서 추적
  - P95/P99는 상시 애플리케이션 Histogram 대신 필요할 때 실행하는 k6에서 측정
- 5단계 Redis 제거는 보류하고 현재 Redisson 분산 락을 1차 직렬화 수단으로 유지하기로 결정했습니다.
  - MySQL Unique Constraint는 최종 데이터 정합성 방어선으로 계속 유지
  - Redis 장애 시 신규 접수가 실패할 수 있는 한계를 수용된 위험으로 문서화
  - 실제 장애 또는 Redis와 독립적인 접수 가용성 목표가 생기면 MySQL 폴백 구현을 재개
- OpenAI HTTP 호출에 연결 제한 시간 3초와 응답 제한 시간 30초를 적용하고 환경변수로 조정할 수 있게 했습니다.
- OpenAI 응답 본문이나 공급자 오류 본문은 감사 로그에 남기지 않고 분류 코드와 HTTP 상태만 기록하도록 변경했습니다.
- 기존 Worker의 고정 크기 Executor와 Semaphore를 외부 AI Bulkhead로 유지해 인스턴스별 동시 호출 수를 제한했습니다.
- OpenAI 응답 제한 시간이 Worker Lease 이상이면 중복 회수 위험이 있으므로 애플리케이션 시작 시 설정 오류로 차단합니다.
- `ReceiptExtractionProcessor`가 직접 Job을 시작하는 구조에서, 선점 서비스가 발급한 `ClaimedReceiptJob`만 처리하는 구조로 변경했습니다.
  - 선점 트랜잭션 커밋 후 외부 AI를 호출해 장시간 DB Row Lock을 보유하지 않음
  - 추출 완료 시점에 현재 Job의 중복 감지 값을 다시 읽어 검증 결과에 반영
- Worker polling 간격, Lease, batch, concurrency와 Worker ID를 환경변수로 설정할 수 있게 했습니다.
- 신규 업로드 API를 동기 `201 Created`에서 비동기 접수 `202 Accepted`로 변경했습니다.
  - 업로드 요청 경로에서 이미지 품질 검사와 Fake/OpenAI 호출 제거
  - 처리 전 영수증 업무 상태는 `null`, 기술 처리 상태는 `jobStatus=QUEUED`로 명시
  - 영수증 조회 응답에 업무 상태와 분리된 `jobStatus` 추가
  - 같은 멱등성 키나 중복 이미지는 기존 Receipt와 Job을 `200 OK`로 반환
- IntelliJ HTTP Client 요청을 2단계의 `202 Accepted`와 `QUEUED` 접수 확인 흐름으로 변경했습니다.
- IntelliJ HTTP Client 요청 파일을 실제 OpenAI 영수증 테스트 흐름으로 정리했습니다.
  - Git 제외 경로의 `uploads/local/receipt1.jpeg`, `receipt2.jpeg`를 각각 업로드
  - 영수증별 ID를 자동 저장해 조회와 감사 로그 요청에 연결
  - 서로 다른 멱등성 키를 사용해 두 영수증을 독립적으로 처리
- 로컬 MySQL의 기본 포트 `3306`과 Docker MySQL의 포트 충돌을 피하기 위해 Docker 호스트 포트를 `3307`로 변경했습니다.
  - Docker 포트 매핑: `3307:3306`
  - 애플리케이션 기본 `DB_URL`과 `.env.example`을 `localhost:3307`로 통일
  - 컨테이너 내부 MySQL 포트는 기존과 동일한 `3306` 유지
- 프로젝트 Java 기준 버전을 21에서 17로 변경했습니다.
  - 현재 구현에서 Virtual Thread 등 Java 21 전용 기능을 사용하지 않음
  - Spring Boot 3.3이 지원하는 LTS 기준선과 로컬 개발 환경의 일치성을 우선
  - Java 21 전용 `List.getFirst()` 테스트 코드를 Java 17 호환 `List.get(0)`으로 변경
- 동일 이미지 동시 처리 방식을 MySQL `SELECT ... FOR UPDATE`와 수동 재시도에서 Redisson 분산 락으로 변경했습니다.
  - 락 키: `receipt:duplicate:{companyId}:{imageSha256}`
  - Redis 락 획득 후 MySQL을 다시 조회하여 TOCTOU 경쟁 조건 방지
  - 수동 3회 재시도와 `shortBackoff()` 제거
  - Redis 장애와 별개로 최종 중복 저장을 막기 위한 MySQL Unique Constraint 유지
- Docker Compose와 Testcontainers 환경에 Redis 7.2를 추가했습니다.
- 초기 데이터베이스 선택을 PostgreSQL에서 MySQL 8.4 LTS로 변경했습니다.
  - 이유: 프로젝트 기술 스택 요구사항 변경
  - 반영: MySQL Connector, Flyway MySQL 모듈, MySQL 전용 Docker Compose와 문자셋 설정
- 이미지 중복 검사는 애플리케이션 선조회와 DB Unique Constraint를 함께 사용하도록 구성했습니다.
  - 선조회 목적: 동일 이미지에 대한 불필요한 외부 AI 호출 감소
  - DB 제약 목적: 동시 요청에서도 최종 정합성 보장

### Fixed

- 최초 동일 이미지 요청이 동시에 몰려 Redis 락 대기 시간이 초과되더라도 MySQL에 중복 처리 완료 행이 존재하면 `409` 대신 기존 결과를 반환하도록 보완했습니다.
- Spring 컨텍스트 종료 이벤트에서 Worker polling을 먼저 중단하고 테스트 컨텍스트마다 H2 DB 이름을 격리해, 스키마 종료와 Scheduler 조회가 겹치며 발생하던 종료 시점 오류 로그를 제거했습니다.
- 애플리케이션 컨텍스트 종료 중 Scheduler가 새 DB polling을 시작하지 않도록 Worker 종료 플래그를 추가했습니다.
- 실제 MySQL 동시 업로드 테스트에서 유니크 인덱스 및 중복 상태 갱신 경합으로 발생한 데드락 희생 트랜잭션을 처리했습니다.
  - 증상: 동일 이미지 6건을 동시에 제출할 때 일부 요청이 `CannotAcquireLockException`으로 실패
  - 해결: Redisson 분산 락으로 동일 이미지의 저장과 중복 상태 전이를 직렬화
  - 검증: Testcontainers MySQL 8.4와 Redis 7.2 동시 업로드 테스트에서 영수증 한 건만 생성되고 `NEEDS_REVIEW`로 전환되는지 확인
- MockMvc 테스트 응답을 UTF-8로 읽도록 수정해 한글 상호 검증 오류를 해결했습니다.
- Flyway의 `image_sha256` 타입을 Hibernate 매핑과 일치하는 `VARCHAR(64)`로 수정했습니다.

### Security

- 영수증·회사·Worker처럼 값이 계속 증가하는 식별자를 Prometheus 태그로 사용하지 않습니다.
- 외부 API 응답 본문에 포함될 수 있는 민감정보를 오류 메시지와 감사 로그에 복사하지 않습니다.
- `.env`, 로컬 설정, 키·인증서, 업로드 디렉터리를 Git 제외 대상으로 등록했습니다.
- 업로드 파일명에서 경로 정보를 제거합니다.
- 원본 이미지 바이트를 DB와 감사 로그에 저장하지 않습니다. 비동기 처리용 로컬 파일은 Git 제외 경로에만 저장합니다.
- 로컬 이미지 저장 키에는 회사 식별자 원문 대신 해시를 사용하고 경로 이탈을 차단합니다.
- OpenAI 키가 없거나 추출이 실패하면 자동 승인하지 않고 `MANUAL_ENTRY`로 전환합니다.

### Verification

- 8단계 동일 이미지 Spike 재측정: 200 VU·30초, 55,966건, 1,863.67 req/s, 평균 106.84ms, P95 242.90ms, P99 350.52ms, 실패율 0%
- 개선 전 대비 동일 이미지 처리량 3.25배 증가, P95 70.5% 감소, Receipt 1건·Job 1건·중복 감사 이벤트 1건 수렴
- 8단계 고유 이미지 200건 재측정: 563.42 req/s, P95 271.09ms, P99 292.65ms, 실패율 0%
- 고유 Job 200건 전체 완료 시간 51.237초에서 1.5757초로 96.9% 감소, COMPLETED·AUTO_APPROVED 각각 200건과 감사 이벤트 800건 확인
- Temurin JDK 17에서 `./gradlew test --rerun-tasks`: 전체 테스트 통과, 실패 0건, 건너뜀 0건
- Docker Prometheus target `receipt-expense-review` 상태 `UP`, scrape 오류 없음
- k6 Smoke: 5 VU·10초, 7,179건, 716.04 req/s, P95 20.31ms, P99 31.88ms, 실패율 0%
- k6 Normal: 20 VU·30초, 24,682건, 822.10 req/s, P95 92.08ms, P99 162.45ms, 실패율 0%
- k6 Heavy: 50 VU·60초, 48,103건, 800.86 req/s, P95 271.64ms, P99 451.76ms, 실패율 0%
- k6 Stress: 100 VU·60초, 34,173건, 568.18 req/s, P95 398.08ms, P99 578.45ms, 실패율 0%
- k6 Spike: 200 VU·30초, 17,368건, 573.60 req/s, P95 824.38ms, P99 1.21초, 실패율 0%
- 100→200 VU에서 처리량은 약 570 req/s로 정체되고 P95가 약 2.1배 증가했으며, 같은 구간 애플리케이션 CPU 최대 12.82%, HikariCP 활성 1/10·대기 0, 미완료 Job 0 확인
- 고부하 이후에도 DB에서 Receipt 1건, ExtractionJob 1건, Job COMPLETED로 수렴
- 고유 이미지 200건 동시 접수: 약 0.5초, 419.16 req/s, P95 409.82ms, P99 447.19ms, 실패율 0%
- 고유 Receipt·Job 각각 200건, 최대 미완료 Job 188건, 51.237초 후 COMPLETED·AUTO_APPROVED 각각 200건, 실패·유실·중복 0건 확인
- 다섯 프로필 누적 131,505건에서 프로세스 CPU 최대 13.97%, HikariCP 활성 커넥션 최대 1, 미완료 Job 0 확인
- Docker Compose k6 Heavy 재측정: 38,782건, 645.63 req/s, P95 170.43ms, P99 257.52ms, 실패율 0%
- 같은 구간 MySQL CPU 최대 15.55%, 실행 스레드 최대 3, HikariCP 활성 최대 1/10·대기 0, 행 잠금 대기·Slow Query 0, 물리 Buffer Pool 읽기 증가 0 확인
- Grafana health 정상, Prometheus 데이터 소스와 `영수증 API · DB 부하` 대시보드 프로비저닝 확인
- DB에서 Receipt 1건, ExtractionJob 1건, Job `COMPLETED`로 수렴
- Temurin JDK 17에서 `./gradlew test --rerun-tasks`: 24건 통과, 실패 0건, 건너뜀 0건
- Worker concurrency를 1로 설정한 두 Job 처리에서 실제 외부 추출 구간의 최대 동시 호출이 1인지 검증
- 추출 오류 1회 후 `RETRY_WAIT`에서 10초 뒤 재선점해 `COMPLETED`로 복구하고 추출기를 총 2회 호출
- 추출 오류가 반복되면 총 3회 시도 후 `EXTRACTION_FAILED`, Job `FAILED`, Receipt `MANUAL_ENTRY`로 종료
- 로컬 가짜 HTTP 서버의 429·503·401·지연 응답을 모두 안전한 `ExtractionException`으로 변환
- Temurin JDK 17에서 `./gradlew test --rerun-tasks`: 17건 통과, 실패 0건, 건너뜀 0건
- 자동 Scheduler Worker가 Job 1건을 `QUEUED → PROCESSING → COMPLETED`로 처리하고 Fake 추출기를 1회만 호출
- Testcontainers MySQL 8.4에서 두 Worker가 6개 Job을 반복 polling해 중복 없이 전부 선점
- Lease 만료 Job을 새 Worker가 복구·완료하고 만료된 `claimToken`의 결과 반영을 거부
- 동일 이미지 100건 동시 접수 결과: Receipt 1건, ExtractionJob 1건, 업로드 구간 추출 호출 0회, 124ms
- 단일 Job 후속 처리 결과: 추출기 호출 1회 및 기존 검증·감사 흐름 정상 완료
- Testcontainers MySQL 8.4에서 Flyway V2 스키마 검증과 6건 동시 접수의 Receipt/Job 단일 수렴 확인
- `./gradlew test --tests com.example.receipt.extraction.ConcurrentExtractionCharacterizationTest --info`
  - `CONCURRENT_EXTRACTION_BASELINE requests=100 receipts=1 extractionCalls=100 elapsedMillis=499`
- Temurin JDK 17에서 `./gradlew test`
- 단위·API 통합·동시성 테스트 전체 통과
- Testcontainers MySQL 8.4에서 Flyway 스키마 검증 및 실제 동시 제출 테스트 통과

## 향후 기록 예정

- Redis 정책 캐시 및 무효화 전략
- 객체 스토리지 암호화와 보존 기간 정책
- 인증·인가 및 회사별 경비 규정 관리
- 흐림·잘림·반사광 품질 검사
