# Issue #573 Commerce shared 경계 구현 교훈

## Context

Voucher campaign 호환성 계약은 정규 상태 기반 구현과 event-sourced 구현만 소비하는
commerce 도메인 fixture였다. 기존 `shared` 모듈에 계약과 테스트가 있었기 때문에 범용
공용 유틸리티와 domain-specific 계약의 경계가 package와 Gradle 의존성에 드러나지 않았다.

## Decision or Finding

- `commerce/shared`를 자동 등록되는 `:commerce-shared` 모듈로 두고 계약과 전용 테스트를
  `io.bluetape4k.workshop.commerce.shared.voucher` package로 이동했다.
- 두 Voucher campaign 소비자는 `testImplementation(project(":commerce-shared"))`만 사용하며,
  production service·persistence·adapter·migration은 새 모듈에 넣지 않는다.
- `shared`의 기존 범용 fixture와 회귀 테스트는 유지한다. 날짜가 다른 기존 lesson은 당시
  조사 결과를 보존하는 역사 기록으로 남기고, 현재 상태는 이 lesson에서 갱신한다.
- 예상과 달리 `settings.gradle.kts` 자동 등록만으로는 변경이 끝나지 않았다. README 양쪽,
  H2/default smoke, artifact 수집, stale-check 필수 파일 목록까지 함께 연결해야 새 모듈이
  로컬과 CI에서 실제로 검증된다.
- 자동 모듈 등록만으로 끝내지 않고 commerce README 양쪽, H2/default smoke, artifact 수집,
  stale-check 필수 파일 목록까지 같은 변경에서 연결한다.

## Outcome

- [Issue #573](https://github.com/bluetape4k/bluetape4k-workshop/issues/573)의 계약 경계가
  디렉터리, package, Gradle project, 소비자 import에서 일관되게 보인다.
- 정규 상태 기반과 event-sourced 소비자의 계약 테스트가 동일한 `commerce-shared` API를
  통해 계속 실행된다.
- 새 모듈은 외부 인프라가 없어 H2/default smoke 경로에 포함되고 container smoke 목록에는
  중복 등록하지 않는다.

## Verification

- `./gradlew :commerce-shared:build --rerun-tasks --console=plain --max-workers=1`
- `./gradlew :commerce-promotion-voucher-campaign:test --console=plain --max-workers=1`
- `./gradlew :commerce-event-sourced-promotion-voucher-campaign:test --console=plain --max-workers=1`
- `./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --console=plain --max-workers=1`
- `./scripts/smoke-validate.sh commerce` (`BUILD SUCCESSFUL in 8m 42s`)
- `./gradlew detekt --console=plain --max-workers=1` (`BUILD SUCCESSFUL in 46s`)
- `./gradlew projects --console=plain`, stale-check, README language/parity, `actionlint`,
  `git diff --check`

## Future Guidance

새 shared 후보를 추가할 때는 먼저 소비 범위를 확인한다. 여러 독립 도메인에서 쓰는
도구성 capability는 root `shared`에 두고, 한 domain family의 contract·scenario·fixture는
해당 domain의 `shared` module로 제한한다. 새 module을 추가하면 다음 등록 체인을 함께
검증한다.

`settings.gradle.kts` 자동 등록 → README/README.ko 모듈 표와 실행 명령 → smoke 그룹과
artifact 경로 → stale-check 필수 파일 목록 → 소비자 compile 및 계약 테스트

`commerce-shared`는 범용 보관함이 아니므로, 실제 commerce 소비자가 생긴 계약·fixture만
추가하고 business behavior는 각 구현 모듈의 경계 안에 유지한다.
