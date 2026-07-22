# SaaS Usage Metering & Billing Ledger

[English](README.md) | 한국어

이 예제는 API 호출량, 저장 용량, 메시지 수처럼 계속 들어오는 usage를 중복 없이 수집하고, 사용 시점의 가격을 적용해 청구 원장과 invoice를 만드는 과정을 다룬다. 어려운 지점은 계산식이 아니라 시간과 재시작이다. 같은 이벤트가 여러 번 도착하고, 가격은 중간에 바뀌며, 월 마감 worker는 언제든 죽을 수 있고, 마감 뒤 늦게 도착한 usage도 회계 이력에서 사라지면 안 된다.

예제는 Java 25, Spring Boot 4, Kotlin, JetBrains Exposed JDBC, `bluetape4k-exposed-jdbc`, PostgreSQL만 사용한다. Redis, leader election, broker는 baseline correctness에 필요하지 않다. 모든 concrete repository는 Bluetape `ExposedJdbcRepository`를 구현하며 production 코드와 test fixture 모두 Exposed DAO/DSL만 사용한다.

![Architecture](../../docs/images/readme-diagrams/usage-metering-billing-architecture-01.png)

## 먼저 기억할 세 가지

1. `receivedAt`은 client가 아니라 server `Clock`이 만든다. 마감 cutoff는 이 시간을 사용한다.
2. 가격은 청구 시점이 아니라 usage의 `occurredAt`으로 선택한다. 가격 구간은 `[effectiveFrom, effectiveTo)`다.
3. 완료된 돈은 고치지 않는다. 잘못되거나 늦은 결과는 원본을 가리키는 새 debit/credit ledger entry로 보정한다.

이 구분 덕분에 mutable workflow와 immutable financial fact를 같은 데이터베이스에 두면서도 역할을 섞지 않는다.

## 상태 모델

Billing period와 close run은 서로 다른 상태를 가진다. Period는 청구 경계이고 close run은 그 경계를 처리하는 재시작 가능한 작업이다.

![State diagram](../../docs/images/readme-diagrams/usage-metering-billing-state-01.png)

| 대상 | 전이 | 의미 |
|---|---|---|
| Billing period | `OPEN → CLOSING → FINALIZED` | cutoff를 한 번 고정하고 invoice와 함께 닫는다 |
| Close run | `RUNNING → READY_TO_FINALIZE → FINALIZED` | 모든 eligible usage가 가격을 찾았을 때만 finalize한다 |
| Validation branch | `RUNNING → FAILED_VALIDATION` | price gap을 숨기지 않고 운영자가 복구할 수 있게 멈춘다 |
| Repair branch | `FAILED_VALIDATION → RUNNING` | 명시적 가격 복구 뒤 같은 checkpoint 계약으로 재개한다 |

`FINALIZED` period는 다시 열지 않는다. 마감 cutoff 뒤에 도착했지만 service period 안에서 발생한 usage는 현재 server time을 포함하는 유일한 `OPEN` posting period에 `DEBIT_ADJUSTMENT`로 기록한다.

## 두 종류의 중복 방지

HTTP retry와 producer retry는 같은 문제가 아니다.

- `(tenantId, operation, keyDigest)` command receipt는 동일한 HTTP command의 status/body를 재생한다.
- `(tenantId, sourceSystem, sourceEventId)` unique constraint는 idempotency key가 달라도 같은 producer event가 두 번 저장되는 것을 막는다.

Raw `Idempotency-Key`와 request body는 저장하지 않는다. canonical field map의 SHA-256 fingerprint만 저장한다. 같은 key와 같은 fingerprint는 replay, 다른 fingerprint는 conflict다. 처리 중 owner가 죽으면 30초 lease가 지난 뒤 새 owner token으로 takeover한다. Terminal update는 `(receiptId, ownerToken, IN_PROGRESS)` CAS라서 오래된 owner가 결과를 덮어쓰지 못한다.

![Idempotent ingestion sequence](../../docs/images/readme-diagrams/usage-metering-billing-ingestion-sequence-01.png)

Receipt acquire/takeover는 짧은 `REQUIRES_NEW` transaction이다. Usage 저장과 terminal CAS는 후속 transaction이다. 따라서 domain commit 뒤 HTTP response가 유실돼도 재시도가 이미 commit된 결과를 찾고 응답을 재생할 수 있다.

## 가격 timeline

