# 이벤트 소싱 프로모션 바우처 캠페인

[English](README.md) | 한국어

이 Java 25, Spring Boot 4 예제는 캠페인과 바우처 이력을 append-only PostgreSQL
event store에 보관한다. Command는 expected stream version과 idempotency receipt를
사용한다. 별도 lease를 가진 projection이 public read model을 만들며, event
authority를 교체하지 않고도 read model을 rebuild할 수 있다.

## Architecture

![이벤트 소싱 바우처 architecture](../../docs/images/readme-diagrams/event-sourced-promotion-voucher-architecture-01.png)

Spring MVC의 blocking 작업은 Java 25 virtual thread에서 실행하지만 PostgreSQL
동시성은 Spring-managed HikariCP pool로 제한한다. Command, projection, rebuild,
readiness lane은 서로 다른 permit을 예약한다. Event store, stream head,
idempotency receipt, snapshot, projection checkpoint, poison record, generation
pointer, operator audit record는 모두 PostgreSQL에 남는다.

Generic CRUD가 안전한 repository는 bluetape4k `ExposedJdbcRepository`를 사용한다.
Event store와 projection recovery boundary는 의도적으로 CRUD 상속 대신 semantic
operation만 제공한다. 따라서 append fencing, lease ownership, checkpoint,
deduplication, read-model atomicity를 우회할 수 없다.

## Event Envelope

각 `EventEnvelope`에는 다음 필드가 있다.

| 필드 | 계약 |
|---|---|
| `eventId` | UUIDv7 identity |
| `tenantId` | Tenant isolation key |
| `stream.type`, `stream.id`, `stream.version` | Aggregate stream과 expected ordering |
| `globalPosition` | Stream 전체에 걸친 단조 증가 projection cursor |
| `eventType`, `schemaVersion` | Stable type과 upcast version |
| `occurredAt`, `recordedAt` | Domain time과 durable recording time |
| `correlationId`, `causationId` | 추적 가능한 command/event 관계 |
| `actorSurrogate`, `actorHmacKeyVersion` | 삭제 가능한 identity indirection이며 raw identity가 아님 |
| `payload`, `canonicalChecksum` | 크기가 제한된 canonical JSON과 tamper-evident checksum |

Payload는 64 KiB, depth 16, string 하나당 8 KiB로 제한한다. Voucher code, raw
사용자 식별자, idempotency key, authorization, device/IP 같은 민감 필드는 append
전에 거부한다. 알 수 없는 schema와 네 단계를 넘는 upcast chain은 fail closed한다.

## Consistency and Lag

![Command에서 projection까지의 sequence](../../docs/images/readme-diagrams/event-sourced-promotion-voucher-command-projection-sequence-01.png)

성공한 command는 event row와 terminal idempotency receipt를 한 transaction에서
commit한다. Response는 authoritative position과 read-model position을 함께 노출한다.

| Header | 의미 |
|---|---|
| `X-Stream-Position` | Response와 관련된 최신 committed event-store position |
| `X-Projection-Position` | 현재 read model에 반영된 position |
| `X-Projection-Lag` | `stream - projection` |
| `X-Min-Stream-Position` | Caller가 선택적으로 지정하는 query fence |
| `Idempotency-Replayed` / `X-Idempotent-Replay` | 저장된 terminal outcome replay 여부. 표현은 더 최신 aggregate 상태를 반영할 수 있음 |
| `Retry-After` | 진행 중 command 또는 지연된 projection을 다시 확인할 안전한 대기 시간 |

`GET /api/v1/campaigns/{campaignId}`는 projection이
`X-Min-Stream-Position`에 도달하면 `200`을 반환한다. Write는 commit됐지만
projection이 아직 따라오지 못하면 position header와 `Retry-After: 1`을 포함한
`202 PROJECTION_PENDING`을 반환한다. 이때 GET을 재시도하거나 수동 새로고침해야
하며, read lag를 고치려고 non-idempotent command를 반복하면 안 된다.

SSE는 `snapshot` event를 먼저 보내고 opaque `Last-Event-ID` cursor 순서로 public
descriptor를 전송한다. 재연결할 때 마지막 cursor를 사용한다. 형식이 잘못되었거나
현재 position보다 앞선 cursor는 stable safe error로 거부하고, 오래되었지만
유효한 cursor는 새 snapshot부터 재개한다. Queue overflow는 terminal `reset`을
보내며, client는 그 뒤 새 snapshot을 조회한다.

## HTTP Contract

