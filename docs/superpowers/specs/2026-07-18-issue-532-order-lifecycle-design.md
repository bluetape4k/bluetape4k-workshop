# Issue #532 Order Lifecycle and Fulfillment Orchestrator 설계

## 목표

브라우저에서 주문 수명주기를 실행하고 진단할 수 있는 Spring Boot reference
application을 `commerce/order-lifecycle-fulfillment`에 추가한다. `Order`,
`PaymentAttempt`, `InventoryReservation`, `FulfillmentGroup`, `RefundCase`는 하나의
거대 상태가 아니라 각자 revision을 가진 PostgreSQL 권위 상태 머신으로 유지한다.

핵심 완료 조건은 다음과 같다.

- 같은 idempotency key와 같은 payload는 저장된 HTTP 결과를 재생한다.
- 같은 key와 다른 payload는 결정적인 `409 Conflict`를 반환한다.
- payment 성공은 fulfillment 완료를 직접 만들지 않는다.
- 중복되거나 순서가 뒤바뀐 provider event는 terminal transition을 다시 적용하지 않는다.
- split shipment, partial cancellation, refund, operator reconciliation을 각각 감사할 수 있다.
- 브라우저 화면은 aggregate revision, publication lag, unresolved provider event,
  cancel/refund reason을 보여준다.

## 범위와 비범위

### 포함

- 주문 제출과 주문 line 저장
- payment attempt 생성과 결정적 fake provider event 수신
- inventory reservation 확정 또는 reconciliation 전환
- line을 여러 fulfillment group으로 분할하고 독립적으로 배송
- 미배송 line 일부 취소와 refund case 생성
- 실패 publication과 provider event의 operator reconciliation
- PostgreSQL 기반 HTTP idempotency lease/terminal replay fixture
- Exposed 기반 Spring Modulith publication repository와 재발행
- browser UI, snapshot-first SSE, Actuator/Micrometer 운영 정보

### 제외

- 실제 payment gateway, PCI 데이터, tax/carrier 계산
- 실제 notification, Kafka, Redis, leader election
- 범용 idempotency 또는 범용 broker outbox 공용 모듈
- 여러 애플리케이션에 앞서 도입하는 공유 abstraction

## 선택안

### 채택: application-owned 상태와 idempotency + 공개된 Exposed Modulith 저장소

도메인 상태, provider inbox, transition audit, HTTP idempotency record는 이
application이 소유한다. 비동기 이벤트 publication은 이미 공개된
`bluetape4k-exposed-spring-modulith`를 사용한다.

이 선택은 #1055와 #391의 contract/fixture 작업을 병렬 검증 자료로 사용하되 #532를
막는 배포 의존성으로 만들지 않는다. 두 번째 reference application에서도 같은
경계가 필요하다는 증거가 생길 때만 공용 모듈화를 검토한다.

### 기각: Redis idempotency

기존 `spring-boot/idempotency`는 Redis cache replay 예제이고 fingerprint conflict,
in-flight owner lease, interrupted owner recovery, PostgreSQL 업무 transaction과의 원자성을
제공하지 않는다. 이 예제에는 Redis가 필요하지 않으므로 Lettuce도 추가하지 않는다.

### 기각: application-owned broker outbox

#390이 검증한 Exposed 기반 Spring Modulith `EventPublicationRepository`가 공개되어 있다.
동일 책임의 outbox를 새로 만들면 replay/completion 계약이 중복된다.

## 상태 모델

| Aggregate | 상태 | 독립 revision의 의미 |
|---|---|---|
| `Order` | `SUBMITTED`, `ACCEPTED`, `FULFILLMENT_IN_PROGRESS`, `COMPLETED`, `CANCELLED` | 고객 주문의 전체 진행 상태만 표현한다. |
| `PaymentAttempt` | `CREATED`, `AUTHORIZING`, `SUCCEEDED`, `FAILED`, `CANCELLED` | provider authorization의 terminal 결과를 표현한다. |
| `InventoryReservation` | `HELD`, `COMMITTED`, `RELEASED`, `EXPIRED`, `RECONCILIATION_REQUIRED` | 재고 권위와 보정 필요 여부를 표현한다. |
| `FulfillmentGroup` | `REQUESTED`, `ALLOCATED`, `PICKING`, `SHIPPED`, `DELIVERED`, `CANCELLED` | 배송 묶음별 진행을 표현한다. |
| `RefundCase` | `REQUESTED`, `PENDING_PROVIDER`, `SUCCEEDED`, `FAILED`, `MANUAL_REVIEW` | 환불 provider 진행과 운영자 개입을 표현한다. |

상태 전이는 `(aggregate_type, aggregate_id, revision)` 유일 키를 가진 audit row로
남긴다. audit에는 bounded reason code와 actor type만 두며 payment payload, 고객 정보,
idempotency key 원문은 저장하거나 log/metric label로 노출하지 않는다.

## HTTP idempotency 계약

scope는 `(tenant_id, operation, idempotency_key_hash)`다. 원문 key는 SHA-256으로
축약하고 request body는 field 순서가 안정적인 canonical DTO로 직렬화한 뒤 별도
SHA-256 fingerprint를 계산한다.

