# 장애 대응 고도화 로드맵

## 1. 목표

이 문서는 AI 영수증·경비 검수 시스템을 기능 중심 MVP에서 다음 수준의 백엔드 시스템으로 고도화하기 위한 작업 순서를 기록합니다.

> AI 오류를 감지하고, 외부 AI 또는 애플리케이션 장애에도 접수한 영수증을 유실하지 않으며, 복구 결과와 성능을 수치로 증명한다.

장애가 전혀 발생하지 않게 만드는 것이 아니라 다음을 보장하는 것이 목표입니다.

- 접수 완료 응답을 받은 영수증은 DB에 남는다.
- 정상적인 동시 요청에서는 동일 이미지의 AI 호출이 한 번으로 수렴한다.
- 외부 AI 호출은 장애 상황에서 `at-least-once`일 수 있지만 DB 상태 반영은 멱등하게 처리한다.
- Worker가 중단되어도 미완료 작업을 다시 처리한다.
- Redis 분산 락을 유지하는 현재 구조의 장애 경계와 후속 개선 조건을 명확히 기록한다.
- 오류 원인과 재시도를 감사 로그로 추적하고 처리량·지연·적체를 외부 도구로 측정할 수 있다.
- AI는 값을 제안할 뿐 자동 승인 여부는 결정론적 규칙이 판단한다.

## 2. 현재 확인된 장애 위험

### 2.1 동시 요청의 AI 중복 호출

현재 업로드 흐름은 DB 중복 선조회 후 OpenAI를 호출하고, 저장 직전에 Redis 락을 획득합니다. 같은 새 이미지가 동시에 들어오면 DB 레코드는 Unique Constraint로 한 건만 저장되더라도 외부 AI는 요청 수만큼 호출될 수 있습니다.

### 2.2 외부 AI 성공과 DB 저장 사이의 유실 구간

OpenAI 호출 이후 Receipt와 감사 이벤트를 저장하므로, AI 호출 성공 직후 애플리케이션이 종료되면 접수 기록과 추출 결과는 남지 않고 비용만 발생할 수 있습니다.

### 2.3 동기 외부 호출에 의한 요청 스레드 점유

실제 OpenAI 테스트는 건당 약 6~7초가 걸렸습니다. 외부 API가 느려지면 업로드 요청 스레드가 장시간 대기하여 조회와 검수 같은 다른 API까지 영향을 받을 수 있습니다.

### 2.4 단순 재시도 정책의 비용

429, 5xx, 네트워크 타임아웃, 인증 실패, 요청 오류와 파싱 실패를 현재는 모두 같은 추출 실패로 처리합니다. 구현과 설명은 단순하지만 401이나 잘못된 요청도 최대 3회 호출될 수 있으므로, 운영 지표에서 불필요한 비용이 확인되면 오류별 정책을 추가해야 합니다.

### 2.5 Redis가 필수 처리 경로에 존재

현재 Redis 락 획득에 실패하면 MySQL Unique Constraint를 이용한 최종 정합성 처리까지 도달하지 못할 수 있습니다. Redis는 성능과 경합 감소를 위한 보조 수단이어야 하며 접수 내구성의 필수 조건이 되어서는 안 됩니다.

## 3. 목표 처리 구조

```text
이미지 업로드
  → 이미지 해시 계산
  → Receipt + IdempotencyRecord + ExtractionJob + UPLOADED 감사 이벤트 저장
  → 202 Accepted 반환
  → Worker가 ExtractionJob 선점
  → OpenAI 호출
  → 결정론적 검증
  → Receipt와 Job 상태 및 감사 이벤트 갱신
```

초기 비동기화에서는 Kafka를 도입하지 않습니다. `receipt_extraction_jobs`를 MySQL 기반 내구성 작업 큐로 사용합니다. 다른 서비스나 메시지 브로커로 이벤트를 전달해야 할 요구가 생길 때 Transactional Outbox를 추가합니다.

영수증 업무 상태와 기술 작업 상태는 분리합니다.

- 영수증 상태: 처리 전에는 `null`, 처리 후에는 `AUTO_APPROVED`, `NEEDS_REVIEW`, `NEEDS_RECAPTURE`, `MANUAL_ENTRY`, `UNREADABLE`, `APPROVED`, `REJECTED`
- 추출 작업 상태: `QUEUED`, `PROCESSING`, `RETRY_WAIT`, `COMPLETED`, `FAILED`