모든 API는 `X-Workshop-Tenant`, `X-Workshop-Principal`을 요구한다. Command는
`Idempotency-Key`도 요구한다. Operator route는 workshop operator
secret/guard/role header를 추가로 요구하고 rebuild mutation에는
`X-Expected-Generation-Token`이 필요하다.

| Method와 route | 성공 | 재시도 또는 operator action |
|---|---|---|
| `POST /operator/api/v1/campaigns` | `201`; replay는 저장된 terminal outcome을 반환하며 표현은 최신 aggregate 상태일 수 있음 | `409 COMMAND_IN_PROGRESS`이면 같은 idempotency key로 재시도 |
| `POST /operator/api/v1/campaigns/{campaignId}/activate` | `200` | Revision conflict를 해결하고 새 key로 blind retry하지 않음 |
| `POST /api/v1/campaigns/{campaignId}/claims` | `201` | 진행 중이거나 transport가 불확실하면 같은 key로 재시도 |
| `POST /api/v1/claims/{claimId}/redeem` | `200` | 재시도 전 stable conflict code 확인 |
| `POST /api/v1/claims/{claimId}/release` | `200` | 재시도 전 stable conflict code 확인 |
| `GET /api/v1/campaigns/{campaignId}` | Fresh body `200` | `202`면 GET 재시도 또는 수동 새로고침 |
| `GET /api/v1/campaigns/{campaignId}/events` | SSE `snapshot`과 cursor event | `Last-Event-ID`로 재연결하고 `reset` 뒤 새 snapshot 조회 |
| `POST /operator/api/v1/projections/{projection}/rebuilds` | `202` | Status를 poll하고 반환된 generation/token 사용 |
| `GET /operator/api/v1/projections/{projection}/rebuilds/{generation}` | `200` | State와 checkpoint 진단 |
| `POST .../rebuilds/{generation}/cancel` | `200` | `CANCELLED`까지 poll |
| `POST .../rebuilds/{generation}/resume` | `200` | Retryable `FAILED`만 재개. 취소 후에는 새 rebuild 시작 |
| `POST .../poison-events/{eventId}/retry` | `200` | `409 POISON_RETRY_BACKOFF`, `Retry-After` 준수 |
| `POST .../reconciliation` | `200` | Activation 전 lag, failed poison count, digest 검증 |

Campaign action은 state에 따라 제한된다.

| 현재 state | 허용 action | 거부 action |
|---|---|---|
| `DRAFT` | Activate, capacity change | Activation 전 allocate/redeem |
| `ACTIVE` | Allocate, redeem, release, capacity change | 두 번째 activation, allocation보다 작은 capacity |
| `PAUSED` | Release와 operator recovery | 새 allocation |
| `ENDED` | Reconciliation과 historical read | 새 allocation 또는 activation |

Voucher transition은 `ELIGIBLE`에서 `ALLOCATED`로 이동한 뒤 `REDEEMED`,
`RELEASED`, `EXPIRED`, `REVOKED` 중 하나로 끝난다. Expected revision과
idempotency receipt 덕분에 concurrent caller는 하나의 terminal result로 수렴한다.

## Projection Recovery

![Projection rebuild state](../../docs/images/readme-diagrams/event-sourced-promotion-voucher-rebuild-state-01.png)

Projection worker는 15초 lease를 가지고 5초마다 갱신하며 transaction 하나에서
최대 200 events 또는 2 MiB를 적용한다. Checkpoint, deduplication, read-model
mutation, lease fencing은 원자적으로 commit한다. Poison event가 생기면 실패한
event를 건너뛰지 않고 operator가 확인할 수 있는 degraded 경로로 전환한다.

Rebuild는 새 generation을 `BUILDING`으로 생성하고 고정 target까지 따라간 뒤
`VALIDATING`에 들어간다. Position과 canonical digest를 검증한 뒤에만 `ACTIVE`가
된다. Active pointer는 fenced compare-and-set으로 변경하며 이전 generation은
audit과 bounded cleanup을 위해 `RETIRED`로 보존한다. Cancel/resume은 cancellation revision을
증가시켜 stale worker의 write를 차단한다.

## Security

Immutable event에는 raw 사용자 식별자를 저장하지 않는다. Command boundary는
identity HMAC을 삭제 가능한 `voucher_subject_identity_mapping`의 random UUIDv7
surrogate에 연결한다. Erasure는 event history를 다시 쓰지 않고 mapping만
삭제한다. HMAC 입력은 version, purpose, tenant, domain으로 분리한다.

