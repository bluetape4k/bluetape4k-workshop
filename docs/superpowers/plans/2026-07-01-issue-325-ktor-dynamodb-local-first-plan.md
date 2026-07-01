# Issue #325 - Ktor DynamoDB Local-First Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILLS: Use `bluetape4k-workflow`,
> `bluetape4k-code-patterns`, `bluetape4k-blog`, `bluetape4k-diagram`,
> `test-driven-development`, and `verification-before-completion`. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local-first Ktor + DynamoDB workshop module that demonstrates
`DynamoDbKtorPlugin`, conditional create, optimistic update, bounded pagination,
safe error mapping, and emulator-backed verification.

**Spec:** `docs/superpowers/specs/2026-07-01-issue-325-ktor-dynamodb-local-first-design.md`

**Architecture:** Ktor routes own HTTP and request validation. An
`OrderSessionService` owns domain semantics and error mapping. A thin
`OrderSessionDynamoRepository` uses `DynamoDbKtorRepository` for create/read
paths and AWS Kotlin SDK DynamoDB commands for bounded scan, conditional
create, optimistic update, and conditional delete. Tests supply
Floci/LocalStack endpoint, region, credentials, unique table names, and command
count instrumentation through `bluetape4k-testcontainers`.

**Tech Stack:** Kotlin, Ktor server/test host, kotlinx serialization,
AWS SDK for Kotlin DynamoDB, `bluetape4k-aws-ktor`, `bluetape4k-aws-kotlin`,
`bluetape4k-testcontainers`, JUnit 5, `bluetape4k-assertions`,
`SuspendedJobTester`, CairoSVG-rendered README diagrams.

---

## File Structure

- Create `aws/ktor-dynamodb/build.gradle.kts`
- Create `aws/ktor-dynamodb/src/main/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/*`
- Create `aws/ktor-dynamodb/src/main/resources/logback.xml`
- Create `aws/ktor-dynamodb/src/test/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/*`
- Create `aws/ktor-dynamodb/src/test/resources/junit-platform.properties`
- Create `aws/ktor-dynamodb/src/test/resources/logback-test.xml`
- Create `aws/ktor-dynamodb/README.md`
- Create `aws/ktor-dynamodb/README.ko.md`
- Modify `gradle/libs.versions.toml`
- Modify `README.md`
- Modify `README.ko.md`
- Modify `aws/README.md`
- Modify `aws/README.ko.md`
- Modify `.github/workflows/Examples.yml`
- Modify `scripts/smoke-validate.sh`
- Modify diagram validators if new diagram filenames must be registered:
  `scripts/validate-readme-architecture-diagrams.mjs`,
  `scripts/validate-sequence-diagrams.mjs`
- Create `docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg/png`
- Create `docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg/png`
- Create `docs/review/2026-07-01-issue-325-implementation-review.md`
- Create `docs/lessons/2026-07-01-issue-325-ktor-dynamodb-local-first.md`

## Dependency and API Guard

- [ ] Add catalog aliases:
  - `bluetape4k-aws-ktor = { module = "io.github.bluetape4k.aws:bluetape4k-aws-ktor" }`
  - `bluetape4k-aws-kotlin = { module = "io.github.bluetape4k.aws:bluetape4k-aws-kotlin" }`
  - `aws-kotlin-dynamodb = { module = "aws.sdk.kotlin:dynamodb", version.ref = "aws-kotlin" }`
  - `aws2-auth = { module = "software.amazon.awssdk:auth", version.ref = "aws2" }` only if compile evidence proves Java SDK v2 auth helpers are needed.