## 4. 단계별 구현 계획

진행 상태 표기:

- `[ ]`: 시작 전
- `[~]`: 진행 중
- `[x]`: 완료

### 1단계. AI 중복 호출 장애 재현

상태: `[x]` (2026-08-23 완료)

구현 범위:

- 호출 횟수를 기록하는 `CountingReceiptExtractor` 테스트 대역 추가
- 동일 이미지 100건 동시 업로드 테스트 추가
- 영수증 수, 추출 작업 수, 추출기 호출 횟수를 각각 검증
- 응답 지연을 주입할 수 있는 `DelayedReceiptExtractor` 추가
- 현재 구조에서 발생하는 중복 호출 수와 처리 시간을 기준값으로 기록

완료 조건:

- [x] 개선 전 테스트에서 DB 영수증은 한 건이지만 추출기가 여러 번 호출되는 현상을 재현한다.
- [x] 재현 조건, 호출 횟수, 실행 시간을 이 문서에 기록한다.
- [x] 실제 OpenAI 키와 실제 영수증 없이 테스트할 수 있다.

측정 결과:

```text
측정일: 2026-08-23
환경: Temurin JDK 17, Spring Boot test 프로필, H2, 결정론적 Fake 추출기
동시 요청: 100건
생성된 Receipt: 1건
추출기 호출: 100회
테스트 구간 소요 시간: 499ms
```

실행 명령:

```bash
./gradlew test --tests com.example.receipt.extraction.ConcurrentExtractionCharacterizationTest --info
```

위 499ms와 100회 호출은 2단계 적용 전 기준값입니다. 현재 같은 테스트는 회귀 방지 테스트로 전환되어 Receipt 1건, Job 1건, 업로드 구간 추출 호출 0회를 검증합니다.

해석:

- MySQL Unique Constraint와 애플리케이션 락으로 최종 Receipt는 한 건에 수렴한다.
- 최초 Receipt가 저장되기 전에 100개 요청이 모두 추출 단계로 진입하여 추출기가 100번 호출됐다.
- 실제 OpenAI 환경이라면 동일 이미지 한 장이 정상적인 동시 요청만으로 최대 100회의 API 비용과 Rate Limit을 소비할 수 있다.
- 2단계에서는 Receipt와 ExtractionJob을 AI 호출 전에 원자적으로 생성하여 승리한 한 요청만 추출 작업을 갖도록 변경한다.

권장 커밋 메시지:

```text
test: 동시 업로드 시 AI 중복 호출 문제 재현
```

### 2단계. 업로드 접수와 AI 처리 분리

상태: `[x]` (2026-08-23 완료)

구현 범위:

- Flyway로 `receipt_extraction_jobs` 테이블 추가
- Receipt, IdempotencyRecord, ExtractionJob, UPLOADED 감사 이벤트를 한 트랜잭션으로 저장
- 외부 AI 호출을 업로드 요청 경로에서 제거
- 업로드 API를 `202 Accepted`와 Receipt ID 반환 방식으로 변경
- 중복 이미지와 동일 멱등성 키 요청은 기존 Receipt를 반환
- Receipt 생성에 성공한 요청만 ExtractionJob을 생성하도록 DB Unique Constraint 구성
- 업로드 종료 후에도 처리할 수 있도록 `ReceiptImageStorage` 포트와 로컬 개발용 원자적 파일 저장 구현 추가
- 품질 검사·추출·검증을 실행하는 `ReceiptExtractionProcessor` 경계 추가

`receipt_extraction_jobs` 기본 필드:

```text
id, version, receipt_id, status, image_storage_key, duplicate_detected,
attempt_count, available_at,
started_at, completed_at, locked_by, lease_until,
last_error_code, last_error_message, created_at, updated_at
```

완료 조건:

- [x] 업로드 API가 OpenAI 응답을 기다리지 않고 접수 결과를 반환한다.
- [x] `202 Accepted` 응답 이후 Receipt와 ExtractionJob이 반드시 DB에 존재한다.
- [x] 동일 이미지 100건 요청에서 Receipt와 ExtractionJob이 각각 한 건만 생성된다.
- [x] 업로드 요청 구간의 추출기 호출이 0회인지 검증한다.
- [x] Testcontainers MySQL 8.4에서 V2 Flyway 스키마와 동시 접수를 검증한다.