Production은 Base64로 인코딩한 안정적인 32-byte 이상 key를 주입해야 한다.
Retired key는 receipt/snapshot의 최대 replay window 동안 유지한다. 필요한 key를
제거하면 추측한 response 대신 `503 REPLAY_KEY_UNAVAILABLE`로 실패한다. Mapping
backup은 별도 암호화·접근 등급을 사용하고 restore readiness 전에 erasure
deletion journal을 반영해야 한다.

## Failure Injection

Integration fixture는 command phase, projection apply, snapshot maintenance,
rebuild processing을 일시 정지하거나 실패시킬 수 있다. 이를 이용해 active generation 보존,
idempotent retry, lease takeover, poison-event degradation, stale-fence rejection,
active-generation 보존을 증명한다. 모두 test-only hook이며 production default를
바꾸지 않는다.

## Performance Profiles

기본 `test`는 container-free다. `integrationTest`는 PostgreSQL Testcontainers와
Spring-managed HikariCP datasource를 사용한다. Opt-in `stressTest`는 correctness와
machine-sensitive threshold를 분리한다.

- Hot stream: virtual-thread client 64개, campaign 하나, command 1,000개
- Independent stream: client 64개, campaign 32개, command 1,000개
- Query plan: tenant 100개, campaign 1,000개, stream 10,000개, event 100,000개,
  projection row 100,000개와 `EXPLAIN (ANALYZE, BUFFERS)`
- Budget: unexpected sequential scan 금지, buffer/latency 제한, starvation 0,
  terminal/committed/conflict/Hikari/stream-head/append-fence 수치 분리

이 profile은 regression evidence이며 production capacity 보장은 아니다.

## Runbook

1. Health, projection lag, failed poison count, rebuild state, Hikari waiting,
   stream-head wait, append-fence wait를 확인한다.
2. Projection state를 바꾸기 전에 event handler, schema/upcaster, key availability,
   deployment 문제를 수정한다.
3. 실패가 하나의 event로 격리되면 poison event 하나만 retry한다. Generation이
   불완전하거나 넓게 불일치하면 rebuild를 시작하거나 resume한다.
4. Checkpoint가 목표 stream position과 같은지 확인하고 canonical projection
   digest를 비교한다. Reconciliation을 실행하고 operator audit record를 조회한다.
5. 검증된 candidate만 activate한다. Validation이 실패하면 현재 `ACTIVE`
   generation을 유지한다. 잘못된 active generation을 교체하려면 handler를
   수정하고 event authority에서 새 rebuild를 시작한다. Event를 rewrite하거나
   보존된 pointer를 수동 복원하지 않는다.
6. Reconciliation을 다시 실행하고 lag와 failed poison count가 0이 될 때까지
   alert를 유지한다.

Projection lag 10,000 events, foreground permit utilization 80%, poison event
`FAILED`, rebuild ETA 10분 초과, lock/statement timeout 1% 초과에 alert를 건다.
Retry, rebuild, cancel, resume, reconciliation, activation 요청자는 operator audit
lookup으로 확인한다.

## Run

```bash
./gradlew :commerce-event-sourced-promotion-voucher-campaign:test --console=plain
./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --console=plain
./gradlew :commerce-event-sourced-promotion-voucher-campaign:koverXmlReport --console=plain
./gradlew :commerce-event-sourced-promotion-voucher-campaign:stressTest \
  -PeventSourcedStress=true --console=plain
node scripts/validate-event-sourced-voucher-readme.mjs
EXPECTED_GRADLE_PROJECTS=112 ./scripts/smoke-validate.sh stale-check
```

PostgreSQL datasource와 production HMAC key를 준비한 뒤 application을 실행한다.

```bash
export VOUCHER_HMAC_ACTIVE_VERSION=2
export VOUCHER_HMAC_ACTIVE_KEY_BASE64='<base64-secret>'
./gradlew :commerce-event-sourced-promotion-voucher-campaign:bootRun
```

## Production Boundary

이 workshop은 하나의 PostgreSQL event authority, bounded synchronous projection
worker, generation-safe rebuild, normalized voucher 예제와 호환되는 stable HTTP
contract에 집중한다. Multi-region event replication, schema 배포 자동화,
tax/payment 처리, Kafka read-model transport는 제공하지 않는다. Broker delivery를
추가하더라도 expected version, idempotency, fencing, checkpoint, deduplication,
active-pointer semantics를 유지해야 한다.