- [ ] Keep bluetape4k aliases versionless under the root BOM.
- [ ] Apply `alias(libs.plugins.kotlin.serialization)` in the module build.
- [ ] Use `implementation(platform(libs.ktor.bom))`, `libs.ktor.serialization.kotlinx.json`, and `libs.kotlinx.serialization.json`.
- [ ] Verify against current local source before implementation:
  `DynamoDbKtorPlugin`, `Application.dynamoDb()`,
  `DynamoDbKtorRuntime.repository(...)`, `DynamoDbKtorRepository.put/findById`,
  `DynamoItemMapper`, `DynamoItemReader`, `partitionKeyOf`,
  `stringAttrDefinitionOf`, `FlociServer.Launcher.floci`, and
  `LocalStackServer.Launcher.getLocalStack("dynamodb")`.
- [ ] Confirm the local source API for `DynamoDbKtorRepository.scan` before use.
      If it is a paginator `Flow`, do not use it for the learner-facing bounded
      list API; use AWS Kotlin `DynamoDbClient.scan` directly for one page.

## Error Code Matrix

All route tests, README curl examples, and StatusPages mappings must assert the
HTTP status and stable `ErrorResponse.code`, not status alone.

| condition | status | code | safe message rule |
| --- | --- | --- | --- |
| blank or invalid field | `400` | `VALIDATION_FAILED` | Name the invalid field, not the payload. |
| malformed JSON | `400` | `MALFORMED_JSON` | Do not echo parser text or request body. |
| oversized body | `413` | `REQUEST_TOO_LARGE` | Do not parse or log payload content. |
| malformed/foreign `nextToken` | `400` | `INVALID_PAGE_TOKEN` | Do not reveal decoded token internals. |
| duplicate create | `409` | `ORDER_SESSION_EXISTS` | Mention conflict by id only. |
| stale optimistic update | `409` | `ORDER_SESSION_VERSION_CONFLICT` | Mention expected/current mismatch safely. |
| missing read/update/delete | `404` | `ORDER_SESSION_NOT_FOUND` | Mention not found by id only. |
| readiness refresh down/inactive | `503` | `DYNAMODB_NOT_READY` | No endpoint, AWS request id, or raw AWS message. |
| unexpected DynamoDB/emulator failure | `503` | `DYNAMODB_UNAVAILABLE` | Stable message; details only as sanitized log fields. |

## Task 1: Module Skeleton

**Complexity:** medium

**Files:**
- Create `aws/ktor-dynamodb/build.gradle.kts`
- Create `aws/ktor-dynamodb/src/main/resources/logback.xml`
- Create `aws/ktor-dynamodb/src/test/resources/junit-platform.properties`
- Create `aws/ktor-dynamodb/src/test/resources/logback-test.xml`
- Modify `gradle/libs.versions.toml`

- [ ] Create the Gradle build with `application`,
      `alias(libs.plugins.kotlin.serialization)`, Ktor server Netty,
      `ContentNegotiation`, `CallLogging`, `StatusPages`, kotlinx JSON,
      AWS Kotlin DynamoDB, `bluetape4k-aws-ktor`,
      `bluetape4k-aws-kotlin`, `bluetape4k-ktor-core`,
      `bluetape4k-logging`, `bluetape4k-coroutines`, JUnit 5,
      Ktor test host/client, `bluetape4k-testcontainers`, and
      `bluetape4k-assertions`.
- [ ] Configure `mainClass` to
      `io.bluetape4k.workshop.aws.ktordynamodb.KtorDynamoDbApplicationKt`.
- [ ] Disable JUnit parallel execution in `junit-platform.properties`.
- [ ] Add test logback configuration consistent with neighboring modules.
- [ ] Run `./gradlew projects --console=plain` via context-mode and verify
      `:aws-ktor-dynamodb`.
- [ ] Run
      `./gradlew :aws-ktor-dynamodb:compileKotlin --warning-mode all --console=plain`.

## Task 1.5: Minimal Compile Skeleton For Behavioral Red Tests

**Complexity:** medium

**Files:**
- Create minimal production files listed in Task 3 with placeholder behavior.

