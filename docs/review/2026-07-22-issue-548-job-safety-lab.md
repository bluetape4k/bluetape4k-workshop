# Issue #548 Job Safety Lab 인라인 코드 리뷰

Date: 2026-07-22
Scope: `leader/job-safety-lab`, README/diagram, root registration, Examples workflow
Review mode: subagent 없이 performance, stability, security, Ops, developer/API, user/caller 여섯 관점을 각각 점검

## 결론

- P0: 0
- P1: 0 (리뷰 중 발견한 2건 수정 완료)
- P2: 3 (예제와 production 경계를 README에 명시)
- P3: 1 (향후 dependency warning 정리 후보)

이 예제는 leader election, resource fencing, PostgreSQL authority, transactional outbox, 외부 효과 reconciliation을 서로 다른 보장으로 유지한다. Redis leader owner token은 opaque이며 어떤 코드 경로에서도 `FencingToken`으로 변환하지 않는다. 모든 일반 DB 작업은 Exposed DSL/DAO와 `ExposedJdbcRepository` 경계 안에 있다.

## 관점별 결과

| 관점 | 결과 | 근거 |
| --- | --- | --- |
| Performance | PASS | fence Redis key는 `ConflictKey` digest 단위이고 Lua key는 같은 hash slot을 사용한다. DB transaction 안에는 provider/Redis 호출이 없다. outbox claim hot path는 `(status, nextAttemptAt)` index와 `FOR UPDATE`를 사용하며 timeline은 최대 512개다. |
| Stability | PASS after fix | fence 42 이후 fence 41은 PostgreSQL conditional update 0 row로 거부된다. claim 직후 process가 죽어도 timeout 뒤 provider query로 회수한다. interruption은 두 lease를 해제한 뒤 다시 전파한다. Spring context 종료 시 Exposed database registration을 제거한다. |
| Security | PASS | stateless HTTP Basic, 기본 `denyAll`, operator-only reset/effect/unsafe route, health만 공개한다. unsafe controller는 `lab-unsafe & !prod`와 property 이중 gate다. Redis key에는 raw tenant/conflict 값 대신 SHA-256 digest를 쓴다. |
| Ops | PASS | stable rejection reason, bounded timeline, explicit `SKIPPED`/`REJECTED`/`FAILED`, claim timeout, namespace epoch recovery, mixed-version marker 순서를 runbook에 설명한다. default test와 container integration을 CI에서 분리하고 결과 artifact를 등록했다. |
| Developer/API | PASS | Java 25는 신규 모듈에만 적용한다. Spring Boot MVC만 제공한다. 모든 concrete repository가 Bluetape `ExposedJdbcRepository`를 구현하며 architecture test가 raw DB escape hatch를 막는다. 여섯 scenario enum과 effect 결과가 closed contract다. |
| User/caller | PASS | README locale pair가 동일한 여섯 실패 시나리오, 11개 상태, 실행/보안/복구 command, A41/B42 순서, microservice extraction 규칙을 제공한다. 네 PNG를 원본 크기로 확인했다. |

## 리뷰 중 수정한 P1

### 1. process crash 뒤 `CLAIMED` outbox가 영구 정체될 수 있었다

기존 worker는 `PENDING` 또는 `RECONCILIATION_REQUIRED` row만 선택했다. claim transaction commit 직후 provider 결과를 저장하기 전에 process가 죽으면 row는 `CLAIMED`에 남고 다시 선택되지 않았다.

수정 후 claim은 configurable `workshop.job-safety.outbox.claim-timeout`을 기록한다. 만료된 `CLAIMED` row는 provider를 다시 execute하지 않고 원래 `OperationId`로 query하는 reconciliation lane에서만 회수한다. 회귀 테스트는 provider가 이미 적용한 뒤 worker가 죽은 상황에서 execute count와 application count가 1로 유지되는지 확인한다.

### 2. domain callback의 `InterruptedException`이 `FAILED` 결과로 흡수됐다

일반 domain exception은 안정적인 `FAILED(DOMAIN_FAILURE)` 결과로 매핑해도 되지만 interruption은 cancellation signal이다. 수정 후 interrupt flag를 복구하고 fence와 leader lease를 모두 해제한 다음 원래 `InterruptedException`을 전파한다. release 중 interruption도 flag를 보존한다.

## P2/P3 disposition

- P2 — startup schema: `SchemaUtils.createMissingTablesAndColumns`는 workshop bootstrap에만 사용한다. production은 reviewed Flyway/Liquibase migration으로 교체해야 하며 README에 경계를 명시했다.
- P2 — provider adapter: 기본 provider는 결정론적 fake다. 실제 provider에서는 stable idempotency key와 lookup API 계약을 별도 integration test로 증명해야 한다.
- P2 — identity/observability: HTTP Basic과 low-cardinality 운영 지침만 제공한다. production IdP/JWT, audit retention, dashboard/alert 정책은 배포 환경 소유다.
- P3 — Java 25 integration에서 Netty가 native-access 미래 경고를 출력한다. 현재 테스트는 성공하며 dependency/toolchain 갱신 시 `--enable-native-access` 필요 여부를 재평가한다.

## 검증 증거

- default tests: 58 passing
- PostgreSQL + Redis integration tests: 12 passing
- README contract/validator: 2 locales, 6 scenarios, 11 states, 8 assets
- diagram QA: 4 targets, weak reference 0, geometry/endpoint/connector/mixed-corner 모두 PASS
- `actionlint`, stale-check, Gradle project discovery, source guards, `git diff --check`: PASS
- module-local detekt task는 등록되어 있지 않으므로 실행 대상이 아니며 Kotlin compile과 architecture test를 사용했다.
