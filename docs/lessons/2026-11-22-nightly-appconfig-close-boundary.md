# Nightly AppConfig in-flight 종료 경계

## Context

2026-11-21 scheduled Nightly run `44963126804`가 `aws/settings-boundary`의
`AppConfigDataSpringIntegrationTest.context close remains bounded while a runtime poll is in flight()`에서 실패했다. 기존 테스트는 지연된 fake poll 응답을 기다리지 않는 `context.close()`를 6초 이내라고 wall-clock으로 단정했지만, 호스티드 smoke 부하에서는 약 6.2초가 걸려 false failure가 되었다. 같은 테스트의 의도는 production lifecycle 구현을 바꾸는 것이 아니라, 종료가 지연된 poll을 기다리지 않는다는 소비자 경계를 고정하는 것이다.

## Decision or Finding

- production AWS lifecycle 코드는 변경하지 않는다.
- fake 응답 지연을 15초로 늘려 종료 대기가 회귀하면 새 경계 안에서 관찰되도록 한다.
- elapsed time은 `System.nanoTime()`으로 측정하고, lifecycle의 5초 종료 대기보다 여유 있는 8초 미만을 `shouldBeLessThan`으로 검증한다.
- 안정 릴리스 소비자는 `bluetape4k-dependencies:2.1.0`을 사용하고, historical snapshot 문서는 당시 재현 가능한 계약으로 보존한다.

## Outcome

소비자 테스트는 호스티드 실행기의 일시적인 wall-clock 지터를 실패로 오인하지 않으면서도, 지연된 15초 poll 응답을 기다리는 종료 회귀를 계속 잡는다. upstream lifecycle이나 AWS SDK timeout/retry 정책을 복제하거나 완화하지 않는다.

## Verification

- 대상 lifecycle 테스트: 1/1 PASS
- `:aws-settings-boundary:test`: 25/25 PASS
- `./scripts/smoke-validate.sh all-smoke`: `BUILD SUCCESSFUL in 8m 22s`
- `scripts/smoke-validate.sh stale-check`, active snapshot scan, `git diff --check`: PASS

## Future Guidance

호스티드 CI에서 lifecycle 시간 경계를 검증할 때는 `Instant.now()` 차이만으로 외부 지연을 단정하지 말고 monotonic clock을 사용한다. fake 응답 지연은 assertion bound보다 충분히 길게 두어 “실제로 기다리지 않음”과 “응답이 빨리 끝남”을 구분한다. production timeout을 넓혀 consumer test를 맞추지 말고, upstream lifecycle 소유권과 consumer 경계의 책임을 먼저 분리한다.
