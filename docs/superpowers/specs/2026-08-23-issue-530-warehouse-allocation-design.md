# Issue #530 Warehouse Allocation 및 Pick-Wave Planner 설계

- 날짜: 2026-08-24
- 저장소: `bluetape4k/bluetape4k-workshop`
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/530
- 상위 Epic: https://github.com/bluetape4k/bluetape4k-workshop/issues/523
- 작업 브랜치: `feat/issue-530-warehouse-allocation`
- 대상 모듈: `optimization/warehouse-allocation`

## 결정 요약

`optimization/warehouse-allocation`을 독립 Spring Boot 애플리케이션 모듈로 추가한다.
이 모듈은 order line, warehouse SKU stock snapshot, pick wave, carrier cutoff,
shipping rule, picker capacity, committed allocation pin을 자체 domain으로 소유한다.

planner는 deterministic fake solver로 allocation과 pick-wave 제안을 생성한다. 제안의
plan ID와 snapshot version은 PostgreSQL에 기록하지만, 제안 자체를 재고 권위로 승격하지
않는다. 사용자가 plan을 승인할 때 PostgreSQL transaction이 현재 stock reservation,
주문 line, cutoff, picker capacity, pin revision을 다시 읽고 CAS로 예약을 확정한다. 어느
하나라도 stale이면 전체 승인을 rollback하고 bounded conflict를 반환한다.

#524의 plan audit와 stale-callback 개념은 versioned HTTP/fixture seam으로 검증한다.
`planning-contracts`의 내부 Kotlin 구현을 Gradle implementation dependency로 직접
가져오지 않는다. 실제 custom Solver service, Kafka, WMS, carrier, picker-capacity
provider가 준비되지 않은 환경에서도 기록된 outbox fixture만으로 모든 기본 실행과 CI를
재현할 수 있어야 한다.

## 배경과 현재 증거

Issue #530은 warehouse allocation과 pick-wave assignment를 제안하는 reference
application을 요구하지만, production WMS·robotics·carrier API·자동 stock commit은
비목표로 명시한다. delivery 제약은 Java 25, Bluetape capability 우선 조사, deterministic
fake/provider fixture, PostgreSQL inventory reservation authority, 그리고 #524의 plan
audit/stale-callback contract다.

현재 저장소에는 `optimization/planning-contracts`와
`optimization/field-service-dispatch`가 있다. #525는 독립 모듈과 deterministic planner,
proposal/commit 분리, PostgreSQL CAS, redacted console 패턴을 이미 검증했다. 그러나
#530은 재고 수량·reservation·split shipment·pick-wave를 다루므로 #525 내부 구현을
공유 dependency로 추출하지 않고 별도의 domain과 저장소 경계를 둔다.

이 문서의 외부 근거는 다음 live issue와 저장소 구현이다.

- Issue #530의 목표·범위·완료 조건·비목표·delivery 제약
- `docs/superpowers/specs/2026-08-20-issue-525-field-service-design.md`
- `optimization/field-service-dispatch`의 PostgreSQL revision/CAS와 redacted read model
- `optimization/planning-contracts`의 inbox/outbox와 stale callback lifecycle

Timefold 또는 다른 provider의 production capability를 현재 구현의 전제로 삼지 않는다.
provider API가 없는 상태에서 확인할 수 없는 주장은 이 설계와 README에서 명시적으로
제외한다.

## GNO 조사 결과와 설계 반영

2026-08-23에 컬렉션을 제한하지 않고 다음 전역 명령으로 관련 자료를 조사했다.

```bash
gno --offline query --fast --no-graph --limit 8 --json "warehouse allocation pick-wave planner"
gno --offline query --fast --no-graph --limit 8 --json "PostgreSQL reservation authority stale oversell inventory CAS"
gno --offline query --fast --no-graph --limit 8 --json "deterministic solver outbox duplicate out-of-order restart replay"
gno --offline query --fast --no-graph --limit 8 --json "planning-contracts stale callback outbox fencing aggregate version"
```

검색 결과 중 설계 판단에 직접 사용한 원문은 다음과 같다. GNO는 조사·탐색의 근거이며,
현재 Issue와 저장소 파일의 live 상태가 충돌할 경우 live 상태를 우선한다.

| GNO source | 확인한 결정 근거 |
|---|---|
| `gno://bluetape4k-github/bluetape4k-workshop/issues/000523.md` | Epic child는 authoritative aggregate, plan revision guard, callback idempotency, pinned work, operator explanation, 공통 failure fixture를 각자 증명하고 provider 없는 deterministic fixture부터 시작한다. |
| `gno://bluetape4k-github/bluetape4k-workshop/issues/000530.md` | 재고 reservation은 PostgreSQL authority이고 planner는 proposal/pick-wave를 만들며, production WMS·robotics·carrier 연동과 자동 stock commit은 비목표다. |
| `gno://bluetape4k-wiki/research/2026-07-18-timefold-solver-optimization-reference-applications.md` | planning entity/fact를 분리하고 hard/medium/soft score, unassigned reason, pinned allocation, versioned event와 replan을 모델링한다. solver 결과는 reservation을 확정하지 않으며 DB finalization과 deterministic positive/negative tests가 필요하다. |
| `gno://bluetape4k-docs/bluetape4k-workshop/optimization/planning-contracts/README.ko.md` | request와 outbox intent를 같은 transaction에 기록하고, callback signature/inbox/revision을 검증한 뒤 state를 변경한다. lease와 fencing은 DB clock/affected rows로 판정하며, 내부 `project()` dependency와 Redis를 경계 밖으로 둔다. |
| `gno://bluetape4k-docs/bluetape4k-workshop/docs/superpowers/plans/2026-08-20-issue-525-field-service.md` | 독립 child, deterministic planner, TestMutexService, `--max-workers=1`, redacted console, #524 재사용은 구현 pattern이 아니라 검증할 경계로만 가져온다. |
| `gno://bluetape4k-docs/clinic-appointment/docs/lessons/2026-08-05-issue-41-transactional-outbox-messaging.md` | aggregate mutation과 outbox intent는 caller transaction에 묶고 broker I/O는 밖에서 수행한다. lease expiry·fencing·partial rollback·stale owner의 affected rows `0`을 검증하며, API 성공은 broker delivery가 아닌 durable intent commit을 뜻한다. |

이 조사로 기존 결정을 다음처럼 구체화한다.

1. planner는 planning entity/fact를 immutable snapshot으로 분리하고 hard constraint를
   먼저 만족시킨 뒤 medium(배정량·split·긴급도), soft(고정 travel rank·picker load)
   순서로 점수화한다. overconstrained line은 임의 보정하지 않고 닫힌 unassigned reason과
   split reason을 남긴다.
2. fixture event는 canonical digest와 source revision으로 inbox에 기록하고, 최신 event만
   aggregate를 변경한다. event별 immutable target과 aggregate key가 일치하는지 먼저 검증한
   뒤, `WHERE source_event_revision < incomingRevision` CAS와 `affectedRows`로 적용 여부를
   판정한다. 같은 batch는 aggregate별 source revision 오름차순으로 적용하고 최대 revision
   하나의 replan intent로 coalesce하되, duplicate·낮은 revision·다른 digest는 각각
   no-op/audit/conflict로 terminal history를 만든다. pin은 replan에서 planning fact로
   보존하고 승인 시 현재 revision을 재확인한다.
3. reservation 승인 transaction은 reservation row뿐 아니라 aggregate mutation, audit,
   outbox intent를 함께 커밋한다. local deterministic effect는 effect row와 completion을
   같은 DB transaction으로 처리해 exactly-once를 주장할 수 있지만, 외부 HTTP/Kafka relay는
   transaction 밖의 at-least-once delivery로 제한한다. 외부 adapter는 operation key를
   provider idempotency key로 전달하고 status reconciliation을 지원할 때만 중복 없음으로
   승격한다. lease owner가 바뀌었을 때 DB 조건 update의 affected rows가 `0`이면 side effect를
   만들지 않는다.
4. #524 연동은 request/outbox/signature/inbox/revision fixture의 compatibility proof로
   한정한다. `planning-contracts` 내부 구현, JDK 21 virtual-thread artifact, Redis,
   실제 Kafka·Timefold credential은 기본 실행과 CI에 포함하지 않는다. Java 25 capability는
   `MultithreadingTester`와 PostgreSQL fixture로 lifecycle을 먼저 증명한 뒤 제한된 worker에
   적용한다. #524의 현재 `FAKE` signature는 literal `fake` fixture로 검증하고, HMAC은
   기본 비활성인 별도 custom-solver fixture 경계로 둔다.
5. 공통 SDK를 선행 추출하지 않는다. 두 child에서 반복되는 계약이 확인되기 전까지는
   #525 pattern을 복사 가능한 검증 기준으로만 사용하고, warehouse 전용 aggregate와
   reservation schema를 독립적으로 유지한다.

## 목표

1. synthetic order line과 warehouse stock을 사용해 allocation과 pick-wave 제안을 만드는
   실행 가능한 reference application을 추가한다.
2. available stock, cold-chain/hazmat capability, carrier cutoff, committed allocation
   pin을 hard constraint로 모델링한다.
3. plan ID, input snapshot version, hard/medium/soft score, unassigned reason,
   split-shipment reason, manual pin, reservation acceptance/rejection, replan history를
   redacted browser console과 REST read model로 표시한다.
4. inventory adjustment, reservation rejection, order cancellation, carrier cutoff,
   picker capacity 변경, warehouse incident를 deterministic outbox/Kafka fixture로
   소비한다.
5. plan 승인 시 현재 inventory reservation을 transactionally 재확인해 stale plan이
   oversell을 만들지 않도록 한다.
6. duplicate/out-of-order inventory event, solver restart, reservation retry,
   cancellation, pinned allocation replan이 하나의 terminal history로 수렴하는 것을
   테스트로 고정한다.
7. Java 25와 저장소의 `bluetape4k-dependencies` BOM, Exposed, PostgreSQL/Testcontainers,
   Bluetape logging/assertions/virtual-thread capability를 재사용한다.

## 비목표

- production WMS, robotics, warehouse execution, scanner/device integration
- 실제 carrier API, routing/geocoding, shipping label 또는 delivery promise
- 실제 Kafka broker, Timefold tenant/API key, custom Solver deployment을 CI의 필수 조건으로
  사용
- planner가 재고를 직접 commit하거나 PostgreSQL reservation을 대체하는 구조
- 범용 inventory engine, universal fulfillment library, Epic의 다른 child가 사용할 공용
  optimization SDK
- provider가 반환한 raw payload, credential, 주소, 개인 정보의 저장·로그·브라우저 노출
- Gin/FastAPI 등 non-JVM parity variant. 별도 variant가 추가될 때는 동일 versioned
  contract와 failure fixture를 별도 설계한다.

## 선택지와 결정

### 선택지 A — 새 독립 모듈 + deterministic planner (채택)

`optimization/warehouse-allocation`이 warehouse domain, planner, persistence, fixture,
redacted console을 모두 소유한다. #524와는 좁은 versioned HTTP/fixture contract만
검증한다. 이 구조는 현재 제공되지 않는 solver/provider를 fake로 대체하면서 PostgreSQL
reservation authority와 독립 child 경계를 보존한다.

### 선택지 B — `planning-contracts` 내부 구현을 직접 재사용 (제외)

Gradle implementation dependency로 #524의 내부 service와 table을 가져오면 초기 코드는
줄어들 수 있다. 그러나 내부 visibility와 aggregate model에 결합되고 warehouse reservation
revision이 plan lifecycle에 섞인다. #524가 제공하는 계약만 검증한다는 Epic 경계를
위반하므로 채택하지 않는다.

### 선택지 C — commerce fulfillment에 inventory 공용 계층을 먼저 추가 (제외)

기존 commerce reservation 예제를 확장하면 일부 reservation 정책을 재사용할 수 있다.
하지만 #530의 workshop reference가 범용 inventory authority로 확장되고, 여러 child와
동시에 migration해야 하는 범위가 생긴다. 실제 두 소비자에서 반복되는 계약이 확인된
뒤 별도 child로 공용화한다.

## 모듈과 런타임 구조

```text
static browser console / REST read model
                 │ redacted query + operator command
                 v
WarehouseAllocationController
                 │ DTO validation, request/idempotency boundary
                 v
WarehouseAllocationApplicationService
       ┌─────────┼──────────┬──────────┐
       v         v          v          v
 event inbox  deterministic  plan       approval/
 + replay     planner         history    reservation CAS
       │         │            │          │
       └─────────┴────────────┴──────────┘
                 v
       PostgreSQL authority + audit/outbox
```

