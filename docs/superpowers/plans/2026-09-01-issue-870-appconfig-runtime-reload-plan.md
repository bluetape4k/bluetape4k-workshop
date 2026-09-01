# Issue #870 AppConfig ConfigData·runtime reload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 `aws/settings-boundary` 소비자 예제를 Spring Boot 4의 upstream
`aws-app-config:` ConfigData와 선택적 runtime reload까지 확장하고, 기본 smoke는
credential-isolated로 유지한다.

**Architecture:** Spring Boot의 표준 ConfigData resolver/loader가 AppConfig Data
초기 payload를 `Environment`에 넣고, upstream context lifecycle이
`refresh-interval`이 명시된 source만 단일 scheduler로 갱신한다. 이 repository는
upstream internal 클래스를 복제하지 않고 versionless AWS SDK alias, bootstrap/runtime
timeout customizer, loopback `HttpServer` contract test와 bilingual README만 소유한다.

**Tech Stack:** Kotlin 2.4, Java 25, Spring Boot 4.1, AWS SDK v2
`appconfigdata`, bluetape4k `2.0.0-SNAPSHOT` dependencies BOM, JUnit 5,
Awaitility, MockK, JDK `HttpServer`, Gradle version catalog.

---

## 파일 구조와 책임

- Modify: `gradle/libs.versions.toml` — AWS SDK v2 `appconfigdata` versionless alias.
- Modify: `aws/settings-boundary/build.gradle.kts` — Kotlin Spring/Spring Boot
  plugins, Spring Boot starter와 bluetape4k AWS Spring starter.
- Create: `aws/settings-boundary/src/main/kotlin/io/bluetape4k/workshop/aws/settings/SettingsBoundarySpringApplication.kt`
  — Spring Boot 진입점과 ConfigData bootstrap timeout initializer.
- Create: `aws/settings-boundary/src/main/kotlin/io/bluetape4k/workshop/aws/settings/SettingsBoundaryAppConfigConfiguration.kt`
  — runtime `AwsSyncClientCustomizer`와 production API/attempt timeout(10초/5초).
- Create: `aws/settings-boundary/src/main/resources/application.yml` — 기본
  AppConfig disabled, no remote call/resource.
- Create: `aws/settings-boundary/src/main/resources/application-appconfig.yml`
  — explicit profile의 `aws-app-config:` import, `prefix=appconfig`, optional/fail-fast.
- Create: `aws/settings-boundary/src/test/kotlin/io/bluetape4k/workshop/aws/settings/AppConfigDataSpringIntegrationTest.kt`
  — 실제 SpringApplication, static synthetic credentials, loopback fake, 30초 bounded
  runtime reload/Environment-vs-bean proof와 timeout·close 증거.
- Modify: `aws/settings-boundary/README.md`, `README.ko.md` — import, reload,
  timeout, IAM, security/cost, rebinding and commands.
- Modify: `docs/coverage-matrix.md`, `.github/workflows/Examples.yml`,
  `scripts/smoke-validate.sh` — validation evidence and smoke comment/group.
- Create: `docs/lessons/2026-09-01-issue-870-appconfig-runtime-reload.md` — Korean
  implementation lesson and verification evidence.
- Create: `docs/review/2026-09-01-issue-870-appconfig-runtime-reload-spec-review.md`
  (already completed) and later implementation/pre-PR review artifacts.

## Task 1: Add the versionless build surface

**Files:** `gradle/libs.versions.toml`, `aws/settings-boundary/build.gradle.kts`

- [x] **Step 1: Record the catalog and plugin change.** Keep the existing
  `bluetape4k-dependencies` platform as the only Bluetape authority and add:

```toml
# AWS SDK v2
aws2-bom = { module = "software.amazon.awssdk:bom", version.ref = "aws2" }
aws2-appconfigdata-lib = { module = "software.amazon.awssdk:appconfigdata" }
```

  Add `kotlin.spring`, `spring.boot`, `springBoot.mainClass`,
  `libs.bluetape4k.aws`, `libs.aws2.appconfigdata.lib`, Awaitility, Spring Boot
  autoconfigure processors, and Spring Boot test. Do not add a Bluetape module
  version or individual Bluetape BOM.

- [x] **Step 2: Run dependency resolution before source changes.**

