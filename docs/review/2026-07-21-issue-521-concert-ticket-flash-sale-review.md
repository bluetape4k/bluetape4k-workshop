# #521 Concert Ticket Flash Sale 검토

날짜: 2026-07-21
모듈: `:commerce-concert-ticket-flash-sale`
범위: `commerce/concert-ticket-flash-sale`
브랜치: `feature/issue-521-concert-ticket-flash-sale`

## 결론

- inline 7-Tier 검토 결과 P0 0건, P1 0건이다.
- PostgreSQL과 `ExposedJdbcRepository`가 inventory, USER/IP guard, purchase, claim, receipt의 최종 권위다.
- Redis/Lettuce는 waiting-room과 foreground duplicate suppression에만 사용한다.
- unknown payment, late approval, refund, ticket revoke는 restart 이후에도 stable operation/effect ID로 수렴한다.
- README는 구현된 recovery HTTP와 core stream contract를 production extension point와 분리한다.

## 7-Tier 검토

| 관점 | 결과 | 근거 |
|---|---|---|
| Functional correctness | PASS | inventory/guard lock ordering, fenced payment/refund/ticket transition, refund+disposition restock gate를 확인했다. |
| Tests | PASS | PostgreSQL/Redis integration, hostile last-inventory race, worker restart lookup, migration compatibility, opt-in stress를 검증했다. |
| Error handling | PASS | timeout은 `UNKNOWN`으로 유지하고 stable allowlist problem, no-store owner-safe 404, bounded retry/quarantine을 제공한다. |
| Documentation | PASS | bilingual runbook, SVG/PNG 6개, state/customer-action mapping, 실제 제공 범위와 extension point 구분을 확인했다. |
| Security | PASS | production fail-closed, loopback-only demo identity/operator token, public payload sensitive-field rejection을 확인했다. |
| Performance/operations | PASS | DB lane permit, bounded batch/deadline, slow-consumer eviction, low-cardinality metrics, independent readiness/liveness를 확인했다. |
| Code quality/ecosystem | PASS | Java 25, Spring Boot, Spring Modulith, Exposed, 정확한 `ExposedJdbcRepository`, bluetape4k-lettuce/logging/testcontainers를 재사용한다. |

## 검토 중 수정한 항목

1. 일반 persistence에 남아 있던 raw JDBC 형태를 Exposed DSL/DAO와 `ExposedJdbcRepository` 구현체로 교체했다. direct JDBC는 migration runner만 허용한다.
2. static demo가 security chain에 막히지 않도록 문서 asset만 `permitAll`로 열고 모든 `/api/**`는 기존처럼 인증 필수로 유지했다.
3. README에서 network SSE, seed/reset, public purchase-start를 제공하는 것처럼 보일 수 있는 설명을 제거하고 실제 core contract와 production adapter 책임을 구분했다.
4. timeout/late approval diagram과 state mapping에 `NEVER_ISSUED`, refund success, revoke 완료 전 restock 금지 규칙을 명시했다.
5. `bluetape-kotlin-patterns` 전수 검토에서 raw `UUID.randomUUID`, Kotlin `require`, monitor `synchronized`를 Bluetape UUID/validation helper와 `ReentrantLock`으로 교체했다. 모든 production data class의 `Serializable`/`serialVersionUID`를 architecture test로 고정했다. worker/Redis 장애 경계에는 `KLogging`을 추가하고 Exposed lambda는 receiver-shadowing 방지 local value를 사용한다.

## Ecosystem 및 dependency 확인

- version authority: root `bluetape4k-dependencies` platform only
- persistence: JetBrains Exposed core/DAO/JDBC/Spring, Bluetape4k `ExposedJdbcRepository`
- Redis: `bluetape4k-lettuce`, Bucket4j/Lettuce
- runtime: `bluetape4k-virtualthread-api` + `virtualthread-jdk25`
- observability: `bluetape4k-logging`, `bluetape4k-micrometer`
- integration fixture: `bluetape4k-testcontainers` PostgreSQL/Redis
- 신규 dependency와 개별 Bluetape BOM/version pin은 없다.

## 검증 결과

- `:commerce-concert-ticket-flash-sale:test`: PASS, 73 tests
- `KotlinPatternArchitectureTest`: PASS, 2 architecture guards
- `:commerce-concert-ticket-flash-sale:build`: PASS
- root `detekt`: PASS(`NO-SOURCE` 포함)
- commerce smoke lane: PASS
- validation stale-check: PASS, 108 active Gradle projects
- `actionlint .github/workflows/Examples.yml`: PASS
- `:commerce-concert-ticket-flash-sale:ticketStressTest -PticketStressRun=local-issue-521`: PASS, opt-in stress test 1개
- `TicketBrowserContractTest`: PASS
- headless Chrome 360px/focus/non-color status/polling fallback: PASS
- runbook validator: PASS, 2 locales / 11 ordered sections / 6 SVG+PNG pairs
- 각 SVG direct XML/text/connector/geometry/endpoint/mixed-corner audit: PASS
- PNG 6개 full-size inspection: PASS

## 비차단 production 과제

- 실제 IdP/JWT와 operator RBAC adapter
- 실제 PostgreSQL의 stable operation lookup/idempotency 보증 검증
- durable outbox/event log와 network SSE adapter
- 실측 arrival rate/provider latency 기반 capacity 재산정
