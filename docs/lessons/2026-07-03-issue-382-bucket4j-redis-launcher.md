# Issue 382 Bucket4j Redis Launcher Alignment

## 배경

`bucker4j-bluetape4k-webflux` example은 runtime bootstrap code에서 직접
`RedisServer(useDefaultPort = true)`를 구성해 Redis를 시작했다. 이는 bluetape4k
Testcontainers launcher singleton pattern을 우회하고 local startup을 고정 Redis port에
결합했다.

## 결정

- 나머지 workshop Redis-backed example과 맞추기 위해 `dev`와 `test` profile에서
  `RedisServer.Launcher.redis`를 사용한다.
- module bootstrap에서 manual `start()`와 `ShutdownQueue` wiring을 제거한다.
- 나머지 Lettuce configuration이 바뀌지 않도록 Spring property wiring은
  `testcontainers.redis.*`를 통해 유지한다.
- 실제 launcher behavior와 real Gradle project path를 README와 README.ko에 반영한다.
- 추출된 rate-limit key 대신 `$this`를 출력하던 인접 trace logging을 수정한다.

## 결과

module은 이제 Redis에 bluetape4k ecosystem launcher를 사용하고, fixed default-port coupling을
피하며, learner-facing smoke command를 등록된 Gradle project name과 맞춘다.

## 검증

- Baseline build before issue work: `/tmp/issue382-baseline-build.log` — `BUILD SUCCESSFUL in 1m 43s`.
- `:bucker4j-bluetape4k-webflux:compileKotlin`
- `:bucker4j-bluetape4k-webflux:compileTestKotlin`
- `:bucker4j-bluetape4k-webflux:test` with 6 executed tests and 2 existing skipped tests (`/tmp/issue382-targeted-test.log`)
- README parity and README language validators (`/tmp/issue382-readme-parity.log`, `/tmp/issue382-readme-language.log`)
- Full build after work: `/tmp/issue382-full-build.log` — `BUILD SUCCESSFUL in 1m 35s`.
- `git diff --check` (`/tmp/issue382-diff-check.log`)

## 향후 참고

directory name이 Gradle project name과 달라 README command가 실패하면 `./gradlew projects`로
검증하고 같은 PR에서 문서를 갱신한다. bluetape4k가 이미 감싼 Testcontainers infrastructure는
test가 명시적으로 isolated failure container를 필요로 하지 않는 한 `XxxServer.Launcher`를
사용한다.