- [ ] Add an entrypoint, route installer, config seam, service/repository
      interfaces, DTO/data class names, and error types so tests compile before
      full behavior exists.
- [ ] Mark non-user API classes/functions `internal` by default. Add English
      KDoc with contract and a short example for any public class/function that
      remains public.
- [ ] Ensure every new `data class`, including domain models, config values,
      page-token values, and DTOs, implements `java.io.Serializable` and defines
      a companion `serialVersionUID`.
- [ ] Use named arguments for DTO/config construction. For internal functions
      that need two or more same-typed parameters, introduce a named value
      object/factory instead of relying on positional `String`/`Long` pairs.
- [ ] Keep placeholder implementations explicit and deterministic, for example
      `TODO("implemented in Task 3")`; red tests must compile and fail
      behaviorally, not fail with unresolved references.
- [ ] Run
      `./gradlew :aws-ktor-dynamodb:compileTestKotlin --warning-mode all --console=plain`
      after adding red tests and before the first full `test` run.

## Task 2: TDD Red Tests

**Complexity:** high

**Files:**
- Create `aws/ktor-dynamodb/src/test/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/KtorDynamoDbApplicationTest.kt`
- Create test support under the same package if needed.

- [ ] Add tests first with `testApplication` and Ktor JSON client using
      `ContentNegotiation { json(...) }`.
- [ ] Runner rule: route tests use `testApplication {}` directly. IO-bound
      direct suspend tests and setup/cleanup use `runSuspendIO`; do not use
      `runTest` for Ktor `testApplication`, Testcontainers, or real IO.
- [ ] Use `FlociServer.Launcher.floci` by default and
      `LocalStackServer.Launcher.getLocalStack("dynamodb")` when
      `-Dbluetape4k.aws.emulator=localstack`.
- [ ] Use unique table names via `Base58.randomString(8)` or another
      bluetape4k-approved unique string helper; use unique item ids per test.
- [ ] Use per-class table names, a test-owned id prefix, list assertions scoped
      to owned ids, and `@AfterAll` best-effort `DeleteTable`/item cleanup with
      a bounded timeout. Cleanup failures are logged as sanitized test
      diagnostics, not hidden as success evidence.
- [ ] Add test support that can count DynamoDB SDK operations by operation name.
      Assert the command budget for create, duplicate create, read, list,
      update success, update failure fallback, delete, validation failures, and
      readiness probes.
- [ ] Add red tests for:
  - create then read one order session,
  - bounded list default limit, maximum limit, continuation token, malformed
    token `400 INVALID_PAGE_TOKEN`, and one `Scan` call per page,
  - duplicate create returns `409 ORDER_SESSION_EXISTS` with one failed
    `PutItem`,
  - update with matching `expectedVersion` increments `version`,
  - update success uses one `UpdateItem` and no `GetItem`,
  - stale update returns `409 ORDER_SESSION_VERSION_CONFLICT` with at most one
    failed `UpdateItem` plus one fallback `GetItem`,
  - missing read/update/delete returns `404 ORDER_SESSION_NOT_FOUND`,
  - conditional delete success returns `204`,
  - invalid status, blank id/customer id/table name, non-positive
    `expectedVersion`, and `limit` outside `1..100` return safe `400` codes,
  - malformed JSON returns `400 MALFORMED_JSON`,
  - oversized JSON over `64 KiB` returns `413 REQUEST_TOO_LARGE` with zero
    DynamoDB commands,
  - readiness returns `200 ReadinessResponse(status="UP")`,
  - repeated readiness probes before TTL issue zero DynamoDB commands,
  - readiness TTL refresh calls at most one `DescribeTable`,
  - readiness refresh timeout within 2 seconds returns safe `503 DOWN`,
  - startup table bootstrap timeout/failure may fail application startup,
  - unexpected DynamoDB/emulator failure maps to safe `503 DYNAMODB_UNAVAILABLE`,
  - repository/service `CancellationException` is rethrown, not mapped, not
    retried, not swallowed by `runCatching`, and not logged as a DynamoDB error,
  - expression-looking values such as `#id`, `:expected`,
    `attribute_exists(id)`, quotes, and delimiters are stored/compared as
    bound values only,
  - JSON serialization round-trips DTOs and uses only kotlinx serializers.