```bash
./gradlew :aws-settings-boundary:dependencies \
  --configuration runtimeClasspath --no-daemon --console=plain
```

Expected: `software.amazon.awssdk:appconfigdata:2.46.17` is selected from
`aws2-bom`, and the AWS Spring Boot module is selected without a module-level
version through the root `bluetape4k-dependencies:2.0.0-SNAPSHOT` BOM (the
currently published AWS BOM coordinate is allowed to remain its own
`1.0.0-SNAPSHOT` line). No explicit Bluetape version is present in the module
or catalog.

## Task 2: Add a credential-isolated Spring Boot entrypoint and timeout owners

**Files:**
`aws/settings-boundary/src/main/kotlin/io/bluetape4k/workshop/aws/settings/SettingsBoundarySpringApplication.kt`,
`aws/settings-boundary/src/main/kotlin/io/bluetape4k/workshop/aws/settings/SettingsBoundaryAppConfigConfiguration.kt`

- [x] **Step 1: Write the failing integration test first.** The test must build a
  `SpringApplication` with `WebApplicationType.NONE`, register a
  `BootstrapRegistryInitializer`, and inject a synthetic
  `StaticCredentialsProvider` into both ConfigData bootstrap and runtime bean
  customizers. Store only token ordinal/one-way marker, never token text. The
  fake handlers must accept only `POST /configurationsessions` and
  `GET /configuration`, bind to `127.0.0.1:0`, and return 405/404 otherwise.

```kotlin
val application = SpringApplicationBuilder(
    SettingsBoundarySpringApplication::class.java,
    AppConfigProbeConfiguration::class.java,
).web(WebApplicationType.NONE).properties(
    "spring.config.import=aws-app-config:application#profile#environment?format=properties&prefix=appconfig",
    "bluetape4k.aws.region=us-east-1",
    "bluetape4k.aws.app-config.region=us-east-1",
    "bluetape4k.aws.app-config.endpoint-override=${server.endpoint}",
    "bluetape4k.aws.app-config.refresh-interval=15s",
).build()
application.addBootstrapRegistryInitializer(
    BootstrapRegistryInitializer { registry ->
        registry.register(
            AwsSyncClientCustomizer::class.java,
            BootstrapRegistry.InstanceSupplier.of(testCustomizer),
        )
    },
)
```

  The fake returns `feature=initial` on the first `GetLatestConfiguration`
  response and `feature=updated` on the second. The test asserts initial
  `Environment` and probe values, then waits with Awaitility (maximum 20
  seconds; whole test `@Timeout(30)`) for only `Environment` to change. It also
  asserts header identifiers, per-request auth-present markers, token ordinals,
  `maxConcurrentRequests == 1` for the single consumer source, request count
  stability after close, bounded
  context close (≤6 seconds), and fake executor termination. The fake uses at
  least two request-executor threads plus a barrier-capable handler. The barrier
  is enabled only after the initial `GetLatestConfiguration` response, for the
  first scheduled reload; it never blocks context bootstrap. The consumer result
  is limited to single-source overlap/close; duplicate-source deduplication is
  tracked only by the upstream named lifecycle test below.

- [x] **Step 2: Run the targeted test and record RED.**

```bash
./gradlew :aws-settings-boundary:test \
  --tests '*AppConfigDataSpringIntegrationTest' --no-daemon --console=plain
```

Expected before the entrypoint exists: `compileTestKotlin` fails with unresolved
`SettingsBoundarySpringApplication` (RED). Keep this output in the workflow
evidence and do not weaken the test to make it compile.

- [x] **Step 3: Implement the minimal entrypoint.** Add:

```kotlin
@SpringBootApplication
class SettingsBoundarySpringApplication

fun main(args: Array<String>) {
    val application = SpringApplication(SettingsBoundarySpringApplication::class.java)
    application.addBootstrapRegistryInitializer(
        BootstrapRegistryInitializer { registry ->
            registry.register(
                AwsSyncClientCustomizer::class.java,
                BootstrapRegistry.InstanceSupplier.of(appConfigTimeoutCustomizer(
                    apiCallTimeout = Duration.ofSeconds(10),
                    apiCallAttemptTimeout = Duration.ofSeconds(5),
                )),
            )
        },
    )
    application.run(*args)
}
```

  The initializer must filter `context.serviceName == "appconfigdata"`, leave
  credentials on the default AWS chain in production, and apply SDK
  `ClientOverrideConfiguration` only to AppConfig Data.