측정 결과:

```text
측정일: 2026-08-23
환경: Temurin JDK 17, Spring Boot test 프로필, H2, 결정론적 Fake 추출기
동시 요청: 100건
생성된 Receipt: 1건
생성된 ExtractionJob: 1건
업로드 요청 구간 추출기 호출: 0회
테스트 구간 소요 시간: 124ms
단일 Job 후속 처리 시 추출기 호출: 1회
```

현재 경계:

- 이미지 파일은 DB 트랜잭션 전에 원자적 파일 이동으로 저장하며, DB 레코드 네 종류만 하나의 트랜잭션으로 커밋한다.
- DB 저장에 실패한 이미지의 고아 파일 정리는 운영 객체 스토리지 전환 시 보완한다.
- 2단계의 `ReceiptExtractionProcessor`는 단건 처리 기능만 제공한다. 자동 선점, Lease, 재시작 복구는 3단계 범위다.

권장 커밋 메시지:

```text
feat: 영수증 접수와 AI 추출 작업을 원자적으로 저장
```

### 3단계. 다중 Worker와 중단 작업 복구

상태: `[x]` (2026-08-24 완료)

구현 범위:

- `SELECT ... FOR UPDATE SKIP LOCKED` 기반 작업 선점
- `locked_by`, `lease_until`을 이용한 Lease 처리
- `QUEUED → PROCESSING → COMPLETED` 정상 상태 전이
- Lease가 만료된 `PROCESSING` 작업 회수
- 완료된 작업의 결과가 중복 반영되지 않도록 멱등 갱신
- 두 개 이상의 Worker가 동시에 동작하는 Testcontainers 통합 테스트

구현 상세:

- Scheduler가 polling할 때 인스턴스의 빈 Executor 슬롯 수만큼만 Job을 선점
- 고정 크기 Executor와 Semaphore로 외부 AI 동시 처리량 제한
- 선점 트랜잭션과 외부 AI 호출을 분리해 DB Row Lock 보유 시간을 최소화
- 선점마다 고유 `claim_token`을 발급하고 `worker_id`, `lease_until`과 함께 소유권으로 검증
- Lease 만료 Job을 `PROCESSING → QUEUED`로 회수하고 `EXTRACTION_JOB_RECOVERED` 감사 이벤트 기록
- 이전 Worker의 늦은 완료는 만료된 claim token으로 판별해 Receipt와 Job에 반영하지 않음
- 실행 가능 Job용 `(status, available_at, id)` 인덱스와 만료 복구용 `(status, lease_until, id)` 인덱스 적용

완료 조건:

- [x] 동일 Job을 두 Worker가 동시에 완료 처리하지 않는다.
- [x] Worker 강제 종료 상황을 재현해 Lease 만료 시 다른 Worker가 작업을 복구한다.
- [x] 새 Worker가 복구 Job을 완료하고 이전 Worker의 늦은 완료 결과는 거부한다.

검증 결과:

```text
측정일: 2026-08-24
환경: Temurin JDK 17, Spring Boot 3.3, Testcontainers MySQL 8.4 / Redis 7.2
자동 Worker: QUEUED Job 1건 → COMPLETED, Fake 추출기 호출 1회
다중 Worker: 서로 다른 Job 6건 → 중복 선점 0건, 전체 6건 PROCESSING
Lease 복구: 만료 Job 1건 회수 → 새 Worker 완료, 이전 claimToken 결과 차단
전체 테스트: 17건 통과, 실패 0건, 건너뜀 0건
```

현재 보장 경계:

- 외부 AI 호출 자체는 Worker 종료와 Lease 만료가 겹치면 `at-least-once`로 중복될 수 있다.
- Receipt와 Job의 DB 결과 반영은 현재 `claimToken` 소유권을 검증해 한 번만 허용한다.
- 처리 시간이 Lease를 넘지 않도록 기본 60초를 사용하며, heartbeat와 재시도 정책은 4단계에서 보강한다.

권장 커밋 메시지:

```text
feat: Lease 기반 비동기 추출 Worker와 작업 복구 구현
```

### 4단계. OpenAI Timeout과 단순 재시도

상태: `[x]` (2026-08-26 완료)