- [ ] Add a bounded contention test with `SuspendedJobTester`: concurrent
      updates with the same expected version use at least eight concurrent jobs,
      a 10-second route-test timeout, exactly one success, and `N - 1` `409`
      responses, with no sleep-based loops.
- [ ] Add config-level assertions or test seams proving AWS SDK local-test retry
      bounds: max attempts 2, total call deadline 5 seconds, backoff cap 500
      milliseconds, and no domain-level retry for validation failures or
      `ConditionalCheckFailedException`.
- [ ] Add captured-log redaction tests proving no raw URI query, headers,
      bodies, endpoints, credentials, AWS request ids, raw AWS messages, or
      payloads appear in logs or error responses.
- [ ] Use only `bluetape4k-assertions`; do not introduce AssertJ, Kluent, JUnit
      assertions, or `kotlin.test` assertions.
- [ ] Run
      `./gradlew :aws-ktor-dynamodb:compileTestKotlin --warning-mode all --console=plain`
      and confirm tests compile before the first full red test run.
- [ ] Run
      `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1`
      and record expected red failures before production implementation.

## Task 3: Application, DTOs, And Repository

**Complexity:** high

**Files:**
- Create `KtorDynamoDbApplication.kt`
- Create `DynamoDbLocalConfig.kt`
- Create `OrderSessionModels.kt`
- Create `OrderSessionRepository.kt`
- Create `OrderSessionService.kt`
- Create `OrderSessionRoutes.kt`
- Create `OrderSessionErrors.kt`

- [ ] Implement `@Serializable` DTOs and all other new `data class` values so
      they also implement `java.io.Serializable` and define
      `serialVersionUID`: `CreateOrderSessionRequest`,
      `UpdateOrderSessionRequest`, `OrderSessionResponse`,
      `OrderSessionListResponse`, `ErrorResponse`, `ReadinessResponse`,
      `OrderSession`, runtime config, and page-token value classes.
- [ ] Model `OrderSessionStatus` as an allowlisted enum with
      `CREATED`, `APPROVED`, and `CANCELLED`.
- [ ] Use bluetape4k validation helpers such as `requireNotBlank` and
      `requireInRange` for caller input; use `check` only for internal
      invariants.
- [ ] Validate fields at the task level: `id`, `customerId`, and `tableName`
      nonblank; `expectedVersion > 0`; `limit in 1..100`; malformed or foreign
      `nextToken` returns `400 INVALID_PAGE_TOKEN`; local/test endpoint and
      dummy credential properties are present when mode is local/test.
- [ ] Configure Ktor `ContentNegotiation` with kotlinx JSON, `CallLogging`, and
      `StatusPages`.
- [ ] Use only Ktor kotlinx DTO serializers; do not install Jackson content
      negotiation, Jackson default typing, or polymorphic serializers for this
      module. Verify by dependency/config grep plus serialization tests.
- [ ] Configure request body size with a concrete `64 KiB` max and return safe
      `413 REQUEST_TOO_LARGE` before parsing/logging oversized payloads.
- [ ] Configure `CallLogging` so it logs only method, route template, status,
      diagnostic id, operation, and safe error class. Do not log raw URI query,
      headers, bodies, endpoints, credentials, AWS request ids/messages, or
      payloads.
- [ ] Configure `StatusPages` for validation failures, malformed receive/
      serialization exceptions, domain conflicts/misses, readiness/downstream
      failures, and unexpected errors using the Error Code Matrix.
