# Issue #893 JaVers Kafka snapshot projection 구현 리뷰

## 범위

- `develop` 대비 Issue #893 전체 diff
- dependencies 2.0.0이 관리하는 `javers-persistence-kafka:1.0.0` 공개 API
- architecture, API, performance, security, test, operator 여섯 관점
- Testcontainers Kafka/Redis와 MockConsumer 회귀, 문서·manifest·stale guard

## 1차 Findings와 조치

| 심각도 | Finding | 조치 |
|---|---|---|
| P1 | Redis snapshot 존재만 검사해 동일 instance의 미반영 Kafka command 뒤 후속 mutation이 stale head에서 실행될 수 있음 | `pendingProjection` atomic reservation으로 모든 mutation 뒤 catch-up을 강제하고 회귀 추가 |
| P1 | 새 process는 pending 상태를 잃어 기존 Redis head와 Kafka backlog가 어긋난 상태에서 mutation할 수 있음 | 초기 상태를 pending으로 두고 pre-provisioned single-partition topic의 최초 replay 전 mutation을 거부, restart backlog 회귀 추가 |
| P1 | config overload 내부 consumer가 projector `subscribe()` 실패 전에 노출되지 않아 생성 실패 cleanup에서 누수 가능 | Factory가 `KafkaConsumer`를 직접 생성·등록하고 projector에는 `closeConsumerOnClose=false`로 주입 |
| P1 | mutation publish와 replay가 동시에 실행되면 empty replay가 pending 상태를 먼저 해제할 수 있음 | query, mutation, replay, close를 동일 `ReentrantLock`으로 직렬화 |
| P1 | stale guard와 manifest가 참조하는 구현 리뷰 artifact 부재 | 이 문서를 추가하고 stale-check에 포함 |

## 최종 판정

- Architecture: P0 0건, P1 0건
- API: P0 0건, P1 0건
- Performance/Stability: P0 0건, P1 0건
- Security/Privacy: P0 0건, P1 0건
- Test: P0 0건, P1 0건
- Operator: P0 0건, P1 0건

최종 gate는 **PASS**다. dependencies 2.0.0 consumer 범위에서 필수 수정 사항은 남아 있지 않다.

## 수용한 P2 경계

- `replayUntilIdle`은 연속 idle poll 기반 catch-up이며 전체 deadline과 장기 worker는 caller-owned다.
- Lettuce `EXEC` 뒤 command error/connection loss는 commit-unknown 또는 partial projection일 수 있고
  자동 repair나 exactly-once를 제공하지 않는다.
- `close()`는 owned resource를 한 번씩 시도하며 실패 resource 재호출은 보장하지 않는다.
- `CdoSnapshot` query authorization/redaction과 Kafka/Redis TLS/SASL/ACL은 application-owned다.
- Fail-on-second fixture는 pre-EXEC 동등 실패와 offset 미커밋을 검증하며 실제 Redis `DISCARD` 주입은 하지 않는다.
- Factory-level 실제 client close count 대신 source ownership과 synthetic suppressed-exception 회귀를 사용한다.

## 검증 근거

- RED: 후속 mutation guard 추가 전 기대한 `IllegalStateException`이 없어 회귀 실패
- RED: restart backlog에서 초기 catch-up 계약 추가 전 replay 없는 mutation이 차단되지 않음
- GREEN: `:exposed-javers-persistence-audit:clean :exposed-javers-persistence-audit:test --no-configuration-cache`
  에서 17 tests passing, `BUILD SUCCESSFUL`
- Dependency insight:
  - `io.github.bluetape4k.javers:javers-persistence-kafka:1.0.0` ← `bluetape4k-dependencies:2.0.0`
  - `io.github.bluetape4k:bluetape4k-lettuce:2.0.0`
  - `org.apache.kafka:kafka-clients:4.2.0`
  - `io.lettuce:lettuce-core:7.6.0.RELEASE`
- `.github/workflows/Examples.yml`은 기존 module path와 `:exposed-javers-persistence-audit:test`
  membership을 이미 포함해 no-op으로 유지한다.
- Root `detekt`, `data-access-full`, stale-check, ecosystem checker 113 tests, assertion governance,
  README language/diagram, `actionlint`, `git diff --check`를 단일 검증 lane에서 통과했다.

## 운영 교훈

같은 worktree에서 여러 독립 reviewer가 Gradle test를 동시에 실행하면 test-result binary가 서로
삭제되어 모든 test가 pass한 뒤에도 `NoSuchFileException`/`EOFException`이 발생할 수 있다. 리뷰는
병렬로 수행하되 동일 module의 stateful Gradle 검증은 단일 lane에서 순차 실행한다.