각 `(tenant, meter, currency)` 조합은 하나의 pricing schedule authority row를 가진다. Activation은 schedule을 lock한 뒤 열린 마지막 구간을 한 번 닫고 새 immutable version을 append한다.

```text
v1: [2026-01-01T00:00Z, 2026-03-01T00:00Z)  USD 0.10
v2: [2026-03-01T00:00Z, ∞)                  USD 0.12
```

`2026-03-01T00:00Z` usage에는 정확히 v2가 적용된다. Normal activation은 backdate/overlap을 거부한다. Historical gap repair는 별도 operator command로 격리하고, 이미 ledger가 참조한 구간은 바꾸지 않는 것이 production 확장 규칙이다.

## 재시작 가능한 마감

Close 시작 transaction은 period를 `OPEN → CLOSING`으로 바꾸고 `cutoffReceivedAt`과 close run을 함께 만든다. Worker와 operator `process-next`는 같은 `BillingCloseService.processNextBatch`를 호출한다.

한 batch는 다음 순서로 처리한다.

1. `(occurredAt, usageEventId)` checkpoint 뒤의 usage를 최대 200개 읽는다.
2. `occurredAt`에 맞는 price version을 찾는다.
3. `quantity × unitPrice`를 currency 규칙으로 계산한다.
4. `CHARGE` ledger append와 checkpoint 갱신을 한 transaction에 commit한다.
5. 다음 row가 없고 unpriced usage가 0일 때만 `READY_TO_FINALIZE`로 전이한다.

![Close and reconciliation sequence](../../docs/images/readme-diagrams/usage-metering-billing-close-reconciliation-01.png)

프로세스가 3번과 4번 사이에서 죽으면 transaction 전체가 rollback된다. Commit 직후 죽으면 같은 keyset 구간을 다시 볼 수 있지만 ledger unique key가 중복 금액을 막는다. 이 예제의 핵심은 worker가 한 번만 실행된다고 가정하지 않는 데 있다.

## Immutable invoice와 provenance

Invoice finalize transaction은 `READY_TO_FINALIZE` close run에만 허용된다.

- posting period의 eligible ledger를 고정된 snapshot으로 읽는다.
- `(meterId, priceVersionId, entryType)`별 line을 만든다.
- 모든 ledger entry를 정확히 한 invoice line과 연결한다.
- `sum(line.amount) == invoice.total == sum(linked ledger.amount)`를 검증한다.
- invoice, lines, provenance, period `FINALIZED`, close run `FINALIZED`를 함께 commit한다.

Ledger/invoice repository는 generic `save`, `saveAll`, `delete*`를 `UnsupportedOperationException`으로 거부한다. 수정 API를 숨기는 수준이 아니라 repository contract에서 append-only를 강제한다.

## Late usage, credit, reconciliation

Late usage debit은 원래 service period와 현재 posting period를 모두 저장한다. 가격은 original `occurredAt`, posting period는 server posting time으로 선택한다. Credit은 양수 amount와 `CREDIT_ADJUSTMENT` direction을 사용하고 `relatedOriginalEntryId`로 원본을 연결한다. 음수 금액의 의미를 여러 곳에서 해석하게 만들지 않는다.

Reconciliation은 billing authority를 바꾸지 않는 read-only scan이다. Cutoff 전·후 unledgered usage, ledger-price mismatch, invoice-line mismatch, invoice-total mismatch, tenant/currency mismatch의 여섯 immutable finding을 남긴다. Late-usage repair는 `ROLE_OPERATOR`, `Idempotency-Key`, finding의 expected digest를 요구한다. 현재 usage digest가 여전히 같고 debit이 아직 없을 때만 append하며, 이미 처리됐거나 오래된 finding은 거부한다. Finding을 근거로 자동 수정하지 않는다.

## Exposed와 PostgreSQL 경계

| 관심사 | 권위 |
|---|---|
| Command replay/takeover | PostgreSQL unique + owner-token CAS |
| Producer duplicate | PostgreSQL source-event unique constraint |
| Price interval | schedule row serialization + half-open query |
| Close progress | fixed cutoff + keyset checkpoint |
| Financial history | append-only ledger/invoice/provenance |
| Data access | JetBrains Exposed DAO/DSL + `ExposedJdbcRepository` |

`JdbcTemplate`, `java.sql.*`, `PreparedStatement`, `Transaction.exec`, migration SQL은 사용하지 않는다. Test fixture만 `SchemaUtils`로 PostgreSQL schema를 준비한다. 애플리케이션은 production schema를 자동 생성하지 않는다. 실제 서비스에서는 조직의 migration pipeline이 Exposed table contract와 일치하는 schema를 배포해야 한다.

