# Issue #524 기획 계약 설계

- 날짜: 2026-07-18
- 저장소: `bluetape4k/bluetape4k-workshop`
- 분기: `feature/issue-524-planning-contracts`
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/524
- 모듈: `optimization/planning-contracts`
- Gradle 프로젝트: `:optimization-planning-contracts`

## 문제

최적화 참조 애플리케이션에는 재사용 가능한 애플리케이션 소유 경계가 필요합니다.
버전이 지정된 계획 데이터 세트 제출, 비동기 공급자 수신
결과를 확인하고 허용된 결과를 도메인 명령으로 변환합니다. 경계는 반드시
공유 최적화 라이브러리 또는 라이브 Timefold 플랫폼 이전에 유용합니다.
임차인이 존재합니다.

워크샵에는 현재 `optimization/` 모듈 그룹이 없으며 이를 수행하는 예제도 없습니다.
다음을 모두 함께 보여줍니다.

- 공급자 중립적인 `PlanningEngine` 계약;
- PostgreSQL 지원 요청, 받은 편지함, 보낸 편지함 및 감사 상태
- 중복되고 오래되고 순서가 잘못된 콜백 처리
- 최종 명령 전에 집계 버전 재검증;
- 결정론적 오프라인 공급자 설비;
- Java HTTP 및 JDBC 경계를 차단하기 위한 25개의 가상 스레드 실행.

## 현재 증거

- Issue #524은 결정론적 가짜 및 계약 고정물을 명시적으로 요구합니다.
  라이브 공급자 자격 증명 및 공개 웹훅 배포는 선택적 증거입니다.
- 루트 빌드는 Bluetape에 대해 `bluetape4k-dependencies:1.3.1`만 가져옵니다.
  버전 거버넌스. 따라서 게시된 Exposed 모듈은 그대로 유지되어야 합니다.
  버전 없는 별칭은 BOM에 의해 해결됩니다.
- 카탈로그는 이미 `bluetape4k-exposed-core`을 노출하고 있습니다.
  `bluetape4k-exposed-jdbc`, `bluetape4k-exposed-jdbc-tests`,
  `bluetape4k-exposed-jackson3`, `bluetape4k-http`,
  `bluetape4k-jackson3`, `bluetape4k-idgenerators`,
  `bluetape4k-micrometer`, `bluetape4k-virtualthread-api` 및
  `bluetape4k-virtualthread-jdk25`.
- `exposed/mvc-jdbc`은 출시된 인터페이스 기반 저장소 API를 보여줍니다.
  `override val table`, `extractId`, `ResultRow.toEntity`.
- `messaging/kafka-outbox-fallback`은 제한된 PostgreSQL 임대를 보여줍니다.
  재시도 및 Kafka을 추가하지 않고도 빌릴 수 있는 데드 레터 상태입니다.
- `bluetape4k-http`은 프로덕션 가상 스레드 HTTP 클라이언트를 제공하지만
  기본 임시 재시도는 공급자 제출 `POST`을 모호하게 만들어서는 안 됩니다.
- 저장소의 CodeGraph 인덱스가 검색된 노드와 일치하는 노드를 반환하지 않았습니다.
  repository/outbox/virtual-thread 기호이므로 디자인은 직접적으로 기반을 둡니다.
  소스 검사와 GNO 및 실시간 이슈 증거.

## 목표

1. `optimization/` 아래에 Spring Boot 4.1 MVC 참조 모듈을 추가합니다.
2. `optimization/` 아래의 모든 모듈에 대해 Java 25를 목표로 삼고 다른 모듈은 유지합니다.
   Java 21 타겟에 대한 워크샵 모듈입니다.
3. `bluetape4k-virtualthread-api`을 JDK 25 런타임 공급자와 함께 사용하세요.
   HTTP, 서블릿 및 백그라운드 작업자 실행을 차단합니다.
4. PostgreSQL, HikariCP, Exposed JDBC 및 Bluetape Exposed 저장소를 다음과 같이 사용합니다.
   지속성 기준.
