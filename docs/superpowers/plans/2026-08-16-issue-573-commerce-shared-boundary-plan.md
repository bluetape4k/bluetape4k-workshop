# Issue #573 Commerce shared boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `VoucherCampaignBlackBoxContract`를 독립 `:commerce-shared` 모듈로 이동하고 두 voucher consumer test source set이 명시적인 commerce 경계를 사용하도록 만든다.

**Architecture:** `commerce/shared`는 `bluetape4k-core`만 main dependency로 사용하는 contract/fixture 모듈이다. contract는 consumer 테스트가 호출할 수 있도록 `src/main`에 두고, contract 자체의 validation 회귀 테스트는 `src/test`에 둔다. `:shared`는 cross-domain HTTP/Redis utility만 유지한다.

**Tech Stack:** Kotlin 2.4, Java 25, Gradle auto-module discovery, JUnit 5, `bluetape4k-assertions`, `bluetape4k-core`, existing `bluetape4k-dependencies` BOM.

---

## 작업 전제와 파일 책임

| 책임 | 파일 | 변경 방식 |
|---|---|---|
| 새 module graph와 artifact | `commerce/shared/build.gradle.kts` | 생성 |
| contract production source | `commerce/shared/src/main/kotlin/io/bluetape4k/workshop/commerce/shared/voucher/VoucherCampaignBlackBoxContract.kt` | 이동·package 변경 |
| contract validation 회귀 | `commerce/shared/src/test/kotlin/io/bluetape4k/workshop/commerce/shared/voucher/VoucherCampaignBlackBoxContractTest.kt` | 이동·package 변경 |
| module test runtime | `commerce/shared/src/test/resources/junit-platform.properties`, `logback-test.xml` | 기존 `shared` 파일 복사 |
| consumer dependency/import | 두 voucher consumer `build.gradle.kts`와 compatibility test 3개 | `:commerce-shared` 및 package로 갱신 |
| module reader docs | `commerce/shared/README.md`, `README.ko.md`, `commerce/README.md`, `commerce/README.ko.md` | 생성·locale parity 갱신 |
| CI/smoke/stale 등록 | `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh` | container-free test 및 registration guard 추가 |

`settings.gradle.kts`는 이미 `includeModules("commerce", false, true)`를 사용하므로 수동 `include`를 추가하지 않는다. `shared/README.md`와 `shared/README.ko.md`에는 현재 voucher contract 설명이 없으므로 문서 삭제는 하지 않고 old package 검색으로 N/A를 증명한다.

## 위험 예측과 대응

| 위험 | 신호 | 대응·재실행 지점 |
|---|---|---|
| Gradle auto-registration 누락 | `./gradlew projects`에 `commerce-shared` 없음 | `build.gradle.kts`/경로를 보정한 뒤 projects부터 재실행 |
| contract source/test package 불일치 | `:commerce-shared:test` compile unresolved reference | source와 test package를 같은 exact prefix로 맞추고 red/green을 다시 실행 |
| consumer가 `:shared`의 다른 API를 암묵적으로 사용 | old dependency 제거 후 compile failure가 voucher 외 symbol에서 발생 | 실제 import를 분리해 필요한 cross-domain utility만 원래 dependency로 복구하고 범위를 보고 |
| CI에서 새 contract test 미실행 | workflow smoke 목록·artifact 경로에 module 없음 | YAML 목록과 artifact path를 함께 갱신하고 `actionlint`/구조 검색 실행 |
| historical lesson 의미 변경 | 기존 lesson의 날짜/결정 문장이 구현 상태와 혼동됨 | 기존 lesson은 당시 결정 기록으로 보존하고 새 outcome lesson을 별도 생성 |

## Task 1: 새 module과 RED contract test 준비

**Files:**

- Create: `commerce/shared/build.gradle.kts`
- Create: `commerce/shared/README.md`
- Create: `commerce/shared/README.ko.md`
- Create: `commerce/shared/src/test/resources/junit-platform.properties`
- Create: `commerce/shared/src/test/resources/logback-test.xml`
- Move and modify: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/voucher/VoucherCampaignBlackBoxContractTest.kt` → `commerce/shared/src/test/kotlin/io/bluetape4k/workshop/commerce/shared/voucher/VoucherCampaignBlackBoxContractTest.kt`

- [ ] **Step 1: Create only the module configuration and reader docs**

`commerce/shared/build.gradle.kts`는 다음 의존성만 선언한다.

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.core)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.logback.lib)
}
```

