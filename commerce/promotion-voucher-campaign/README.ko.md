# 프로모션 바우처 캠페인

[English](README.md) | 한국어

이 Spring Boot MVC 예제는 응답 유실, Redis 장애, review 지연, worker 재시작이 발생해도 경쟁이 심한 바우처 캠페인의 정합성을 지키는 방법을 보여 줍니다. 최종 판단은 PostgreSQL만 내립니다. Redis admission, Bloom risk hint, leader election, Spring Modulith delivery는 처리량과 복구를 돕지만 바우처 상태 전이를 승인하지 않습니다.

> Workshop 경계: 서버는 loopback에 bind하고 production IAM, OAuth, CSRF 인프라 대신 local header를 사용합니다. Operator route와 workshop secret을 non-loopback interface에 노출하면 안 됩니다.

## 학습 목표

- Java 25 virtual thread에서 Tomcat MVC를 실행하되 HikariCP connection은 16개로 제한합니다.
- JDBC connection을 얻기 전에 DB permit을 foreground 12, worker 1, SSE maintenance 3개 lane으로 예약합니다.
- `bluetape4k-exposed-jdbc`로 tenant 범위 Exposed repository를 구현하고 `PostgreSQLServer`로 운영 형태의 테스트를 수행합니다.
- 닫힌 idempotency response descriptor를 저장해 응답 유실이나 process restart 뒤에도 같은 command를 replay합니다.
- Redis/Lettuce rate admission과 Bloom signal은 advisory로만 사용하고 PostgreSQL capacity와 상태 전이가 항상 결정하게 합니다.
- 즉시 allocation의 one-time code는 command 응답에서만 전달하고, review 승인 후 code 전달은 안전한 `GET` snapshot과 idempotent acknowledgement command로 분리합니다.
- Reconciliation은 durable하고 leader가 조정하되 제한된 수동 복구 경로를 유지합니다.
- `bluetape4k-logging`으로 판단을 기록하면서 raw tenant, user, voucher code, idempotency key, operator secret은 남기지 않습니다.

## Architecture

![프로모션 바우처 캠페인 Architecture](../../docs/images/readme-diagrams/commerce-promotion-voucher-campaign-readme-architecture-01.png)

중앙 경로는 Browser 또는 live `WebTestClient` -> Tomcat MVC -> application-owned idempotency와 command service -> Exposed repository -> PostgreSQL입니다. Redis admission/Bloom, leader worker, Spring Modulith publication은 advisory 또는 durable side path이며 PostgreSQL transaction 없이 capacity를 소비하지 못합니다.

## Sequence Diagram

![바우처 allocation, review, redemption, retry, reconciliation Sequence Diagram](../../docs/images/readme-diagrams/commerce-promotion-voucher-campaign-readme-sequence-01.png)

Sequence는 allocation, 선택적 review 승인과 acknowledgement, redemption, Redis degraded, 응답 유실 replay, reconciliation을 따라갑니다. 같은 idempotency key를 다시 보내면 저장된 descriptor를 반환하고 capacity mutation은 반복하지 않습니다.

## 사전 준비

- JDK 25
- 빈 `voucher_campaign` database가 있는 PostgreSQL 16+
- Advisory 경로를 활성화할 때만 Redis
- `curl`; browser는 선택 사항

## 시작과 설정

| 관심사 | 기본값 | 변경 방법 |
|---|---:|---|
| HTTP | `127.0.0.1:8080` | `VOUCHER_SERVER_ADDRESS`, `VOUCHER_SERVER_PORT` |
| Management | `127.0.0.1:8081` | `VOUCHER_MANAGEMENT_PORT` |
| PostgreSQL | `jdbc:postgresql://localhost:5432/voucher_campaign` | `VOUCHER_DATABASE_URL`, `VOUCHER_DATABASE_USERNAME`, `VOUCHER_DATABASE_PASSWORD` |
| Hikari | max 16, min idle 4, connection timeout 60s | `application.yml` |
| Transaction / MVC async | 60s / 60s | `application.yml` |
| Tomcat | connection 8,000, platform-thread fallback 8,000 | `application.yml` |
| Redis | disabled, `redis://127.0.0.1:6379` | `VOUCHER_REDIS_ENABLED`, `VOUCHER_REDIS_URI` |
| Operator 경계 | local development secret | `VOUCHER_OPERATOR_SECRET`, `VOUCHER_OPERATOR_GUARD` |
| Key material | local development key | `VOUCHER_GENERATION_KEY_1`, `VOUCHER_VERIFICATION_KEY_1`, `VOUCHER_IDENTITY_KEY`, `VOUCHER_RISK_KEY`, `VOUCHER_REDIS_SLOT_KEY` |

