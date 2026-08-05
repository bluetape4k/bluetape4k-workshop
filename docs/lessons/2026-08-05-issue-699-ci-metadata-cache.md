# Issue #699 CI metadata cache와 voucher fixture 안정화

## Context

Issue #699의 compile-only CI는 `collectReachabilityMetadata`가 `schemas` 없는
GraalVM reachability metadata repository를 읽으면서 실패했고, 같은 시기의
Container Examples는 voucher compatibility fixture의 stale 상태에서 예기치 않은
HTTP `409`를 반환했다. Voucher contract의 runtime-relative window와 fixture reset은
이전 수정으로 이미 반영되어 있었지만, compile-only 경로는 metadata task를 계속
제외하고 있어 원래 acceptance를 충족하지 못했다.

## Decision

- GraalVM Native Build Tools를 `1.1.7`로 올려 metadata repository `1.0.8`을 사용한다.
- Gradle이 시작되기 전에 `schemas`가 없는 native-build-tools repository entry만
  제거한다. 정상 repository는 보존하고, 다음 metadata task가 fresh archive를
  재취득하도록 한다.
- `setup-gradle`이 이 plugin 전용 `native-build-tools` 경로를 저장하지 않도록
  compile-only CI/Nightly cache에서 제외한다.
- CI/Nightly compile-only `build`/`assemble`에서는 metadata task를 다시 실행한다.
  Test/Examples 전용 경로는 native-image 산출물을 소비하지 않으므로 기존의
  선택적 task 제외를 유지한다.
- Voucher production code는 바꾸지 않는다. compatibility test의 요청·fixture
  경계와 이전의 runtime-relative campaign window를 그대로 검증한다.

## Outcome

오염된 cache를 자동으로 정리하면서 compile-only 경로가 실제 reachability metadata
task를 검증한다. Voucher 문제는 기존 isolation/reset 수정과 함께 fresh PostgreSQL
환경에서 재검증되며, CI cache drift가 voucher assertion에 섞이지 않는다.

## Verification

- `:bucket4j-caffeine-web:collectReachabilityMetadata` — metadata `1.0.8`, PASS
- `./gradlew build -x detekt -x test -x integrationTest -x stressTest -x migrationCompatibilityTest --parallel --continue`
  — metadata task 포함, `707 actionable tasks`, PASS
- `:commerce-event-sourced-promotion-voucher-campaign:integrationTest` — 77 tests, PASS
- `:commerce-promotion-voucher-campaign:test` — 188 tests, PASS
- `node --test scripts/graalvm-metadata-cache.test.mjs scripts/high-contention/*.test.mjs`
  — 39 tests, PASS
- `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/Examples.yml` — PASS
- `bash -n scripts/repair-graalvm-metadata-cache.sh`, `git diff --check` — PASS
- Local Docker-backed validation completed; fresh GitHub Actions run and retry remain
  post-PR evidence.
- `env -u TESTCONTAINERS_RYUK_DISABLED ./scripts/smoke-validate.sh high-contention-contract`
  — 31 Node contract tests, build-logic, operations, and concert-ticket module runs
  모두 local Colima context에서 PASS.

## Future Guidance

Reachability metadata는 optional compile/test bypass로 고정하지 말고, plugin이 요구하는
schema marker를 cache boundary에서 검증한다. Native-image plugin을 올릴 때는 default
metadata repository version, cold-cache task, 오염 cache 복구 test, compile-only task
graph를 함께 확인한다. Voucher compatibility contract에는 고정된 과거 campaign
window나 공유 idempotency key를 추가하지 않는다.

## Local Colima contract boundary

Colima는 `unix:///Users/debop/.colima/default/docker.sock`처럼 macOS 호스트 경로를
원격 Docker engine에 전달한다. Testcontainers Ryuk가 이 경로를 container bind mount로
만들려 하면 `operation not supported`가 발생하고, 계약 테스트가 의도한 cutpoint 예외 대신
`ContainerLaunchException`을 받는다.

로컬 `high-contention-contract`는 Docker context와 `DOCKER_HOST`를 덮어쓰지 않는다.
대신 각 container-backed Gradle module을 별도의 `--no-daemon` 실행으로 격리하고,
각 실행에만 `TESTCONTAINERS_RYUK_DISABLED=true`를 주입한다. 모듈을 한 Gradle 실행에
합치면 Testcontainers worker의 cross-module 상태가 재사용되어 Ryuk가 다시 시작될 수
있으므로, operations와 concert-ticket 실행을 합치지 않는다. Ryuk 자동 정리를 끈
대신 high-contention 테스트가 소유 label을 사용해 명시적으로 container/network를
정리하는 기존 계약을 유지한다.

앞으로 Colima에서 이 계약을 실행할 때 `/var/run/docker.sock`로 `DOCKER_HOST`를
강제하지 말고, smoke script의 scoped Ryuk/no-daemon 경계를 재사용한다. CI는 runner의
`/var/run/docker.sock`와 동일한 Ryuk 비활성화 정책을 계속 사용한다.