README는 module이 commerce voucher compatibility contract와 fixture만 제공하며, production service/persistence를 포함하지 않는다고 설명한다. English와 Korean 문서는 같은 heading·module name·Gradle command를 유지한다.

- [ ] **Step 2: Move the test before moving production source**

```bash
mkdir -p commerce/shared/src/test/kotlin/io/bluetape4k/workshop/commerce/shared/voucher
git mv \
  shared/src/test/kotlin/io/bluetape4k/workshop/shared/voucher/VoucherCampaignBlackBoxContractTest.kt \
  commerce/shared/src/test/kotlin/io/bluetape4k/workshop/commerce/shared/voucher/VoucherCampaignBlackBoxContractTest.kt
```

`package`를 `io.bluetape4k.workshop.commerce.shared.voucher`로 바꾸고, 기존 `shared/src/test/resources/junit-platform.properties`와 `logback-test.xml`을 새 module test resources로 복사한다. 이 단계에서는 production contract source를 아직 이동하지 않는다.

- [ ] **Step 3: Verify RED**

Run:

```bash
./gradlew :commerce-shared:test --rerun-tasks --console=plain
```

Expected: `VoucherCampaignBlackBoxContractTest.kt`가 새 package의 `VoucherCampaign*` 타입을 찾지 못해 Kotlin compilation failure가 발생한다. 테스트가 source 부재가 아닌 다른 설정 오류로 실패하면 원인을 먼저 수정하고 RED를 다시 확인한다.

## Task 2: Contract source 이동과 GREEN proof

**Files:**

- Move and modify: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/voucher/VoucherCampaignBlackBoxContract.kt` → `commerce/shared/src/main/kotlin/io/bluetape4k/workshop/commerce/shared/voucher/VoucherCampaignBlackBoxContract.kt`
- Delete by move: old `shared/src/main/kotlin/io/bluetape4k/workshop/shared/voucher/` directory when empty

- [ ] **Step 1: Move source and update its package**

```bash
mkdir -p commerce/shared/src/main/kotlin/io/bluetape4k/workshop/commerce/shared/voucher
git mv \
  shared/src/main/kotlin/io/bluetape4k/workshop/shared/voucher/VoucherCampaignBlackBoxContract.kt \
  commerce/shared/src/main/kotlin/io/bluetape4k/workshop/commerce/shared/voucher/VoucherCampaignBlackBoxContract.kt
```

Production source와 test source의 package 선언을 모두 다음으로 맞춘다.

```kotlin
package io.bluetape4k.workshop.commerce.shared.voucher
```

계약 클래스의 validation 호출, scenario 상수, normalized result 필드, 예외 타입은 변경하지 않는다.

- [ ] **Step 2: Verify GREEN and old shared cleanup**

Run sequentially:

```bash
./gradlew :commerce-shared:test --rerun-tasks --console=plain
./gradlew :shared:test --rerun-tasks --console=plain
```

Expected: 새 module contract test가 PASS하고 `:shared:test`도 PASS한다. 다음 검색은 old source/package를 반환하지 않아야 한다.

```bash
! rg -n "io\.bluetape4k\.workshop\.shared\.voucher|shared/src/(main|test).*/voucher" shared commerce/shared
```

## Task 3: 두 consumer를 새 contract module로 전환

**Files:**

- Modify: `commerce/promotion-voucher-campaign/build.gradle.kts`
- Modify: `commerce/event-sourced-promotion-voucher-campaign/build.gradle.kts`
- Modify: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/VoucherCampaignBlackBoxCompatibilityIntegrationTest.kt`
- Modify: `commerce/event-sourced-promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web/EventSourcedVoucherCompatibilityIntegrationTest.kt`
- Modify: `commerce/event-sourced-promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web/OperatorContractAccess.kt`

- [ ] **Step 1: Replace the contract dependency**

두 consumer `dependencies` block의 다음 선언을 제거한다.

```kotlin
testImplementation(project(":shared"))
```

같은 위치에 다음을 추가한다.