```bash
export VOUCHER_DATABASE_URL=jdbc:postgresql://localhost:5432/voucher_campaign
export VOUCHER_DATABASE_USERNAME=vouchers
export VOUCHER_DATABASE_PASSWORD=vouchers
export VOUCHER_OPERATOR_SECRET='replace-with-at-least-32-random-bytes'
./gradlew :commerce-promotion-voucher-campaign:bootRun
```

Advisory admission을 확인할 때만 Redis를 활성화합니다.

```bash
export VOUCHER_REDIS_ENABLED=true
export VOUCHER_REDIS_URI=redis://127.0.0.1:6379
./gradlew :commerce-promotion-voucher-campaign:bootRun
```

Virtual thread가 DB connection 비용을 낮추지는 않습니다. Hikari는 16으로 유지합니다. 60초 connection/transaction timeout은 제한된 대기를 허용하고, 12/1/3 permit lane은 foreground, worker, SSE maintenance가 서로의 예약 capacity를 소비하지 못하게 합니다.

## Seed와 Reset

Startup은 PostgreSQL advisory lock 아래에서 checksum을 검증한 `V001__voucher_campaign.sql` migration을 적용합니다. 다음 operator API로 deterministic workshop campaign을 생성합니다. 이 과정이 walkthrough의 seed입니다.

```bash
CAMPAIGN_ID=018f3b8c-45a7-7cc1-8f1d-31b63140c001
TENANT=voucher-demo
ORIGIN=http://127.0.0.1:8080
OPERATOR_SECRET="$VOUCHER_OPERATOR_SECRET"

curl -i -X POST "$ORIGIN/operator/api/v1/campaigns" \
  -H "Origin: $ORIGIN" -H 'Content-Type: application/json' \
  -H 'X-Workshop-Guard: voucher-workshop-operator' \
  -H "X-Workshop-Operator-Secret: $OPERATOR_SECRET" \
  -H "X-Workshop-Tenant: $TENANT" -H 'Idempotency-Key: campaign-create-0001' \
  -H 'If-None-Match: *' \
  -d '{"campaignId":"018f3b8c-45a7-7cc1-8f1d-31b63140c001","startsAt":"2026-07-20T00:00:00Z","endsAt":"2036-07-20T00:00:00Z","capacity":100,"perUserLimit":1,"redemptionTtlSeconds":3600}'

curl -i -X POST "$ORIGIN/operator/api/v1/campaigns/$CAMPAIGN_ID/activate" \
  -H "Origin: $ORIGIN" -H 'Content-Type: application/json' \
  -H 'X-Workshop-Guard: voucher-workshop-operator' \
  -H "X-Workshop-Operator-Secret: $OPERATOR_SECRET" \
  -H "X-Workshop-Tenant: $TENANT" -H 'Idempotency-Key: campaign-activate-0001' \
  -d '{"expectedRevision":0}'
```

보호된 `POST /operator/api/v1/fixtures/reset` route는 선택한 workshop tenant의 campaign, claim, review, audit, inbox row와 준비된 fixture signal만 삭제합니다. `local`, `demo`, `test` profile에서만 존재하고 일반 operator guard와 idempotency key를 요구하며 공유 또는 암시적 tenant를 reset하지 않습니다. 전체 schema rehearsal은 app을 중지하고 전용 database를 다시 생성합니다.

## Customer curl walkthrough

바우처를 allocate합니다. `userRef`는 `X-Workshop-Principal`과 같아야 합니다.

