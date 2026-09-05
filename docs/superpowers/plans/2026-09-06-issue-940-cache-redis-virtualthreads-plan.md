# Issue #940 cache-redis VirtualThreads executor 구현 계획

## 순서

1. GNO, live Issue #940, stable 2.0.0 provider와 현재 bean graph를 대조한다.
2. managed executor 종료, dependency graph와 destruction recorder, MDC 복원 회귀를 먼저 추가해 RED를 확인한다.
   latch로 blocked in-flight 작업을 만든 뒤 context close가 2초 안에 반환하고 작업을 interrupt하지 않으며,
   신규 submit은 거부되고 release 후 termination되는지 검증한다. 같은 worker fixture에서 success/error/null caller와
   pre-existing worker MDC를 모두 검증한다.
3. API/JDK25 의존성과 `VirtualThreads.executorService()` bean을 추가하고 기존
   `applicationTaskExecutor`와 Lettuce 공유 관계를 보존한다. provider-defined thread prefix를
   그대로 사용하고 bean name과 `runtimeName()`을 관측 계약으로 검증한다.
4. build에는 `implementation(libs.bluetape4k.virtualthread.api)`와
   `runtimeOnly(libs.bluetape4k.virtualthread.jdk25)`만 추가한다. EN/KO README, coverage matrix, lesson과
   issue-specific stale guard를 갱신하고, manifest에 issue 940, exact head/base, allowed paths와 implementation
   review artifact를 등록한다. 기존 Examples cache-redis path/task/report wiring은 중복 추가하지 않는다.
5. clean module tests, root detekt, stale, README/manifest/checker 검증과 독립 구현 리뷰를 통과한다.
   cache-redis는 Testcontainers 의존 module이므로 Docker-free `all-smoke`/`spring-boot` 그룹에 넣지 않고,
   기존 Examples container-backed lane과 직접 module test로 검증한다.
6. #923 head 위에 Lore commit과 Korean PR을 만들고 exact-head hosted CI를 확인한다.

## 중단 조건

- stable 2.0.0 artifact에서 `VirtualThreads` API/JDK25 provider가 resolve되지 않으면 snapshot으로 우회하지 않는다.
- context close 뒤 executor shutdown 또는 Lettuce dependency order를 증명하지 못하면 완료하지 않는다.
- root의 사용자 변경과 관련 없는 worktree는 건드리지 않는다.

## 검증 명령과 기대값

- `./gradlew :spring-boot-cache-redis:cleanTest :spring-boot-cache-redis:test --no-build-cache --no-daemon --max-workers=1`
- `./gradlew :spring-boot-cache-redis:dependencyInsight --dependency bluetape4k-virtualthread-api --configuration testRuntimeClasspath`
  및 JDK25 artifact가 모두 `2.0.0`으로 resolve되어야 한다.
- `./gradlew detekt`, `./scripts/smoke-validate.sh stale-check`
- 기존 `.github/workflows/Examples.yml` container-backed lane의 `:spring-boot-cache-redis:test`를 유지하고,
  Docker-free `all-smoke`에는 추가하지 않는다.
- README language/parity, ecosystem checker 113 tests, trusted exact PR scope, assertion governance,
  manifest JSON과 `git diff --check`

## 체크리스트

- [x] GNO/live/provider/current-code 조사
- [x] 설계와 계획 작성
- [x] 독립 계획 리뷰 P0/P1 0건
- [x] TDD RED와 production 구현
- [x] 문서·manifest·stale guard
- [x] clean tests와 정적/계약 검증
- [x] 독립 구현 리뷰 P0/P1 0건
- [ ] PR exact-head hosted CI와 metadata 확인
