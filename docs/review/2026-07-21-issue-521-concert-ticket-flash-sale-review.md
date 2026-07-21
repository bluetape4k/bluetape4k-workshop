# #521 Concert Ticket Flash Sale 검토

Date: 2026-07-21
Module: `:commerce-concert-ticket-flash-sale`
Scope: `commerce/concert-ticket-flash-sale`
Branch: `feature/issue-521-concert-ticket-flash-sale`

## 결론

- Inline 7-Tier 검토 결과 P0 0건, P1 0건이다.
- PostgreSQL과 `ExposedJdbcRepository`가 inventory, USER/IP guard, purchase, claim, receipt의 최종 권위다.
- Redis/Lettuce는 waiting-room과 foreground duplicate suppression에만 사용한다.
- Unknown payment, late approval, refund, ticket revoke가 restart 이후에도 stable operation/effect ID로 수렴한다.
- README는 구현된 recovery HTTP와 core stream contract를 production extension point와 분리한다.

## 7-Tier 검토

| 관점 | 결과 | 근거 |
|---|---|---|
| Functional correctness | PASS | inventory/guard lock ordering, fenced payment/refund/ticket transition, refund+disposition restock gate |
| Tests | PASS | PostgreSQL/Redis integration, hostile last-inventory race, worker restart lookup, migration compatibility, opt-in stress |
| Error handling | PASS | timeout을 `UNKNOWN`으로 유지하고 stable allowlist problem, no-store owner-safe 404, bounded retry/quarantine 제공 |
| Documentation | PASS | bilingual runbook, 6개 SVG/PNG, state/customer-action mapping, 실제 제공 범위와 extension point 구분 |
| Security | PASS | production fail closed, loopback-only demo identity/operator token, public payload sensitive-field rejection |
| Performance/operations | PASS | DB lane permit, bounded batch/deadline, slow-consumer eviction, low-cardinality metrics, independent readiness/liveness |
| Code quality/ecosystem | PASS | Java 25, Spring Boot, Spring Modulith, Exposed, exact `ExposedJdbcRepository`, bluetape4k-lettuce/logging/testcontainers 재사용 |

## 검토 중 수정한 항목

1. 일반 persistence에 남아 있던 raw JDBC 형태를 Exposed DSL/DAO와
   `ExposedJdbcRepository` 구현체로 교체했다. Direct JDBC는 migration runner만 허용한다.
2. Static demo가 security chain에 막히지 않도록 문서 asset만 `permitAll`로 열고 모든 `/api/**`는
   기존과 같이 인증 필수로 유지했다.
3. README에서 network SSE, seed/reset, public purchase-start를 제공하는 것처럼 보일 수 있는 설명을
   제거하고 실제 core contract와 production adapter 책임을 구분했다.
4. Timeout/late approval diagram과 state mapping에 `NEVER_ISSUED`, refund success, revoke 완료 전
   restock 금지 규칙을 명시했다.

## Ecosystem 및 dependency 확인

- Version authority: root `bluetape4k-dependencies` platform only
- Persistence: JetBrains Exposed core/DAO/JDBC/Spring, Bluetape4k `ExposedJdbcRepository`
- Redis: `bluetape4k-lettuce`, Bucket4j/Lettuce
- Runtime: `bluetape4k-virtualthread-api` + `virtualthread-jdk25`
- Observability: `bluetape4k-logging`, `bluetape4k-micrometer`
- Integration fixtures: `bluetape4k-testcontainers` PostgreSQL/Redis
- 신규 dependency와 개별 Bluetape BOM/version pin 없음

## 검증 결과

- `:commerce-concert-ticket-flash-sale:test`: PASS, 70 tests
- `:commerce-concert-ticket-flash-sale:build`: PASS
- Root `detekt`: PASS (`NO-SOURCE` 포함)
- Commerce smoke lane: PASS
- Validation stale-check: PASS, 108 active Gradle projects
- `actionlint .github/workflows/Examples.yml`: PASS
- `:commerce-concert-ticket-flash-sale:ticketStressTest -PticketStressRun=local-issue-521`: PASS, 1 opt-in stress test
- `TicketBrowserContractTest`: PASS
- Headless Chrome 360px/focus/non-color status/polling fallback: PASS
- Runbook validator: PASS, 2 locale / 11 ordered section / 6 SVG+PNG pair
- 각 SVG direct XML/text/connector/geometry/endpoint/mixed-corner audit: PASS
- 6개 PNG full-size inspection: PASS

## 비차단 production 과제

- 실제 IdP/JWT와 operator RBAC adapter
- 실제 PG의 stable operation lookup/idempotency 보증 검증
- Durable outbox/event log와 network SSE adapter
- 실측 arrival rate/provider latency 기반 capacity 재산정
