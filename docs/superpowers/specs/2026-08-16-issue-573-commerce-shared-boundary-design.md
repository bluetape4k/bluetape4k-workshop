# Issue #573 Commerce shared 경계 설계

상태: 승인된 설계

## 문제와 목표

`:shared`는 여러 workshop 예제가 사용하는 HTTP client 확장과 테스트 인프라를
제공한다. 현재 `VoucherCampaignBlackBoxContract`와 전용 테스트가 같은 모듈에
있지만, 이 계약을 소비하는 예제는 commerce voucher campaign 두 개뿐이다.
범용 모듈에 commerce 전용 compatibility vocabulary를 두면 shared의 책임 범위와
실제 재사용 범위가 일치하지 않는다.

Issue #573의 목표는 voucher compatibility contract를 자동 등록되는
`:commerce-shared` 모듈로 이동하고, 두 consumer의 테스트가 명시적인 commerce
경계를 의존하도록 만드는 것이다. Voucher business behavior, persistence,
adapter, migration은 변경하지 않는다.

## 현재 근거

- `settings.gradle.kts`의 `includeModules("commerce", false, true)`가
  `commerce/*` 하위 디렉터리를 자동 등록한다.
- 계약 구현은
  `shared/src/main/kotlin/io/bluetape4k/workshop/shared/voucher/VoucherCampaignBlackBoxContract.kt`
  한 파일에 있고 전용 검증은 같은 패키지의
  `VoucherCampaignBlackBoxContractTest.kt`에 있다.
- 현재 consumer는
  `commerce/promotion-voucher-campaign`과
  `commerce/event-sourced-promotion-voucher-campaign`의 test source set이다.
- 두 consumer의 현재 `io.bluetape4k.workshop.shared.voucher` import는 계약
  타입뿐이며, 이 Issue 범위에서 `:shared` 의존성을 `:commerce-shared`로
  교체할 수 있다.
- 현재 `./gradlew :shared:test`는 기준 commit `f191a7c`에서 성공한다.
- 기존 lesson
  `docs/lessons/2026-07-24-issue-573-commerce-shared-boundary.md`는 같은
  패키지와 모듈 경계를 이미 결정했다.

## 선택한 접근

### 독립 `commerce/shared` 모듈

새 모듈은 다음 구조를 사용한다.

```text
commerce/shared/
  build.gradle.kts
  README.md
  README.ko.md
  src/main/kotlin/io/bluetape4k/workshop/commerce/shared/voucher/
    VoucherCampaignBlackBoxContract.kt
  src/test/kotlin/io/bluetape4k/workshop/commerce/shared/voucher/
    VoucherCampaignBlackBoxContractTest.kt
  src/test/resources/
    junit-platform.properties
    logback-test.xml
```

계약은 consumer test source set에서 사용할 수 있도록 `src/main`에 둔다.
모듈은 `bluetape4k-core`만 main dependency로 사용하고, 테스트에는 기존
프로젝트 관례에 맞춰 `bluetape4k-junit5`와 `bluetape4k-assertions`를 사용한다.
새로운 외부 dependency나 버전 pinning은 추가하지 않는다.

계약의 package는 다음으로 변경한다.

```kotlin
io.bluetape4k.workshop.commerce.shared.voucher
```

두 consumer는 `testImplementation(project(":commerce-shared"))`만 계약
의존성으로 선언하고, import를 새 package로 갱신한다. `:shared`의 voucher
source/test는 제거한다.

### 대안과 기각 사유

- 기존 production 모듈의 `java-test-fixtures`를 노출하는 방법은 fixture가
  특정 voucher production module에 결합되므로 두 consumer의 공통 경계를
  표현하지 못한다.
- `:shared`에 package만 추가하는 방법은 commerce 전용 contract가 범용
  module에 남아 Issue의 책임 경계 문제를 해결하지 못한다.

## 등록·문서·검증 경로

- `settings.gradle.kts`는 자동 discovery가 새 디렉터리를 등록하는지
  `./gradlew projects`로 확인한다. 수동 include는 추가하지 않는다.
- `commerce/README.md`와 `commerce/README.ko.md`에 `shared` 모듈의
  contract/fixture 역할을 추가하고 `:commerce-shared:test` 실행 예를
  기록한다.
- `shared/README.md`와 `shared/README.ko.md`는 HTTP/Redis utility만
  설명하도록 유지하고 voucher contract를 언급하지 않는지 확인한다.
- `.github/workflows/Examples.yml`의 container-free smoke Gradle 목록과
  test-result artifact 목록에 `:commerce-shared:test`를 추가한다. 이미
  `commerce/**`, `shared/**`, `settings.gradle.kts` path filter가 새 모듈을
  포함하므로 path filter 자체는 변경하지 않는다.
