# Issue #892 JaVers history limit pushdown 구현 리뷰

## 범위

- `exposed/javers-persistence-audit`의 Redis bounded history adapter와 서비스 계약
- `exposed/javers-approval-workflow`의 bounded newest-first history 계약
- dependencies BOM `2.0.0` 해석, 테스트, README, workflow, ecosystem manifest

## 독립 리뷰 결과

- P0: 없음
- P1: 없음
- P2: 최초 리뷰에서 `QueryParams` fallback 변형별 테스트 보강을 권고했다.
- P3: 없음
- 최종 verdict: **PASS**

독립 리뷰는 exact-instance fast path가 aggregate, skip, snapshot query limit, commit/date/version/author/property/type
조건을 명시적으로 거부하고 기존 repository로 fallback하는지 확인했다. 또한 adapter와 delegate가 동일한
`JaversCodec`, Redis key, Redisson codec, `JsonConverter`를 공유하며,
`range(-limit, -1).asReversed()`가 empty/short history와 concurrent append에서도 bounded newest-first 계약을
유지함을 확인했다.

## P2 반영

fallback table에 `authorLikeIgnoreCase`, `toDate`, `fromInstant`, `toInstant`, `fromVersion`, `toVersion`,
`toCommitId`, `commitPropertiesLike` 변형을 추가했다. `snapshotQueryLimit`은 JaVers가 snapshot query 자체에서
허용하지 않으므로 integration query로 만들지 않고 production predicate가 해당 값 존재 시 fast path를
거부하도록 유지했다.

## 검증 증거

- 변경 모듈 테스트: 20 tests, failures 0, errors 0, skipped 0
- Redis counting codec: `limit=1/2`에서 decode 1/2
- concurrent append/read: 반환 크기 2 이하, newest-first, 최종 version `30, 29`
- unsupported query fallback table: 기존 JaVers 의미와 결과 limit 유지
- 루트 `detekt`: PASS
- README language/parity, `actionlint`, `git diff --check`: PASS
- ecosystem checker: 113 tests PASS
- assertion governance: 4 tests와 1,190개 테스트 소스 scan PASS
- dependency insight: `bluetape4k-dependencies:2.0.0`, `javers-core:1.0.0`,
  `javers-persistence-redis:1.0.0`, `bluetape4k-redisson:2.0.0`

## 남은 게이트

- stale-check와 trusted manifest checker
- PR exact-head hosted CI와 metadata 검증
- 다섯 PR 병합 직전 사용자의 단일 명시 승인