5. 결정적 가짜를 기본 공급자로 만들고 기본값 CI을 완전히 유지합니다.
   네트워크 프리.
6. 요청 및 보낼 편지함 상태를 원자적으로 유지한 다음 중복 및 보낸 편지함 상태를 수렴합니다.
   승인된 계획 감사 내역 하나로 전달을 다시 시작합니다.
7. 오래되거나 순서가 잘못된 공급자 결과를 거부하고 소유를 재검증합니다.
   최종 명령을 실행하기 전에 집계 버전을 확인합니다.
8. 점수, 개정, 상태 및 안전을 포함하는 수정된 읽기 모델을 노출합니다.
   제약 설명만.

## 논골

- 공유 프로덕션 최적화 라이브러리를 게시하지 마십시오.
- Timefold 테넌트, API 키 또는 공개 웹훅 경로가 필요하지 않습니다.
- 계획 결과를 최종 재고, 예약, 인력 배치 또는 파견으로 만들지 마십시오.
  애플리케이션 소유의 집계 버전 확인 없이 결정합니다.
- 첫 번째 구현에서는 Redis, Kafka, JaVers 또는 Resilience4j를 추가하지 마세요.
- `bluetape4k-exposed-timefold-solver-persistence`을 사용하지 마십시오. 그것은 지속된다
  로컬 해결자 점수 유형이며 공급자 중립적인 HTTP을 정의하지 않습니다.
  계약.
- Bluetape의 가상 스레드 SPI인 경우 직접 JDK 실행기 팩토리를 사용하지 마십시오.
  수명주기 경계를 제공합니다.

## 접근 옵션

### 옵션 A: 결정론적 가짜가 포함된 애플리케이션 소유 PostgreSQL 계약

공급자 중립 포트, PostgreSQL 요청을 사용하여 하나의 Spring MVC 모듈을 생성합니다.
받은 편지함, 보낸 편지함 및 감사 테이블, 결정적 가짜 어댑터 및 비활성화된 HTTP
녹음된 조명기로 뒷받침되는 어댑터 프로필.

이익:

- 게시되지 않은 라이브러리나 외부 자격 증명 없이 문제를 전달합니다.
- 이후 예제에서 재사용할 수 있는 구체적인 계약 증거를 생성합니다.
- 하나의 워크샵 모듈에서 일관성과 재생 동작을 검사할 수 있습니다.
- 관련 없는 항목을 추가하지 않고 관련 Bluetape 생태계 재사용을 극대화합니다.
  하부 구조.

소송 비용:

- 워크샵은 소량의 애플리케이션별 임대 및 콜백을 보유하고 있습니다.
  SQL.
- 실시간 공급자 배포는 선택적인 후속 증거로 남아 있습니다.

### 옵션 B: 공유 HTTP 멱등성 및 PostgreSQL 동시성 모듈을 기다립니다.

 #1055까지 #524 지연하고 #391 공통 인프라를 게시합니다.

이익:

- 잠재적으로 애플리케이션 소유의 픽스처 코드가 줄어듭니다.

소송 비용:

- 의도한 증거 흐름을 뒤집습니다. 예는 공유된 내용을 증명해야 합니다.
  추출 전 경계.
- 게시되지 않은 기능을  #524과 반대로 전달 차단기로 간주합니다.

### 옵션 C: Redis/Kafka-first 비동기 파이프라인

콜백 중복 제거 및 작업자 파견에는 PostgreSQL만 사용하여 Redis 또는 Kafka를 사용하세요.
최종 요청 상태.

이익:

- 분산된 대기열 인프라를 보여줍니다.

소송 비용:

- 공급자 계약이 안정되기 전에 두 개의 일관성 시스템을 추가합니다.
- 집계 버전 재검증 및 재시작 증명을 읽기 어렵게 만듭니다.
- Redis은 PostgreSQL이 소유한 첫 번째 멱등성 계약에는 필요하지 않습니다.

## 결정

