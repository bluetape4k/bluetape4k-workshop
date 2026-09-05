# Issue #894 Redis head metadata fail-closed 구현 리뷰

## 범위

- Redisson/Lettuce factory startup validation과 resource ordering
- malformed diagnostics의 raw identifier 비노출
- snapshot-only partial loss와 empty initial state 구분
- 기존 bounded history와 Kafka projection 회귀
- dependencies 2.0.0, workflow/stale/ecosystem 계약

## 구현 요약

- `requireConsistentOrderAuditHead`가 provider `getHeadId()`의 malformed 검증을 startup에서 실행한다.
- head가 없으면 documented snapshot-index key 존재를 O(1)로 확인하고 partial loss를 generic error로 거부한다.
- Kafka factory는 Redis validation이 끝난 뒤 producer/consumer를 만든다. 실패 시 owned Lettuce repository를 닫는다.
- 실행 중 query-time O(N) revalidation이나 자동 복구는 추가하지 않았다.

## 검증

- RED: malformed Redisson/Lettuce와 snapshot-only partial-loss 3개 회귀가 구현 전 실패
- GREEN: 신규 5개 targeted regression 통과
- clean module test: 22개 통과, `BUILD SUCCESSFUL`
- root `detekt`, `data-access-full`, `stale-check`, ecosystem checker 113개, assertion governance 1,197개,
  README language, actionlint, `git diff --check`: 통과
- README parity는 기존 optimization README 3쌍의 language-switch 누락으로 baseline과 동일하게 실패했으며
  이 Issue의 변경 파일에는 offender가 없다.
- dependency insight: `bluetape4k-dependencies:2.0.0`, `javers-persistence-redis:1.0.0`,
  `bluetape4k-redisson:2.0.0`, Redisson `4.6.1`, Lettuce `7.6.0.RELEASE`
- hosted CI는 PR 생성 후 exact-head에서 확인한다.

## Gate

- architecture/API/performance: P0=0/P1=0 PASS
- security/test/operator: P0=0/P1=0 PASS
- 전체: 구현 독립 리뷰 P0=0/P1=0. PR gate 진행 가능
