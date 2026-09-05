# Issue #953 Okio BufferedSuspendedSink 공개 API 이전

## Context

`io/okio-examples`는 `bluetape4k-okio`와 같은 FQCN으로 `BufferedSuspendedSink`와 구현을 복제해 안정판
`2.0.0`의 bounded no-progress 개선을 가렸다. local 구현은 `SuspendedSource.read()`가 계속 `0`을 반환하면
`write`와 `writeAll`에서 끝나지 않았다.

## Decision or Finding

local buffered sink interface와 구현만 삭제하고 공개 `SuspendedSink.buffered()`를 사용한다. local source/sink
adapter 전체 호환을 주장하지 않고, 이번 호출 경로의 JVM method ABI만 compile과 통합 테스트로 확인한다.
안정판 `close()`는 underlying close 실패를 suppressed에 추가하지 않으므로 workshop은 최초 write 오류 보존과
underlying close 시도만 회귀로 고정한다.

## Outcome

모든 write overload의 exact payload, complete segment와 tail, `write`/`writeAll`의 연속 zero-byte read 8회 제한,
flush와 idempotent close를 provider 공개 API 경계에서 검증한다. consumer 예제는 내부 구현 class를 더 이상 직접
참조하지 않는다.

## Verification

먼저 local duplicate가 두 no-progress 테스트에서 가상 시간 timeout으로 실패하는 RED를 확인한다. 중복 제거 뒤
`okio-examples` 전체 1,051개 테스트로 unit, socket, file-channel 호환을 함께 검증하고 hosted Examples smoke에도
module 결과 artifact를 남긴다.

## Future Guidance

consumer workshop에서 provider와 같은 FQCN의 공개 abstraction을 복제하지 않는다. 안정판 API를 공개 factory로
사용하고, provider의 내부 class나 아직 제공하지 않는 오류 정책을 consumer 계약으로 주장하지 않는다.