구현 범위:

- 연결 및 응답 타임아웃 설정
- 이해하기 쉬운 고정 지연 재시도 적용
- 최대 시도 횟수와 `RETRY_WAIT`의 `available_at` 계산
- 3단계의 고정 크기 Executor와 Semaphore Bulkhead로 OpenAI 동시 호출 수 제한
- 최대 재시도 초과 시 Receipt를 `MANUAL_ENTRY`, Job을 `FAILED`로 전환

기본 재시도 정책:

| 조건 | 처리 |
|---|---|
| `ExtractionException` 발생, 시도 횟수 3회 미만 | 10초 뒤 재시도 |
| 세 번째 `ExtractionException` | Job `FAILED`, Receipt `MANUAL_ENTRY` |
| 이미지 저장소 오류 | 즉시 Job `FAILED`, Receipt `MANUAL_ENTRY` |

완료 조건:

- [x] 추출 실패 후 10초가 지나면 Job을 다시 선점해 성공할 수 있다.
- [x] 추출이 세 번 연속 실패하면 사람이 입력하도록 안전하게 전환한다.
- [x] OpenAI가 느려져도 업로드·조회·검수 API의 요청 스레드와 처리량이 보호된다.

구현 상세:

- OpenAI 연결 제한 시간 3초, 응답 제한 시간 30초를 기본값으로 적용
- `RETRY_WAIT.available_at`에 다음 실행 시각을 저장해 Worker 스레드를 잠재우지 않는 내구성 재시도 구현
- 모든 `ExtractionException`에 기본 최대 3회, 10초 고정 지연 적용
- Processor가 `attemptCount < 3`만 확인해 재시도 또는 최종 실패를 결정
- 세 번째 실패는 Job을 `FAILED`, Receipt를 `MANUAL_ENTRY`로 안전 전환
- 재시도 예약과 최종 실패를 감사 이벤트에 기록
- Worker Lease가 OpenAI 응답 제한 시간보다 짧거나 같으면 시작 시 설정 오류로 차단
- 기존 Executor와 Semaphore를 Bulkhead로 재사용해 인스턴스별 외부 호출 수를 `receipt.worker.concurrency` 이하로 제한

단순화의 의도와 한계:

- 처음 만든 복잡한 오류 분류와 Circuit Breaker는 제거했습니다. 현재 규모에서 직접 설명하고 유지할 수 있는 구조를 우선했습니다.
- 401·잘못된 요청처럼 재시도로 회복되지 않는 오류도 최대 3회 호출될 수 있습니다.
- 운영 지표에서 공급자 연쇄 장애나 불필요한 재시도 비용이 실제 문제로 확인되면 오류 분류와 Circuit Breaker를 다음 개선으로 도입합니다.

검증 결과:

```text
측정일: 2026-08-25
환경: Temurin JDK 17, Spring Boot 3.3, H2 및 로컬 가짜 HTTP 서버
추출 실패 1회 후 성공: RETRY_WAIT → COMPLETED, 추출기 호출 2회
추출 실패 반복: 총 3회 후 EXTRACTION_FAILED, FAILED + MANUAL_ENTRY
OpenAI HTTP 오류와 지연 응답: ExtractionException으로 안전하게 변환
전체 테스트: 24건 통과, 실패 0건, 건너뜀 0건
```

권장 커밋 메시지:

```text
feat: OpenAI 타임아웃과 재시도 및 장애 격리 적용
```

### 5단계. Redis 분산 락 유지와 장애 위험 기록

상태: `[~]` (2026-08-25 구현 보류, 설계 결정 완료)

결정 내용:

- 현재 Redisson 분산 락을 동일 이미지 요청의 1차 직렬화 수단으로 유지한다.
- `(company_id, image_sha256)`, `(company_id, idempotency_key)`, `receipt_extraction_jobs.receipt_id`의 MySQL Unique Constraint를 최종 데이터 정합성 방어선으로 유지한다.
- Redis 장애 시 신규 접수가 실패할 수 있다는 사실을 수용된 위험으로 README와 이 문서에 명시한다.
- 현 단계에서는 Redis 제거를 위한 예외 처리와 동시 Insert 충돌 복구 코드를 추가하지 않는다.
- 실제 운영에서 Redis 장애가 관측되거나 접수 가용성 목표가 정해지면 MySQL 폴백을 별도 단계로 재개한다.