```bash
USER_REF=user-demo
curl -i -X POST "$ORIGIN/api/v1/campaigns/$CAMPAIGN_ID/claims" \
  -H 'Content-Type: application/json' -H "X-Workshop-Tenant: $TENANT" \
  -H "X-Workshop-Principal: $USER_REF" -H 'Idempotency-Key: allocation-0001' \
  -d '{"userRef":"user-demo"}'
```

응답의 `claimId`, `revision`, one-time `code`를 보관합니다. 안전한 `GET /api/v1/claims/{claimId}`는 code를 재구성하거나 반환하지 않습니다. 즉시 allocation 응답은 이미 code를 전달했으므로 이 경로에서는 acknowledgement route를 호출하지 않고 바로 redemption으로 진행합니다.

`reviewId`를 반환한 allocation만 code를 생략합니다. Operator가 해당 review를 승인한 뒤 새로 발급된 code를 정확히 한 번 수령하고 acknowledge합니다.

```bash
curl -i -X POST "$ORIGIN/api/v1/claims/$CLAIM_ID/code-acknowledgements" \
  -H 'Content-Type: application/json' -H "X-Workshop-Tenant: $TENANT" \
  -H "X-Workshop-Principal: $USER_REF" -H 'Idempotency-Key: code-ack-0001' \
  -d "{\"expectedRevision\":$REVISION}"
```

최신 revision과 business reference로 redeem합니다.

```bash
curl -i -X POST "$ORIGIN/api/v1/claims/$CLAIM_ID/redeem" \
  -H 'Content-Type: application/json' -H "X-Workshop-Tenant: $TENANT" \
  -H "X-Workshop-Principal: $USER_REF" -H 'Idempotency-Key: redeem-0001' \
  -d "{\"code\":\"$VOUCHER_CODE\",\"expectedRevision\":$REVISION,\"redemptionReference\":\"order-demo-0001\"}"
```

## 응답 유실 멱등 재시도

Allocation 또는 redemption 응답을 잃었다면 method, route, header, key, body를 모두 동일하게 다시 보냅니다. 응답에 `Idempotency-Replayed: true`가 포함됩니다. 같은 key에 다른 fingerprint를 사용하면 `409 IDEMPOTENCY_FINGERPRINT_CONFLICT`, 아직 owner가 처리 중이면 `Retry-After`와 `409 COMMAND_IN_PROGRESS`를 반환합니다. Terminal failure descriptor도 성공 descriptor와 같은 방식으로 replay합니다.

## Allocation과 Redemption Review

Guarded fixture API는 `local`, `demo`, `test` profile에만 존재합니다. `allocation-review`, `bloom-false-positive`, `redis-outage`는 해당 principal의 다음 allocation을 준비하고, `redemption-review`는 allocation에 소비되지 않고 다음 redemption을 준비합니다. Review가 필요한 command는 HTTP 202를 반환합니다. Fixture 요청 replay는 저장된 응답만 반환하며 소비된 signal을 다시 준비하지 않습니다.

```bash
curl -i -X POST "$ORIGIN/operator/api/v1/fixtures/allocation-review/run" \
  -H "Origin: $ORIGIN" -H 'Content-Type: application/json' \
  -H 'X-Workshop-Guard: voucher-workshop-operator' \
  -H "X-Workshop-Operator-Secret: $OPERATOR_SECRET" \
  -H "X-Workshop-Tenant: $TENANT" -H 'Idempotency-Key: fixture-review-0001' \
  -d '{"principalRef":"review-user"}'
```

Campaign/claim ID와 review/claim expected revision을 사용해 `/operator/api/v1/reviews/{reviewId}/approve|reject`를 호출합니다. 승인하면 claim은 code 발급 가능한 상태가 되고 customer가 code acknowledgement command로 one-time code를 수령하고 확인해야 합니다. 이 분리 덕분에 operator `GET`이나 customer `GET`이 replay 가능한 바우처 material을 노출하지 않습니다.

## Reconciliation

Spring Modulith publication과 event inbox가 durable delivery evidence를 보존합니다. Leader worker는 예약된 worker capacity로 한 번에 최대 50개 row를 10초 deadline 안에서 처리합니다. Leader scheduling을 사용할 수 없을 때 같은 제한 경로를 수동으로 실행합니다.