- [ ] Install `DynamoDbKtorPlugin` with `autoCreateTables = true`,
      `BillingMode.PayPerRequest`, partition key `id`, endpoint, region,
      credentials provider, and bounded table-ready timeout.
- [ ] Name the DynamoDB client lifecycle explicitly:
      local/test mode uses a plugin-owned AWS Kotlin `DynamoDbClient` built
      from the supplied emulator endpoint, region, and dummy credentials;
      real mode uses AWS Kotlin default/environment credentials only after
      explicit `-Dbluetape4k.aws.mode=real`; plugin-owned clients close on Ktor
      shutdown; injected test clients remain owned by the fixture.
- [ ] Keep production/main application code free of Testcontainers.
- [ ] Parse runtime config:
  - default `-Dbluetape4k.aws.mode=local`,
  - optional `-Dbluetape4k.aws.emulator=localstack` for tests,
  - explicit `-Dbluetape4k.aws.mode=real` for manual real AWS only,
  - `-Dbluetape4k.aws.region`,
  - `-Dbluetape4k.aws.dynamodb.table-name`,
  - `-Dbluetape4k.aws.dynamodb.endpoint-url`,
  - `-Dbluetape4k.aws.access-key-id`,
  - `-Dbluetape4k.aws.secret-access-key`.
- [ ] Fail closed in local/test mode when an emulator endpoint or dummy
      credentials are absent. Add a test proving default/local mode never
      falls back to real AWS SDK defaults and real mode requires the explicit
      `mode=real` flag.
- [ ] For local/test mode, tests supply dummy credentials and emulator endpoint.
      For real mode, use AWS Kotlin default/environment credential provider
      chain without hardcoded keys.
- [ ] Implement `OrderSessionDynamoRepository`:
  - create with `PutItem` and `attribute_not_exists(#id)`,
  - read with `GetItem`,
  - bounded list with AWS Kotlin `DynamoDbClient.scan` directly, one `Scan`
    page, default limit 25, max 100, `exclusiveStartKey`,
    `lastEvaluatedKey`, opaque token encode/decode, and malformed token `400`,
  - update success with one `UpdateItem` using
    `attribute_exists(#id) AND #version = :expected`,
  - update failure with one fallback `GetItem` only after
    `ConditionalCheckFailedException`,
  - delete with conditional `DeleteItem attribute_exists(#id)`.
- [ ] Never concatenate request-controlled strings into DynamoDB expressions;
      use fixed `ExpressionAttributeNames` and bound `AttributeValue`s.
- [ ] Rethrow `CancellationException` before broad exception mapping.
- [ ] Log only sanitized structured fields: operation, safe error class, and
      diagnostic id. Do not log raw AWS messages, request ids, endpoints,
      headers, credentials, or payloads.
- [ ] Implement readiness state:
  - startup/table bootstrap failure may fail application startup,
  - normal probes use cached state and issue zero DynamoDB commands,
  - optional TTL refresh at most one `DescribeTable` every 30 seconds with
    2-second timeout,
  - `200` for `UP`, `503` for post-start refresh down/inactive.
- [ ] Add injectable readiness clock/checker seams for tests. Verify no
      readiness refresh job/client survives `testApplication` stop.
- [ ] Use AWS SDK local-test retry bounds: max attempts 2, total call deadline
      5 seconds, backoff cap 500 milliseconds.
- [ ] Run focused tests serially until green.

## Task 4: README and Diagram Assets

**Complexity:** high

**Files:**
- Create `aws/ktor-dynamodb/README.md`
- Create `aws/ktor-dynamodb/README.ko.md`
- Modify `README.md`
- Modify `README.ko.md`
- Modify `aws/README.md`
- Modify `aws/README.ko.md`
- Create SVG/PNG diagrams under `docs/images/readme-diagrams/`

- [ ] Load `$bluetape4k-diagram` fresh before creating diagrams.
- [ ] Write English and Korean module READMEs with language switch and
      source-equivalent sections: overview, architecture, API, local run,
      test commands, LocalStack parity, real AWS opt-in, error handling,
      unsupported capabilities, cleanup, and diagrams.