옵션 A를 사용하세요. #1055 및 #391는 병렬 contract/fixture 트랙을 유지합니다.
순차적 배포 종속성. Redis은 나중에 추가할 수 있습니다.
소비자는 캐시, 조정 또는 분산된 받은 편지함이 필요하다는 것을 증명합니다. 그 길은 반드시
상추를 사용하세요.

## 건축학

### 런타임 구성요소

- `PlanningContractsApplication`: Spring Boot 애플리케이션 진입점.
- `PlanningEngine`: 공급자 중립 submission/status 포트.
- `DeterministicPlanningEngine`: 기본, 네트워크 프리 어댑터.
- `TimefoldPlatformPlanningEngine`: Timefold에 대한 프로필 어댑터가 비활성화되었습니다.
  플랫폼 REST 계약.
- `CustomSolverPlanningEngine`: 사용자 정의 솔버에 대한 프로필 어댑터가 비활성화되었습니다.
  동일한 버전의 HTTP 계약을 사용하는 서비스입니다.
- `PlanningRequestService`: 하나의 트랜잭션에 요청 및 보낼 편지함 행을 생성합니다.
- `PlanningOutboxWorker`: 만기가 된 작업을 청구하고 선택한 어댑터를 호출합니다.
  가상 스레드이며 작업 소유의 retry/dead-letter 상태를 기록합니다.
  거래.
- `PlanningCallbackService`: 콜백 신뢰성을 확인하고 받은 편지함을 삽입합니다.
  이벤트가 없는 경우 공급자 개정과 집계 버전을 비교하여 추가합니다.
  승인된 감사 기록 ​​1개.
- `PlanningCommandService`: 소유하고 있는 집계 버전을 재검증합니다.
  PostgreSQL 최종 사령부 후보를 반환하기 직전.
- `PlanningQueryService`: 지속성 상태를 수정된 읽기 모델에 매핑합니다.
- `PlanningController`: 요청, 콜백, 처리, 명령 및 쿼리
  워크샵 엔드포인트.

### 지속성 모델

`planning_aggregates`

- `aggregate_id` 고유 비즈니스 ID
- `aggregate_version`
- `updated_at`

`planning_requests`

- UUID v7 `id`
- `aggregate_id`, `aggregate_version`, `dataset_id`
- `parent_revision`, `accepted_revision`
- `status`, `score_summary`, `redacted_explanation`
- `provider`, `provider_request_id`
- 감사 타임스탬프

`planning_outbox`

- 긴 ID
- `planning_request_id` 고유함
- `payload`을 닫힌 JSON 문서로
- `status`, `attempt_count`, `next_attempt_at`
- `claimed_by`, `claimed_until`
- 제한된 위생 처리된 `last_error_code`, `last_error_summary`
- 감사 타임스탬프

`planning_callback_inbox`

- 긴 ID
- `provider`, `event_id` 함께 고유함
- `planning_request_id`, `provider_revision`
- `received_at`, `processed_at`, `outcome`
- 감사 타임스탬프

`planning_audits`

- 긴 ID
- `planning_request_id`, `callback_event_id`
- `aggregate_version`, `provider_revision`
- `status`, `score_summary`, `redacted_explanation`
- `decision`: 승인됨, 중복됨, 오래된 개정판, 집계 변경됨, 거부됨
- `created_at`

리포지토리는 Bluetape 인터페이스를 적극적으로 사용합니다.

- `PlanningRequestRepository : UUIDAuditableJdbcRepository`
- `PlanningOutboxRepository : LongAuditableJdbcRepository`
- `PlanningCallbackInboxRepository : LongAuditableJdbcRepository`
- `PlanningAuditRepository : LongJdbcRepository`

상속된 CRUD, 존재 여부, 개수 및 페이징 동작이 직접 사용됩니다.
애플리케이션별 SQL은 부재 시 삽입, 임대 청구, 개정으로 제한됩니다.
compare/update 및 집계 버전 재검증.

### 데이터 흐름