보류 이유:

- 현재 규모의 사내 경비 시스템에서는 Redisson으로 경합을 줄이는 단순한 흐름이 설명과 운영에 적합하다.
- Redis 제거는 동시 Insert의 데드락·유니크 충돌을 정상 결과로 복구하는 추가 복잡성을 만든다.
- 아직 실제 Redis 장애 지표가 없으므로 위험과 복잡성의 교환을 문서화하고 다음 단계로 진행한다.

후속 재개 조건: Redis 장애로 업로드 실패가 발생하거나, 영수증 접수 API에 Redis와 독립적인 가용성 목표가 요구될 때.

### 6단계. 관측 가능성과 장애 검증 수치화

상태: `[x]` (2026-08-25 완료)

구현 범위:

- DB 적재 없는 최소 Micrometer Counter·Gauge 추가
- Docker Prometheus 수집 구성
- 재시도, 최종 실패, Worker Lease 복구 테스트
- k6 Smoke·Normal·Heavy 부하 테스트 스크립트 추가

주요 지표:

- k6 업로드 API P50/P95/P99
- 추출 성공·재시도·최종 실패 횟수
- 처리 성공률과 최종 실패율
- 중복 AI 호출 차단 건수
- 미완료 Job 수
- Spring 기본 프로세스 CPU·HikariCP·HTTP 요청 지표

완료 조건:

- [x] 동일 이미지 정상 동시 요청에서 Receipt 1건, Job 1건, AI 호출 1회를 확인한다.
- [x] Worker 종료 후 Lease 복구와 미완료 Job Gauge를 검증한다.
- [x] 재시도, 실패, 중복, 적체 지표를 Prometheus에서 확인할 수 있다.
- [x] API P95/P99는 k6로 외부에서 측정한다.
- [x] 측정 결과를 README에 수치로 기록한다.

구현 상세:

- 처음 구현한 `ExtractionAttempt` 엔티티·테이블·조회 API·토큰/비용 기록은 호출마다 DB 행이 누적되는 비용 때문에 제거
- 개별 재시도·실패 원인은 기존 감사 로그로 추적
- `ReceiptMetrics`는 업로드·중복·추출 성공·재시도·최종 실패·Lease 복구 Counter만 유지
- Prometheus scrape 시 MySQL을 한 번 조회하는 미완료 Job Gauge만 유지
- 애플리케이션 Histogram은 제거하고 P95/P99는 필요할 때 실행하는 k6로 측정
- Docker Prometheus가 `/actuator/prometheus`를 5초마다 수집
- 5·20·50·100·200 VU의 다섯 가지 k6 프로필과 65KB 부하 전용 합성 JPEG 추가

측정 결과:

```text
측정일: 2026-08-26
환경: Temurin JDK 17, MySQL 8.4, Redis 7.2, Prometheus 3.5, Fake 추출기, 65KB 합성 JPEG, MacBook Pro
Smoke  5 VU × 10초:  7,179건, 716.04 req/s, P95 20.31ms,  P99 31.88ms,  실패율 0%
Normal 20 VU × 30초: 24,682건, 822.10 req/s, P95 92.08ms,  P99 162.45ms, 실패율 0%
Heavy 50 VU × 60초: 48,103건, 800.86 req/s, P95 271.64ms, P99 451.76ms, 실패율 0%
Stress 100 VU × 60초: 34,173건, 568.18 req/s, P95 398.08ms, P99 578.45ms, 실패율 0%
Spike  200 VU × 30초: 17,368건, 573.60 req/s, P95 824.38ms, P99 1.21s, 실패율 0%
다섯 프로필 누적 요청: 131,505건
Prometheus: 프로세스 CPU 최대 13.97%, HikariCP 활성 커넥션 최대 1, 미완료 Job 0
DB 수렴: Receipt 1건, ExtractionJob 1건, Job COMPLETED
```