## Security와 운영 신호

모든 `/api/**`는 인증이 필요하고 `/api/v1/operator/**`는 `ROLE_OPERATOR`가 필요하다. Tenant API는 principal name과 path `tenantId`가 같아야 한다. 예제는 `SecurityFilterChain` 경계만 제공하므로 실제 배포에서는 조직의 JWT/OAuth2 `AuthenticationProvider`를 연결해야 한다.

Metric tag에는 bounded `operation`, `result`, `type`만 사용한다. Tenant, meter, source event, idempotency key는 tag로 사용하지 않는다. Health는 DB 접근과 close backlog처럼 제한된 query만 노출해야 하며 상세 식별자는 operator 진단 경계 안에 둔다.

권장 alert는 다음과 같다.

| Signal | Warning | 첫 조치 |
|---|---|---|
| Oldest `CLOSING` age | billing SLA 초과 | run state와 last checkpoint 확인 |
| Unpriced usage | 0보다 큼 | price timeline gap 조사 |
| Receipt takeover ratio | 지속 증가 | domain latency/DB timeout 확인 |
| Reconciliation findings | 증가 또는 장기 미해결 | finding type별 bounded repair 검토 |
| DB permit rejection | 지속 발생 | ingress를 줄이고 transaction duration 조사 |

## 실행과 검증

필수 조건은 JDK 25와 Docker 호환 container runtime이다. 테스트는 `bluetape4k-testcontainers`의 PostgreSQL을 사용한다.

```bash
java -version
./gradlew :commerce-usage-metering-billing-ledger:test --max-workers=1
./gradlew :commerce-usage-metering-billing-ledger:integrationTest --rerun-tasks --max-workers=1
./gradlew :commerce-usage-metering-billing-ledger:stressTest --rerun-tasks --max-workers=1
```

Default `test`는 container-free unit/architecture test만 실행한다. `integrationTest`는 unique/CAS/transaction/restart behavior와 reconciliation 여섯 유형을 PostgreSQL에서 검증한다. `stressTest`는 매 batch마다 새 worker를 구성하면서 usage 10,000건을 닫고 charge가 정확히 10,000건인지 증명한다. Capacity benchmark가 아니라 recovery 회귀 test다.

## Microservice extraction guide

이 예제를 바로 여러 서비스로 나누지 않는다. 먼저 modular monolith에서 transaction invariant와 운영 지표를 확인한 뒤 ownership 순서로 분리한다.

1. **Ingestion service**: source-event unique와 receipt를 소유한다. `UsageAccepted` event에는 stable usage ID, tenant, meter, quantity, occurred/received time, schema version을 넣는다.
2. **Pricing service**: schedule과 immutable price version을 소유한다. Billing은 price ID가 포함된 versioned snapshot을 소비하거나 idempotent lookup contract를 사용한다.
3. **Billing service**: period, close checkpoint, ledger, invoice를 함께 소유한다. 이 경계는 가장 강한 transaction invariant가 있으므로 더 잘게 쪼개지 않는다.
4. **Reconciliation service**: 각 owner의 read model을 비교하고 finding만 소유한다. 다른 서비스 DB를 직접 수정하지 않고 idempotent repair command를 보낸다.

분리 뒤에는 shared database와 distributed transaction을 사용하지 않는다. In-process call은 transactional outbox와 schema-versioned event로 바꾸고, consumer마다 dedup receipt를 둔다. Broker ordering은 database fencing/CAS를 대신하지 않는다. Late adjustment, invoice provenance, tenant predicate, low-cardinality metric 계약은 서비스 경계를 넘어 그대로 유지한다.

## 코드 탐색 순서

1. `domain/`에서 money, time window, state invariant를 읽는다.
2. `persistence/MeteringTables.kt`와 `MeteringExposedJdbcRepository.kt`에서 authority와 append-only guard를 확인한다.
3. `idempotency/CommandReceiptService.kt`에서 replay/takeover를 본다.
4. `application/BillingCloseService.kt`와 `InvoiceService.kt`에서 checkpoint와 finalization transaction을 따라간다.
5. `MeteringEndToEndIntegrationTest`에서 전체 lifecycle을 실행한다.

이 순서로 보면 controller보다 데이터 권위가 먼저 보이고, 예제를 실제 서비스에 옮길 때 무엇을 보존해야 하는지 분명해진다.