1. `POST /api/planning/requests`은 버전이 지정된 데이터 세트 참조의 유효성을 검사합니다.
2. 하나의 Spring 트랜잭션은 요청과 해당 아웃박스 행을 삽입합니다.
3. 작업자는 제한된 배치를 요청하고 `PlanningEngine`을 통해 제출합니다.
   Bluetape Java 25 가상 스레드 실행기.
4. 결정론적 가짜는 기록된 비동기 결과를 반환합니다. HTTP 어댑터
   기본이 아닌 프로필 뒤에 동일한 정규화된 계약을 노출합니다.
5. `POST /api/planning/callbacks/{provider}`은 서명을 확인하기 전에 서명을 확인합니다.
   상태 변경 후 부재 시 받은 편지함 삽입을 수행합니다.
6. 중복된 이벤트 ID는 작동하지 않습니다. 새로운 이벤트는 공급자 개정과
   감사 항목을 추가하기 전에 요청의 소유 집계 버전을 확인합니다.
7. 승인된 최신 개정판만 요청 읽기 상태를 업데이트합니다.
8. `POST /api/planning/requests/{id}/commands`은(는) 집계 버전을 다시 읽습니다.
   PostgreSQL. 변경된 버전은 명령 후보를 거부합니다.
9. `GET /api/planning/requests/{id}`은 정규화되고 수정된 필드만 반환합니다.

## 가상 스레드 계약

- `optimization/*`은 Java 25 툴체인을 사용하여 컴파일하고 테스트합니다.
- 비최적화 모듈은 Java/Kotlin 목표를 21로 유지합니다.
- 모듈은 `bluetape4k-virtualthread-api`에 의존하며 다음을 사용합니다.
  `runtimeOnly(bluetape4k-virtualthread-jdk25)`.
- 전이적 JDK 21 공급자는 모든 모듈 구성에서 제외되므로
  런타임 공급자 선택은 모호할 수 없습니다.
- Tomcat 및 작업자 실행자는 `VirtualThreads`을 통해 생성되고 소유됩니다.
  스프링 라이프사이클 빈.
- 들어오는 Spring 트랜잭션은 Tomcat 가상 스레드에 유지됩니다. JDBC 일은
  트랜잭션 내부에서 다른 집행자에게 제출되지 않았습니다.
- 각 보낸 편지함 작업은 가상 스레드 작업 내에서 자체 트랜잭션을 엽니다.
- 새 코드에서는 모니터 기반 동기화가 금지됩니다.

## HTTP 어댑터 계약

- `productionVirtualThreadHttpClientOf`은 Java 25 차단 클라이언트를 제공합니다.
- GET/status 호출은 클라이언트의 제한된 일시적 재시도를 사용할 수 있습니다.
- 공급자 제출 `POST`은(는) 자동 전송 재시도를 비활성화해야 합니다.
  공급자는 멱등성 키 계약을 증명합니다. PostgreSQL 보낼 편지함 재생이 남아 있음
  신뢰할 수 있는 재시도 계층.
- Request/response 픽스처는 명시적 내용이 포함된 JSON 문서로 제한됩니다.
  유형, 시간 초과, 본문 닫기 및 수정 동작.
- WireMock 계약 테스트에서는 제출 성공, 시간 초과, 5xx, 잘못된 JSON,
  외부 네트워크 액세스 없이 상태 조회 및 민감한 필드 수정이 가능합니다.

## 콜백 보안

- 결정론적 가짜는 서명 확인을 우회합니다.
  명시적인 test/profile 검증자.
- HTTP 공급자 프로필은 구성된 원시 웹훅 비밀과 함께 JCE `Mac`를 사용합니다.
  상수 시간 서명 비교.
- 누락되거나 형식이 잘못되었거나 만료되었거나 일치하지 않는 서명이 받은 편지함 이전에 실패함
  삽입.
- 원시 콜백 페이로드, 비밀, 공급자 자격 증명, 스택 추적 및 JDBC
  URL은 읽기 모델이나 저장된 오류 요약에 표시되지 않습니다.