이 측정은 중복 이미지 접수 경로의 기준값이며 실제 OpenAI 신규 추출 처리량은 아니다. 100 VU에서 200 VU로 증가해도 처리량은 약 570 req/s로 정체되고 P95는 398.08ms에서 824.38ms로 증가했다. 같은 구간 애플리케이션 CPU 최대 12.82%, HikariCP 활성 최대 1/10·대기 0, 미완료 Job 0이므로 CPU나 DB 풀 포화보다 동일 Redis 락 키의 직렬화가 우선 병목 후보다. 추가 Docker Compose Heavy 측정에서는 50 VU·60초 동안 38,782건, 645.63 req/s, P95 170.43ms, P99 257.52ms, 실패율 0%를 확인했다. 같은 구간 MySQL CPU 최대 15.55%, 실행 스레드 최대 3, HikariCP 활성 최대 1/10·대기 0, 행 잠금 대기·Slow Query 0, 물리 Buffer Pool 읽기 증가 0이었다. Grafana에는 Prometheus 기반 API·HikariCP·Job 적체 대시보드를 자동 프로비저닝했으며, MySQL 내부 지표의 상시 수집은 별도 MySQL Exporter 권한이 필요해 보류했다. 5단계를 보류했으므로 Redis 종료 테스트는 6단계 완료 조건에서 제외했다. 토큰·비용 영속화는 현재 규모에서 운영 필요보다 리소스 비용이 크다고 판단해 제외했다.

별도 200 VU·고유 이미지 200건 테스트에서는 200건을 약 0.5초 안에 모두 접수했고 P95 409.82ms, P99 447.19ms, 실패율 0%를 확인했다. DB에는 고유 Receipt·Job이 각각 200건 저장됐고 최대 미완료 Job은 188건, 최종 완료까지 51.237초가 걸렸다. Fake 추출기가 즉시 응답해도 `batchSize=4`, polling 1초 설정 때문에 소비 속도가 약 4건/초로 제한되는 두 번째 병목 기준선이다.

권장 커밋 메시지:

```text
feat: AI 추출 장애와 성능 지표 및 부하 테스트 추가
```

### 향후 선택 과제. 회사 정책 DB화와 Redis 캐시

상태: `[ ]`

구현 범위:

- 설정 파일의 회사 경비 정책을 `company_policies` 테이블로 이동
- 회사별 정책 버전과 변경 이력 관리
- Redis Cache-Aside 적용
- `policy:{companyId}:{version}` 형식의 버전 키 사용
- TTL 설정과 정책 변경 후 캐시 무효화
- Redis 장애 시 MySQL 폴백
- Cache Stampede 방지와 hit/miss 메트릭 추가
- AI 추출 결과 캐시가 필요하면 이미지 해시, 모델, 프롬프트, 스키마 버전을 키에 포함
- 캐시 적중 여부와 무관하게 결정론적 검증은 항상 다시 실행

완료 조건:

- 회사마다 서로 다른 한도와 금지 정책이 적용된다.
- 정책 변경 후 이전 캐시가 사용되지 않는다.
- Redis 장애 시에도 MySQL 정책으로 동일한 검증 결과를 만든다.
- 캐시 적용 전후 DB 부하와 P95 응답 시간을 비교한다.

권장 커밋 메시지:

```text
feat: 회사 정책 버전 관리와 Redis Cache-Aside 적용
```

### 8단계. 고부하 병목 개선과 재검증

상태: `[x]` (2026-08-26 완료)

목표:

- 6단계 100·200 VU 측정값을 변경 전 기준선으로 고정한다.
- 동일 이미지 중복 경로의 Redis 락 대기와 요청당 DB 왕복 횟수를 병목 후보로 검증한다.
- 기존 Receipt가 확인된 요청은 정합성을 해치지 않는 범위에서 빠른 조회 경로를 적용한다.
- Worker 작업이 끝나 실행 슬롯이 비면 다음 1초 polling을 기다리지 않고 후속 Job을 즉시 선점하도록 개선한다.
- 같은 k6 프로필로 변경 전후 처리량과 P95/P99, 오류율, HikariCP 대기와 CPU를 비교한다.
- k6 결과를 Prometheus Remote Write로 전송해 Grafana에서 개선 전후를 같은 시간축으로 시각화한다.
- 성능 개선 때문에 MySQL Unique Constraint와 감사 로그 정합성이 깨지지 않는지 동시성 테스트로 검증한다.

완료 조건:

- 100·200 VU에서 오류율 1% 미만을 유지한다.
- 변경 전보다 P95 또는 안정 처리량이 수치로 개선된다.
- 고유 이미지 200건의 전체 완료 시간을 51.237초보다 단축한다.
- 동일 이미지 요청이 Receipt 1건과 ExtractionJob 1건으로 수렴한다.
- 개선 근거와 한계를 README에 기록한다.