1. record가 없으면 `IN_PROGRESS`, owner token, lease deadline을 원자적으로 획득한다.
2. 같은 fingerprint의 `SUCCEEDED`/`FAILED` terminal row는 저장된 status/body를 재생한다.
3. 다른 fingerprint는 `409 IDEMPOTENCY_FINGERPRINT_CONFLICT`다.
4. 유효한 다른 owner의 `IN_PROGRESS`는 `409 IDEMPOTENCY_IN_PROGRESS`와 retry-after를 준다.
5. 만료 lease는 새 owner가 획득한다. 이전 owner의 늦은 finalize는 owner token 비교로 거부한다.
6. terminal result는 bounded retention을 가지며 cleanup은 완료/실패 row만 삭제한다.

주문 생성과 idempotency terminal finalize는 같은 Spring/Exposed transaction에서
처리한다. 비정상 종료 fixture는 만료 lease 회수와 stale owner finalize 거부를 검증한다.

## 이벤트와 일관성 경계

- command service는 Exposed JDBC transaction에서 aggregate와 audit를 갱신하고
  `ApplicationEventPublisher`로 작은 event DTO를 발행한다.
- Spring Modulith가 listener별 publication row를 같은 PostgreSQL에 기록한다.
- `bluetape4k-exposed-spring-modulith`의 `UPDATE` completion mode를 사용해 completed,
  failed, incomplete 상태를 운영 화면에서 집계한다.
- listener는 provider event id 또는 domain event id를 기준으로 idempotent하게 처리한다.
- operator endpoint는 `FailedEventPublications`와 `IncompleteEventPublications`를 통해
  bounded batch를 재발행한다.

## 결정적 payment fake와 순서 뒤바뀜

`DeterministicPaymentProvider`는 request의 fixture mode로 다음 결과를 만든다.

- `SUCCESS`: authorization 후 success event
- `DECLINE`: terminal failure event
- `DELAYED_SUCCESS`: 요청과 event 수신을 분리
- `OUT_OF_ORDER`: success 다음 delayed authorizing event
- `DUPLICATE_SUCCESS`: 같은 provider event id를 반복

provider inbox는 `(provider, provider_event_id)`를 유일 키로 갖고 payload fingerprint도
보관한다. 동일 event/payload는 `DUPLICATE`, 동일 id/다른 payload는 `CONFLICT`, terminal
상태보다 과거인 event는 `IGNORED_OUT_OF_ORDER`로 기록한다. 어떤 경우에도 terminal
revision을 다시 증가시키지 않는다.

## 브라우저와 SSE

- `/`는 dependency가 없는 static HTML/vanilla JavaScript 화면이다.
- `/api/v1/orders`와 하위 command endpoint로 lifecycle을 실행한다.
- `/api/v1/orders/{id}`는 모든 aggregate snapshot, revisions, audit, publication 상태를 반환한다.
- `/api/v1/orders/{id}/events`는 먼저 snapshot event를 보내고 audit cursor 이후 event를 보낸다.
- `Last-Event-ID`를 cursor로 사용하며 heartbeat와 reconnect를 지원한다.
- emitter마다 Java 25 virtual thread 하나를 사용하되 최대 연결 수, timeout, interruption,
  executor close를 테스트한다.

## Java 25와 Bluetape virtual threads

모듈 toolchain은 Java 25로 고정한다. blocking PostgreSQL 조회, fake provider dispatch,
SSE polling 경계에서만 `bluetape4k-virtualthread-api`를 사용하고 runtime provider는
`bluetape4k-virtualthread-jdk25`만 둔다. JDK 21 provider는 제외한다.

## 관측성과 보안

- Spring Modulith publication gauge는 공개 모듈의
  `bluetape4k.exposed.modulith.publications`를 사용한다.
- application metric은 aggregate type/status, outcome 같은 low-cardinality tag만 쓴다.
- log에는 hashed request correlation, order id, bounded reason code만 둔다.
- idempotency key, provider payload, payment token, 고객 입력은 log/metric에 두지 않는다.
- API error는 stable code와 correlation id만 반환하고 내부 exception을 노출하지 않는다.

## 검증 전략

- 순수 상태 머신 단위 테스트
- `bluetape4k-exposed-jdbc-tests`의 `withTables`와 PostgreSQL fixture 기반 repository 테스트
- `PostgreSQLServer.Launcher.postgres` 통합 테스트
- `MultithreadingTester`로 동일 key 동시 획득, fingerprint conflict, lease takeover 검증
- Spring Modulith failed publication과 replay 검증
- duplicate/out-of-order provider event와 terminal revision 불변 검증
- split fulfillment, partial cancellation, refund audit 검증
- MockMvc와 SSE lifecycle/Last-Event-ID 검증
- restart 후 PostgreSQL snapshot/publication/inbox 복구 검증

## 리스크와 완화

| 리스크 | 완화 |
|---|---|
| 하나의 service가 여러 상태 머신을 암묵적으로 결합 | aggregate별 repository와 transition policy를 분리하고 event로 다음 단계를 연결한다. |
| Exposed transaction과 Modulith publication transaction 불일치 | 공개 auto-configuration과 Spring transaction manager를 사용하고 실패 publication 통합 테스트를 둔다. |
| SSE connection 누수 | bounded registry, timeout, completion/error callback, executor close 테스트를 둔다. |
| fake가 실제 실패 순서를 충분히 표현하지 못함 | duplicate, delayed, out-of-order, decline mode를 고정 fixture로 제공한다. |
| 운영 화면이 민감 payload를 노출 | snapshot DTO를 allow-list로 만들고 payload 원문을 반환하지 않는다. |