- 요청 및 콜백 본문에는 명시적인 크기 제한이 있습니다.

## 생태계 역량 선택

| 책임 | 재사용된 블루테이프 module/capability | 결정 및 이유 | Unavailable/fake 제약 |
|---|---|---|---|
| 버전 거버넌스 | `bluetape4k-dependencies:1.3.1` | 유일한 Bluetape 버전 권한; 개별 BOM 또는 모듈 핀 없음 | 없음 |
| JDBC 저장소 | `bluetape4k-exposed-core`, `bluetape4k-exposed-jdbc` | UUID/Long 저장소 인터페이스 및 감사 가능한 테이블 사용 | 사용자 정의 원자 SQL는 계속해서 애플리케이션 소유 |
| 리포지토리 테스트 | `bluetape4k-exposed-jdbc-tests` | 호환되는 경우 PostgreSQL 저장소 테스트 fixtures/utilities 재사용 | 생산 의존성은 여전히 ​​`exposed-jdbc` |
| JSON 매핑 | `bluetape4k-exposed-jackson3`, `bluetape4k-jackson3` | 기본 입력 없이 닫힌 DTO 직렬화 | 공급자 고정 장치는 모듈 소유 |
| HTTP 차단 | `bluetape4k-http` | 프로덕션 가상 스레드 HC5 클라이언트 | 제출 재시도는 어댑터에 따라 disabled/controlled입니다. |
| 가상 스레드 | `bluetape4k-virtualthread-api`, `bluetape4k-virtualthread-jdk25` | JDK-중립 API Java 25 런타임 공급자 포함 | JDK 21개 제공업체 제외 |
| 식별자 | `bluetape4k-idgenerators` | UUID v7 요청 ID | 이벤트 ID는 공급자가 제공한 것으로 유지되며 공급자마다 고유합니다. |
| 로깅 컨텍스트 | `bluetape4k-logging` | `KLogging` 및 request/provider MDC 컨텍스트 | 원시 페이로드 로깅 금지 |
| 관찰 가능성 | `bluetape4k-micrometer` | submit/callback/command 경계 주변 관찰 | 외부 원격 측정 백엔드가 필요하지 않습니다 |
| PostgreSQL 테스트 | `bluetape4k-testcontainers` | `PostgreSQLServer.Launcher.postgres` 싱글톤 | 통합 레인에만 Docker가 필요함 |
| HTTP 비품 | Bluetape WireMock 실행기 | 지역 기록 제공자 비품 | 기본 CI에는 라이브 tenant/API 키가 없습니다 |
| 동시성 테스트 | `bluetape4k-junit5`, `MultithreadingTester` | 중복 콜백 수렴 및 임대 동작 증명 | 임시 원시 스레드 하네스 없음 |
| Redis | 없음 | 애플리케이션 소유 PostgreSQL inbox/outbox에는 필요하지 않음 | 나중에 필요한 경우 Lettuce |
| Kafka | 없음 |  #524에 브로커 경계가 없습니다 | 소비자 계약에서 요구하는 경우에만 재방문 |
| 리더선출 | 없음 | DB 임대를 사용하는 단일 프로세스 작업장 작업자이면 충분합니다 | 다중 인스턴스 스케줄링은 나중에 리더를 재사용할 수 있습니다 |
| 스냅샷 감사 | 없음 | 명시적인 추가 전용 계획 감사가 JaVers diffs | JaVers 이 계약이 거부됨 |
| 시간 배 지속성 | 없음 | 로컬 해결자 점수 지속성은 공급자 중립적이지 않습니다 HTTP | 라이브 어댑터는 비활성화된 프로필로 유지됩니다. |

## 실패 모드 및 복구