- [x] **Step 4: Implement runtime customizer and production timeout values.**
  `SettingsBoundaryAppConfigConfiguration` is conditional on
  `bluetape4k.aws.app-config.enabled=true` and provides one
  `AwsSyncClientCustomizer`. It applies API timeout 10 seconds and attempt
  timeout 5 seconds only for `appconfigdata`. Test-only customizer values are
  500ms or less and are never placed in `application-appconfig.yml`.

- [x] **Step 5: Run the integration test to GREEN.**

```bash
./gradlew :aws-settings-boundary:test \
  --tests '*AppConfigDataSpringIntegrationTest' --no-build-cache \
  --no-daemon --console=plain
```

Expected: the local fake receives one session and two sequential latest calls;
the test passes within 30 seconds, and context/server cleanup runs in `finally`.
Add a separate deterministic delayed loopback test with a response delay over
500ms and test-only API/attempt timeout no greater than 500ms; assert completion
within 2 seconds and verify both the ConfigData bootstrap initializer and the
runtime application customizer apply the timeout only to `appconfigdata` (a
non-AppConfig service builder remains unchanged). This test must never use the
production 10s/5s values.

## Task 3: Add safe default/profile resources and deterministic fake contracts

**Files:**
`aws/settings-boundary/src/main/resources/application.yml`,
`aws/settings-boundary/src/main/resources/application-appconfig.yml`,
`aws/settings-boundary/src/test/kotlin/io/bluetape4k/workshop/aws/settings/AppConfigDataSpringIntegrationTest.kt`

- [x] **Step 1: Add the default resource and negative test.** Use:

```yaml
spring:
  application:
    name: aws-settings-boundary
bluetape4k:
  aws:
    app-config:
      enabled: false
```

  Start the default application context without an import and assert no
  `AppConfigDataClient` bean or AppConfig property source is created.

- [x] **Step 2: Add the explicit profile resource.** Use:

```yaml
spring:
  config:
    import: optional:aws-app-config:application#profile#environment?format=properties&prefix=appconfig
bluetape4k:
  aws:
    app-config:
      enabled: true
      region: ${AWS_REGION:ap-northeast-2}
      fail-fast: false
      # refresh-interval: 15s
```

  Do not include endpoint override or credentials in the checked-in profile.
  A real run is explicitly opt-in with trusted HTTPS endpoint/credentials.

- [x] **Step 3: Harden fake response boundaries.** The fake must:
  - bind only loopback and port `0`;
  - reject wrong method/path with 405/404;
  - use an explicit bounded request executor and track active/max-active counts;
  - store only request counts, header identifier values, per-request
    auth-present markers, and token ordinal (never `Authorization`, token, or
    payload text);
  - limit response body to a small fixed fixture and stop in `finally`;
  - use `StaticCredentialsProvider` and never system properties, credential files,
    environment credential variables, or IMDS;
  - expose a bounded close/termination assertion: active handlers reach zero,
    the executor terminates, and request count remains unchanged after context
    close and server stop. A JUnit `@Timeout(30)` timeout path plus an
    `AfterEach`/`finally` watchdog must still stop the server and await executor
    termination. After close, observe a 1-second bounded quiescence window
    before asserting request-count stability; closing the context twice must
    remain error-free.

