# Issue #893 JaVers Kafka snapshot projection 구현 계획

## 목표

기존 persistence-audit 예제가 2.0.0의 Kafka snapshot projector를 직접 소비하도록 하고,
단일 partition·batch offset·중복 replay·restart rebuild 계약을 Testcontainers와 문서로 고정한다.

## 순서

1. GNO/live Issue #893, upstream Issue #304/PR #315와 stable 2.0.0 source를 현재 module에 대조한다.
2. 설계와 계획을 작성하고 architecture, API, performance, security, test, operator 관점의 독립 리뷰를
   받아 P0/P1을 0으로 수렴시킨다.
3. baseline module test를 보존하고 Kafka→transactional Lettuce Redis integration, duplicate replay,
   restart rebuild, first-empty-poll 이후 record 처리, multi-partition poll-before guard, mutation 전
   pre-EXEC 동등 target failure 후 실패 instance를 닫고 같은 group의 새 consumer/projector로 수행하는
   committed-offset retry 회귀를 production
   구현 전에 추가해 RED를 확인한다.
4. versionless JaVers Kafka/Lettuce aliases와 직접 API surface인 Kafka/Lettuce client dependency를
   module에 추가하고 `KafkaRedisOrderAuditFactory`와 소유권이 명시된 `KafkaRedisOrderAuditPipeline`을 구현한다.
5. pipeline은 내부 Kafka-backed writer JaVers, transactional Lettuce-backed reader JaVers, projector를 동일
   topic과 target repository에 연결한다. 공개 facade는 command를 Kafka에, query를 Redis에만 위임한다.
   consumer config는 nonblank group, auto-commit false, earliest reset을 검증한다. finite catch-up
   기본 연속 idle poll 3회의 `replayUntilIdle` 및 idempotent close를 제공한다.
   close는 consumer, producer, Redis connection을 모두 시도하고 최초 예외에 후속 cleanup 예외를 suppress한다.
6. module README pair, root README pair, coverage matrix, lesson/index, ecosystem manifest와 좁은 stale-check를
   갱신한다. `.github/workflows/Examples.yml`의 기존 path filter와 smoke/full task membership은 structural
   check로 유지 여부를 증명하고 변경이 없으면 lesson/PR DoD에 no-op으로 기록한다. README/lesson/stale
   guard에는 최초 module 도입 Issue #290의 후속임을 명시한다.
7. clean module tests, root detekt, smoke/full/stale/ecosystem/README/actionlint/diff 검증을 실행한다.
   dependency insight는 JaVers Kafka, Kafka client, Lettuce repository/client가 dependencies 2.0.0 및
   catalog authority에서 해석되는지 확인한다.
8. 독립 구현 리뷰에서 P0/P1을 0으로 수렴시킨 뒤 Lore commit을 만들고 push한다.
9. Korean PR을 생성해 Issue #893, milestone 2.0.0, assignee debop을 연결하고 exact-head hosted CI와
   review thread/body DoD를 검증한다.
10. 다음 Issue #894 worktree를 #893 head 위에 쌓고, 다섯 PR이 모두 준비될 때까지 merge하지 않는다.

## 파일 범위

- `exposed/javers-persistence-audit/build.gradle.kts`
- `exposed/javers-persistence-audit/src/main/**`
- `exposed/javers-persistence-audit/src/test/**`
- `.github/workflows/Examples.yml` 검토(no-op 가능)
- module/root README pair와 `docs/coverage-matrix.md`
- `docs/superpowers/specs/**`, `docs/superpowers/plans/**`, `docs/review/**`, `docs/lessons/**`
- ecosystem reuse manifest/checker와 `scripts/smoke-validate.sh`

## 중단 조건

- stable dependencies 2.0.0에서 projector/public repository API가 resolve되지 않으면 구현을 중단하고
  provider/consumer mismatch를 증거로 남긴다.
- single-partition guard나 batch-after-success/pre-EXEC failure/same-group retry offset 계약을 실제 API 또는 fixture로 입증할 수 없으면
  수용 기준을 완료로 표시하지 않는다.
- 기존 root user change와 unrelated worktree는 건드리지 않는다.
- merge는 다섯 PR exact-head 재검증과 사용자의 마지막 명시적 승인 전에는 수행하지 않는다.

## 체크리스트

- [x] GNO/live/upstream/current-code 조사
- [x] baseline module test
- [x] 설계와 구현 계획 작성
- [x] 독립 설계·계획 리뷰 P0/P1 0건
- [x] failing regression 증거
- [x] production 구현
- [x] 문서·manifest·workflow guard
- [x] README/lesson의 #290 후속 추적과 first-empty-poll 회귀
- [x] clean tests와 정적/계약 검증
- [x] 독립 구현 리뷰 P0/P1 0건
- [ ] PR exact-head hosted CI와 metadata 확인