| 실패 | 필수 동작 | 증거 |
|---|---|---|
| 제출 전 요청 커밋 후 충돌 발생 | 다시 시작한 후 만료된 보낼 편지함 행이 회수됨 | PostgreSQL restart/replay 통합 테스트 |
| HTTP 시간 초과 또는 제출 시 5xx | Release/expire 임대, 제한된 재시도 예약, 데드 레터 | WireMock 플러스 저장소 통합 테스트 |
| 공급자가 승인한 후 로컬 승인 전에 충돌이 발생함 | 재생은 지원되는 경우 동일한 요청 ID와 공급자 멱등성 키를 사용합니다. 그렇지 않으면 상태 조정이 다른 POST보다 우선합니다 | 어댑터 계약 테스트 |
| 중복된 콜백 | Unique `(provider,event_id)`은(는) 아무 작업도 수행하지 않고 두 번째 승인된 감사를 생성하지 않습니다. | 동시 콜백 테스트 |
| 잘못된 콜백 | 이전 공급자 개정은 오래된 것으로 감사되어 승인된 상태를 대체할 수 없습니다. | 통합 테스트 |
| 해결 중에 변경된 집계 | 콜백이 녹음되었지만 허용되지 않습니다. 최종 명령은 변경된 집계 버전도 거부합니다 | 통합 테스트 |
| 잘못된 서명 | 민감한 출력 없이 받은편지함을 삽입하기 전에 거부 | MVC 음성 테스트 |
| 잘못된 공급자 본문 | 삭제 실패, 보낼 편지함 retryable/dead-letterable 유지, 원시 예외 유지 안 함 | WireMock 음성 테스트 |
| 활성 작업으로 작업자 종료 | 청구 중지, 제한된 배수 후 실행 프로그램 종료, 임대를 복구 가능하게 유지 | 수명주기 테스트 |

## 모델 및 API 경계 읽기

`PlanningReadModel`은 다음만 노출합니다.

- `requestId`
- `datasetId`
- `aggregateId`
- `aggregateVersion`
- `revision`
- `status`
- `score`
- `constraintExplanations` 제한된 수정 요약
- 타임스탬프

제출 페이로드, 콜백 본문, 웹훅 서명, API 키를 노출하지 않습니다.
공급자 오류 본문 또는 저장된 발신함 페이로드입니다.

## 호환성 및 마이그레이션

- 이것은 새로운 애플리케이션 모듈이며 기존 공개를 변경하지 않습니다.
  라이브러리 API.
- 루트 빌드는 경로 구분 도구 체인을 얻습니다: Java `optimization/*`의 경우 25개,
  Java 그 외 21개.
- CI은 두 툴체인을 모두 해결할 수 있는 JDK에서 실행됩니다. 컴파일된 목표 레벨
  모듈별로 유지됩니다.
- 데이터베이스 마이그레이션 도구가 도입되지 않았습니다. 워크숍이 격리되어 초기화됩니다.
  Exposed를 통한 스키마; 생산 추출에는 Flyway 또는
  동등한 마이그레이션 시스템.

## 테스트 전략

1. 가짜 결정론, 정규화된 상태, 수정 및 수정을 위한 순수 계약 테스트
   명령 버전 비교.
2. PostgreSQL 저장소 테스트를 통해
   `PostgreSQLServer.Launcher.postgres` 고유 받은 편지함 삽입, 임대 청구,
   개정 순서 및 집계 재검증.
3. `MultithreadingTester` 중복 콜백 및 발신함 청구로 인한 스트레스
   독점.
4. WireMock 어댑터는 submit/status 오류 및 수명 주기 계약을 테스트합니다.
5. 요청, 콜백, 읽기 모델 및 명령에 대한 Spring MVC 통합 테스트
   엔드포인트.
6. 커밋된 보낸 편지함 행이 하나의 승인된 감사로 수렴된다는 증거를 다시 시작하세요.
   작업자 재구성 후.

Testcontainers 명령은 `--max-workers=1`과 함께 순차적으로 실행되며 새로운 명령을 사용합니다.
오래된 출력이 실패를 숨길 수 있을 때 테스트 실행.

## 허용 기준 매핑