- [ ] Document local run as an executable runbook. Because main code must not
      start Testcontainers, the README must include an emulator prerequisite,
      endpoint URL, region, table name, dummy credentials, app start command,
      readiness curl, and stop/cleanup steps. Do not present
      `./gradlew :aws-ktor-dynamodb:run -Dbluetape4k.aws.mode=local` without
      an endpoint and credentials.
- [ ] Include copy-pasteable `curl` examples for create, read, bounded list,
      update, delete, duplicate create `409`, stale update `409`, missing item
      `404`, invalid/malformed JSON `400`, and readiness.
- [ ] Use `BASE_URL` in curl examples. Keep success examples as an ordered
      create/read/list/update/delete flow. Use `curl -i` or
      `-w '%{http_code}'` for negative cases so learners can see status and
      body. Show bounded-list `nextToken` reuse and negative response bodies
      with the exact Error Code Matrix codes.
- [ ] Mark real AWS mode as advanced/optional, guarded by
      `-Dbluetape4k.aws.mode=real`, excluded from tests/CI, and requiring cost
      and cleanup awareness.
- [ ] Include unauthenticated-workshop-only warnings: mutating routes must not
      be exposed publicly without authentication, authorization, network
      controls, least-privilege IAM, rate limits, and body limits.
- [ ] Include unsupported scope explicitly: IAM policy management, public
      exposure, production migrations, GSI, streams, transactions, production
      query design, and schema evolution.
- [ ] Include copy-paste cleanup commands for real AWS table deletion and
      local/emulator table cleanup where applicable. State that CI and default
      tests never use real AWS mode.
- [ ] Update root and AWS README locale pairs with the module row and focused
      Gradle command.
- [ ] Create architecture diagram:
  - top-to-bottom or clearly layered flow,
  - visible local emulator boundary and optional real AWS boundary,
  - Ktor route/service/repository/plugin/runtime/DynamoDB table layers,
  - connector legend if line styles differ,
  - official AWS/DynamoDB icon only from the shared catalog when used,
  - aligned card text and rounded orthogonal connectors.
- [ ] Create sequence diagram:
  - numbered call labels above lines,
  - transparent alt/branch regions,
  - branch-specific muted colors,
  - color-matched arrowheads,
  - no labels covering call lines,
  - `400`/`404`/`409` paths and bounded list/pagination.
- [ ] Render SVGs:
      `~/.local/bin/cairosvg <svg> -o <png> -s 2`.
- [ ] Run `xmllint --noout` on new SVGs.
- [ ] Run full `$bluetape4k-diagram` checklist and repo validators:
  - `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg`
  - `node scripts/validate-readme-architecture-diagrams.mjs`
  - `node scripts/validate-sequence-diagrams.mjs`
  - applicable geometry/endpoint/mixed-corner/connector/marker/label audits.
- [ ] Record diagram evidence in the review artifact: ledger rows, connector
      and marker counts, geometry audit output, `WEAK`/`UNAVAILABLE` handling,
      and full-size PNG inspection notes.
- [ ] Open every touched PNG full-size and reject any connector, text, card,
      icon, rounded-corner, arrowhead, palette, or sequence-style defect.
- [ ] Run README locale validators and record module/root/aws locale pair
      evidence:
  - `node scripts/validate-readme-parity.mjs`
  - `node scripts/validate-readme-language.mjs`

## Task 5: CI, Smoke, And Registration

**Complexity:** medium

**Files:**
- Modify `.github/workflows/Examples.yml`
- Modify `scripts/smoke-validate.sh`
- Modify diagram validator allowlists if required.

- [ ] Add `aws/ktor-dynamodb/**` to `Examples.yml` push and PR path filters.
- [ ] Add `:aws-ktor-dynamodb:test` to the existing sequential
      `container-examples` job only; keep it out of no-container smoke lanes.
