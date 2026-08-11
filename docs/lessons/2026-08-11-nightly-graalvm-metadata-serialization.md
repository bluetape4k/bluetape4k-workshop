# Nightly GraalVM reachability metadata 병렬 추출 경쟁

## Context

Nightly 전체 실행 `31426461382`는 `Build (compile only)`에서
`:bucket4j-caffeine-web:collectReachabilityMetadata`와
`:bucket4j-redis:collectReachabilityMetadata`가 같은
`native-build-tools/repositories/*/exploded` 경로를 사용하던 중
`schemas` 디렉터리가 없는 repository를 관측해 실패했다. 동일 SHA의
`31331456919`와 이전 SHA의 `31213392388`에서도 같은 오류가 반복됐고,
동일 SHA의 다른 실행은 통과해 간헐적 경쟁 조건임을 확인했다.

## Decision

- Nightly compile lane은 GraalVM metadata task를 계속 실행한다. `-x
  collectReachabilityMetadata`로 증상을 숨기지 않는다.
- 공유 metadata repository를 여러 Gradle 프로젝트가 동시에 추출하지 않도록
  `--no-parallel --max-workers=1`을 명시한다.
- cache repair 단계와 `schemas` 검증은 유지한다. 이는 기존 오염 cache를
  정리하는 경계이며, extraction 경쟁을 대체하지 않는다.
- Nightly 명령이 다시 병렬화되지 않도록 source-policy 회귀 테스트를 둔다.

## Verification

- 실패 run `31426461382`: 두 `collectReachabilityMetadata` task의 동일
  `schemas` 누락 오류와 `Nightly Status`의 파생 실패를 확인했다.
- 회귀 테스트 RED: 기존 `--parallel` 명령에서 직렬화 assertion 실패.
- 회귀 테스트 GREEN: `node --test scripts/high-contention/source-policy.test.mjs`.
- `node --test scripts/graalvm-metadata-cache.test.mjs scripts/high-contention/*.test.mjs` — 42 tests, PASS.
- 대상 task 직렬 실행: `:bucket4j-caffeine-web:collectReachabilityMetadata`
  및 `:bucket4j-redis:collectReachabilityMetadata`, metadata `1.0.8`, PASS.
- `actionlint .github/workflows/nightly.yml .github/workflows/ci.yml` — PASS.
- 전체 로컬 `assemble`은 이 worktree에서 소비자 snapshot 의존성
  `org.jetbrains.exposed:exposed-dao:.` 해석 불가로 중단됐다. 이는 이번
  workflow flag 수정과 무관한 로컬 dependency-resolution gap이다.

## Future Guidance

Native metadata task를 다시 compile/test lane에 포함할 때는 cache repair,
schema marker 검증, 단일 worker 실행, 그리고 workflow flag 회귀 테스트를
한 세트로 유지한다. CI와 Nightly의 build 명령을 다르게 두지 않는다.