```kotlin
testImplementation(project(":commerce-shared"))
```

현재 세 compatibility test 파일의 `io.bluetape4k.workshop.shared.voucher.*` import를 `io.bluetape4k.workshop.commerce.shared.voucher.*`로 바꾼다. production source와 다른 `shared` utility import가 새로 발견되면 contract 이동과 분리해 원래 dependency 필요성을 검토한다.

- [ ] **Step 2: Verify consumer compile before container tests**

```bash
./gradlew \
  :commerce-promotion-voucher-campaign:compileTestKotlin \
  :commerce-event-sourced-promotion-voucher-campaign:compileTestKotlin \
  --rerun-tasks --console=plain --max-workers=1
```

Expected: 두 test source set이 새 package와 `:commerce-shared` artifact를 해석하고 compile한다.

- [ ] **Step 3: Run the two compatibility tests sequentially**

```bash
./gradlew :commerce-promotion-voucher-campaign:test --tests '*VoucherCampaignBlackBoxCompatibilityIntegrationTest' --console=plain --max-workers=1
./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --tests '*EventSourcedVoucherCompatibilityIntegrationTest' --console=plain --max-workers=1
```

두 테스트는 기존 normalized scenario 및 replay 결과를 유지해야 한다. Docker/Colima를 사용하는 두 번째 명령은 container preflight를 통과한 뒤 실행하며, unavailable이면 raw failure와 함께 해당 검증을 PENDING으로 남긴다.

## Task 4: Module 문서와 repository validation chain 갱신

**Files:**

- Modify: `commerce/README.md`
- Modify: `commerce/README.ko.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`

- [ ] **Step 1: Add the module to both commerce README locales**

Modules table에 다음 의미의 row를 English/Korean으로 추가한다.

```markdown
| [`shared`](shared/) | Voucher campaign compatibility contract and cross-example fixtures | No external infrastructure |
```

Run section에 다음 명령을 locale 양쪽에 추가한다.

```bash
./gradlew :commerce-shared:test --max-workers=1
```

새 module README도 같은 package, module scope, command를 설명하고 두 locale의 link/heading parity를 유지한다.

- [ ] **Step 2: Register the container-free smoke contract**

`.github/workflows/Examples.yml`의 smoke `Run H2/default examples` 목록에
`:commerce-shared:test`를 추가하고 smoke artifact 목록에 다음 경로를 추가한다.

```text
commerce/shared/build/test-results/test/*.xml
commerce/shared/build/reports/tests/test/
```

기존 `commerce/**`, `shared/**`, `settings.gradle.kts` path filter는 새 module을 이미 포함하므로 변경하지 않는다. Container examples 목록에는 추가하지 않는다.

- [ ] **Step 3: Update local commerce smoke and stale registration guard**

`scripts/smoke-validate.sh`의 `commerce)` Gradle 목록에 `:commerce-shared:test`를 추가하고, `stale-check)`의 required module loop를 다음처럼 확장한다.

```bash
for module in image-processing/barcode-api commerce/shared; do
```

이 변경은 새 module의 `build.gradle.kts`, `README.md`, `README.ko.md` 존재를 로컬 guard로 고정한다.

- [ ] **Step 4: Verify registration and documentation links**

```bash
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
```

Expected: project graph에 `:commerce-shared`가 표시되고 stale/module/readme checks가 PASS한다. 새 module README에 diagram을 추가하지 않았으므로 diagram QA는 N/A이며 broken image link 검사에는 새 local image path가 없다.

## Task 5: Full affected validation and final scope checks

**Files:** current branch diff only; no new production files beyond the approved module/contract/docs/validation paths.

- [ ] **Step 1: Run the new module and consumer validation in dependency order**

```bash
./gradlew :commerce-shared:build --rerun-tasks --console=plain --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:test --console=plain --max-workers=1
./gradlew :commerce-event-sourced-promotion-voucher-campaign:test --console=plain --max-workers=1
./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --console=plain --max-workers=1
```

The two consumer test tasks remain sequential because the repository serializes Testcontainers-backed tests. Retry-only success is not accepted without inspecting any preceding failure.

- [ ] **Step 2: Run smoke and static checks**

```bash
./scripts/smoke-validate.sh commerce
./gradlew detekt --console=plain --max-workers=1
git diff --check
```

