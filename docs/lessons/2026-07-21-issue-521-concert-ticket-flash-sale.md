# Issue #521 콘서트 티켓 Flash Sale 예제 교훈

## 핵심 교훈

- Redis의 원자성은 foreground 중복 억제에만 사용하고 PostgreSQL USER/IP guard를 남긴다.
- 결제 timeout을 실패로 해석하지 않고 stable operation ID로 조회한다.
- 환불과 ticket disposition이 모두 확정되기 전에는 재고를 복구하지 않는다.
- 일반 DB 접근은 JetBrains Exposed와 Bluetape4k의 정확한 `ExposedJdbcRepository` 경계 안에 둔다.
  Direct JDBC는 versioned startup migration에만 허용하고 architecture test로 이 예외를 고정한다.

## 권한을 분리해야 복구가 단순해진다

Redis waiting-room grant와 USER/IP lease는 burst를 흡수하는 임시 조정 장치다. Redis가 재시작되어도
판매 수량, 활성 구매, 결제 결과를 재구성하려 하지 않는다. PostgreSQL이 inventory row, durable
USER/IP guard, purchase attempt, payment/refund operation, ticket disposition을 함께 보관하기 때문에
worker가 재시작돼도 stable ID lookup으로 계속 수렴할 수 있다.

이 분리는 장애 정책도 명확하게 한다. Redis 장애 시 신규 purchase admission은 fail closed하지만
liveness와 기존 attempt 조회, payment/refund/ticket recovery는 유지한다. Redis availability를 전체
서비스 liveness와 같은 신호로 묶으면 운영자가 불필요하게 복구 경로까지 중단하게 된다.

## Timeout과 취소는 terminal outcome이 아니다

Provider 호출 timeout 뒤 새 결제를 만들거나 곧바로 재고를 반환하면 late approval과 충돌한다.
이 예제는 authorization operation ID를 먼저 저장하고 `UNKNOWN`을
`RECONCILIATION_REQUIRED`로 전이한다. 취소 요청 후 late approval이 확인되면 ticket disposition을
`NEVER_ISSUED`로 고정하고 환불을 시작한다. 이 경로에서는 티켓 발급 effect 자체를 만들지 않는다.

Claim token과 revision fence는 오래 걸린 worker 결과가 newer recovery 결과를 덮는 일을 막는다.
Provider effect 전에 operation/effect receipt를 남기고 동일 stable ID를 재사용하면 response loss와
process restart를 중복 승인·환불·티켓 발급으로 바꾸지 않아도 된다.

## Restock은 하나의 상태가 아니라 결합 invariant다

`REFUNDED`만 보고 재고를 복구해도 부족하다. 이미 발급된 티켓이 아직 유효하면 같은 좌석을 두 명이
사용할 수 있다. 따라서 refund success와 `NEVER_ISSUED` 또는 `REVOKED` disposition이 함께
확정되어야 한다. `REFUND_PENDING`, `REVOKE_PENDING`, quarantine은 운영 backlog이지 재고 복구
신호가 아니다.

## Exposed repository 경계

초기 구현에서 raw SQL이 일반 repository 동작으로 번질 가능성이 있었다. 최종 구조는 모든 normal
transaction을 Exposed DSL/DAO로 수행하고 repository가 `ExposedJdbcRepository`를 구현하도록 했다.
PostgreSQL의 `FOR UPDATE SKIP LOCKED`처럼 필요한 vendor 기능도 Exposed transaction 안에서
`exec`로 제한한다. Schema bootstrap만 JDBC connection을 직접 사용하며 별도 migration class와
architecture test가 그 예외를 설명한다.

## Kotlin ecosystem 규칙도 실행 가능한 계약이어야 한다

코드 리뷰 체크리스트만으로는 신규 파일에 `UUID.randomUUID`, Kotlin `require`, monitor 기반
`synchronized`가 다시 들어오는 것을 막기 어렵다. 이 예제는 UUID v7 생성과 validation을 Bluetape
helper로 통일하고, 동시성 의도를 `ReentrantLock`으로 드러낸다. 외부 provider와 Redis 장애 경계는
민감한 token이나 payload를 남기지 않는 `KLogging` 이벤트로 관측한다.

Production data class에는 `Serializable`과 명시적인 `serialVersionUID`를 요구한다. 소스 금지 패턴과
컴파일된 data class의 직렬화 계약을 `KotlinPatternArchitectureTest`가 검사하므로, 규칙 위반은 문서와
실제 구현이 벌어진 뒤가 아니라 PR 테스트 단계에서 실패한다. Exposed `insert`/`update` lambda에서는
column과 이름이 겹치는 입력을 먼저 의미 있는 로컬 값으로 추출해 receiver shadowing을 피한다.

## 문서와 데모에서 배운 점

Core의 bounded `TicketEventStream`은 snapshot-first subscription과 slow-consumer eviction을 검증하지만
durable event log나 network SSE endpoint는 아니다. README와 browser demo는 이를 명확히 구분하고
owner-safe polling fallback만 실제 HTTP 계약으로 제시한다. 구현하지 않은 public seed/reset,
purchase-start, SSE endpoint를 문서 편의를 위해 있는 것처럼 쓰지 않는 것이 production 예제의 신뢰성을
높인다.

## 남은 production 과제

- 실제 JWT/IdP와 operator RBAC adapter
- 실제 PG의 operation lookup/idempotency 계약 검증
- 측정된 트래픽으로 capacity 재산정
- Transactional outbox와 durable event high-water mark
- Payment/refund/ticket `ReconciliationJob` wiring과 backlog query
- Trusted proxy allowlist, audit retention, key rotation, backup/restore drill

## 재사용 후보

USER/IP 두 Redis key를 한 번에 생성·검증·해제하고 owner token으로 stale release를 막는 Lua lease는
다른 admission 도메인에서도 쓸 수 있다. 예제 로컬 구현을 성급히 generic API로 만들지 않고
`bluetape4k-lettuce` 후속 이슈 [#1065](https://github.com/bluetape4k/bluetape4k-projects/issues/1065)로
분리했다.