- [ ] Add artifact paths:
  - `aws/ktor-dynamodb/build/test-results/test/*.xml`
  - `aws/ktor-dynamodb/build/reports/tests/test/`
- [ ] Create an explicit `aws-full` Docker-backed group in
      `scripts/smoke-validate.sh` with
      `:aws-ktor-dynamodb:test --continue --max-workers=1`; update help text.
      Keep `:aws-ktor-dynamodb:test` out of `all-smoke`.
- [ ] Increment stale-check expected project count from 94 to 95 after
      `./gradlew projects` confirms the module.
- [ ] Verify CI/scripts do not enable real AWS or reference AWS secrets:
      `rg 'AWS_|mode=real|secrets\\.' .github scripts`.
- [ ] In CI, use dummy local AWS environment only for emulator tests when
      needed. Do not add `${{ secrets.AWS_* }}` or `bluetape4k.aws.mode=real`.
- [ ] Compare cold module test runtime against the existing
      `container-examples` 35-minute job budget and adjust the job timeout only
      if evidence requires it.
- [ ] Run `actionlint .github/workflows/Examples.yml`.
- [ ] Run `./scripts/smoke-validate.sh stale-check`.
- [ ] Run `./scripts/smoke-validate.sh aws-full` serially when feasible.

## Task 6: Verification, Review, Lessons, And PR

**Complexity:** high

**Files:**
- Create `docs/review/2026-07-01-issue-325-implementation-review.md`
- Create `docs/lessons/2026-07-01-issue-325-ktor-dynamodb-local-first.md`

- [ ] Run IDE diagnostics if available; if unavailable, record fallback to
      Gradle compile/tests.
- [ ] Run targeted verification:
  - `./gradlew :aws-ktor-dynamodb:compileKotlin --warning-mode all --console=plain`
  - `./gradlew :aws-ktor-dynamodb:compileTestKotlin --warning-mode all --console=plain`
  - `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1`
  - `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1 -Dbluetape4k.aws.emulator=localstack`
  - `./gradlew projects --console=plain`
  - `./scripts/smoke-validate.sh stale-check`
  - `./scripts/smoke-validate.sh aws-full`
  - `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg`
  - `node scripts/validate-readme-architecture-diagrams.mjs`
  - `node scripts/validate-sequence-diagrams.mjs`
  - `node scripts/validate-readme-parity.mjs`
  - `node scripts/validate-readme-language.mjs`
  - `actionlint .github/workflows/Examples.yml`
  - `git diff --check`
- [ ] Record cold/warm module test runtime:
  - cold:
    `./gradlew :aws-ktor-dynamodb:cleanTest :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1 --no-build-cache`
  - warm immediate rerun:
    `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1`
  - escalate if warm runtime exceeds 3 minutes or cold runtime exceeds 6
    minutes.
- [ ] Run Step 6-R 7-Tier implementation review:
  - performance: command counts, list bounds, runtime budget,
  - stability: Testcontainers isolation, timeout/retry bounds, cancellation,
  - security: credential/log redaction, expression injection, JSON/body limit,
  - operator: runbook, readiness, cleanup, CI lane,
  - developer/API: DTO/API shape, build aliases, codebase conventions,
  - user/caller: README parity, examples, diagrams, unsupported scope,
  - main integration: severity normalization and P0/P1 convergence.
- [ ] Save review artifact under
      `docs/review/2026-07-01-issue-325-implementation-review.md`.
- [ ] Record a lesson with context, decision, outcome, verification evidence,
      and future-agent guidance.
- [ ] Commit with Lore protocol.
- [ ] Create PR resolving #325, assign `debop`, and mirror issue milestone and
      labels.
- [ ] Verify live PR metadata with `gh pr view`.
- [ ] Verify live PR body with `gh pr view --json body`; final `##` heading
      must be `## DoD Status`.