구현 결과:

- 최초 중복 처리가 끝난 동일 이미지는 `duplicateDetected`를 기준으로 Redis 락을 건너뛰고 기존 결과를 반환
- 최초 동시 요청의 Redis 락 대기 시간 초과 시에도 MySQL에서 완료된 중복 결과가 확인되면 `409` 대신 기존 결과로 수렴
- 중복 표시를 Job의 단일 기준으로 통일해 규칙 재계산, 버전 증가와 감사 이벤트 중복 생성을 차단
- Worker 작업 완료 직후 빈 슬롯에 다음 Job 한 건을 즉시 보충
- Executor의 짧은 인계 대기열과 Semaphore를 함께 사용해 실행 중·대기 중 작업 합계를 `concurrency` 이하로 제한
- 빠른 경로 횟수를 `receipt_upload_duplicate_fast_path_total`로 관측
- 처리량, P95와 200 Job 완료 시간의 전후 비교를 Grafana 대시보드에 추가

측정 결과:

```text
동일 이미지, 200 VU × 30초
처리량: 573.60 → 1,863.67 req/s (3.25배)
P95: 824.38 → 242.90ms (70.5% 감소)
P99: 1.21초 → 350.52ms (약 71.0% 감소)
실패율: 0% → 0%
DB 수렴: Receipt 1건, Job 1건, 중복 감사 이벤트 1건

고유 이미지 200건, 200 VU
접수 처리량: 419.16 → 563.42 req/s
P95: 409.82 → 271.09ms
전체 Job 완료: 51.237 → 1.5757초 (32.5배 단축)
DB 수렴: COMPLETED 200건, AUTO_APPROVED 200건, 감사 이벤트 800건
```

검증:

- [x] 처리 완료된 동일 이미지 100건이 추가 락 호출 0회로 같은 Receipt를 반환한다.
- [x] 중복 감사 이벤트가 최초 한 번만 기록된다.
- [x] 정기 polling을 한 번만 호출해도 Worker가 빈 슬롯을 즉시 보충해 후속 Job을 모두 처리한다.
- [x] 전체 단위·통합 테스트가 통과한다.

## 5. 전체 완료 기준

- [x] 접수 완료된 영수증이 애플리케이션 또는 Worker 종료로 유실되지 않는다.
- [x] 동일 이미지 100건의 정상 동시 요청이 Receipt 1건, Job 1건, AI 호출 1회로 수렴한다.
- [x] 외부 AI 추출 실패가 같은 고정 지연 정책으로 최대 3회 처리된다.
- [x] 재시도와 Worker 동시 호출 제한 동작이 테스트된다.
- [~] Redis 장애 위험을 문서화했으며 MySQL 폴백은 운영 필요가 확인될 때 재개한다.
- [x] Worker 중단 후 Lease 기반으로 작업이 복구된다.
- [x] AI 오류가 자동 승인으로 연결되지 않는다.
- [x] 처리율, 지연 시간, 실패율과 적체를 수치로 확인할 수 있다.
- [x] 모든 DB·API·상태 전이 변경이 `CHANGELOG.md`에 기록된다.
- [x] 실제 영수증, 개인정보와 API 키가 저장소에 커밋되지 않는다.

## 6. 권장 작업 순서와 이력서 서술

작업은 반드시 1단계부터 순서대로 진행합니다. 장애 재현 테스트 없이 아키텍처를 먼저 변경하지 않으며, 각 단계가 끝날 때 테스트 결과와 측정값을 이 문서와 `CHANGELOG.md`에 갱신합니다.

최종 이력서 서술 목표:

> 동시 영수증 업로드에서 외부 AI 중복 호출과 Redis 락 직렬화, 고정 polling에 따른 작업 적체를 재현했습니다. MySQL 내구성 작업 큐와 멱등 Worker, Lease 복구를 적용하고 처리 완료 중복의 락 우회 및 Worker 즉시 보충으로 개선했습니다. 동일 이미지 200 VU 처리량을 3.25배 높이고 P95를 70.5% 낮췄으며, 고유 Job 200건 완료 시간을 51.237초에서 1.5757초로 단축했습니다.