패키지 경계는 다음과 같이 고정한다.

모든 Kotlin source의 root package는
`io.bluetape4k.workshop.optimization.warehouseallocation`이다. 애플리케이션 진입점은
`io.bluetape4k.workshop.optimization.warehouseallocation.WarehouseAllocationApplicationKt`로
고정하고, `WarehouseAllocationApplication.kt`의 top-level `main`과
`springBoot { mainClass.set("io.bluetape4k.workshop.optimization.warehouseallocation.WarehouseAllocationApplicationKt") }`
를 함께 선언한다. application class와 Spring configuration은 public으로 두고, domain,
application, persistence, adapter, web 구현은 기존 모듈 규칙에 따라 `internal`을 기본으로
한다. HTTP wire contract는 public 문서 계약이지만 Kotlin controller/DTO class는 `internal`로
두어 public ABI를 만들지 않는다. module 밖으로 public으로 노출하는 것은 명시된 test fixture
port뿐이며, 내부 repository 타입은 내보내지 않는다. 유일한 public fixture ABI는
`src/testFixtures/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/fixture/WarehouseAllocationFixturePort.kt`의
`io.bluetape4k.workshop.optimization.warehouseallocation.fixture.WarehouseAllocationFixturePort`
interface로 고정한다. 시그니처는
`fun reset(seed: Long): String`, `fun ingest(canonicalEvent: String): String`,
`fun snapshot(datasetId: String): String`이며 각각 dataset ID, operation key, bounded
`warehouse-canonical-v1` snapshot JSON을 반환한다. fixture port는 `java-test-fixtures`
source set에서만 제공하고 production artifact에는 포함하지 않으며, 입력 문자열은 body
256 KiB와 canonical event schema bound를 따른다.

- `domain/`: value object, aggregate, state, score, reason code, event contract
- `planner/`: immutable input snapshot, deterministic allocation/pick-wave algorithm
- `application/`: event ingest, replan, approval, retry, cancellation, query orchestration
- `persistence/`: Exposed table, record, repository, transaction, schema fixture
- `adapter/fake/`: recorded outbox/Kafka events, fake solver result, restart/fencing fixture
- `adapter/http/`: #524-compatible audit/stale-callback contract의 optional mapper
- `web/`: REST controller, bounded error DTO, redacted view model, static console

planner와 domain은 Spring context나 JDBC transaction을 직접 참조하지 않는다. persistence와
web은 application service를 통해서만 업무 상태를 변경한다. 외부 provider adapter가
추가되더라도 provider 결과는 `PlanProposal`로 변환한 뒤 동일한 stale 검증을 거친다.

## 도메인 모델

### Warehouse와 SKU stock snapshot

`Warehouse`는 synthetic `warehouseId`, 표시용 이름, timezone, capability 집합
(`COLD_CHAIN`, `HAZMAT`), picker pool/capacity와 `revision`을 가진다. capability와 picker
capacity의 단일 write owner는 `Warehouse`이며 `warehouse.incident`와
`picker.capacity.changed`가 이 aggregate의 revision을 증가시킨다.

`SkuStockSnapshot`은 `(warehouseId, sku)`를 자연 키로 하고 다음 값을 가진다.

- `onHandQuantity`, `reservedQuantity`, `availableQuantity = onHand - reserved`
- `stockRevision`, `sourceEventRevision`, `updatedAt`
- warehouse capability에서 계산된 `handlingCapabilities` snapshot

available quantity는 저장된 최종 값이 아니라 snapshot에서 계산한다. stock의
`handlingCapabilities`는 warehouse에서 파생된 immutable snapshot이지 별도 write authority가
아니다. 음수 quantity, finite하지 않은 수, capability와 SKU rule의 모순은 입력 단계에서
거부한다. 승인 시
reservation row update가 `availableQuantity >= requestedQuantity`를 같은 SQL 조건으로
검사해 stale read에 의한 oversell을 막는다.

### Order와 order line

`Order` aggregate는 `warehouse_alloc_orders`가 단일 write owner이며 `orderId`, `status`,
`revision`, order line ID 목록을 보관한다. `OrderStatus`는
`OPEN`, `PARTIALLY_ALLOCATED`, `CANCELLED`, `COMPLETED`로 닫힌다. order-level cancel은
`expectedOrderRevision`을 `WHERE revision = expectedOrderRevision AND status NOT IN
(CANCELLED, COMPLETED)` 조건으로 CAS하고, 성공한 경우 같은 transaction에서 모든 active
order line을 `CANCELLED`로 전이하고 reservation release, audit, outbox intent를 기록한다.
cancel command는 `order`를 먼저 잠근 뒤 line ID 오름차순으로 lock하며, approve/cutoff/
capacity/pin mutation과 동일한 전역 lock 순서를 사용한다. affected rows가 0이면
`RESERVATION_CONFLICT` 또는 `UNKNOWN_TARGET`으로 rollback하고 order/line/reservation의
부분 상태를 남기지 않는다.

Order 상태 projection은 다음 우선순위 truth table을 사용한다. line이 하나 이상 있어야
하며, 상태를 계산한 뒤 order revision을 한 번만 증가시킨다.

| 모든 line 상태 조건 | projected `OrderStatus` |
|---|---|
| 모든 line이 `CANCELLED` | `CANCELLED` |
| 모든 line이 `FULFILLED` | `COMPLETED` |
| 모든 line이 `OPEN` | `OPEN` |
| 그 밖의 혼합(`ALLOCATED`, `PARTIALLY_ALLOCATED`, `OPEN`, `CANCELLED`, `FULFILLED`) | `PARTIALLY_ALLOCATED` |

`CANCELLED`와 `FULFILLED`가 섞인 경우도 첫 두 행을 만족하지 않으므로
`PARTIALLY_ALLOCATED`다. projection 결과가 기존 값과 같아도 source line mutation이 성공한
경우에만 parent order revision을 증가시키며, no-op duplicate는 revision을 증가시키지 않는다.

`OrderLine`은 `orderLineId`, `orderId`, `sku`, `requestedQuantity`, destination class,
`shippingRule`, `carrierCutoff`, `status`, `revision`을 가진다. `OrderLineStatus`는
`OPEN`, `ALLOCATED`, `PARTIALLY_ALLOCATED`, `CANCELLED`, `FULFILLED`로 닫힌다. shipping
rule은
`STANDARD`, `COLD_CHAIN`, `HAZMAT`, `COLD_CHAIN_AND_HAZMAT`처럼 닫힌 enum으로 표현하고
주소·carrier credential·자유 형식 provider rule은 저장하지 않는다.

cancellation event는 아직 승인되지 않은 proposal을 stale 처리하고, 이미 예약된 allocation은
동일 transaction에서 reservation release와 audit를 수행한 뒤 `CANCELLED` terminal 상태로
수렴한다.

### Pick wave와 picker capacity

`PickWave`는 `waveId`, warehouse, cutoff bucket, warehouse의
`pickerCapacityRevision` snapshot, ordered allocation IDs, status, `revision`을 가진다.
wave의 `maxLines`는 해당 warehouse capacity에서 계산된 snapshot이며 mutable write owner가
아니다. planner가 capacity를 초과하는 line은 `PICKER_CAPACITY` reason으로 미배정 처리하거나
다음 wave로 이동시킨다. 승인 시 warehouse와 wave를 모두 재검증해 snapshot이 현재 capacity와
일치하는지 확인한다.

### Allocation pin과 reservation

`CommittedAllocationPin`은 order line과 warehouse/SKU/quantity를 묶고 `pinRevision`,
`createdBy`, `active`를 보관한다. manual pin은 승인 전 planner가 변경하지 않으며,
replan 시 pin과 현재 stock/capability가 충돌하면 `PIN_CONFLICT` 또는
`PIN_STALE` reason을 남긴다. pin을 제거하는 command는 expected revision과 idempotency
key를 요구한다.

`Reservation`은 PostgreSQL이 소유하는 최종 업무 상태다. 상태는 `PENDING`, `ACCEPTED`,
`REJECTED`, `RELEASED`, `CANCELLED`이며 한 line을 여러 warehouse로 나누는 split shipment를
허용한다. reservation row의 natural key는 `(planId, orderLineId, warehouseId)`이고,
`warehouse_alloc_order_lines.active_plan_id`가 한 order line에 동시에 유효한 plan이 하나만
존재하도록 CAS로 보호한다. 이미 같은 `planId`가 active이면 replay할 수 있지만 다른 plan은
`ACTIVE_PLAN_CONFLICT`로 거부한다. planner의 proposal allocation과 reservation row를
동일한 것으로 취급하지 않는다.

### PlanProposal과 score

`PlanProposal`은 다음을 보존한다.

- `planId`, `planRevision`, `parentPlanRevision`, `requestGeneration`, `fencingToken`
- warehouse stock, order, order line, wave, pin의 immutable input snapshot version; order
  snapshot에는 approval에서 사용할 `expectedOrderRevision`을 별도로 보존한다.
- allocation proposal과 pick-wave assignment
- `hardScore`, `mediumScore`, `softScore`
- unassigned reason과 split-shipment reason의 닫힌 code 목록
- `DRAFT`, `STALE`, `APPROVED`, `REJECTED`, `CANCELLED` 상태

wire의 `PlanStatus` 값은 `DRAFT|STALE|APPROVED|REJECTED|CANCELLED`이고,
`WarehouseAllocationReasonCode` 값은
`STOCK_UNAVAILABLE|COLD_CHAIN|HAZMAT|CARRIER_CUTOFF|PICKER_CAPACITY|WAREHOUSE_INCIDENT|PIN_CONFLICT|PIN_STALE|SPLIT_SHIPMENT`로 닫는다.

점수는 외부 solver의 자유 형식 문자열이 아니라 유한 정수 구조로 저장한다.

- `hardScore`: hard constraint 위반 수의 음수. 완전 배정이면 `0`이며, 제안에서
  미배정으로 남긴 line도 어떤 hard constraint 때문에 배정하지 못했는지 reason으로
  설명한다.
- `mediumScore`: split shipment 수, cutoff slack 부족, wave capacity 사용량에 대한
  음수 penalty.
- `softScore`: warehouse 선택의 고정 travel rank와 picker load imbalance에 대한 음수
  penalty.

동일 input snapshot과 seed는 항상 같은 allocation, wave 순서, score, reason, plan digest를
생성해야 한다. plan digest는 `warehouse-canonical-v1` 규칙의 canonical JSON(SHA-256)으로
계산하며 raw solver payload를 저장하지 않는다. canonical JSON은 UTF-8, 정렬된 object key,
planner가 사전에 정렬한 배열, `null` 필드 생략, exponent 없는 정규화된 finite 숫자,
UTC `Instant`의 `YYYY-MM-DDTHH:mm:ss.SSSZ` 표현을 사용한다. canonical schema version은
digest 입력에 포함하고 대표 event/plan의 golden JSON과 SHA-256을 contract test로 고정한다.

## 결정론적 planner 규칙

planner는 다음 순서로 입력을 정렬하고 배정한다.

1. `carrierCutoff`가 이른 order line
2. `shippingRule`의 hard capability 요구가 큰 line
3. requested quantity가 큰 line
4. `orderLineId` 오름차순

각 line은 warehouse ID 오름차순, wave ID 오름차순으로 후보를 평가한다.

1. available stock과 requested quantity를 확인한다.
2. shipping rule이 요구하는 cold-chain/hazmat capability를 확인한다.
3. carrier cutoff 이전에 pick 가능한 wave와 picker capacity를 확인한다.
4. active committed pin이 있으면 pin warehouse와 quantity를 먼저 보존한다.
5. 남은 quantity가 있으면 split shipment 정책을 확인하고 후보 warehouse를 계속
   평가한다.
6. 후보가 없으면 allocation을 생성하지 않고 가장 구체적인 hard reason을 남긴다.

동률은 `warehouseId`, `waveId`, `orderLineId`의 lexical order로 해소한다. planner는
재고를 변경하거나 외부 HTTP/Kafka를 호출하지 않는다. 누락된 travel/capacity edge는
임의의 기본값으로 보정하지 않고 `WAREHOUSE_INCIDENT` 또는 `PICKER_CAPACITY` reason으로
남긴다.

### Planner 입력과 자원 예산

