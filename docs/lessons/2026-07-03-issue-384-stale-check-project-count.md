# Issue 384 Stale Check Project Count

## 배경

`./scripts/smoke-validate.sh stale-check`에는 hard-coded Gradle project count baseline이
있었다. issue snapshot은 repository가 활성 project 106개로 drift되었다고 말했지만, 현재
`develop`은 활성 project 100개를 보고한다.

## 결정

현재 Gradle project graph를 default baseline으로 사용하고, fixed baseline comparison은 명시적
opt-in으로 유지한다.

```bash
EXPECTED_GRADLE_PROJECTS=100 ./scripts/smoke-validate.sh stale-check
```

이렇게 하면 의도적 module addition 이후 false warning을 피하면서도 strict count drift check를
위한 manual/CI hook을 보존할 수 있다.

## 검증

- edit 전 baseline full local build가 통과했다.
- default stale-check는 `Active modules: 100 (expected: current Gradle project graph)`로 통과했다.
- explicit baseline stale-check는 `Active modules: 100 (expected: 100)`로 통과했다.
- stale README ref 또는 broken image link는 보고되지 않았다.
- post-work full build는 `BUILD SUCCESSFUL in 2m 22s`로 통과했다.