## Final Verification Checklist

- [ ] `./gradlew :aws-ktor-dynamodb:compileKotlin --warning-mode all --console=plain`
- [ ] `./gradlew :aws-ktor-dynamodb:compileTestKotlin --warning-mode all --console=plain`
- [ ] `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1`
- [ ] `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1 -Dbluetape4k.aws.emulator=localstack`
- [ ] `./gradlew projects --console=plain`
- [ ] `./scripts/smoke-validate.sh stale-check`
- [ ] `./scripts/smoke-validate.sh aws-full`
- [ ] `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg`
- [ ] `node scripts/validate-readme-architecture-diagrams.mjs`
- [ ] `node scripts/validate-sequence-diagrams.mjs`
- [ ] `node scripts/validate-readme-parity.mjs`
- [ ] `node scripts/validate-readme-language.mjs`
- [ ] `actionlint .github/workflows/Examples.yml`
- [ ] `xmllint --noout docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg`
- [ ] `xmllint --noout docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg`
- [ ] `$bluetape4k-diagram` full checklist plus full-size PNG eye inspection
- [ ] `rg 'AWS_|mode=real|secrets\\.' .github scripts`
- [ ] `git diff --check`

## Step 3-R 7-Tier Plan Review Log

Initial review: REJECT with P0 = 0 and P1 > 0.

| Tier | P0 | P1 themes applied to plan |
| --- | --- | --- |
| Performance | 0 | SDK command-count tests, readiness budget tests, retry/deadline evidence, `64 KiB` body limit, stronger contention shape. |
| Stability | 0 | Task 1.5 compile skeleton, cleanup fixture, cancellation tests, concrete readiness failure tests, lifecycle ownership. |
| Security | 0 | Fail-closed local mode, body/log redaction tests, unexpected failure mapping, auth boundary docs, CI secret guard. |
| Operator | 0 | Executable local runbook, LocalStack verification command, `aws-full` smoke group, CI runtime checkpoint, cleanup commands. |
| Developer/API | 0 | Direct `DynamoDbClient.scan` for one page, explicit validation, internal/KDoc rule, all data classes Serializable, runner rule. |
| User/Caller | 0 | README parity validators, Error Code Matrix, targeted diagram QA command/evidence, concrete curl and unsupported-scope docs. |

Final rerun:

| Tier | Final status | Evidence |
| --- | --- | --- |
| Performance | PASS | Confirmed command-count tests, readiness budget tests, retry/deadline evidence, body limit, contention shape, and runtime evidence are explicit. |
| Stability | PASS | Confirmed Task 1.5 behavioral red-test skeleton, cleanup fixture, cancellation tests, readiness failure tests, timeout/retry proof, and lifecycle ownership. |
| Security | PASS | Confirmed fail-closed local mode, request-size limit, log/response redaction, auth boundary docs, CI real-AWS guard, expression binding, and kotlinx-only JSON. |
| Operator | PASS | Confirmed executable local runbook, LocalStack test command, readiness evidence, exact `aws-full` smoke group, CI runtime checkpoint, and cleanup/cost commands. |
| Developer/API | PASS | Confirmed direct `DynamoDbClient.scan`, explicit validation, internal/KDoc rule, Serializable data classes, cancellation evidence, runner rule, and compile order. |
| User/Caller | PASS | Confirmed README parity/language validators, Error Code Matrix, targeted diagram QA/evidence, curl examples, unsupported scope, and full-size eye inspection gate. |
| Main integration | PASS | `git diff --check -- docs/superpowers/specs/2026-07-01-issue-325-ktor-dynamodb-local-first-design.md docs/superpowers/plans/2026-07-01-issue-325-ktor-dynamodb-local-first-plan.md` passed after spec/plan alignment. |

Step 3-R gate: PASS with P0 = 0 and P1 = 0.
