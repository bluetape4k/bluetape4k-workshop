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

## Bluetape skill 준수 감사

사용자 지적 항목만 수정하는 대신, `bluetape-kotlin-patterns`, Spring Boot/Exposed/testing reference,
`bluetape-diagram`, workshop module guard를 현재 source와 실행 결과로 다시 점검했다.

| Contract | 현재 구현과 잠금 장치 | 판정 |
| --- | --- | --- |
| Kotlin durable model | 다섯 서비스의 모든 `data class`가 `Serializable`과 `serialVersionUID`를 선언한다. source architecture test가 수량 불일치를 막는다. | PASS |
| Public wire contract | 5개 versioned integration envelope에 English KDoc, direct codec tests, README wire-boundary 설명을 둔다. | PASS |
| Caller/internal validation | external decoder는 Bluetape `requireNotBlank`/numeric helper를 사용하고, fixture boundary는 `requireEquals`를 사용한다. internal state에만 `check`을 둔다. | PASS |
| Operational logging | 세 external Kafka decoder는 `KLogging`과 inbound outcome log를 사용하며 Query permanent failure는 stable `query.inbound.quarantined` event를 남긴다. raw payload/credential은 기록하지 않는다. | PASS |
| Spring/Exposed data boundary | concrete persistence repository는 `ExposedJdbcRepository`를 구현한다. source guard가 raw JDBC, `JdbcTemplate`, statement API, `Transaction.exec`를 차단한다. | PASS |
| Test discipline | JUnit 5 + bluetape assertions를 사용하고, exception assertions/polling assertion의 generic `runCatching`/bare `check`을 제거했다. 다섯 서비스에 nonparallel JUnit/logback test resource를 추가했다. | PASS |
| Java/Spring module | 모든 신규 서비스와 composition module은 Java 25 toolchain, Spring Boot configuration, BOM-only bluetape dependency resolution을 유지한다. | PASS |
| Diagram/document guard | architecture 1개, canonical state 1개, source-backed sequence 4개를 SVG/PNG pair로 유지하고 README validator, generator, QA wrapper를 함께 갱신했다. | PASS |

## 구현 경계 검토

| 경계 | 확인 결과 |
| --- | --- |
| DB access | 모든 concrete repository가 `ExposedJdbcRepository`를 사용한다. composition의 source guard가 `java.sql.`, `DriverManager`, `JdbcTemplate`, statement 생성, `Transaction.exec`를 차단한다. |
| ownership | Meter=price, Usage=accepted usage, Billing=replicated price evidence/rated charge, Invoice=append-only line, Query=projection/checkpoint/quarantine이다. |
| delivery | producer는 local outbox를 lease/claim 후 publish하고, receiver는 local inbox/quarantine 결정을 먼저 durable하게 만든다. |
| correction | `AdjustmentPosted`는 원본 charge event를 `correctionOf`로 참조하는 새 Invoice line과 `InvoiceCorrectionIssued`를 만든다. 기존 line은 갱신하지 않는다. |
| schema | Query는 v1/v2를 명시적으로 수용하고 unknown mandatory version은 durable quarantine으로 보낸다. |
| operator path | Query quarantine snapshot/redrive audit, outbox retry wait, aggregate version gap이 각각 명시적인 관찰/복구 지점이다. |
| Kotlin contract | service data model은 `Serializable`과 명시적 `serialVersionUID`를 가지며, composition architecture test가 누락을 차단한다. |
| decoder and log | 외부 Kafka decoder는 Bluetape validation helper로 required field를 검증하고, listener debug log는 raw payload 없이 event ID/type 또는 quarantine reason만 기록한다. |

## Composition 시나리오 증거

Kafka + PostgreSQL container suite는 다음 11개 시나리오, 12개 test를 실행했다.

1. delayed publication recovery
2. duplicate Usage delivery
3. out-of-order aggregate version defer/retry
4. deterministic transport outage → `RETRY_WAIT` → actual Kafka recovery
5. Toxiproxy real broker-path outage → same outbox row recovery
6. Usage restart after durable price evidence
7. poison quarantine, independent progress, redrive request
8. additive v2 acceptance and v99 quarantine
9. Query tenant authority isolation
10. price/usage/charge/invoice/query delivery parity
11. append-only Invoice correction and Query-visible correction history

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

generator와 explicit-target QA wrapper를 실행했고, 각 asset을 full-size PNG로 확인했다.

| Asset | PNG size | Source and visual contract | Result |
| --- | --- | --- | --- |
| architecture | 3400×1840 | source-backed responsibility cards and orthogonal Kafka/DB connections | PASS, markers 14 / connectors 9 / cards 10 |
| outbox-inbox-state | 3200×1700 | canonical state machine retained; obsolete `state-01` pair removed | PASS, markers 9 / connectors 4 |
| delivery | 3400×2040 | participants, lifelines, activations, numbered messages, branch frame | PASS, markers 6 / connectors 4 |
| poison-recovery | 3400×2040 | participants, lifelines, activations, numbered messages, branch frame | PASS, markers 6 / connectors 3 |
| correction | 3400×2040 | participants, lifelines, activations, numbered messages, branch frame | PASS, markers 6 / connectors 4 |
| extraction | 3400×2040 | participants, lifelines, activations, numbered messages, branch frame | PASS, markers 6 / connectors 4 |

| 검사 | 결과 |
| --- | --- |
| SVG marker fill/stroke 및 endpoint marker audit | PASS (6 targets, marker failures 0) |
| connector geometry, endpoint, mixed-corner audit | PASS |
| rounded orthogonal connector / spline 금지 | PASS |
| CairoSVG PNG 재생성 뒤 SVG marker/endpoint audit 및 PNG 원본 육안 대조 | PASS |
| full-size PNG 육안 검사 | PASS (table의 6 assets, clipped text/arrowhead reversal 없음) |

따라서 diagram은 SVG만 맞는 상태로 끝내지 않았다. raster 화살촉 방향은 SVG marker/endpoint audit 뒤
재생성한 PNG 원본을 개별적으로 대조해 확인했으며, 픽셀 비교기가 있다고 과장하지 않는다.

## 검증 명령과 결과

| 명령 | 결과 |
| --- | --- |
| `./gradlew --no-daemon :commerce-usage-billing-microservices-composition-tests:test --tests '...PersistenceArchitectureTest' --max-workers=1` | PASS |
| `./gradlew :commerce-usage-billing-microservices-composition-tests:integrationTest :commerce-usage-billing-microservices-composition-tests:koverXmlReport -Pkotlin.incremental=false --no-build-cache --max-workers=1 --no-daemon` | PASS, 12 tests across 10 composition scenario classes; failures/errors/skipped 0 |
| `./gradlew --no-daemon :commerce-usage-billing-microservices-composition-tests:integrationTest --tests '...CorrectionIntegrationTest' --max-workers=1` | PASS |
| `./gradlew :commerce-usage-billing-microservices-composition-tests:koverXmlReport -Pkotlin.incremental=false --no-build-cache --max-workers=1 --no-daemon` | PASS, service aggregation: line 2177 covered/188 missed, class 240 covered/19 missed |
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