- [x] **Step 4: Add deterministic format/failure tests.** Keep the 15-second
  Spring integrations for the successful initial-load/first runtime update and
  the in-flight close boundary (the suite costs about 40 seconds).
  Add fast consumer-boundary tests using the same fake fixture for JSON,
  `prefix=appconfig`, optional/fail-fast, method/path rejection, and disabled
  default profile. The upstream lifecycle tests own empty/malformed payload
  last-good retention and transport-to-new-session retry; record those upstream
  test references rather than reimplementing an unbounded retry loop here.
  Consumer failure tests set `refreshInterval=null`, use a per-test wall-clock
  bound, and capture logger/output for sentinel failures, asserting that raw
  token, `Authorization`, payload, and synthetic credentials are absent.
  Include a malicious fixture key such as `spring.application.name` or
  `management.*` and assert it is visible only under `appconfig.*`, never as a
  top-level operational property.
  Duplicate-source scheduler, real overlap prevention, atomic map replacement, empty/malformed
  last-good retention, and transport-to-new-session retry are verified by the
  upstream `AppConfigReloadLifecycleTest.one scheduler and one task per refreshable source update the latest values`,
  `AppConfigReloadLifecycleTest.empty response retains values while advancing the token`,
  `AppConfigReloadLifecycleTest.decode failure retains map while advancing response token`,
  `AppConfigReloadLifecycleTest.transport failure discards session and retries with a new session`,
  `AppConfigDataPropertySourceTest.property names and values switch atomically`,
  and `AwsConfigDataBootstrapBridgeTest.initialized-only holder does not create or close unused client`
  tests referenced in the lesson/review; the consumer suite does not duplicate
  those internal lifecycle tests.
  Keep the public consumer boundary and use a direct SDK test client only for
  timeout behavior.

## Task 4: Document caller contract and update repository guards

**Files:** `aws/settings-boundary/README.md`, `README.ko.md`,
`docs/coverage-matrix.md`, `.github/workflows/Examples.yml`,
`scripts/smoke-validate.sh`

- [x] **Step 1: Update both READMEs equivalently.** Add import URI and profile
  commands:

```bash
./gradlew :aws-settings-boundary:test
./gradlew :aws-settings-boundary:bootRun --args='--spring.profiles.active=appconfig'
```

  Explain that `optional:` and `fail-fast=false` are for non-security feature
  flags only; `prefix=appconfig` prevents top-level `spring.*` injection; real
  AWS needs IAM `appconfig:StartConfigurationSession` and
  `appconfig:GetLatestConfiguration`; polling can incur cost/traffic; default
  profile is disabled. Include the 10s/5s production timeout guidance and note
  that 500ms is test-only. State that endpoint override must be a trusted HTTPS
  host, must not be supplied by remote AppConfig data, environment data, or
  unreviewed command-line input, and is rejected by the consumer's
  highest-precedence, region-derived AWS hostname allowlist before ConfigData
  client creation. HTTP is accepted only for the literal-loopback fake.
  Endpoint values supplied by application/profile ConfigData remain a
  deployment-policy responsibility because they are loaded after this
  pre-ConfigData guard.

- [x] **Step 2: Add a concise caller table.** State precisely:

| Caller | After AppConfig reload |
| --- | --- |
| `Environment#getProperty` | latest atomic AppConfig values |
| `@Value` field | initial binding only |
| `@ConfigurationProperties` bean | initial binding only; no automatic rebind |

  State that Spring Cloud Context refresh/rebinding is intentionally out of scope.

- [x] **Step 3: Update matrix/workflow/script.** Keep the module in existing
  smoke groups, update its comment from Secrets Manager/SSM only to include the
  AppConfig ConfigData contract, and add stale-check/lesson references if the
  repository guard requires them. Do not create a new module or Testcontainers
  job.

- [x] **Step 4: Run documentation checks.**

```bash
node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  aws/settings-boundary/README.ko.md \
  docs/lessons/2026-09-01-issue-870-appconfig-runtime-reload.md
bash scripts/smoke-validate.sh stale-check
git diff --check
```

Expected: terminology audit findings=0, stale-check PASS, and no whitespace
errors. Compare README sections line-by-line for semantic parity.

## Task 5: Verification, lesson, review, and PR

**Files:** `docs/lessons/2026-09-01-issue-870-appconfig-runtime-reload.md`,
`docs/review/2026-09-01-issue-870-appconfig-runtime-reload-implementation-review.md`,
`docs/review/2026-09-01-issue-870-appconfig-runtime-reload-pre-pr-review.md`

- [x] **Step 1: Run targeted and module verification.**

```bash
./gradlew :aws-settings-boundary:compileKotlin \
  :aws-settings-boundary:compileTestKotlin --no-daemon --console=plain
./gradlew :aws-settings-boundary:test --no-build-cache --no-daemon --console=plain
./gradlew :aws-settings-boundary:build --no-build-cache --no-daemon --console=plain
./gradlew detekt --no-daemon --console=plain
bash scripts/smoke-validate.sh stale-check
git diff --check
```

