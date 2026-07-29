# Kover Coverage Policy

## Current Status

`bluetape4k-workshop`은 워크숍/데모 저장소이므로 Kover coverage threshold를
강제하지 않습니다.

## Policy

상태: 문서화된 워크숍/데모 예외입니다.

이 저장소는 여러 framework와 runtime 조합에서 bluetape4k 통합 방식을 보여줍니다.
build와 test health가 주요 신호이며, coverage는 production release gate가 아닙니다.

## Threshold Plan

- 예제가 계속 compile되고 test가 통과하도록 유지합니다.
- 예제가 재사용 가능한 production template로 승격될 때만 coverage report를 정보 신호로
  사용합니다.

## CI/Nightly Contract

CI/Nightly는 build/test 신호를 실행합니다. coverage report를 추가하더라도 기본값으로
실패 threshold를 만들지 말고 정보 제공 용도로만 유지해야 합니다.