If the smoke command fails because of Docker/Colima, record `colima status`, `docker context show`, and `docker info` evidence before classifying the infrastructure gap; do not treat a skipped container test as PASS.

- [ ] **Step 3: Verify exact scope and package/dependency ownership**

```bash
rg -n "shared\.voucher|project\(\":shared\"\)|commerce-shared|VoucherCampaignBlackBoxContract" \
  shared commerce/shared commerce/promotion-voucher-campaign commerce/event-sourced-promotion-voucher-campaign \
  commerce/README.md commerce/README.ko.md .github/workflows/Examples.yml scripts/smoke-validate.sh
git diff --name-only origin/develop...HEAD
git diff --stat origin/develop...HEAD
```

Expected: old package/contract paths occur only in historical lesson text, new module owns the contract, consumer Gradle files use `:commerce-shared`, and no production voucher/persistence/migration files are changed.

## Task 6: Final review, durable lesson, and PR handoff

**Files:**

- Create: `docs/lessons/2026-08-16-issue-573-commerce-shared-boundary.md`
- Modify: `docs/lessons/README.md`
- Optional tracked review evidence: `docs/review/2026-08-16-issue-573-commerce-shared-boundary.md` only if final review needs persistent findings

- [ ] **Step 1: Run Kotlin final checklist and review**

Inspect the final diff against `bluetape-kotlin-patterns/references/checklist.md`:

- KT-FIN-01: source, callers, tests, and both README locales are read back.
- KT-FIN-02/03/05: contract validation and Kotlin safety search are clean; Exposed/production data code is N/A because no such file is touched.
- KT-FIN-06/08/09: module, test, README, workflow, and diagnostics checks are complete.
- KT-FIN-10/11: fresh affected validation, diff check, exact scope, and P0/P1=0 are recorded.

The integrated review must record performance, stability, security, operator, developer/API, and user/caller lenses. No lane may mutate files or create GitHub side effects.

- [ ] **Step 2: Record the implementation lesson**

Create a Korean lesson with Context, Decision or Finding, Outcome, Verification, and Future Guidance. It must state that auto-registration required validation-chain updates, that the old historical lesson was preserved, and that the contract is intentionally test-only at consumer boundaries. Add the new lesson to `docs/lessons/README.md` in date order.

- [ ] **Step 3: Commit the lesson and implementation with Lore trailers**

Before PR creation, verify staged paths contain only Issue #573 files and commit with Korean intent plus `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, and `Not-tested` trailers. The PR body must end with the canonical `## DoD Status` table and reconcile `Required checks: X/Y; N/A: N; Blocked: 0`.

- [ ] **Step 4: Stop at merge-ready boundary**

Create/update the PR only after exact head, issue metadata, branch status, and final review are fresh. Set PR assignee `debop`, milestone `1.4.0`, and labels `refactoring`/`difficulty:intermediate`. Mirror the issue metadata and re-read live PR body, checks, reviews, threads, and mergeability. Stop at CG-16 until the user gives fresh approval for this exact PR head; do not auto-merge.

## Traceability and N/A evidence

| Approved criterion | Plan proof |
|---|---|
| `:commerce-shared` exists and tests pass | Tasks 1, 2, 4, 5 |
| `:shared` has no voucher contract | Task 2 Step 2 and Task 5 Step 3 |
| Both consumers preserve compatibility behavior | Task 3 Steps 2–3 and Task 5 Step 1 |
| Module/README/workflow/smoke/stale registration is complete | Task 4 and Task 5 Step 2 |
| No production/persistence/migration change | Task 5 Step 3 scope diff |
| Durable lesson and PR DoD metadata | Task 6 Steps 2–4 |

N/A is concrete for this scope:

- No external API or dependency version research: the contract uses existing
  `bluetape4k-core`/test aliases and no new version is introduced.
- No diagram or image asset: the module is a contract library and no visual
  content is added; README text/link checks still run.
- No performance/stability benchmark: no runtime path, algorithm, network,
  database, or concurrency behavior changes; affected test execution remains
  sequential and is covered by existing consumer suites.
- No migration/release/publication: the new module is repository-internal and
  no database schema, Maven publication, tag, or release action is authorized.