Expected: all tasks succeed; existing nine boundary tests plus sixteen AppConfig
tests (25 module tests total) pass; each runtime test remains within its 30-second
JUnit bound; no raw
credential, token, payload, or Authorization value appears in reports/logs.

- [ ] **Step 2: Run the Type A performance/stability scan.** Record blocking I/O,
  timeout, retry/backoff, context close,
  fake server cleanup, and direct SDK client close evidence. The in-flight delayed
  request case must prove a measurable context-close upper bound compatible with
  the upstream five-second shutdown wait, zero active handlers/requests after
  close, terminated poller and fake executors, and idempotent double close.
  Duplicate scheduler, atomic replacement, empty/malformed last-good retention,
  and transport/new-session retry timing are delegated to the upstream methods
  `one scheduler and one task per refreshable source update the latest values`,
  `empty response retains values while advancing the token`,
  `decode failure retains map while advancing response token`,
  `transport failure discards session and retries with a new session`, and
  `property names and values switch atomically`, and
  `initialized-only holder does not create or close unused client` named in Task 3;
  this consumer suite verifies successful initial/update values and bounded startup/fake
  failure behavior with `refreshInterval=null`.
  P0/P1 findings block the PR; fix and rerun the affected lens. The consumer's
  normal-path max-concurrency assertion and upstream named lifecycle test own
  the complementary overlap contract, so no third long-running poll is added
  to the smoke group.

- [x] **Step 3: Write the Korean lesson.** Include the selected A alternative,
  upstream reuse decision, static credential boundary, default-off safety,
  Environment-vs-bean limitation, 15-second/30-second test budget, exact commands,
  failures and fixes, and remaining risk that upstream retry count is unbounded.

- [ ] **Step 4: Run six-lens pre-PR review.** Check code, tests, docs, workflow,
  BOM, timeout ownership, endpoint/credential trust, public caller ergonomics,
  and exact issue/PR metadata. Require P0=0, P1=0; fix P2 or record a linked
  follow-up with evidence.

- [ ] **Step 5: Commit and push with Lore trailers.** Use a Korean intent line and
  include `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`,
  `Tested`, and `Not-tested` trailers. Push `feat/issue-870-appconfig-runtime-reload`.

- [ ] **Step 6: Create and verify the PR.** Title:
  `[2.0.0] Issue #870 settings-boundary에 AppConfig ConfigData·runtime reload 예제를 추가한다`
  Body must be Korean, link Issue #870, list exact tests/CI, and end with
  `## DoD Status`. Set milestone `2.0.0`, preserve issue labels/assignee, and
  verify live base/head/body before waiting for CI.

- [ ] **Step 7: Merge gate and cleanup.** Do not merge until exact-head CI/reviews
  are freshly re-read and the user gives a new explicit `승인`. After merge:
  fast-forward root `develop`, rerun module test/stale-check/diff-check, prove
  `develop...origin/develop` parity, remove only this clean feature worktree and
  branch, run `git worktree prune`, and preserve unrelated dirty worktrees.

## Rollback and rerun

- Before PR, revert only this branch's commits or remove the feature worktree; do
  not reset shared `develop` or delete unrelated worktrees.
- If the AppConfig 구성 데이터 test flakes, keep the exact 30-second bound, inspect
  fake request counts/thread residue, and rerun once after diagnosing rather than
  increasing timeouts blindly.
- If shutdown occurs during a delayed synchronous request, assert the elapsed close
  bound, zero post-close requests, and terminated fake/poller executors before
  changing any timeout; do not weaken the lifecycle contract.
- If the upstream 구성 데이터 artifact is unavailable, leave the versionless alias
  and record dependency-resolution evidence as a blocker; do not pin a released
  Bluetape version or copy upstream internal code.

## Plan self-review

- Spec requirements map to Tasks 1–5: build/BOM (1), ConfigData and timeout
  ownership (2), defaults/failure/lifecycle/security (3), bilingual caller/docs
  and guards (4), verification/lesson/PR/cleanup (5).
- No placeholder task remains: every code step names files, types, commands,
  expected output, and rollback behavior.
- The `SettingsBoundarySpringApplication`, `SettingsBoundaryAppConfigConfiguration`,
  `AppConfigProbeConfiguration`, fake server marker fields, and timeout values use
  one consistent naming contract throughout the plan.