```bash
curl -i -X POST "$ORIGIN/operator/api/v1/reconciliation/run" \
  -H "Origin: $ORIGIN" -H 'Content-Type: application/json' \
  -H 'X-Workshop-Guard: voucher-workshop-operator' \
  -H "X-Workshop-Operator-Secret: $OPERATOR_SECRET" \
  -H "X-Workshop-Tenant: $TENANT" -H 'Idempotency-Key: reconcile-0001' \
  -d '{}'
```

## Redis와 PostgreSQL 장애

- Redis timeout, outage, Bloom false positive는 admission/risk evidence만 바꿉니다. Command는 local permit과 PostgreSQL 경계로 fallback합니다. Risk hint는 review를 열 수 있지만 그 자체로 capacity를 소비하지 못합니다.
- PostgreSQL outage, Hikari exhaustion, lock timeout, DB permit rejection은 authoritative command를 중단합니다. 애플리케이션은 Redis만으로 mutation을 수락하지 않습니다. 같은 idempotency key를 보존하고 `Retry-After`와 backend 복구 뒤에만 재시도합니다.

## SSE 재연결과 Polling fallback

Tenant/principal header와 함께 `/api/v1/campaigns/{campaignId}/events`를 구독합니다. Stream은 authoritative snapshot을 먼저 보낸 뒤 audit row, heartbeat, reset event를 전달합니다. 마지막 event ID로 재연결합니다. `503 SSE_CAPACITY_REJECTED`는 `Retry-After: 2`와 `GET /api/v1/campaigns/{campaignId}`를 가리키는 `Link` header를 포함하므로 JSON snapshot polling fallback으로 전환합니다. 느린 client는 queue, payload, write timeout 한계를 넘으면 종료되고 모든 exit 경로에서 SSE permit을 반환합니다.

## Browser walkthrough

`http://127.0.0.1:8080/`을 열고 seed campaign UUID, tenant, principal, local operator secret을 입력한 뒤 연결합니다. Console은 authoritative snapshot을 읽고 SSE를 시작하며 active campaign에서만 allocation을 활성화하고 audit/reconciliation event를 표시합니다. Cookbook 항목을 선택하고 **Run scenario**를 누릅니다. Runner는 선택 tenant campaign을 reset/recreate하고 필요한 server signal을 준비한 뒤 client concurrency 또는 delayed-event command를 실행하며 scenario invariant를 검증하고 PostgreSQL state를 다시 읽어 audit/SSE evidence를 timeline에 남깁니다. **Reset tenant**는 다른 tenant를 건드리지 않고 같은 보호된 tenant-local reset만 수행합니다. Standalone operator pause/activate/end는 confirmation dialog를 거칩니다.

## 안정적인 Error와 Retry catalog

| Status | Stable code | Retry 규칙 |
|---:|---|---|
| 400 | `INVALID_REQUEST` | Header/body를 수정합니다. 실패 payload를 그대로 반복하지 않습니다. |
| 403 | `OPERATOR_ACCESS_DENIED` | Loopback, same-origin, guard, secret, tenant, JSON precondition을 복구합니다. |
| 404 | `RESOURCE_NOT_FOUND`, `CAMPAIGN_NOT_FOUND`, `CLAIM_NOT_FOUND`, `REVIEW_NOT_FOUND` | Tenant와 identifier를 확인합니다. Cross-tenant read는 의도적으로 없는 것처럼 보입니다. |
| 409 | `COMMAND_IN_PROGRESS`, `IDEMPOTENCY_FINGERPRINT_CONFLICT`, `CAMPAIGN_ALREADY_EXISTS`, `CAMPAIGN_PAUSED`, `CAMPAIGN_NOT_ACTIVE`, `CAMPAIGN_NOT_STARTED`, `CAMPAIGN_ENDED`, `CAPACITY_EXHAUSTED`, `PER_USER_LIMIT_REACHED`, `INVALID_CODE`, `CLAIM_EXPIRED`, `CLAIM_REVOKED`, `ALREADY_REDEEMED`, `CONCURRENT_MODIFICATION`, `CODE_ALREADY_ACKNOWLEDGED`, `RECONCILIATION_IN_PROGRESS` | `COMMAND_IN_PROGRESS`, `CAMPAIGN_PAUSED`, reconciliation-in-progress만 `Retry-After` 뒤에 재시도합니다. 나머지는 상태나 요청을 바꿔야 합니다. |
| 412 | `STALE_REVISION` | Authoritative snapshot을 다시 읽고 새 revision과 새 idempotency key로 command를 만듭니다. |
| 429 | `RATE_LIMITED` | `Retry-After`만큼 기다립니다. PostgreSQL 상태는 바뀌지 않았습니다. |
| 503 | `DATABASE_BULKHEAD_REJECTED`, `AUTHORITATIVE_BACKEND_UNAVAILABLE`, `IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE`, `SSE_CAPACITY_REJECTED`, `SERVICE_SHUTTING_DOWN` | 요청을 보존하고 `Retry-After`를 따르며 해당 경계를 복구한 뒤 안전할 때 같은 key로 재시도합니다. |
| 500 | `INTERNAL_ERROR` | Replay 여부를 결정하기 전에 request ID와 redacted log를 확인합니다. |