planner는 dataset 하나에 대해 order line 500개, warehouse 100개, pick wave 200개, stock
row 10,000개, pin 500개를 상한으로 둔다. 후보 평가량은
`lineCount × warehouseCount × waveCount <= 2_000_000`이어야 하며, 상한을 넘으면 planner를
실행하지 않고 `PLANNER_INPUT_TOO_LARGE`를 반환한다. 이 값은 처리량 보장이 아니라
결정론적 실행을 위한 admission guard다. planner executor는 동시에 2개 job, 실행 대기 목록
20개, job deadline 2초로 제한한다. 실행 대기 목록이 가득 차면 `PLANNER_CAPACITY_EXCEEDED`, deadline을
넘기면 `PLANNER_DEADLINE_EXCEEDED`로 종료하고 plan이나 reservation을 만들지 않는다.
두 outcome 모두 bounded audit를 남기며, 입력 상한 초과와 deadline 초과는 caller가 dataset을
줄여 재요청해야 하고, executor 포화만 `Retry-After` 후 같은 request를 재시도할 수 있다.
정렬·tie-break·digest는 job 수와 무관하게 동일하므로 재시도해도 plan 결과가 달라지지 않는다.

## 권위·revision·상태 전이

| 영역 | PostgreSQL 권위 | 보조/fixture | stale 판정 |
|---|---|---|---|
| stock/reservation | stock revision, reserved quantity, reservation state | inventory outbox event | aggregate revision + source event revision |
| order | status, line membership, order revision | order fixture | order revision + derived line status |
| order line | status, requested quantity, line revision | order fixture | line revision + parent order CAS |
| pick wave | ordered allocation, wave status/revision; warehouse capacity revision snapshot | picker fixture | wave revision + warehouse capacity revision |
| plan | plan status, snapshot versions including `expectedOrderRevision`, fencing token | solver result fixture | request generation + fencing token + order revision |
| pin | active pin, pin revision | operator command | pin revision |

plan revision, source event revision, stock revision, request generation은 서로 다른
namespace다. 한 영역의 숫자를 다른 영역의 순서를 판정하는 데 사용하지 않는다.

### Event inbox와 out-of-order 처리

event는 `(aggregateType, aggregateId, eventKey)`와 canonical payload SHA-256 digest를
저장한다.

- 동일 key와 동일 digest: `DUPLICATE` no-op. 업무 상태와 outbox를 변경하지 않는다.
- 동일 key와 다른 digest: `EVENT_KEY_REUSED` conflict. side effect를 만들지 않는다.
- source event revision이 현재보다 낮음: `STALE_EVENT` audit만 남긴다.
- 같은 source event revision에 다른 digest: `EVENT_REVISION_CONFLICT`로 거부한다.
- 최신 event: inbox insert와 aggregate update, audit, replan outbox enqueue를 한
  transaction에서 확정한다.

지원 fixture event는 다음과 같다.

| 이벤트 | 적용 상태 | replan/예약 효과 |
|---|---|---|
| `inventory.adjusted` | stock snapshot revision 증가 | 영향을 받는 SKU plan stale |
| `reservation.rejected` | reservation `REJECTED` | 해당 line 재계획, rejection reason 기록 |
| `order.cancelled` | line `CANCELLED` | 미승인 plan stale, 승인 reservation release |
| `carrier.cutoff.changed` | line cutoff revision 증가 | cutoff 위반 allocation 재계획 |
| `picker.capacity.changed` | warehouse picker-pool revision 증가, wave capacity snapshot stale | overflow line 재계획 |
| `warehouse.incident` | warehouse capability revision 증가/일시 중지 | 해당 warehouse allocation 재계획 |

event target은 `inventory.adjusted → stock`, `reservation.rejected → reservation/order-line`,
`order.cancelled → order-line`, `carrier.cutoff.changed → order-line`,
`picker.capacity.changed → warehouse`, `warehouse.incident → warehouse`로 고정한다.
`order.cancelled`는 line-scoped event이지만 parent `Order`를 먼저 resolve하고 order lock을
획득한다. `lineRevision` CAS와 함께 parent order revision을 원자 증가시키고 모든 active
line의 상태로 aggregate `OrderStatus`를 재계산하므로 Order write owner를 우회하지 않는다.
order-level cancel command와 plan approval도 같은 order aggregate service를 호출해 order
revision과 status를 같은 transaction에서 갱신한다. event DTO의 target ID와 inbox key가 다르면 inbox·aggregate·outbox 모두 쓰지 않는다. 같은
transaction에서 여러 revision이 도착하면 aggregate별 stable ID와 revision 오름차순으로
CAS한다. pending/retryable replan outbox의 natural key는 `(aggregateType, aggregateId)` 하나이며
`maxRevision = GREATEST(current, incoming)`으로 원자 갱신한다. 이미 claimed/delivered된
generation에는 새 successor generation을 한 번만 만들고, 한 시점에 실행 가능한 pending
intent는 하나로 제한한다. 별도 transaction의 revision 5/6 경합은 먼저 성공한 CAS가 권위를
가지며, 뒤의 낮은 revision은 `STALE_EVENT`로 끝나고 높은 revision은 다시 CAS한다.

outbox row는 stable event ID, operation key, aggregate revision, `leaseOwner`, `leaseToken`,
`leaseExpiresAt`, `fencingToken`, `nextAttemptAt`, `attempt`, `status`를 가진다. effect row도
`leaseOwner`, `leaseToken`, `leaseExpiresAt`, `fencingToken`, `nextAttemptAt`, `attempt`,
`deliveryAttempted`를 보유해 outbox lease와 결합한다.
상태는
`PENDING → CLAIMED → DELIVERED` 또는 `CLAIMED → DELIVERY_UNKNOWN`, `RETRYABLE → CLAIMED`,
`DEAD_LETTER`이며, effect row는
`CLAIMED`, `RETRYABLE`, `COMPLETED`, `RECONCILE_REQUIRED`, `DEAD_LETTER`를 구분한다.
claim·renew·complete·reclaim은
DB `clock_timestamp()`와 짧은 claim transaction, 현재 owner/token/fencing 조건,
`affectedRows == 1`을 필수로 한다. 만료 owner의 renew/complete가 0 rows이면 side effect를
만들지 않고 audit만 남긴다. `operationKey`와 handler별 effect key에는 database unique
constraint를 두며 local fixture는 effect row와 completion을 같은 transaction으로 기록한다.
외부 relay는 send 직전 fenced effect claim을 다시 확인하고 operation key를 provider
idempotency key로 전달한다. 외부 I/O timeout은 5초로 lease 15초보다 짧게 두며, send 전에
종료된 crash는 `RETRYABLE`, send 후 응답을 잃은 crash는 `DELIVERY_UNKNOWN`과
`RECONCILE_REQUIRED`로 기록한다. worker는 stable order로 batch를
claim하고 재시도는 최대 5회, 1초부터 30초까지의 bounded backoff를 사용하며 이후
`DEAD_LETTER`로 전이한다. startup과 lease-expiry sweep은 local effect 또는 send 전 external
effect의 만료 `CLAIMED`를 `RETRYABLE`, send 후 응답을 잃은 external effect의 만료 `CLAIMED`를
`DELIVERY_UNKNOWN`/`RECONCILE_REQUIRED`로 되돌린다. reconciliation은 provider에서 찾으면
`DELIVERED`/`COMPLETED`, provider가 idempotency key의 definitive not-found를 반환하면
`RETRYABLE`/`RETRYABLE`, 상태를 알 수 없으면 `DELIVERY_UNKNOWN`/`RECONCILE_REQUIRED`로
남긴다. 최대 시도 초과는 `DEAD_LETTER`/`DEAD_LETTER`로 원자 전이한다. operator redrive는
동일 operation key와 새 lease로 `DEAD_LETTER → RETRYABLE`을 수행하고 redrive audit를
남긴다. restart 후 inbox digest, local effect, terminal audit가 중복되지 않아야 한다.

### #524 compatibility contract

기본 실행은 `adapter/fake`만 사용한다. 선택적 HTTP adapter는 내부 #524 타입을 import하지
않고 자체 wire DTO와 application port mapper를 소유한다. `planning-contracts-v1` fixture
metadata와 #524의 현재 endpoint를 다음처럼 매핑한다.

| 방향 | 계약 | #530 매핑 |
|---|---|---|
| #530 → #524 | `POST /api/planning/requests` request JSON | `aggregateId`, `aggregateVersion`, `datasetId`, `parentRevision`, `provider`를 exact field로 전송한다. snapshot마다 불변 `aggregateId = warehouse-allocation:<datasetId>:snapshot:<datasetVersion>`, `aggregateVersion = 0`, 안정된 `datasetId`, `parentRevision = null`을 사용한다. 응답은 **202** `{"id":"<UUID>","status":"QUEUED"}`이고 응답 `id`를 callback/query의 `planningRequestId`로 binding한다. |
| provider fixture → #524 | `POST /api/planning/callbacks/{provider}` request/response JSON | callback body는 `eventId`, `planningRequestId`, `providerRevision`, `status`(`QUEUED|SUBMITTED|SOLVING|SUCCEEDED|FAILED`), 필수 `scoreSummary`, 선택 `constraintExplanations`를 exact field로 보낸다. `provider=FAKE`는 header `X-Planning-Signature: fake`를 사용하고, callback response `{"decision":"ACCEPTED|DUPLICATE|STALE_REVISION|AGGREGATE_CHANGED|PROVIDER_MISMATCH|REJECTED"}`를 decision source로 삼는다. `scoreSummary`는 `hard=<int>;medium=<int>;soft=<int>` bounded projection이며, `constraintExplanations`는 기본 `[]`으로 둔다. |
| #530 query | `GET /api/planning/requests/{requestId}` response JSON | query read model의 exact field인 `id`, `aggregateId`, `aggregateVersion`, `status`, `provider`, `providerRequestId`, `acceptedRevision`, `scoreSummary`, `redactedExplanation`만 읽는다. callback의 stale decision은 GET 결과로 추론하지 않고 callback response와 #530의 redacted audit fixture에서 읽는다. #530 reservation authority로 승격하지 않는다. |

`datasetVersion`은 stock, order line, wave, Warehouse capability/picker-capacity, pin 변경마다
PostgreSQL transaction에서 증가하는 단일 monotonic dataset revision이다. 복합 snapshot의
component version은 별도로 보존한다. `warehouse.incident`와 `picker.capacity.changed` 전후에도
snapshot aggregate ID가 달라져 서로 다른 planning fact가 같은 immutable snapshot으로
재사용되지 않는다.
`parentRevision`은 #530 내부 `parentPlanRevision`과만 연결되는 plan lineage 필드이며, #524
현재 계약에 dataset revision을 넣지 않는다. 기본 fixture에서는 lineage가 없으므로 `null`로
보내고, 이전 accepted planning revision을 실제로 연결하는 별도 contract가 생길 때만 매핑한다.
현재 #524는 동일 `aggregateId`의 version 변경을 허용하지 않으므로 snapshot마다 불변
aggregate ID를 발급하고 `aggregateVersion=0`으로 보낸다. 같은 snapshot의 재요청은 같은
aggregate ID/version과 #530 idempotency key로 replay하며, datasetVersion 5와 6은 서로 다른
aggregate ID로 연속 요청할 수 있어야 한다. `datasetId`는 조합된 aggregate ID가 #524의
160자 identifier 상한을 넘지 않도록 최대 96자로 제한하고, 이 경계를 golden contract test로
고정한다. callback은
`(provider, planningRequestId, providerRevision)` namespace에서만 순서를 비교한다.