| 이슈기준 | 디자인 증명 |
|---|---|
| 최종 명령은 PostgreSQL | `PlanningCommandService` 및 변경 버전 통합 테스트 |
| 중복 콜백 및 재시작 재시도가 하나의 감사 기록으로 통합 | 고유 받은 편지함 키, 원자성 콜백 서비스, restart/replay 통합 테스트 |
| 읽기 모델은 score/revision/status/redacted 설명만 노출 | 닫힌 `PlanningReadModel`, JSON 누수 검증문 |
| 라이브 공급자가 없는 결정적 가짜 작품 | 기본 가짜 프로필 및 기록된 비품 |
| Timefold 플랫폼과 사용자 정의 솔버 경계는 별개입니다 | 한 포트 뒤에 있는 두 개의 비활성화된 HTTP 어댑터 프로파일 |
| Java 25이고 가상 스레드가 JVM 기본값입니다 | 경로 구분 도구 체인 및 런타임 공급자 어설션 |
| Bluetape 생태계의 적극적 재사용 | 기능 테이블 및 dependency/repository 테스트 |

## 융합 검토

승인된 채팅 디자인은 6가지 필수 렌즈에 대해 다시 검토되었습니다.

| 렌즈 | 초기 발견 | 이 사양으로 수리 | 최신 차단제 수 |
|---|---|---|---|
| 성과 | 무제한 아웃박스 폴링 및 콜백 설명 크기가 커질 수 있음 | 제한된 청구 배치, payload/body/explanation 제한 | P0=0, P1=0 |
| 안정성 | HC5 재시도 및 보낼 편지함 재시도가 중복 제출 가능 POST | 명시적으로 재시도 제출 disabled/controlled; 상태 조정이 모호한 재생보다 우선함 | P0=0, P1=0 |
| 보안 | 콜백 비밀 확인 및 원시 오류 누출이 과소 지정됨 | JCE HMAC, 상수 시간 비교, 받은 편지함 이전 거부, 수정된 폐쇄형 DTO | P0=0, P1=0 |
| Operator/Ops | 다시 시작 및 실행기 종료 소유권이 불분명함 | 복구 가능한 DB 임대, 제한된 배수, 작업 소유 트랜잭션, 메트릭 | P0=0, P1=0 |
| Developer/API | 리포지토리 재사용이 이름만 채택으로 축소될 수 있음 | 정확한 Bluetape 저장소 인터페이스와 제한된 사용자 정의 SQL가 지정됨 | P0=0, P1=0 |
| User/caller | 발신자가 승인된 계획을 최종 비즈니스 결정으로 착각할 수 있음 | 필수 집계 재검증을 통해 별도의 최종 명령 엔드포인트 | P0=0, P1=0 |

P0/P1 결과가 남아 있지 않습니다. P2: 라이브 타임폴드 웹훅 배포가 연기되었습니다.
자격 증명과 공개 경로는 선택적인 외부 증거이기 때문에
오프라인 계약 완료 조건입니다.

## 완료의 정의

- `:optimization-planning-contracts`이 `./gradlew projects`에 나타납니다.
- 최적화 모듈은 Java 25 및 JDK 25 Bluetape를 사용하여 컴파일하고 테스트합니다.
  가상 스레드 공급자.
- 모듈은 승인된 Spring Boot, Exposed, PostgreSQL 및 Bluetape를 사용합니다.
  개별 라이브러리 BOM 또는 명시적인 Bluetape 핀이 없는 종속성 스택.
- 결정적 가짜, HTTP 고정 장치 어댑터, 저장소 패턴, inbox/outbox,
  콜백, 감사 및 최종 명령 동작을 다룹니다.
- 타겟 모듈 테스트, Testcontainers 테스트, 감지, 워크플로 구문,
  등록 확인, 이중 언어 README 패리티 및 `git diff --check` 통과.
- CI/Nightly 및 유효성 검사 그룹에는 Java 25 컨테이너 지원 모듈이 포함됩니다.
- Redis/Kafka 종속성이 없거나 라이브 네트워크 요구 사항이 기본값 CI으로 들어갑니다.