- `scripts/smoke-validate.sh commerce`에 `:commerce-shared:test`를 추가한다.
  새 모듈은 Testcontainers를 사용하지 않으므로 container full lane에는
  추가하지 않는다.
- 새 모듈의 test resources는 기존 `shared` test resources와 동일한
  JUnit/logback 계약을 유지한다.

## 호환성과 경계

- 계약 타입의 validation, scenario 데이터, normalized result vocabulary,
  예외 타입은 이동 전과 동일하게 보존한다.
- 두 consumer의 production source, HTTP adapter, database schema, event
  persistence, migration은 변경하지 않는다.
- `:shared`는 cross-domain utility/test infrastructure만 남긴다.
- `:commerce-shared`는 commerce 예제 사이에서 실제로 소비되는 contract와
  fixture만 제공하며 production service나 persistence를 소유하지 않는다.

## 실패 모드와 대응

1. **자동 module discovery 누락:** `commerce/shared` 디렉터리나
   `build.gradle.kts`가 없으면 `./gradlew projects`에서
   `commerce-shared`가 보이지 않는다. 모듈 graph 확인을 consumer compile보다
   먼저 실행하고, 누락 시 workflow를 진행하지 않는다.
2. **consumer가 이전 package를 계속 참조:** old package 검색과 두
   compatibility test compile을 함께 실행해 stale import를 차단한다.
3. **`:shared`에 contract 잔여:** `rg`로 old package/source와 old
   `VoucherCampaignBlackBoxContract` 경로를 검사하고, 발견하면 경계 DoD를
   닫지 않는다.
4. **검증 경로 누락:** smoke script와 Examples smoke 목록에 새 module이
   빠지면 로컬 targeted test만 통과하고 CI에서 실행되지 않는다. workflow
   YAML과 artifact path를 함께 검토하고 `actionlint` 또는 동등한 YAML
   구조 검사를 실행한다.
5. **불필요한 dependency 확장:** 새 모듈이 `:shared` 또는 production
   voucher module을 다시 의존하면 경계가 순환하거나 넓어진다. Gradle
   dependency 선언과 `./gradlew projects` 결과를 검토해 core/test library
   외 의존성이 없는지 확인한다.

## 검증 전략

1. 새 contract test를 먼저 추가·이동하고, old source 상태에서 새 package
   import가 실패하는 RED 결과를 확인한다.
2. contract source와 test를 새 module로 이동한 뒤
   `./gradlew :commerce-shared:test`를 실행한다.
3. 두 consumer의 targeted test/compile을 순차 실행해 실제 package와
   scenario compatibility를 확인한다.
4. `./gradlew projects`, `./gradlew :commerce-shared:build`, 두 consumer
   test, `./scripts/smoke-validate.sh stale-check`,
   `./scripts/smoke-validate.sh commerce`, `git diff --check`를 실행한다.
   Testcontainers 기반 consumer test는 Docker/Colima 상태를 확인한 뒤
   순차 실행하며, 인프라 unavailable이면 raw evidence와 함께 별도 상태로
   보고한다.

## 완료 기준과 범위 제외

### Acceptance criteria

- `:commerce-shared`가 Gradle project graph에 나타나고 contract test가
  통과한다.
- `:shared`에는 Voucher contract source/test와 old package가 남지 않는다.
- 두 voucher consumer가 새 module/package를 사용하고 compatibility test가
  기존 normalized scenario 결과를 유지한다.
- commerce/shared README locale, root commerce module map, smoke script,
  Examples smoke/artifact 경로가 실제 module 이름과 일치한다.
- production service, persistence, adapter, migration, dependency version은
  변경하지 않는다.

### DoD

- 영향받은 Kotlin/Gradle surface의 KT-01~KT-10 검증이 완료되고 P0/P1이
  없다.
- module registration hazard와 README locale parity가 PASS다.
- `git diff --check`와 targeted/full affected validation이 PASS다.
- Type A lesson을 새 evidence로 갱신해 PR 전에 커밋한다.
- PR metadata는 Issue의 milestone `1.4.0`, labels
  `refactoring`/`difficulty:intermediate`, assignee `debop`을 미러링하고,
  body 마지막 section은 `## DoD Status`다.

### 제외

- AWS/외부 서비스 연동
- voucher business behavior 및 event-sourced persistence 변경
- Issue #568 assertion migration
- Issue #568 관련 fixture/계약 내용 재설계
- 새 diagram 또는 이미지 asset
