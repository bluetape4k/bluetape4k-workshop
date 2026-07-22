# #555 사용량 과금 마이크로서비스 구현 검토

Date: 2026-07-23

Issue: #555

Scope: Meter, Usage, Billing, Invoice, Query의 독립 Spring Boot 서비스, Kafka outbox/inbox
전달, Exposed PostgreSQL persistence, composition 검증, README/diagram/CI 등록.

## 결론

다섯 서비스는 database와 decoder를 공유하지 않고, 각 서비스의 local PostgreSQL transaction에서
business fact와 outbox를 함께 기록한다. Kafka는 at-least-once transport이며, duplicate는 receiver의
`(tenantId, eventId, payloadDigest)` durable 결정으로 흡수한다. raw JDBC/SQL, XA, 공유 DB, end-to-end
exactly-once 주장은 구현과 문서에서 모두 제외했다.

## 구현 경계 검토

| 경계 | 확인 결과 |
| --- | --- |
| DB access | 모든 concrete repository가 `ExposedJdbcRepository`를 사용한다. composition의 source guard가 `java.sql.`, `DriverManager`, `JdbcTemplate`, statement 생성, `Transaction.exec`를 차단한다. |
| ownership | Meter=price, Usage=accepted usage, Billing=replicated price evidence/rated charge, Invoice=append-only line, Query=projection/checkpoint/quarantine이다. |
| delivery | producer는 local outbox를 lease/claim 후 publish하고, receiver는 local inbox/quarantine 결정을 먼저 durable하게 만든다. |
| correction | `AdjustmentPosted`는 원본 charge event를 `correctionOf`로 참조하는 새 Invoice line과 `InvoiceCorrectionIssued`를 만든다. 기존 line은 갱신하지 않는다. |
| schema | Query는 v1/v2를 명시적으로 수용하고 unknown mandatory version은 durable quarantine으로 보낸다. |
| operator path | Query quarantine snapshot/redrive audit, outbox retry wait, aggregate version gap이 각각 명시적인 관찰/복구 지점이다. |

## Composition 시나리오 증거

Kafka + PostgreSQL container suite는 다음을 실행했다.

1. delayed publication recovery
2. duplicate Usage delivery
3. out-of-order aggregate version defer/retry
4. deterministic transport outage → `RETRY_WAIT` → actual Kafka recovery
5. Usage restart after durable price evidence
6. poison quarantine, independent progress, redrive request
7. additive v2 acceptance and v99 quarantine
8. Query tenant authority isolation
9. price/usage/charge/invoice/query delivery parity
10. append-only Invoice correction and Query-visible correction history

`CorrectionIntegrationTest`는 Billing의 original event ID를 `correctionOf`로 전달하여 기존 line 하나가
두 line으로 바뀌는 것이 아니라 새로운 correction line이 추가되는 것을 검증한다.

## Diagram checklist ledger

대상은 다음 여섯 SVG/PNG 쌍이다.

- `usage-billing-microservices-architecture-01`
- `usage-billing-microservices-outbox-inbox-state-01`
- `usage-billing-microservices-delivery-01`
- `usage-billing-microservices-poison-recovery-01`
- `usage-billing-microservices-correction-01`
- `usage-billing-microservices-extraction-01`

`./scripts/smoke-validate.sh diagram-qa`를 실행했고, 두 쌍 모두 다음 검사를 통과했다.

| 검사 | 결과 |
| --- | --- |
| SVG marker fill/stroke 및 endpoint marker audit | PASS (6 targets, marker failures 0) |
| connector geometry, endpoint, mixed-corner audit | PASS |
| rounded orthogonal connector / spline 금지 | PASS |
| CairoSVG PNG 재생성 뒤 SVG/PNG arrowhead 방향 비교 | PASS |
| full-size PNG 육안 검사 | PASS (3200×1840 architecture, 3200×1700 state) |

따라서 diagram은 SVG만 맞는 상태로 끝내지 않았고, raster 변환에서 화살촉 방향이 뒤집히지 않는지를
같은 검증 루프에서 확인했다.

## 검증 명령과 결과

| 명령 | 결과 |
| --- | --- |
| `./gradlew --no-daemon :commerce-usage-billing-microservices-composition-tests:test --tests '...PersistenceArchitectureTest' --max-workers=1` | PASS |
| `./gradlew :commerce-usage-billing-microservices-composition-tests:cleanIntegrationTest :commerce-usage-billing-microservices-composition-tests:integrationTest :commerce-usage-billing-microservices-composition-tests:koverXmlReport -Pkotlin.incremental=false --no-build-cache --max-workers=1 --no-daemon` | PASS, 11 tests across 10 composition scenarios; failures/errors/skipped 0 |
| `./gradlew --no-daemon :commerce-usage-billing-microservices-composition-tests:integrationTest --tests '...CorrectionIntegrationTest' --max-workers=1` | PASS |
| `./gradlew :commerce-usage-billing-microservices-composition-tests:koverXmlReport -Pkotlin.incremental=false --no-build-cache --max-workers=1 --no-daemon` | PASS, service aggregation: line 2167 covered/188 missed, class 240 covered/19 missed |
| `./scripts/smoke-validate.sh commerce` | PASS, 107 tasks including aggregated Kover |
| `./gradlew detekt detektTest -Pkotlin.incremental=false --no-build-cache --max-workers=1 --no-daemon` | PASS, 68 tasks |
| `node scripts/validate-usage-billing-microservices-readme.mjs` | PASS |
| `./scripts/smoke-validate.sh diagram-qa` | PASS |
| `git diff --check` | PASS |

composition module은 production source가 없으므로 자체 Kover report만 생성하면 counter가 0이다. 최종
구성은 Meter, Usage, Billing, Invoice, Query의 Kover artifact를 composition report에 집계한다. 따라서 위
수치는 composition test가 실제 서비스 class를 실행한 결과이며, 빈 XML을 coverage 증거로 사용하지 않는다.

## 남는 의도적 범위

- 이 예제는 Kafka broker failover, multi-region replication, schema registry 운영, 실제 IAM/JWT issuer를
  구현하지 않는다. README는 이를 운영 확장 과제로 남기며 exactly-once를 주장하지 않는다.
- durable poison quarantine/redrive의 concrete operator workflow는 Query service에 구현한다. 다른 consumer의
  permanent contract policy를 동일하게 일반화했다고 주장하지 않는다.
- transport outage는 deterministic test switch와 실제 Kafka recovery를 조합해 검증한다. Docker network
  partition 전체를 재현했다는 주장은 하지 않는다.
