# 변경 기록

이 문서는 프로젝트의 의미 있는 변경사항을 누적해서 기록합니다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)의 `Added`, `Changed`, `Fixed`, `Security` 분류를 참고합니다.

아직 배포하지 않은 작업은 `Unreleased`에 먼저 기록하고, 릴리스할 때 날짜가 있는 버전 항목으로 이동합니다. DB 마이그레이션, API 계약, 상태 전이, 동시성·정합성, 보안, 외부 AI 연동의 변경에는 변경 이유와 검증 방법도 함께 남깁니다.

## Unreleased

### Simplified

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

- `ReceiptStatus`에 각 처리 상태의 한글 `description`을 추가하고 기존 인라인 주석을 제거했습니다.
- `AuditAction`에 각 감사 행위의 한글 `description`을 추가해 코드와 향후 API·관리자 화면에서 의미를 재사용할 수 있도록 했습니다.
- 누락되어 있던 `REVIEW_REJECTED` 감사 행위를 복원했습니다.
- Java 21, Spring Boot 3.3, Gradle 8 기반 프로젝트 골격
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

- 실제 MySQL 동시 업로드 테스트에서 유니크 인덱스 및 중복 상태 갱신 경합으로 발생한 데드락 희생 트랜잭션을 처리했습니다.
  - 증상: 동일 이미지 6건을 동시에 제출할 때 일부 요청이 `CannotAcquireLockException`으로 실패
  - 해결: Redisson 분산 락으로 동일 이미지의 저장과 중복 상태 전이를 직렬화
  - 검증: Testcontainers MySQL 8.4와 Redis 7.2 동시 업로드 테스트에서 영수증 한 건만 생성되고 `NEEDS_REVIEW`로 전환되는지 확인
- MockMvc 테스트 응답을 UTF-8로 읽도록 수정해 한글 상호 검증 오류를 해결했습니다.
- Flyway의 `image_sha256` 타입을 Hibernate 매핑과 일치하는 `VARCHAR(64)`로 수정했습니다.

### Security

- `.env`, 로컬 설정, 키·인증서, 업로드 디렉터리를 Git 제외 대상으로 등록했습니다.
- 업로드 파일명에서 경로 정보를 제거합니다.
- 원본 이미지 바이트를 DB와 감사 로그에 저장하지 않습니다.
- OpenAI 키가 없거나 추출이 실패하면 자동 승인하지 않고 `MANUAL_ENTRY`로 전환합니다.

### Verification

- `./gradlew test`
- 단위·API 통합·동시성 테스트 전체 통과
- Testcontainers MySQL 8.4에서 Flyway 스키마 검증 및 실제 동시 제출 테스트 통과

## 향후 기록 예정

- Redis 정책 캐시 및 무효화 전략
- 작업 큐와 Transactional Outbox 기반 비동기 추출
- 객체 스토리지 암호화와 보존 기간 정책
- 인증·인가 및 회사별 경비 규정 관리
- 흐림·잘림·반사광 품질 검사
- 운영 지표와 모델 비용 측정