## Operator Runbook

| Subsystem | Signal | Query 또는 command | Warning threshold | 판단 | 조치 | 복구 확인 |
|---|---|---|---|---|---|---|
| PostgreSQL | readiness health, Hikari active/pending, lock wait | `curl -s http://127.0.0.1:8081/actuator/health/readiness`; `pg_stat_activity` 확인 | Hikari pending이 60초 acquisition budget 동안 지속되거나 lock wait가 5초에 근접 | Authority 장애 또는 포화 | Traffic 증폭을 멈추고 PostgreSQL을 복구하며 slow/blocked transaction을 조사합니다. 첫 대응으로 pool을 16보다 키우지 않습니다. | readiness `UP`, pending 0, invariant query와 capacity 일치 |
| Redis | degraded health, admission failure | `redis-cli -u "$VOUCHER_REDIS_URI" PING`; `voucher_redis_*` metric 확인 | degraded가 5분 연속 지속 | Advisory 경로 장애 | Redis/network를 복구하고 PostgreSQL-authoritative command는 유지합니다. Boot 때 optional bean이 없었을 때만 재시작합니다. | Probe 3회 성공 후 normal admission, capacity drift 없음 |
| Leader | leader state, worker run timestamp | Prometheus leader/worker metric과 application log 확인 | Leader가 없거나 worker success가 2 scheduling cycle 초과 | Automatic reconciliation 중단 | Redis/leader lease를 복구하거나 제한된 manual reconciliation 1회 실행 | Leader 1개와 다음 scheduled run 성공 |
| Worker | oldest inbox/backlog age, poison count | Inbox status query와 `/operator/api/v1/reconciliation/run` | Oldest backlog age 10분 초과 또는 last success 2 cycle 초과 | Durable work 지연 | 실패 handler/provider를 고치고 poison evidence를 격리한 뒤 bounded batch 실행 | Oldest age 감소, failed 증가 중단, duplicate effect 0 |
| SSE | active campaign, queue rejection, cleanup/leak metric | SSE metric/log 확인; `GET /api/v1/campaigns/{id}` polling | 지속적인 rejection 또는 cleanup leak가 0이 아님 | Streaming capacity 고갈 또는 resource 미반환 | Polling fallback 적용, slow consumer 축소, write timeout/queue pressure 조사 | 새 stream이 snapshot을 받고 disconnect 뒤 active count/permit 반환 |
| Keys | active version, replay-key error | Startup validation과 `IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE` 확인 | Active read version 누락 또는 replay-key failure 1건 이상 | 필요한 verification/decryption material 누락 | Retained generation/verification key version을 복구합니다. 기존 version에 새 key를 대입하지 않습니다. | Startup 성공, 과거 code/replay fixture 검증 성공 |

### Backup, Restore, Key Retention

PostgreSQL table과 active claim, 저장된 idempotency descriptor, audit retention window가 참조하는 모든 key version을 함께 backup합니다. Restore할 때 application을 중지하고 database와 정확한 versioned key material을 같이 복구한 뒤 migration checksum과 key-version coverage를 확인합니다. Node 하나만 시작해 read-only snapshot과 bounded reconciliation을 실행합니다. Retained claim, replay descriptor, audit 요구사항이 더는 참조하지 않을 때만 key를 폐기합니다. Current generation version을 회전해도 이전 verification version을 삭제할 권한은 생기지 않습니다.

