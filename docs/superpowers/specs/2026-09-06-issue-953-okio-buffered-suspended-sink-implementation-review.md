# Issue #953 Okio BufferedSuspendedSink 구현 리뷰

## 범위

- local `BufferedSuspendedSink`와 `RealBufferedSuspendedSink` 중복 제거
- stable `bluetape4k-okio:2.0.0` 공개 `SuspendedSink.buffered()` 소비
- exact payload, complete segment/tail, no-progress, close 실패와 idempotence 회귀
- socket/file-channel JVM method ABI, README, Examples smoke/artifact, stale guard와 manifest

## TDD 근거

- RED: local duplicate에서 `write`와 `writeAll`이 각각 250ms 가상 시간 뒤
  `TimeoutCancellationException`으로 실패했다.
- GREEN: 중복 제거 뒤 `:okio-examples:test --rerun-tasks`가 1,051개 테스트 중 1,036개 성공,
  기존 15개 skip으로 `BUILD SUCCESSFUL`이었다.
- dependency insight: `io.github.bluetape4k:bluetape4k-okio:2.0.0`이 root BOM constraint로 선택됐다.

## 계약 판정

- provider 내부 구현 class를 직접 생성하지 않고 모든 consumer test가 공개 `.buffered()`를 사용한다.
- `write`와 `writeAll`은 각각 연속 zero-byte read 8회 뒤 stable 2.0.0의 정확한 `IOException` 메시지를 낸다.
- buffered tail write와 underlying close가 모두 실패하면 최초 write 오류를 유지하고 close는 한 번 시도한다.
- stable 2.0.0은 close 오류를 suppressed에 추가하지 않으므로 해당 동작은 주장하지 않는다.
- local `SuspendedSink`/`SuspendedSource` 전체 API 호환이 아니라 이번 호출 descriptor와 socket/file-channel
  실행 호환만 확인한다.

## 독립 리뷰

- architecture/API: P0 0건, P1 0건, P2 0건, PASS
- test/operations: 검증 artifact 갱신 뒤 P0 0건, P1 0건, local PASS. ByteArray 기본 인자와 오류 payload
  비노출 P2도 회귀로 추가했다. hosted CI는 PR 이후 별도 merge gate로 남는다.

## 검증

- clean module: 1,051 tests, 15 existing skipped, `BUILD SUCCESSFUL`
- focused public buffered sink: 7 tests, 0 failures/errors/skips
- dependency insight: `bluetape4k-okio:2.0.0` selected by BOM constraint
- serialization, all-smoke 371 tasks, detekt 112 tasks, stale-check: PASS
- ecosystem checker 113 tests, assertion governance 1,200 files: PASS
- `actionlint`, README language/parity, `git diff --check`: PASS
- hosted CI: PENDING

## 판정

- P0: 0건
- P1: 0건
- 결론: local implementation PASS, hosted CI PENDING

## 리뷰에서 보강한 사항

- `write(byteArray)` 기본 인자 bridge와 ranged ByteArray 호출을 모두 exact payload에 포함했다.
- close 오류 메시지에 `sensitive-tail-payload`가 노출되지 않음을 bluetape4k assertion으로 검증했다.
- stale guard가 두 test file 각각의 공개 `.buffered()` 사용과 payload 비노출 회귀를 확인하도록 강화했다.
