# Issue #892 JaVers history limit pushdown 구현 계획

## 목표

두 기존 JaVers workshop 서비스가 2.0.0 bounded history query 계약을 직접 소비하게 하고,
query pushdown·newest-first ordering·validation·JVM 호환성을 테스트와 문서로 고정한다.

## 순서

1. GNO 세 collection, live Issue #892, upstream Issue #309/PR #320과 현재 code를 대조한다.
2. 설계와 계획을 작성하고 performance/stability 및 security/operator 관점의 독립 리뷰를 받는다.
3. baseline 두 module test를 실행한다.
4. limit=1/2, Redis decode bound, empty/short history, invalid bounds, unknown id, JVM overload와
   unsupported query fallback 회귀 테스트를 먼저 추가해 기존 구현에서 실패함을 확인한다.
5. persistence module에 exact-instance query만 Redis range로 읽는 bounded repository adapter를
   추가한다. fast path는 모든 filter/aggregate/skip 미설정 allowlist와
   `range(-limit, -1)` inclusive 경계를 사용한다. 두 `getHistory`에는 `1..100` 검증,
   `QueryBuilder.limit`, newest-first 반환,
   `@JvmOverloads`를 적용한다.
6. 두 module README pair, root README pair, coverage matrix, lesson/index, ecosystem manifest,
   stale-check를 갱신한다.
7. clean module tests, root detekt, README language/parity, stale/ecosystem/actionlint,
   manifest/diff/dependency checks를 수행한다.
8. 독립 implementation review에서 P0/P1을 0으로 수렴시킨다.
9. Lore commit, push, Korean PR, milestone/assignee를 설정하고 exact-head hosted CI를 통과시킨다.
10. workflow check-result를 기록하고 다섯 PR 최종 merge gate로 이동한다.

## 중단 조건

- dependencies 2.0.0에서 Redis range adapter가 실제 decode 경계를 제한하지 못하면 구현을
  중단하고 upstream/consumer mismatch를 기록한다.
- 기존 branch/worktree/user 변경과 충돌하면 해당 변경을 보존하고 별도 worktree 범위만 유지한다.
- merge는 다섯 PR exact-head 재검증과 사용자의 마지막 명시적 승인 전에는 수행하지 않는다.

## 체크리스트

- [x] GNO/live/upstream/current-code 조사
- [x] 설계와 구현 계획 작성
- [x] 독립 설계 리뷰 P0/P1 0건
- [x] baseline 및 failing regression 증거
- [x] production 구현
- [x] 문서·manifest·workflow guard
- [x] clean tests와 정적/계약 검증
- [x] 독립 구현 리뷰 P0/P1 0건
- [ ] PR exact-head hosted CI와 metadata 확인