fixture metadata는 다음 literal을 사용한다: `protocolVersion="planning-contracts-v1"`,
`planningContractsCommitPrefix="5ec53f96e"` (현재 #524 DTO/controller를 만든 source commit),
`canonicalSchemaVersion="warehouse-canonical-v1"`. 이 세 값은 metadata golden assertion으로
고정한다. request/callback DTO, `provider=FAKE`의 literal `X-Planning-Signature: fake`,
그리고 별도 custom-solver fixture의 HMAC-SHA-256 signature, provider/request/plan/generation
binding, duplicate event, lower revision, timeout/retry를 모두 golden fixture로 고정한다.
HMAC adapter는 기본 비활성·loopback 전용이며 nonblank env secret, constant-time 비교,
method/path/schema/canonical body와 bounded timestamp를 서명 대상으로 한다. signature와
binding preflight가 성공하기 전에는 #530 state를 변경하지 않는다. 현재 #524 DTO에 없는
warehouse allocation result를 callback `scoreSummary`에 위의 고정 형식으로만 요약하고,
allocation/result와 constraint reason은 #530 local envelope의 닫힌 score/reason 구조로
별도 처리한다. 연속 datasetVersion 2회 요청과
동일 snapshot replay를 별도 contract test로 고정한다.

`WarehouseAllocationPlanningContractTest`는 이 compatibility seam의 단일 명명 테스트다.
이 테스트는 (1) metadata 세 literal과 `planningContractsCommitPrefix`, (2) request `202`
응답의 `id/status=QUEUED`와 `planningRequestId` binding, (3) callback 필수 field와
`FAKE` signature/decision, (4) query read model의 exact field와 redacted explanation,
(5) custom-solver fixture의 HMAC preflight/constant-time failure, (6) canonical digest와
idempotency replay, (7) datasetVersion 5/6의 서로 다른 aggregate ID와 동일 snapshot의
동일 ID replay를 golden JSON으로 assertion한다. provider credential이 없어도 fake fixture만
실행되며 #530 state는 preflight 실패 시 변경되지 않는다.

```bash
./gradlew :optimization-warehouse-allocation:test \
  --tests '*WarehouseAllocationPlanningContractTest' \
  --no-build-cache --max-workers=1 --console=plain
```

## Plan 생성과 승인 흐름

1. `POST /api/warehouse-allocation/replans`가 현재 snapshot을 읽고 `requestGeneration`과
   fencing token을 발급한다. 같은 idempotency key의 terminal 결과는 저장된 plan ID를
   replay한다.
2. planner는 immutable snapshot에서 제안을 만들고 plan digest, score, reason, snapshot
   version을 저장한다. solver가 중단되면 plan은 `REJECTED`가 아니라 retry 가능한
   outbox 상태로 남긴다.
3. solver restart 후 이전 generation의 늦은 결과가 도착하면 fencing token이 맞지 않아
   plan을 변경하지 않고 `STALE_SOLVER_RESULT` audit를 남긴다.
4. `POST /api/warehouse-allocation/plans/{planId}/approve`는 다음 전역 lock 순서를 따른다.
   `plan → order → order_line → pin → pick_wave → warehouse → stock → reservation → inbox/outbox/audit`.
   각 집합은 stable ID 오름차순으로 잠근다. cancellation, cutoff/capacity/incident event,
   pin mutation도 같은 순서의 prefix를 사용해 approve와 deadlock 없이 직렬화한다.
5. plan, order, order line, pin, wave, warehouse capability, stock의 현재 revision과 상태를
   모두 재조회하고 expected-revision CAS를 준비한다. plan snapshot의
   `expectedOrderRevision`과 현재 `Order.revision`이 같아야 하며, order status는
   `OPEN|PARTIALLY_ALLOCATED`여야 한다. order line은 `status IN (OPEN,
   PARTIALLY_ALLOCATED)`이고 `active_plan_id IS NULL OR active_plan_id = planId`여야 한다.
   pin은 active plan의 pin revision과 일치해야 하며, wave assignment/cutoff와 Warehouse의
   현재 picker-capacity/capability revision도 현재 조건을 만족해야 한다. wave의 capacity
   값은 Warehouse에서 파생된 snapshot과 비교하고 wave 자체를 capacity write owner로
   취급하지 않는다.
6. split allocation을 `(warehouseId, sku)`별로 먼저 합산한 뒤 stock row를 key 오름차순으로
   한 번씩만 conditional update한다. 각 update는 `availableQuantity >= aggregatedQuantity`
   와 expected stock revision을 함께 검사한다. affected row 수가 예상과 다르면
   `RESERVATION_CONFLICT`로 전체 rollback한다.
7. reservation, committed allocation, order-line `active_plan_id`, order aggregate status/revision,
   plan status, audit, outbox enqueue를 같은 transaction에서 확정한다. order status는 모든
   line의 상태를 다시 계산해 `OPEN|PARTIALLY_ALLOCATED|COMPLETED` 중 하나로 CAS 갱신한다.
   승인된 plan은 다시 allocation을 생성하지 않으며, 동일 idempotency key 재호출은 저장된
   결과를 replay한다.
8. order cancellation, reservation rejection, warehouse incident가 승인 중 발생하면
   transaction 경계를 넘지 않고 conflict로 끝난다. 이미 확정된 reservation을 취소하거나
   rejection으로 terminal 처리하는 경우에는 현재 `planId`를 조건으로
   `order_line.active_plan_id = NULL`을 원자적으로 해제하고, release/cancel audit와 후속
   replan event를 같은 transaction에서 기록한다. 이미 NULL인 반복 cancellation은 no-op
   terminal audit로 처리하며 다른 plan의 active marker는 해제하지 않는다.

이 흐름은 planner가 본 stock snapshot과 승인 시점의 stock reservation 사이의 TOCTOU를
허용하지 않는다. PostgreSQL update 조건이 통과하지 않은 부분 예약은 남기지 않는다.

## HTTP와 redacted console 계약

### Query

- `GET /warehouse-allocation`: static operator console
- `GET /api/warehouse-allocation/stock`: warehouse/SKU available snapshot
- `GET /api/warehouse-allocation/orders/{orderId}`: line, pin, reservation summary
- `GET /api/warehouse-allocation/plans/{planId}`: score, allocations, reasons, history
- `GET /api/warehouse-allocation/replans/{generation}`: generation과 stale/terminal 상태
- `GET /api/warehouse-allocation/outbox/{operationKey}`: outbox/effect의 paired 상태, 시도 횟수,
  다음 시각, reconciliation 필요 여부와 redrive 가능 여부를 redacted DTO로 반환

### Command

- `POST /api/warehouse-allocation/events`: fixture event ingest
- `POST /api/warehouse-allocation/replans`: deterministic plan 생성
- `POST /api/warehouse-allocation/plans/{planId}/approve`: reservation 승인
- `POST /api/warehouse-allocation/plans/{planId}/reject`: operator rejection
- `POST /api/warehouse-allocation/pins`: manual allocation pin 설정
- `DELETE /api/warehouse-allocation/pins/{pinId}`: expected revision 기반 pin 해제
- `POST /api/warehouse-allocation/orders/{orderId}/cancel`: cancellation fixture command
- `POST /api/warehouse-allocation/outbox/{operationKey}/redrive`: operator가 dead-letter pair를
  retryable로 되돌리는 명령

모든 list query는 `cursor`(첫 요청은 생략, opaque 256자 이하)와 `limit`(기본 20, `1..100`)을
받고 `{items, nextCursor}`를 반환한다. `nextCursor`가 `null`이면 마지막 page다. route별
request/response 계약은 다음과 같이 고정한다.

| route | request 필드 | 성공 status와 response 필드 |
|---|---|---|
| `GET /api/warehouse-allocation/stock` | query `cursor?`, `limit?` | `200 {items:[{warehouseId,sku,availableQuantity,sourceRevision}],nextCursor}` |
| `GET /api/warehouse-allocation/orders/{orderId}` | path `orderId`, query `cursor?`, `limit?` | `200 {orderId,status,revision,lines:[{lineId,status,sku,requestedQuantity,activePlanId:String?,pinRevision:Long?,reservations:[{reservationId,state:ReservationState}]}],nextCursor}` |
| `GET /api/warehouse-allocation/plans/{planId}` | path `planId`, query `cursor?`, `limit?` | `200 {planId,status,datasetVersion,score,allocations,reasons,history,nextCursor}` |
| `GET /api/warehouse-allocation/replans/{generation}` | path `generation` | `200 {generation,status,planId:String?,staleReason:ReplanStaleReason?,requestId}` |
| `GET /api/warehouse-allocation/outbox/{operationKey}` | path `operationKey` | `200 {operationKey,outboxState,effectState:EffectState?,attempt,nextAttemptAt:UTC Instant?,reconciliationRequired,redriveAllowed,requestId}` |
| `POST /api/warehouse-allocation/events` | body `{eventKey,eventType,target,sourceEventRevision,payload}` | `202 {operationKey,requestId,state:EventState}` |
| `POST /api/warehouse-allocation/replans` | body `{datasetId,seed,parentPlanRevision?}` | `202 {operationKey,requestId,generation,state:ReplanState}` |
| `POST /api/warehouse-allocation/plans/{planId}/approve` | path `planId`, body `{expectedPlanRevision}` | `200 {planId,status:PlanStatus,reservationIds,activePlanId,requestId}` |
| `POST /api/warehouse-allocation/plans/{planId}/reject` | path `planId`, body `{expectedPlanRevision,reasonCode}` | `200 {planId,status:PlanStatus,requestId}` |
| `POST /api/warehouse-allocation/pins` | body `{lineId,warehouseId,quantity,expectedLineRevision}` | `200 {pinId,revision,status:PinStatus,requestId}` |
| `DELETE /api/warehouse-allocation/pins/{pinId}` | path `pinId`, body `{expectedRevision}` | `200 {pinId,revision,status:PinStatus,requestId}` |
| `POST /api/warehouse-allocation/orders/{orderId}/cancel` | path `orderId`, body `{expectedOrderRevision}` | `200 {orderId,status:OrderStatus,revision,requestId}` |
| `POST /api/warehouse-allocation/outbox/{operationKey}/redrive` | path `operationKey`, empty body | `202 {operationKey,requestId,state:OutboxState}` |

표의 `?` 필드는 response에서 생략하지 않고 JSON `null`을 반환한다. order line의
`activePlanId`, `pinRevision`은 각각 active plan/pin이 없을 때 `null`이다. `reservations`는
항상 reservation ID 오름차순 배열이며 없으면 `[]`, 최대 500개다. replan query는
`QUEUED|RUNNING`이면 `planId=null, staleReason=null`,
`SUCCEEDED`이면 `planId`만 non-null, `FAILED`이면 두 필드가 모두 `null`, `STALE`이면
`planId`와 `staleReason`이 모두 non-null이어야 한다. outbox query의 `effectState`는
outbox `PENDING`에서만 `null`이고, `nextAttemptAt`은 `PENDING|CLAIMED|RETRYABLE`에서만
non-null이며 `DELIVERY_UNKNOWN|DELIVERED|DEAD_LETTER`에서는 `null`이다. 이 nullable
presence 규칙과 각 terminal 상태의 JSON `null`을 golden response fixture로 고정한다.
그 밖의 모든 response field는 표에 있는 닫힌 DTO만 사용하고 unknown field는 반환하지 않는다. `200`
command는 DB transaction과 terminal 상태까지 끝난 경우에만 반환하며, `202` command는 durable
intent와 polling target을 기록한 뒤 반환한다. 같은 idempotency fingerprint의 재호출은 같은
status와 body를 replay한다. `GET`의 없는 `orderId`, `planId`, `generation`, `operationKey`,
`pinId`는 `404 UNKNOWN_TARGET`이고 조회/명령 모두 DB no-write다.

`POST /events`의 `payload`는 `eventType`에 따라 다음 discriminated DTO 중 하나만 허용한다.
모든 target ID와 payload field는 required이며 `null`과 unknown field는 거부한다.

| `eventType` | `target` | `payload` |
|---|---|---|
| `inventory.adjusted` | `{warehouseId,sku}` | `{onHandQuantity:Int}` |
| `reservation.rejected` | `{reservationId,orderLineId}` | `{reasonCode:RESERVATION_CONFLICT\|CANCELLED\|INCIDENT}` |
| `order.cancelled` | `{orderLineId}` | `{lineRevision:Long}` |
| `carrier.cutoff.changed` | `{orderLineId,carrierCode}` | `{cutoffAt:UTC Instant}` |
| `picker.capacity.changed` | `{warehouseId}` | `{capacity:Int,effectiveAt:UTC Instant}` |
| `warehouse.incident` | `{warehouseId}` | `{incidentCode:WAREHOUSE_INCIDENT,active:Boolean}` |

`eventKey`는 200자, `sourceEventRevision`은 0 이상, quantity/capacity는 0 이상이며
`eventType`과 target/payload 조합이 맞지 않으면 `400 INVALID_REQUEST`로 처리한다.
`GET /plans/{planId}`의 nested field도 닫힌 schema를 사용한다. `score`는
`{hard:Int,medium:Int,soft:Int}`, `allocations`는 `(lineId,warehouseId,waveId,allocationId)`
순서의 `{allocationId,lineId,warehouseId,waveId,sku,quantity:Int,pinned:Boolean}` 배열,
`reasons`는 line ID 순서의 `{lineId,code:WarehouseAllocationReasonCode,affectedQuantity:Int}`
배열, `history`는 revision 순서의
`{revision:Long,status:PlanStatus,reasonCode:WarehouseAllocationReasonCode?,createdAt:UTC Instant,requestId}`
배열이다. `reasonCode`만 terminal reason이 없을 때 `null`이다. 유효한 plan은
`allocations`와 `reasons`를 각각 최대 500개, history page를 최대 100개로 제한하고 전체
response를 256 KiB 이하로 만든다. candidate planner가 이 output bound를 넘기면
`PLANNER_OUTPUT_TOO_LARGE`, `422`, plan/reservation/outbox row `0`으로 거부하므로 caller가
query에서 줄일 수 없는 대형 allocation/reason 배열은 존재하지 않는다. plan query의
`cursor`/`limit`은 history page에 적용한다. 방어적인 serializer overflow는
`413 RESPONSE_TOO_LARGE`와 DB no-write로 처리한다.

wire status/state도 다음 enum으로 닫으며, 표에 없는 literal은 producer와 consumer 모두
거부한다: `OrderStatus = OPEN|PARTIALLY_ALLOCATED|CANCELLED|COMPLETED`,
`OrderLineStatus = OPEN|ALLOCATED|PARTIALLY_ALLOCATED|CANCELLED|FULFILLED`,
`PinStatus = ACTIVE|REMOVED|STALE`, `EventState = ACCEPTED|DUPLICATE`,
`ReplanState = QUEUED|RUNNING|SUCCEEDED|FAILED|STALE`,
`ReplanStaleReason = STALE_SOLVER_RESULT|ORDER_REVISION_CHANGED|STOCK_REVISION_CHANGED|WAVE_REVISION_CHANGED|PIN_REVISION_CHANGED|WAREHOUSE_REVISION_CHANGED|CARRIER_CUTOFF_CHANGED|ORDER_CANCELLED`,
`ReservationState = PENDING|ACCEPTED|REJECTED|RELEASED|CANCELLED`,
`OutboxState = PENDING|CLAIMED|DELIVERY_UNKNOWN|DELIVERED|RETRYABLE|DEAD_LETTER`,
`EffectState = CLAIMED|COMPLETED|RETRYABLE|RECONCILE_REQUIRED|DEAD_LETTER`.
`PlanStatus`와 `WarehouseAllocationReasonCode`는 위의 고정 목록을 재사용한다. `status`와
`state`가 있는 모든 route는 이 목록 중 하나만 반환하고, malformed/unknown enum은
`400 INVALID_REQUEST`로 처리한다. orders read model의 `status`/line `status`/
`reservations[].state`는 `ReservationState`, plans read model의 `status`는 `PlanStatus`,
replans read model의 `status`/`staleReason`는 각각 `ReplanState`/`ReplanStaleReason`, outbox read
model의 `outboxState`/`effectState`는 각각 `OutboxState`/`EffectState`로 직렬화한다.

mutation은 `X-Demo-Operator: true`, `Idempotency-Key`, `X-Request-Id`를 요구한다. mutation
controller 자체는 `demo` profile에서만 등록하고, non-demo profile에서는 route가 존재하지
않는다. 기본 profile은 `server.address=127.0.0.1`과 management binding도 loopback으로
고정하며 startup contract test가 이를 확인한다. 외부 CORS와 공개 operator endpoint는
허용하지 않는다. `X-Demo-Operator`는 local workshop guard일 뿐 인증·인가 수단이 아님을
README에 명시한다.

입력은 body 256 KiB, JSON depth 12, 일반 identifier 160자(datasetId는 96자), event key 200자,
history page 100개, opaque cursor 256자, 일반 quantity `1..1_000_000`, explanation 20개·각
240자 상한을 갖는다. event의 `onHandQuantity`와 `capacity`는 0도 유효하므로 별도로
`0..1_000_000`을 적용한다. path ID와 cursor는 허용된 opaque 형식만 사용하고 concrete
target이 없는 조회는 거부한다.
unknown field와 duplicate JSON key는 거부하고, polymorphic/default typing 없이 닫힌 DTO를
명시적으로 역직렬화한 뒤 canonicalize한다. event payload, plan digest, HTTP idempotency
fingerprint 모두 `warehouse-canonical-v1` profile을 공유한다. required `null`은 거부하고
optional `null`은 absent와 같은 의미로 정의한 필드에서만 생략한다. `-0`은 `0`으로 만들고,
decimal scale은 제거하며 exponent 없는 finite 숫자, Unicode NFC, UTF-8, UTC instant의
고정 표현을 사용한다. 배열은 schema가 set인 경우에만 사전 정렬하고, 순서가 의미인
allocation/history 배열은 입력 순서를 보존한다. 동일 idempotency key는
`(HTTP method, route template, demo scope, key)` namespace로 원자적으로 claim한다.
fingerprint에는 concrete path parameter(`planId`, `pinId`, `orderId`, `generation`, `operationKey`), canonical
body와 command schema version을 포함한다. route template는 namespace 용도로만 사용한다.
`IN_PROGRESS` 요청은
`COMMAND_IN_PROGRESS`, terminal 동일 fingerprint는 저장된 결과 replay, 다른 fingerprint는
`IDEMPOTENCY_FINGERPRINT_CONFLICT`로 처리한다. terminal row 보존 기간은 demo 기준 24시간으로
제한하고 sweep한다. idempotency row 상태는 `IN_PROGRESS`, `RETRYABLE`, `COMPLETED`,
`FAILED_TERMINAL`로 닫는다. `PLANNER_CAPACITY_EXCEEDED`만 retryable admission 결과로
취급하며, queue 포화는 claim transaction 안에서 `IN_PROGRESS → RETRYABLE`과
`nextRetryAt`을 원자 기록해 `Retry-After` 뒤 같은 key를 다시 claim할 수 있게 한다.
`PLANNER_INPUT_TOO_LARGE`, `PLANNER_DEADLINE_EXCEEDED`, `PLANNER_OUTPUT_TOO_LARGE`는
`IN_PROGRESS → FAILED_TERMINAL`로 저장해 같은 key에 실패 응답을 replay하고, dataset을
줄인 새 generation은 새 key를 요구한다. 어느 실패 경로도 plan, reservation, outbox를
부분 생성하지 않는다. `RETRYABLE` row의 같은 fingerprint는 `nextRetryAt` 이후에만
`IN_PROGRESS`로 재claim하며, 그 전에는 `COMMAND_IN_PROGRESS` 대신 저장된 retryable 응답과
`Retry-After`를 반환한다. `RETRYABLE`은 최초 실패 시각부터 24시간, 최대 5회까지 보존하고,
시도 횟수 초과 또는 retention 만료 시 `FAILED_TERMINAL`/`RETRY_EXHAUSTED`로 원자 전이한다.
`RETRY_EXHAUSTED`는 retryable attempt/retention 경계에서만 발생하며, terminal admission
오류의 대체 코드가 아니다.

모든 query 성공 응답은 `200`과 bounded DTO를 사용한다. redrive가 두 paired row의 durable
전이를 기록하면 `202`와 `operationKey`, `requestId`, 현재 상태를 반환하고, 이미 처리된
동일 key는 저장된 결과를 replay한다. command 오류는 다음 고정 계약을 따른다.

| 오류 코드 | HTTP | `retryable` | `nextAction` | caller의 다음 동작 |
|---|---:|---:|---|---|
| `RESERVATION_CONFLICT` | 409 | false | `REPLAN` | 최신 상태를 조회하고 새 replan 후 다시 승인 |
| `ACTIVE_PLAN_CONFLICT` | 409 | false | `REPLAN` | order line의 현재 active plan을 조회하고 새 generation을 생성 |
| `EVENT_KEY_REUSED` | 409 | false | `NO_RETRY` | 원래 event key/digest를 보존하거나 새 event key를 발급 |
| `EVENT_REVISION_CONFLICT` | 409 | false | `NO_RETRY` | aggregate의 최신 revision/digest를 조회하고 새 event를 생성 |
| `STALE_EVENT` | 409 | false | `NO_RETRY` | 최신 source revision을 확인하고 오래된 event 재전송을 중단 |
| `COMMAND_IN_PROGRESS` | 202 | true | `POLL` | `operationKey` query를 polling |
| `PLANNER_INPUT_TOO_LARGE` | 413 | false | `SHRINK_DATASET` | dataset 또는 요청 범위를 상한 이하로 줄임 |
| `PLANNER_DEADLINE_EXCEEDED` | 422 | false | `SHRINK_DATASET` | line/warehouse/wave 범위를 줄여 새 generation 생성 |
| `PLANNER_OUTPUT_TOO_LARGE` | 422 | false | `SHRINK_DATASET` | allocation/reason cardinality가 작은 dataset으로 새 generation 생성 |
| `PLANNER_CAPACITY_EXCEEDED` | 503 | true | `RETRY_AFTER` | `Retry-After` 뒤 같은 idempotency key로 재시도 |
| `RETRY_EXHAUSTED` | 409 | false | `NO_RETRY` | 같은 key의 terminal 결과를 replay하고 새 generation/key를 사용 |
| `IDEMPOTENCY_FINGERPRINT_CONFLICT` | 409 | false | `NO_RETRY` | 원래 concrete target/body를 유지하거나 새 key를 사용 |
| `OUTBOX_NOT_REDRIVABLE` | 409 | false | `RECONCILE` | paired 상태 query 후 reconciliation 또는 새 command 선택 |
| `INVALID_REQUEST_ID` | 400 | false | `NO_RETRY` | 허용된 `X-Request-Id` 형식으로 새 요청 |
| `INVALID_REQUEST` | 400 | false | `NO_RETRY` | 닫힌 DTO field와 required header/body를 맞춰 새 요청 |
| `UNKNOWN_TARGET` | 404 | false | `NO_RETRY` | 존재하는 concrete target을 조회 |
| `RESPONSE_TOO_LARGE` | 413 | false | `SHRINK_DATASET` | page size 또는 query 범위를 줄여 새 요청 |

오류 DTO는 `code`, `requestId`, `retryable`, 선택적 `retryAfterSeconds`, `nextAction` enum과
`COMMAND_IN_PROGRESS`에만 필요한 `operationKey`만 포함하고 raw payload, exception, provider
응답은 포함하지 않는다. outbox query의 `effectState`는
`PENDING`일 때만 `null`이고, 그 외에는 paired whitelist 상태를 반환한다. `DEAD_LETTER`는
`redriveAllowed=true`일 때만 명령 대상으로 표시하고, `DELIVERY_UNKNOWN`/
`RECONCILE_REQUIRED`는 `reconciliationRequired=true`로 표시한다. response와 audit에는
operation key, state, attempt, nextAttemptAt, requestId만 남기며 원문 payload는 재노출하지 않는다.
`COMMAND_IN_PROGRESS` error DTO에만 bounded `operationKey`를 추가하고, 다른 error에서는
이 field를 생략한다. caller는 이 값을 `GET /api/warehouse-allocation/outbox/{operationKey}`의
polling target으로 사용한다.
`nextAction`의 허용 값은 `POLL`, `REPLAN`, `SHRINK_DATASET`, `RETRY_AFTER`, `RECONCILE`,
`NO_RETRY`뿐이다. `COMMAND_IN_PROGRESS`는 `operationKey` query를
polling하고, `PLANNER_CAPACITY_EXCEEDED`는 `Retry-After: 5` header와
`retryAfterSeconds=5`를 함께 반환한다. unknown path target은 `404 UNKNOWN_TARGET`, 누락되거나
형식이 잘못된 required header/body는 `400` validation error로 처리한다.

응답에는 raw event payload, provider body, credential, JDBC URL, 원문 exception, 개인 정보가
없다. request/header/body/exception log와 metric tag도 field allowlist만 사용하며 raw
`Idempotency-Key`, signature, payload, owner token을 금지한다. 입력 ID와 reason은
control-character와 HTML을 거부한다. `createdBy`는 서버가 고정한 bounded demo actor 또는
검증된 principal에서만 만들고 caller header로 직접 받지 않는다. `X-Request-Id`는
`[A-Za-z0-9._-]{1,128}`만 허용하며 invalid 값은 `400 INVALID_REQUEST_ID`로 거부한다. static
console은 CSP와 text-node rendering만 사용한다.
plan에는 synthetic IDs, 제한된 score와 enum reason만 반환한다. unknown reason이나
free-form solver explanation은 저장하지 않는다. 기본 앱은 `127.0.0.1`에만 바인딩하고
production authentication/CSRF를 제공한다고 주장하지 않는다.

## PostgreSQL 저장 경계

최소 테이블은 다음과 같다.

- `warehouse_alloc_warehouses`: warehouse capability, picker pool/capacity와 revision의 단일 권위
- `warehouse_alloc_stock`: `(warehouse_id, sku)` stock/reserved/source revision와 파생 handling snapshot
- `warehouse_alloc_orders`, `warehouse_alloc_order_lines`: order와 line state/revision
- `warehouse_alloc_waves`: wave cutoff, ordered allocation, warehouse capacity snapshot와
  `warehouse_capacity_revision`, wave revision
- `warehouse_alloc_pins`: active committed allocation pin/revision
- `warehouse_alloc_plans`: plan snapshot, score, generation, fencing, status, digest
- `warehouse_alloc_plan_items`: proposal allocation, wave, reasons
- `warehouse_alloc_reservations`: final reservation state와 unique allocation key
- `warehouse_alloc_event_inbox`: event key/digest/source revision/terminal outcome
- `warehouse_alloc_audits`: append-only transition and redacted reason
- `warehouse_alloc_outbox`: replan/reservation/cancellation work, lease owner/token/expiry,
  fencing/attempt/next-attempt/status
- `warehouse_alloc_idempotency`: method/route/demo scope/key fingerprint, `IN_PROGRESS`/
  `RETRYABLE`/`COMPLETED`/`FAILED_TERMINAL` state, claim owner/token/expiry, attempt,
  `nextRetryAt`, bounded response/error code와 terminal response
- `warehouse_alloc_outbox_effects`: operation/effect key claim, local completion 또는 external
  reconciliation 상태

각 repository는 expected revision 조건을 포함한 update와 `affectedRows == 1` 검사를
사용한다. 단순 `updateById`로 reservation 권위를 갱신하지 않는다. order line의
`active_plan_id` CAS가 서로 다른 plan의 동시 승인을 막고, reservation row는 같은 plan의
split allocation만 중복 없이 기록한다. warehouse capability/picker capacity를 변경하는
event와 approval은 같은 warehouse revision CAS를 사용한다. index는 자연키, revision, event
lookup, pending outbox lease, operation/effect key, plan generation, history cursor를
기준으로 추가하며, migration은 demo schema fixture와 함께 검증한다. outbox/effect 상태 전이와
`RECONCILE_REQUIRED` redrive 조건도 database check/unique constraint와 integration test로
고정한다.

## Ecosystem capability selection

| 책임 | 재사용할 capability | 사용하지 않는 이유/제약 | 대체 fixture |
|---|---|---|---|
| BOM/버전 | root `bluetape4k-dependencies` | consumer repo에서 개별 library BOM을 직접 import하지 않음 | 없음 |
| persistence | Exposed JDBC + PostgreSQL | 재고 권위는 PostgreSQL transaction/CAS여야 함 | Testcontainers PostgreSQL |
| serialization | Jackson 3 alias | raw provider payload를 보존하지 않음 | canonical bounded DTO |
| logging | `bluetape4k-logging` | 민감 payload를 로그하지 않음 | redacted event fields |
| IDs | Bluetape ID generator alias | 임의 UUID helper를 새로 추가하지 않음 | deterministic test seed |
| HTTP | Spring Boot MVC/Validation | provider HTTP는 기본 실행 전제가 아님 | local REST + fixture adapter |
| concurrency | Bluetape virtual-thread API/JDK25 runtime | lifecycle/cancellation 증거 없는 provider worker에는 적용하지 않음 | bounded synchronous fake |
| testing | Bluetape assertions/JUnit/Testcontainers | raw GenericContainer와 generic assertions를 중복하지 않음 | shared PostgreSQL launcher |
| #524 lifecycle | versioned HTTP/fixture contract | 내부 `project()` dependency는 boundary를 우회함 | recorded stale-callback fixture |
| Kafka/WMS/carrier | 사용하지 않음 | 이슈에서 optional provider이고 배포 evidence가 없음 | deterministic outbox events |
| Solver | custom Timefold service dependency를 사용하지 않음 | 승인된 dependency policy/deployment가 현재 없음 | deterministic planner |

blocking JDBC/HTTP와 outbox worker는 lifecycle fixture가 통과한 경우에만 Bluetape Java 25
virtual thread로 실행한다. CPU-bound planner는 bounded executor에서 실행한다. shutdown은
admission close → in-flight cancellation/quiescence → lease release → executor drain 순서로
진행하고 30초 안에 완료해야 한다. cancellation은 permit/lease를 `finally`에서 반환하고,
timeout·failure는 `DEAD_LETTER` 또는 retryable outcome으로 기록한다. outbox worker는 최대
4개 동시 job, batch 20개, job timeout 10초, DB lock timeout 2초, queue 100개로 제한하고,
각 lease는 15초 동안 유효하며 5초마다 renew를 시도하고, cancellation grace는 5초로 둔다.
lease 상실은 provider call 전에 감지하면 retryable, call 후 확인 불가 상태면
`RECONCILE_REQUIRED`, 5회 재시도 초과는 `DEAD_LETTER`로 매핑한다. outbox executor queue
초과는 `COMMAND_IN_PROGRESS` 또는 retryable outcome으로 남긴다. lifecycle 증거가
실패하면 virtual-thread provider worker를 사용하지 않고 bounded platform executor로
되돌린다.

운영 관측성은 다음 low-cardinality metric 이름과 label만 사용한다:
`warehouse_allocation_inbox_events_total{outcome}`,
`warehouse_allocation_approval_conflicts_total{reason}`,
`warehouse_allocation_planner_jobs_total{outcome}`,
`warehouse_allocation_outbox_queue_depth`,
`warehouse_allocation_outbox_attempts_total{outcome}`,
`warehouse_allocation_outbox_lease_expiry_total`,
`warehouse_allocation_db_lock_wait_seconds`. ID, key, payload, owner token은 label로 쓰지
않는다. readiness는 schema 준비·executor 상태·Docker fixture availability를 표시하고,
liveness는 process event loop만 확인한다. 검증된 `X-Request-Id`만 audit와 연결하고 원문
header를 metric label로 사용하지 않는다.

## 실패·복구 계약

| 시나리오 | 보장할 결과 | 증거 |
|---|---|---|
| stock 부족 또는 stale approval | 일부 예약도 남기지 않고 `RESERVATION_CONFLICT` rollback | PostgreSQL integration test |
| duplicate inventory event | 동일 digest no-op, audit/outbox 중복 없음 | inbox replay test |
| out-of-order inventory event | 낮은 revision은 `STALE_EVENT`, 최신 상태 유지 | event ordering test |
| solver restart | 이전 fencing token 결과 무시, 최신 generation만 terminal | restart fixture test |
| reservation retry | 동일 reservation key replay 또는 stable rejection | idempotency/CAS test |
| order cancellation | plan/reservation/cancel audit가 terminal history로 수렴 | cancellation integration test |
| pinned allocation replan | pin 보존 또는 명시적 `PIN_CONFLICT`, 임의 이동 없음 | planner + approval test |
| carrier cutoff 변경 | 영향을 받은 proposal stale, cutoff 이후 배정 금지 | cutoff fixture test |
| picker capacity 변경 | overflow가 다른 wave 또는 `PICKER_CAPACITY`로 설명됨 | wave capacity test |
| warehouse incident | incident warehouse 후보 제외, 기존 committed reservation은 권위 유지 | incident replay test |

outbox handler의 side effect가 완료된 뒤 process가 종료되는 crash-injection fixture도
검증한다. Testcontainers/PostgreSQL 검증은 `TestMutexService`를 사용하고, 각 명령은
`--max-workers=1`로 순차 실행한다. partial multi-row rollback, 다른 plan의 동시 승인,
approve×cancel/cutoff/capacity/pin race, duplicate event concurrent insert, lease expiry와
dead-letter recovery, database/process restart를 모두 포함한다. 각 fixture는 최종 상태의
row count, local effect count, audit count, 최대 lock wait를 assertion하고 실패 시 redacted
database snapshot/report artifact를 남긴다. external relay fixture는 crash-before-send,
crash-after-send, stale-owner complete를 분리해 at-least-once와 reconciliation 결과를
검증한다. worker가 claim하기 전 revision 5/6 concurrent ingest는 aggregate 최종 revision 6,
`maxRevision=6`인 coalesced pending replan intent 1개, aggregate overwrite 없음이라는
invariant를 갖는다. revision 6이 먼저 적용되고 revision 5가 뒤따른 실행에서는 낮은
revision CAS의 `STALE_EVENT` audit 1개를 요구한다. revision 5가 먼저 적용되고 revision 6이
뒤따른 실행에서는 두 CAS가 모두 정상 적용될 수 있으므로 stale audit를 필수로 세지 않는다.

### 상태 전이

| 상태 | 허용 전이 | 반복/충돌 처리 |
|---|---|---|
| Plan `DRAFT` | `STALE`, `APPROVED`, `REJECTED`, `CANCELLED` | 같은 command replay, 다른 plan/old generation은 conflict |
| Plan `APPROVED` | `CANCELLED` | approve 재호출은 저장 결과 replay |
| Plan `STALE`/`REJECTED`/`CANCELLED` | 없음 | terminal audit만 남김 |
| Reservation `PENDING` | `ACCEPTED`, `REJECTED`, `CANCELLED` | retry는 operation/effect key로 replay |
| Reservation `ACCEPTED` | `RELEASED`, `CANCELLED` | 중복 cancellation은 no-op audit |
| Reservation `REJECTED`/`RELEASED`/`CANCELLED` | 없음 | 새 plan만 생성 가능 |
| Outbox `PENDING` | `CLAIMED`, `RETRYABLE` (effect `CLAIMED` 또는 `RETRYABLE` 동시 생성) | DB clock과 admission 조건으로만 claim; `CLAIMED` 전이는 outbox/effect lease·fence를 같은 transaction에서 함께 획득 |
| Outbox `CLAIMED` | `DELIVERED`, `DELIVERY_UNKNOWN`, `RETRYABLE`, `DEAD_LETTER` | stale owner의 complete/renew는 0 rows |
| Outbox `DELIVERY_UNKNOWN` | `CLAIMED`, `DELIVERED`, `RETRYABLE`, `DEAD_LETTER` | reconciliation worker가 effect와 함께 두 lease/fence를 원자 claim |
| Outbox `DELIVERED` | 없음 | 동일 operation key redrive 금지 |
| Outbox `RETRYABLE` | `CLAIMED`, `DEAD_LETTER` | `nextAttemptAt` 이후 재claim; `CLAIMED` 전이는 effect `RETRYABLE → CLAIMED`와 두 lease·fence를 같은 transaction에서 함께 획득; 최대 시도 초과는 paired dead-letter |
| Outbox `DEAD_LETTER` | `RETRYABLE` (operator only) | effect와 함께 새 lease/redrive audit |
| Effect `CLAIMED` | `COMPLETED`, `RETRYABLE`, `RECONCILE_REQUIRED`, `DEAD_LETTER` | external I/O crash 후 provider 조회 또는 retry |
| Effect `RETRYABLE` | `CLAIMED`, `DEAD_LETTER` | `nextAttemptAt` 이후에만 재claim |
| Effect `COMPLETED` | 없음 | local side effect 재생성 금지 |
| Effect `RECONCILE_REQUIRED` | `CLAIMED`, `COMPLETED`, `RETRYABLE`, `DEAD_LETTER` | reconciliation lease를 먼저 claim |
| Effect `DEAD_LETTER` | `RETRYABLE` (operator only) | 새 lease와 redrive audit 필요 |

startup recovery는 만료된 outbox lease와 idempotency `IN_PROGRESS` claim을 한 pass 최대
20행, recovery worker 1개, batch deadline 2초, stable primary-key 순서로 reclaim한다. 21행
fixture는 첫 pass에서 정확히 20행만 처리하고 남은 1행은 다음 pass로 넘기며, deadline을 넘긴
행은 mutation 없이 다음 pass에서 재시도한다. plan/reservation/outbox를 만들기 전에 난
crash의 만료 claim은 항상 `RETRYABLE`로, durable DB commit 후 response 전에 난 crash는
`COMPLETED`로 판정한다. `PLANNER_CAPACITY_EXCEEDED` 이외의 입력/비용/출력 admission
failure는 `FAILED_TERMINAL`로 유지하고, retryable claim이 attempt/retention 경계를 넘은
경우에만 `RETRY_EXHAUSTED`를 기록한다. recovery는 fingerprint, attempt와 `nextRetryAt`을
보존하고 retryable retention/max-attempt 경계를 적용한다.
재시도 전 동일 key는 저장된 retryable 응답과 `Retry-After`를 받고, 시각 이후에는 새
claim token으로 `IN_PROGRESS`에 진입한다. poison row는
`DEAD_LETTER` projection에서 조회할 수 있게 한다. operator redrive는 동일 operation key와
새 lease를 사용하며, 원문 payload를 재노출하지 않는다. `RECONCILE_REQUIRED`는
reconciliation worker가 outbox `DELIVERY_UNKNOWN`과 effect를 같은 transaction에서 함께
`CLAIMED`로 전이하고 두 lease/fence를 검증한 뒤 처리한다. `PENDING → CLAIMED`를 수행할
때는 effect `CLAIMED` row를 같은 transaction에서 생성하고 outbox/effect의 lease owner,
lease token, fencing token, expiry를 함께 기록한다. `RETRYABLE → CLAIMED`도 effect
`RETRYABLE → CLAIMED` 전이와 두 lease/fence 갱신을 같은 transaction에서 수행하며, 어느
한 row의 affected rows가 `0`이면 전체를 rollback한다. 최대 시도 초과는
outbox와 effect를 같은 transaction에서 동시에 `DEAD_LETTER`로 전이한다. operator redrive는
두 row를 함께 `DEAD_LETTER → RETRYABLE`로 바꾸고 redrive audit를 남긴다. 이미 local
`COMPLETED`인 operation은 거부한다. local effect가 `RETRYABLE`이면 outbox도 `RETRYABLE`로
남아 다시 claim한다. external send가 끝났지만 확인되지 않은 경우에는 outbox
`DELIVERY_UNKNOWN`과 effect `RECONCILE_REQUIRED` 조합을 사용한다. reconciliation 결과와
outbox/effect 전이는 한 transaction에서 함께 반영하며, effect row가 존재하는 동안
`CLAIMED ↔ CLAIMED`, `Outbox DELIVERED ↔ Effect COMPLETED`,
`DELIVERY_UNKNOWN ↔ RECONCILE_REQUIRED`, `RETRYABLE ↔ RETRYABLE`,
`DEAD_LETTER ↔ DEAD_LETTER` 조합만 허용한다. 아직 effect를 claim하지 않은
`Outbox PENDING`은 effect row가 없는 유일한 예외다. `PENDING → RETRYABLE`을 수행할 때는
effect `RETRYABLE` row와 `nextAttemptAt`을 같은 transaction에서 생성해 orphan outbox를
허용하지 않는다.

## 테스트 invariant와 실행 명령

구현 계획은 아래 fixture 이름과 invariant를 그대로 사용한다. 테스트가 이름만 존재하거나
Docker가 없어서 skip된 경우는 PASS로 분류하지 않는다.

### Planner unit fixture

`WarehouseAllocationPlannerTest`는 다음 synthetic fixture를 고정하고, 각 line case는 동일
snapshot을 reset해 독립적으로 실행한다.

- `warehouse-a`: cold-chain/hazmat 가능, `sku-1` available `5`, wave capacity `2`
- `warehouse-b`: 일반 보관만 가능, `sku-1` available `4`, wave capacity `2`
- `line-cold`: `sku-1` quantity `3`, `COLD_CHAIN`, cutoff 이전 wave 존재
- `line-hazmat`: `sku-1` quantity `2`, `HAZMAT`, warehouse-a만 후보
- `line-over`: `sku-1` quantity `8`, split 허용

positive/negative 테스트는 `allocatedQuantity <= availableQuantity`, cold/hazmat capability
없는 warehouse에는 allocation 없음과 닫힌 reason, cutoff 경계 이전/이후의 배정 차이,
pin warehouse·quantity 보존을 검증한다. feasible case의 기대 결과는 `line-cold`가
warehouse-a에 정확히 `3`, `line-hazmat`가 warehouse-a에 정확히 `2`, `line-over`가
warehouse-a `5`와 warehouse-b `3`으로 정확히 `8`을 배정하는 것이다. feasible case의
`hardScore=0`, split case의 `split-shipment` reason/medium penalty, cutoff 이후 case의
배정 없음과 cutoff reason, pinned case의 pin warehouse·quantity 고정을 정확히 assertion한다.
split 결과는 line별 allocation 합이 requested quantity와 같고, 동일 `(warehouseId, sku)`의
planner 합계가 snapshot available 이하인지 검증한다. 따라서 모든 allocation을 0으로
반환하는 no-op planner는 통과할 수 없다. 동일 snapshot/seed 두 번 실행의 allocation
순서·score·digest는 완전히 같아야 한다.

`WarehouseAllocationPlannerResourceContractTest`는 resource guard를 wall-clock에 의존하지
않는 deterministic fixture로 검증한다. 501개 line fixture는 `PLANNER_INPUT_TOO_LARGE`와
`413`, plan/reservation/outbox row `0`, idempotency `FAILED_TERMINAL`, bounded audit 1개와
동일 key의 413 response replay를 요구한다. line 500개·warehouse
100개·wave 200개 fixture는 후보 평가량 `10_000_000`으로 admission에서 거부되어 같은
`413`과 plan/reservation/outbox row `0` no-write를 남긴다. warehouse 101개, wave 201개,
stock row 10,001개, pin 501개 fixture도 각각 dimension 상한을 넘겨 동일한
`PLANNER_INPUT_TOO_LARGE`, `413`, plan/reservation/outbox row `0`을 남긴다.
candidate boundary fixture는 line 100개·warehouse 100개·wave 200개로 정확히 `2_000_000`을
허용하고, line 101개·warehouse 100개·wave 200개로 `2_020_000`을 거부해 경계 조건을
고정한다.
allocation 501개 또는 reason 501개를 만들도록 고정한 output fixture는
`PLANNER_OUTPUT_TOO_LARGE`, `422`, idempotency `FAILED_TERMINAL`, plan/reservation/outbox
row `0`과 동일 key response replay를 assertion한다.
`StepBudgetPlannerClock`을 주입한 deadline fixture는 고정된
evaluation step에서 `PLANNER_DEADLINE_EXCEEDED`, `422`, `FAILED_TERMINAL` idempotency와
plan/reservation/outbox row `0`을 assertion한다. `BlockingPlannerExecutor`는 running 2개와
queued 20개를 채운 뒤 전체 23번째 요청(queued 기준 21번째)을 거부해
`PLANNER_CAPACITY_EXCEEDED`, `503`, `Retry-After: 5`, idempotency `RETRYABLE`을 만든다.
거부 transaction은 plan/reservation/outbox row `0`이다. 같은 key를 즉시 다시 호출하면
operationKey를 포함하지 않은 `RETRYABLE` 503과 `Retry-After`를 받고, capacity를 해제한
뒤 5초 후 다시 claim해 정상 plan/outbox가 생성되고 최종 response를 replay하는지 검증한다.
`COMMAND_IN_PROGRESS`를 반환하는 별도 durable command fixture만 operationKey를 포함한
202와 operation query polling을 검증한다.

```bash
./gradlew :optimization-warehouse-allocation:test \
  --tests '*WarehouseAllocationPlannerResourceContractTest' \
  --max-workers=1 --console=plain
```

`WarehouseAllocationIdempotencyRecoveryTest`는 (1) claim transaction이 plan/reservation/outbox
mutation 전에 crash, (2) lease expiry, (3) `Retry-After` 이전 동일 key, (4) 이후 재claim,
(5) durable DB commit 후 response process crash, (6) terminal response replay를 순서대로
실행한다. (1)의 만료 claim은 항상 `RETRYABLE`, (5)는 항상 `COMPLETED`로 복구되며,
`PLANNER_CAPACITY_EXCEEDED` 이외의 입력/admission failure는 `FAILED_TERMINAL`로 replay된다.
plan/reservation/outbox partial row는 모두
`0`이다. queue saturation은 operationKey를 만들지 않으며, 동일 key의 retryable response와
`Retry-After`만 반환한다. 별도 `COMMAND_IN_PROGRESS` durable command response에는
operationKey가 있고, 해당 operation query가 최종 상태와 동일 fingerprint response를
반환하는지 golden JSON으로 고정한다. 같은 key를 5회 실패시키거나 retryable retention
24시간을 넘기거나 최대 시도를 소진하면 `FAILED_TERMINAL/RETRY_EXHAUSTED`로 전이하고
`409`, `retryable=false`, `nextAction=NO_RETRY`와 이후 동일 response를 replay하는지
확인한다. startup recovery 21-row fixture는 첫 pass 20행/다음 pass 1행과 deadline no-write를
확인한다.

```bash
./gradlew :optimization-warehouse-allocation:test \
  --tests '*WarehouseAllocationIdempotencyRecoveryTest' \
  --max-workers=1 --console=plain
```

### Event/PostgreSQL replay matrix

`EventInboxPostgresTest`는 여섯 event를 각각 단독·중복·낮은 revision·key reuse로 실행한다.

| event | state invariant | side-effect invariant |
|---|---|---|
| `inventory.adjusted` | stock revision/available이 최신 값 | 같은 digest 재입력은 inbox 1행, replan intent 1개 |
| `reservation.rejected` | reservation `REJECTED`, line은 재계획 가능 | rejection audit 1개, 다른 reservation 미변경 |
| `order.cancelled` | line `CANCELLED`, parent order revision/status CAS, active plan marker 해제 | reservation release/cancel과 replan intent 원자성 |
| `carrier.cutoff.changed` | line cutoff revision 증가 | cutoff 이후 allocation stale, outbox 1개 |
| `picker.capacity.changed` | Warehouse picker revision 증가, wave snapshot stale | overflow reason 또는 다음 wave, capacity owner는 Warehouse |
| `warehouse.incident` | Warehouse capability revision 증가/중지 | incident warehouse 제외, committed reservation 미변경 |

동일 key·다른 digest는 `EVENT_KEY_REUSED`이고 모든 aggregate/inbox side effect를 추가하지
않는다. 서로 다른 event key가 같은 aggregate와 동일 `sourceEventRevision`을 사용하지만
다른 digest를 보내는 fixture는 `EVENT_REVISION_CONFLICT`, aggregate/inbox/outbox no-write,
audit 1건을 assertion한다. revision 6 후 revision 5를 넣는 순서는 `STALE_EVENT` audit를
1개 남기고, revision 5 후 6 순서는 두 상태 변경을 보존하되 aggregate 최종 revision 6과
coalesced replan 1개를 만든다. HTTP contract fixture는 이 세 conflict를 각각 `409` fixed
error DTO(`retryable=false`, `nextAction=NO_RETRY`)로 확인하고, same-digest duplicate만
`202 state=DUPLICATE`와 기존 operation key를 replay한다. 실행 명령은 다음과 같다.

추가 negative fixture는 유효한 `eventType`을 유지한 채 target을 다른 aggregate로 바꾸거나
payload discriminator/field를 다른 event schema로 바꾼다. 각 요청은 `400 INVALID_REQUEST`와
inbox/aggregate/outbox row `0`, audit row `0`을 남기고, canonical digest나 idempotency row도
생성하지 않아야 한다.

```bash
./gradlew :optimization-warehouse-allocation:test \
  --tests '*WarehouseAllocationPlannerTest' \
  --tests '*EventInboxPostgresTest' \
  --tests '*WarehouseAllocationHttpContractTest' \
  --max-workers=1 --console=plain
```

### Reservation CAS와 HTTP/browser fixture

`WarehouseAllocationReservationPostgresTest`는 두 plan이 동일 `sku-1`을 두 warehouse에서
split하는 fixture를 동시에 승인한다. 승자만 stock reserved delta와 reservation row를
반영하고, 패자는 `RESERVATION_CONFLICT`; partial reservation row `0`, line
`active_plan_id`는 승자 plan 또는 NULL, audit는 한 terminal history라는 invariant를
검증한다. 이미 다른 plan이 active인 approval은 내부 `ACTIVE_PLAN_CONFLICT`를 fixed HTTP
`409` error DTO로 매핑하고 reservation/stock no-write를 유지한다. `approve×cancel/cutoff/capacity/pin` 경합은 lock wait가 2초를 넘지 않고
deadlock 없이 한 terminal outcome으로 끝나야 한다.
plan snapshot의 `expectedOrderRevision`을 인위적으로 stale하게 만든 approval fixture도
`409 RESERVATION_CONFLICT`, order/line/stock/reservation no-write, audit bounded conflict를
확인한다.

`WarehouseAllocationOrderCancellationContractTest`는 order에 두 active line을 seed한 뒤
`expectedOrderRevision`이 맞는 order-level cancel이 두 line을 모두 `CANCELLED`로 만들고
parent `Order.revision`을 한 번 증가시키며 reservation release/cancel과 outbox intent를
같은 transaction에서 남기는지 검증한다. stale expected revision은 `409 RESERVATION_CONFLICT`
와 no-write로 끝나며, line-scoped `order.cancelled` event도 parent order lock과 line CAS를
거쳐 동일한 aggregate status/revision 규칙을 사용하는지, GET order가 `OrderStatus`, revision,
각 `OrderLineStatus`와 bounded `reservations[]`를 일관되게 반환하는지 golden fixture로 고정한다.
all-cancelled,
all-fulfilled, all-open, mixed-line 네 fixture가 각각 `CANCELLED`, `COMPLETED`, `OPEN`,
`PARTIALLY_ALLOCATED`로 projection되는지와 duplicate no-op이 order revision을 올리지 않는지도
assertion한다.

```bash
./gradlew :optimization-warehouse-allocation:test \
  --tests '*WarehouseAllocationOrderCancellationContractTest' \
  --no-build-cache --max-workers=1 --console=plain
```

`WarehouseAllocationOutboxStateIntegrationTest`는 effect가 없는 `PENDING`에서
`CLAIMED/CLAIMED`를 원자 생성하는 fixture와 `RETRYABLE/RETRYABLE`에서
`CLAIMED/CLAIMED`를 원자 claim하는 fixture를 먼저 검증한 뒤 outbox/effect pair를
`CLAIMED/CLAIMED`,
`DELIVERY_UNKNOWN/RECONCILE_REQUIRED`, `RETRYABLE/RETRYABLE`,
`DEAD_LETTER/DEAD_LETTER`, `DELIVERED/COMPLETED` 순서로 각각 만들고, max-attempt 전이와
`DELIVERY_UNKNOWN/RECONCILE_REQUIRED → CLAIMED/CLAIMED` reconciliation claim,
operator-only redrive가 두 row를 한 transaction에서 함께 바꾸는지 검증한다. `PENDING →
CLAIMED`와 `RETRYABLE → CLAIMED` 각각에서 lease/fence 불일치 또는 stale owner가 있으면
affected rows `0`, 두 row 원상복구, orphan `CLAIMED` row `0`이어야 한다. effect가 없는
`PENDING`만 pair invariant의 예외이며, `PENDING → RETRYABLE` admission/recovery는 effect
`RETRYABLE`을 함께 생성하고 orphan row가 없어야 한다. stale owner의 complete/renew는
affected rows `0`이고 pair 상태를 바꾸지 않아야 한다.

```bash
./gradlew :optimization-warehouse-allocation:test \
  --tests '*WarehouseAllocationOutboxStateIntegrationTest' \
  --no-build-cache --max-workers=1 --console=plain
```

`WarehouseAllocationHttpContractTest`와 `WarehouseAllocationBrowserContractTest`는 응답과
로그에 raw payload, credential, JDBC URL, 원문 exception, PII가 없고 secret/HTML canary가
text node로만 렌더링되며 CSP가 존재하는지 확인한다. mutation header/idempotency 누락은
DB no-write, non-demo profile은 mutation route 부재, concrete target이 다른 같은 key는
fingerprint conflict를 검증한다. `WarehouseAllocationOperatorRecoveryContractTest`는
outbox query의 paired 상태·시도 횟수·redrive/reconciliation 표시, redrive `202`와
`OUTBOX_NOT_REDRIVABLE` `409`, `RESERVATION_CONFLICT` `409`, planner 입력/자원 오류의
`413`/`422`/`503` status와 `retryable`·`nextAction`을 검증한다. `RESPONSE_TOO_LARGE`
`413`과 `INVALID_REQUEST`/`INVALID_REQUEST_ID` `400`도 검증한다. 모든 response에 raw payload가
없고 동일 idempotency key의 redrive 결과가 replay되는지 확인한다. `COMMAND_IN_PROGRESS`
`202`는 operation query polling target과 `requestId`를 반환하고, queue saturation `503`은
`Retry-After: 5`와 body `retryAfterSeconds=5`를 함께 반환하는지 검증한다. 누락/잘못된
required header 또는 body는 `400`과 DB no-write, 없는 concrete target은 `404 UNKNOWN_TARGET`
과 DB no-write인지 확인한다. malformed event discriminator, unknown nested field, duplicate
JSON key, missing mutation header는 모두 `400 INVALID_REQUEST`로 같은 golden error DTO를
반환하는지 확인한다. order line의 no-plan/no-pin/no-reservation fixture(`reservations=[]`),
replan의 queued/succeeded/stale fixture(`STALE_SOLVER_RESULT`), outbox의 pending/delivered/dead-letter fixture가 nullable field를
생략하지 않고 규칙대로 `null`로 직렬화하는지도 확인한다.

```bash
./gradlew :optimization-warehouse-allocation:test \
  --tests '*WarehouseAllocationHttpContractTest' \
  --tests '*WarehouseAllocationBrowserContractTest' \
  --tests '*WarehouseAllocationOperatorRecoveryContractTest' \
  --no-build-cache --max-workers=1 --console=plain
```

### 모듈 DoD 명령

```bash
./gradlew :optimization-warehouse-allocation:test --no-build-cache --max-workers=1 --console=plain
./gradlew :optimization-warehouse-allocation:build :optimization-warehouse-allocation:detekt \
  --no-build-cache --max-workers=1 --console=plain
./scripts/smoke-validate.sh optimization
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
actionlint .github/workflows/Examples.yml
./gradlew projects --console=plain
test -f optimization/warehouse-allocation/src/test/resources/junit-platform.properties
test -f optimization/warehouse-allocation/src/test/resources/logback-test.xml
git diff --check
```

## 수용 기준과 테스트 매핑

| 요구사항 | 구현 증거 | 테스트 증거 |
|---|---|---|
| order line/stock/wave/rule/capacity/pin 모델 | `domain/` aggregate와 validation | domain validation tests |
| hard constraint | `DeterministicWarehousePlanner` | stock/capability/cutoff/pin tests |
| score/reason/history 표시 | plan read model과 static console | JSON contract + browser smoke |
| deterministic outbox/Kafka fixture | `adapter/fake/` event sequences | duplicate/order/restart replay tests |
| PostgreSQL reservation authority | reservation repository conditional update | Testcontainers oversell race test |
| stale plan oversell 방지 | approval transaction + expected revisions | concurrent approval test |
| cancellation/retry/pinned replan 수렴 | state transition/audit/outbox | terminal history assertions |
| provider dependency 비전제 | fake profile/default wiring | clean build without provider credentials |
| Java 25/BOM/capability policy | module build and catalog aliases | Gradle dependency/compile check |
| #524 request/callback compatibility | versioned adapter/fixture mapping | golden request/callback contract test |
| #524 exact wire/query binding | create `202 {id,status}`, callback required score fields/decision, exact query read model | request/response JSON golden fixture |
| #524 snapshot version compatibility | immutable aggregate ID per dataset snapshot, version `0` replay | datasetVersion 5→6 연속 요청 + same-snapshot replay contract test |
| active plan uniqueness | order-line active-plan CAS + reservation unique key | different-plan concurrent approval test |
| aggregate race safety | global lock order + all mutable revision CAS | approve×cancel/cutoff/capacity/pin race tests |
| canonical digest/idempotency | `warehouse-canonical-v1`, namespaced key store | golden digest and concurrent replay tests |
| idempotency admission lifecycle | `IN_PROGRESS`/`RETRYABLE`/`FAILED_TERMINAL` transitions, `RETRY_EXHAUSTED` mapping, and same-key replay | saturation retry, output-too-large terminal replay, recovery-bound, and terminal-failure tests |
| planner resource guard | dataset cardinality, candidate bound, executor/queue/deadline limits | input-too-large, deadline, queue-saturation contract tests |
| lifecycle/recovery | virtual-thread gate, bounded retry/dead-letter, startup reclaim | cancellation/crash-injection/restart tests |
| outbox delivery boundary | local DB effect exactly-once; external relay at-least-once + provider key/reconciliation | crash-before/after-send, stale-owner 0-row, redrive tests |
| operator recovery/caller contract | redacted outbox query, paired redrive command, fixed HTTP status/error DTO | operator recovery HTTP contract test |
| planner resource/output guard | cardinality/candidate/output/response bounds and no-write admission | `WarehouseAllocationPlannerResourceContractTest` input/dimension/2M/deadline/queue/output fixtures |
| event conflict/no-write | target binding, key reuse, revision conflict, stale event and duplicate replay | `EventInboxPostgresTest` Postgres no-write matrix + `WarehouseAllocationHttpContractTest` HTTP 409/202 mapping |
| order projection/cancellation | parent order CAS, line fan-out, truth-table projection and bounded reservations | `WarehouseAllocationOrderCancellationContractTest` |
| #524 planning seam | FAKE/HMAC, canonical digest, callback/query binding and dataset 5/6 IDs | `WarehouseAllocationPlanningContractTest` |

## 저장소 등록과 문서 동기화 범위

새 module은 settings helper가 자동 등록하더라도 다음 surface를 함께 갱신한다.

- `optimization/warehouse-allocation/build.gradle.kts`의 `java-test-fixtures` plugin과 test resources
- `optimization/warehouse-allocation/src/testFixtures/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/fixture/WarehouseAllocationFixturePort.kt`
- `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/WarehouseAllocationApplication.kt`
- `optimization/warehouse-allocation/src/main/resources/application.yml`와
  `src/test/resources/application-test.yml`; `springBoot.mainClass`는
  `WarehouseAllocationApplicationKt`로 고정
- `optimization/warehouse-allocation/src/test/resources/junit-platform.properties`로
  TestMutexService와 test parallelism을 고정
- `optimization/warehouse-allocation/src/test/resources/logback-test.xml`로 redacted test
  logging pattern과 optimization logger level을 고정
- `optimization/README.md`, `optimization/README.ko.md`, module README pair
- `.github/workflows/Examples.yml`의 path filter, smoke/full task, report artifact
- `scripts/smoke-validate.sh`의 optimization module 목록과 stale-check 대상
- validation matrix와 lessons 문서에서 module name, command, scope를 추가
- root BOM/catalog에는 개별 bluetape version pin이나 별도 library BOM을 추가하지 않음

현재 파일의 정확한 등록 위치도 구현 checklist에 고정한다. `Examples.yml:11,67`의
`optimization/**` path filter는 새 module을 이미 포괄하므로 path 항목을 중복 추가하지
않고, container task `Examples.yml:480-481` 뒤에
`:optimization-warehouse-allocation:test`를 추가한다. 같은 workflow의 test result/report
allowlist `Examples.yml:559-562`에는
`optimization/warehouse-allocation/build/test-results/test/*.xml`와
`optimization/warehouse-allocation/build/reports/tests/test/`를 추가한다.
`scripts/smoke-validate.sh:189-194` optimization case에는
`:optimization-warehouse-allocation:test`를 추가하고, stale-check의 required module
배열 `scripts/smoke-validate.sh:316`에는
`optimization/warehouse-allocation`을 추가해 `build.gradle.kts`, `README.md`,
`README.ko.md`를 검사한다. 현재 `optimization/README.md`와 `README.ko.md`의 module 표와
명령 블록에 새 module을 추가한다. 중앙 검증 기준은
`docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md:60`의 T3 모듈 표와
`docs/lessons/2026-05-23-issue-91-validation-matrix.md:50`의 신규 Testcontainers 모듈
등록 lesson을 함께 갱신하고, 새 `docs/lessons/2026-08-24-issue-530-warehouse-allocation.md`
에는 이번 child의 module/command/scope를 기록한다. matrix T3에는
`:optimization-warehouse-allocation | PostgreSQL`을 추가하고 T4 optimization 실행 명령은
`./gradlew :optimization-warehouse-allocation:test --max-workers=1`을 포함한다. 이 exact path/task/artifact mapping은 implementation PR에서
`actionlint`, smoke/stale-check, README parity/language로 확인한다.

README.md와 README.ko는 같은 module 설명·명령·비목표·provider limitation을 유지하고,
코드/API identifier와 command는 그대로 둔다. public KDoc과 lesson은 한국어로 작성한다.

## 승인된 범위와 후속 결정

이번 child에서 승인된 것은 deterministic reference application, PostgreSQL reservation
authority, fixture 기반 failure proof, redacted local console다. 다음 항목은 구현하지 않고
후속 결정으로 남긴다.

- 실제 Timefold/custom Solver adapter: dependency policy와 deployment evidence가 생긴 뒤
  versioned adapter issue로 분리
- 실제 Kafka/WMS/carrier/picker provider: provider contract와 운영 인증이 준비된 뒤
  별도 integration issue로 분리
- 다른 Epic child가 공통으로 사용할 inventory/optimization library: 두 개 이상의 실제
  consumer에서 반복이 확인된 뒤 API 설계부터 재검토
- #528의 Clinic Appointment/Resource Optimizer 범위: live issue body와 최신 comment의
  warehouse/clinic 방향이 합의되기 전까지 이번 child에서 다루지 않음

이 문서에 없는 생산성·처리량·fulfillment 품질 주장은 하지 않는다. 구현 중 요구사항이
달라지면 먼저 이 문서와 테스트 매핑을 갱신하고, 영향받는 계획과 검토 gate를 다시 연다.
