# #555 Usage Billing Microservices 구현 계획 검토

Date: 2026-07-22

Plan: `docs/superpowers/plans/2026-07-22-issue-555-usage-billing-microservices-plan.md`

Approved design: `docs/superpowers/specs/2026-07-22-issue-555-usage-billing-microservices-design.md`

Scope: 구현 코드는 생성하지 않았다. 이 문서는 계획의 실행 가능성과 설계 충족 여부를 inline 여섯
관점으로 검토한 증거이며, 구현 승인을 대신하지 않는다.

## 결론

14개 작업은 physical service modules, Exposed-only persistence, independent envelope, outbox/inbox,
financial correction, operator recovery, Testcontainers composition, diagram/readme, repository workflow
registration을 TDD RED/GREEN 순서로 연결한다. 최종 계획은 P0=0, P1=0이며 별도 구현 승인을 받을
준비가 됐다.

## 검토 이력

초기 검토에서 다음 P1 두 건을 발견했고 계획 본문에 반영했다.

| P1 | 보정 |
|---|---|
| runtime test가 Gradle project graph를 직접 읽는다는 불명확한 registration test | module 생성 전/후 `./gradlew projects` proof와 `integrationTest` task resolution으로 바꿨다. |
| test-only HTTP contract 예시의 return types가 정의되지 않음 | `ContractHttpResult`, `ContractBillingTotals`를 same fixture snippet에 명시했다. |

보정 뒤 동일 문서를 여섯 관점으로 다시 검토했다.

## 최종 동일 계획 검토

| 관점 | P0 | P1 | 확인 범위 |
|---|---:|---:|---|
| Performance | 0 | 0 | aggregate partition key, bounded claim page/lease, `DEFERRED` backlog, `--max-workers=1`, PostgreSQL race proof |
| Stability | 0 | 0 | send-after-ack crash, duplicate, retry/claim expiry, restart, deferred predecessor, poison quarantine/redrive |
| Security | 0 | 0 | tenant/principal route guard, digest conflict, operator role, payload/key/tenant metric non-disclosure, no cross-database read |
| Operator/Ops | 0 | 0 | backlog/lag/quarantine/health metric, explicit redrive audit, Kover/XML artifacts, staged extraction rollback guide |
| Developer/API | 0 | 0 | six Gradle paths, Java 25/BOM rule, service-local envelope, no-runtime-shared-contract, Exposed architecture/raw API guard |
| User/Caller | 0 | 0 | HTTP idempotency replay/conflict, deterministic delay result, immutable correction, black-box totals parity |

## 설계 요구사항 추적

| 설계 요구사항 | 계획 작업 |
|---|---|
| service-owned deployment/database | 1, 2, 4-9 |
| ExposedJdbcRepository와 raw SQL 금지 | 4, 5-11 |
| envelope evolution과 partition order | 3, 10, 11 |
| outbox/inbox at-least-once semantics | 5-11 |
| delay/reorder/poison/restart | 7, 9-11 |
| immutable charge/invoice correction | 7, 8, 11 |
| tenant/operator/metrics | 9, 11 |
| diagram and extraction/rollback documentation | 12 |
| CI, smoke/full, stale/artifact, lesson | 13, 14 |

## 정적 검증

- plan Markdown fence count: even/balanced
- forbidden-marker scan: zero hit after the P1 corrections
- six target Gradle project paths: all explicitly named
- `git diff --check`: PASS
- no production/test implementation files were created; modified scope is design, plan, and review evidence only

## 구현 전 고정 경계

1. no shared production DTO/repository/database, no XA, no exactly-once claim
2. no raw JDBC/SQL fixture shortcut, including advisory-lock SQL
3. no consumer offset is treated as a financial effect acknowledgement
4. no diagram is accepted without the full SVG/CairoSVG PNG arrow-head and full-size inspection checklist
5. implementation stays inline under the user’s explicit no-subagent constraint

## 다음 게이트

사용자가 이 implementation plan을 명시적으로 승인한 뒤에만 Task 1부터 TDD 순서로 구현한다.
그때도 local commit, push/PR, merge는 각각 별도 workflow gate를 따른다.
