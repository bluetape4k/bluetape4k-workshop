# Issue #894 Redis head metadata fail-closed 구현 계획

## 목표

기존 JaVers persistence audit 조회 최적화와 Kafka Redis read facade에 provider head validation을 적용하고,
missing과 malformed metadata의 의미를 Testcontainers 회귀로 고정한다.

## 순서

1. GNO/live Issue와 upstream Issue #334/PR #345, dependencies 2.0.0 provider source를 대조한다.
2. 설계·계획을 architecture/API/performance/security/test/operator 관점에서 독립 검토해 P0/P1을 제거한다.
3. Redisson malformed commit-id, Lettuce malformed sequence, empty initial state와 snapshot-only partial loss 회귀를
   먼저 추가해 RED를 확인한다.
4. direct Redisson factory와 Kafka→Lettuce factory startup에 최소 head validation을 구현한다. head가 없을 때만
   documented provider key schema의 O(1) snapshot-index existence probe를 수행하고, public `byClass` materialization과
   query-time fresh repository/O(N) scan은 추가하지 않는다. Kafka factory는 read repository 생성→head/index 검증→
   producer/consumer 생성 순서를 지키며 검증 실패 시 read repository를 닫는다. invalid producer config보다 head
   오류가 먼저 반환되는 회귀로 순서를 고정한다.
5. module/root `README.md`와 `README.ko.md`, `docs/coverage-matrix.md`, `docs/ecosystem-reuse-train.json`,
   `docs/lessons/README.md`, 신규 lesson, `scripts/smoke-validate.sh`를 갱신한다. `.github/workflows/Examples.yml`의
   JaVers module path와 `data-access-full` membership은 no-op structural predicate로 검증한다.
6. clean module test, root `detekt`, `data-access-full`, `stale-check`, ecosystem checker, README/assertion guard,
   `actionlint`, `git diff --check`, dependency insight를 실행한다. stale guard는 `#894`, `2.0.0`, lesson/review,
   JaVers test membership과 `2.1.0(-SNAPSHOT)` 부재를 확인한다.
7. 독립 구현 리뷰 P0/P1 0건 후 Lore commit, push, Korean PR을 만들고 exact-head CI를 확인한다.
8. Issue #923 branch를 #894 head 위에 쌓으며 최종 승인 전에는 merge하지 않는다.

## 중단 조건

- stable 2.0.0 provider가 safe diagnostic을 제공하지 않으면 consumer에서 오류 문자열을 재작성하지 않고 mismatch를
  기록한다.
- factory rebuild가 malformed head와 head 없는 기존 snapshot을 실제로 거부하지 않으면 완료로 표시하지 않는다.
- user의 root dirty change와 unrelated worktree를 건드리지 않는다.

## 체크리스트

- [x] GNO/live/upstream/current-code 조사
- [x] baseline module test
- [x] 설계와 계획 작성
- [x] 독립 설계·계획 리뷰 P0/P1 0건
- [x] failing regression 증거
- [x] production 구현
- [x] 문서·manifest·workflow guard
- [x] clean tests와 정적/계약 검증
- [x] 독립 구현 리뷰 P0/P1 0건
- [ ] PR exact-head hosted CI와 metadata 확인
