# #555 Usage Billing Microservices 설계 검토

Date: 2026-07-22

Reviewed design: `docs/superpowers/specs/2026-07-22-issue-555-usage-billing-microservices-design.md`

Scope: 구현 전 설계만 검토했다. 이 검토는 구현 승인이나 runtime validation을 대신하지 않는다.

## 결론

사용자가 명시적으로 요청한 inline 검토로 performance, stability, security, operator/Ops,
developer/API, user/caller 여섯 관점을 독립적으로 적용했다. P0=0, P1=0이며, 발견된 설계상
보완 사항은 모두 승인된 design에 반영되어 implementation plan으로 진행할 수 있다.

## 관점별 결과

| 관점 | P0 | P1 | 검토 결과 |
|---|---:|---:|---|
| Performance | 0 | 0 | aggregate key partitioning, bounded outbox/inbox batch, `DEFERRED` backlog와 sequential Testcontainers lane을 고정했다. |
| Stability | 0 | 0 | send-after-commit crash, duplicate, owner-token claim expiry, restart, poison/redrive, missing predecessor를 durable state transition으로 모델링했다. |
| Security | 0 | 0 | tenant predicate, principal/path match, payload-digest conflict, operator role, low-cardinality telemetry와 secret non-disclosure를 명시했다. |
| Operator/Ops | 0 | 0 | outbox/inbox/quarantine/checkpoint/lag metric, recovery API, staged extraction의 parity/drain/route-only rollback을 명시했다. |
| Developer/API | 0 | 0 | physical Gradle module 경계, runtime shared DTO 금지, versioned envelope, Exposed repository architecture guard, CI artifact chain을 명시했다. |
| User/Caller | 0 | 0 | HTTP idempotency replay, stable conflict response, immutable correction, totals parity와 customer/query boundary를 명시했다. |

## 확인한 핵심 위험과 설계 대응

| 위험 | 설계 대응 |
|---|---|
| Kafka send 뒤 process 종료 | `PUBLISHED` mark 전 재전송을 정상 at-least-once 경로로 인정하고 receiver inbox dedup으로 흡수 |
| partition 밖의 순서를 가정 | key를 `tenantId|aggregateType|aggregateId`로 고정하고 aggregate version만 순서 계약으로 사용 |
| poison이 한 partition 전체를 멈춤 | durable quarantine 뒤 offset을 진행하고 affected aggregate만 block |
| 가격/usage event 역순 | Billing이 pricing evidence가 올 때까지 `DEFERRED`로 유지하고 silent skip 금지 |
| service 경계가 build artifact에서 사라짐 | five sibling deployable module과 test-only composition module로 분리 |
| Exposed-only 규칙 우회 | concrete `ExposedJdbcRepository` assignability와 raw JDBC/SQL API scan을 architecture test/CI에 포함 |
| correction이 기존 금액을 overwrite | `AdjustmentPosted`와 새 invoice/query materialization만 허용 |

## 정적 근거

- `settings.gradle.kts`의 one-level `commerce/` auto-registration을 확인해 sibling module 구조를 선택했다.
- #553 build와 `RepositoryArchitectureTest`에서 Java 25, Spring Boot 4, `ExposedJdbcRepository`,
  test/integration/stress task의 기존 구현 관례를 확인했다.
- `commerce/promotion-voucher-campaign` inbox와 `messaging/transactional-outbox`를 조사했다. 두
  implementation은 state-machine 참고 근거로만 사용하며, raw advisory SQL/JDBC fixture가 섞인
  부분은 #555에서 재사용하지 않는다.
- Spring Kafka 공식 문서의 DB-first transaction/redelivery caveat와 Apache Kafka의 partition-local
  ordering을 설계 전제로 삼았다.

## 다음 게이트

implementation plan은 design의 모든 성공 기준을 exact file path, RED/GREEN test, workflow update,
diagram validation으로 연결해야 한다. plan review와 별도 사용자 승인이 완료되기 전에는
production/test implementation을 생성하지 않는다.