## Scenario Cookbook

| Scenario | Trigger | Authoritative evidence |
|---|---|---|
| Happy allocation/redemption | seed -> allocate -> redeem | claim `REDEEMED`, capacity contribution 1회, audit/SSE `VOUCHER_REDEEMED` |
| Same-key response loss | 같은 allocation 또는 redemption 반복 | `Idempotency-Replayed: true`, claim/effect 1회 |
| Capacity race | Browser **Run scenario**가 capacity 2를 만들고 principal 8개를 동시 제출 | Winner 수가 capacity와 같고 loser는 `CAPACITY_EXHAUSTED` |
| Allocation review | fixture `allocation-review` | claim `PENDING_REVIEW`, open review, 승인/acknowledgement 전 code 없음 |
| Redemption review | 해당 경로 전에 fixture `redemption-review` | Review row와 terminal decision이 PostgreSQL evidence로 유지 |
| Redis outage | fixture `redis-outage` 또는 Redis 중지 | Advisory degraded signal, PostgreSQL 결과가 authoritative |
| Bloom false positive | fixture `bloom-false-positive` | Review가 열리며 Bloom만으로 terminal rejection하지 않음 |
| Delayed event | Browser **Run scenario**에서 `delayed-duplicate-out-of-order`를 선택해 보호된 fixture로 apply, duplicate, lower-sequence event 제출 | Inbox 1회 적용, stale/conflict evidence 유지 |
| Pause/allocation race | Browser **Run scenario**에서 `pause-allocation-race`를 선택해 서로 다른 idempotency key로 operator pause와 customer allocation 동시 제출 | Revision 순서가 authoritative하며 audit가 accepted/rejected command 설명 |
| Redeem/revoke race | Browser **Run scenario**에서 `redeem-revoke-race`를 선택해 같은 claim revision에서 customer redeem과 operator revoke 동시 제출 | Terminal winner 정확히 1개, capacity effect 중복 없음 |
| Policy change race | Browser **Run scenario**가 같은 expected revision으로 policy update 2개 제출 | Policy revision winner 정확히 1개, stale command fail-closed |

## 지원하지 않는 범위

Voucher pool 사전 생성은 #537, event-sourced reconstruction은 #538에서 별도로 다룹니다. 이 모듈은 on-demand opaque code 생성과 state table/audit persistence를 의도적으로 사용합니다. Redemption 이후 reversal/compensation도 구현하지 않습니다. Operator revoke는 충돌하는 terminal transition이 이기기 전까지만 경쟁합니다.

## Troubleshooting

- Startup failure code는 잘못된 Hikari/permit/key/Redis/worker/SSE 설정을 가리킵니다. Validation을 우회하지 말고 설정을 고칩니다.
- `STALE_REVISION`은 client view가 오래됐다는 뜻이지 server가 내부 재시도해야 한다는 뜻이 아닙니다.
- `IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE`은 저장된 descriptor가 누락된 retained key material을 참조한다는 뜻입니다. 해당 key version을 복구합니다.
- SSE reset이 반복되면 요청 cursor가 retention 밖일 수 있습니다. 새 snapshot을 받아들입니다.
- Raw tenant, principal, code, idempotency key, operator secret, request body는 log에 나타나면 안 됩니다. Redaction 실패는 security defect로 조사합니다.

## 검증

테스트는 `PostgreSQLServer`, `RedisServer`, Lettuce, live `WebTestClient`, Java 25 virtual thread를 사용합니다.

```bash
./gradlew :commerce-promotion-voucher-campaign:test --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:migrationCompatibilityTest --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:stressTest -PvoucherStressRun=local --rerun-tasks --max-workers=1
```

이 모듈은 저장소 공통 `bluetape4k-dependencies` platform만 사용합니다. Exposed JDBC/tests, virtual-thread API/JDK25, logging, Testcontainers, Lettuce, Bucket4j, leader를 포함한 Bluetape module version을 로컬에 고정하지 않습니다.
