# AWS S3 Vectors and Access Grants Workshop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local-first Spring Boot AWS workshop example that teaches S3
Vectors search intent and S3 Access Grants authorization boundaries without
requiring live AWS resources in default tests.

**Spec:** `docs/superpowers/specs/2026-06-30-issue-318-s3-vectors-access-grants-design.md`

**Architecture:** The module owns a small document search application service.
It consumes `S3VectorsOperations` and `S3AccessGrantsOperations`, but default
runtime/test wiring uses deterministic local fake beans. Optional real AWS usage
is documented as a manual profile and stays outside CI.

**Tech Stack:** Kotlin, Spring Boot 4 MVC/Validation/Actuator, bluetape4k-aws,
AWS SDK v2 `s3vectors` and `s3control`, coroutines, JUnit 5, MockK,
bluetape4k assertions, CairoSVG-rendered README diagrams.

---

## File Structure

- Create `aws/s3-vectors-access-grants/build.gradle.kts`
- Create `aws/s3-vectors-access-grants/src/main/kotlin/io/bluetape4k/workshop/aws/s3vectorsaccess/*`
- Create `aws/s3-vectors-access-grants/src/main/resources/application.yml`
- Create `aws/s3-vectors-access-grants/src/test/kotlin/io/bluetape4k/workshop/aws/s3vectorsaccess/*`
- Create `aws/s3-vectors-access-grants/src/test/resources/junit-platform.properties`
- Create `aws/s3-vectors-access-grants/src/test/resources/logback-test.xml`
- Create `aws/s3-vectors-access-grants/README.md`
- Create `aws/s3-vectors-access-grants/README.ko.md`
- Modify `gradle/libs.versions.toml`
- Modify `aws/README.md`
- Modify `aws/README.ko.md`
- Modify root `README.md`
- Modify root `README.ko.md`
- Modify `.github/workflows/Examples.yml`
- Modify `scripts/smoke-validate.sh`
- Create `docs/images/readme-diagrams/aws-s3-vectors-access-grants-readme-architecture-01.svg/png`
- Create `docs/images/readme-diagrams/aws-s3-vectors-access-grants-readme-sequence-01.svg/png`
- Create `docs/review/2026-06-30-issue-318-implementation-review.md`
- Create `docs/lessons/2026-06-30-issue-318-s3-vectors-access-grants.md`

## Dependency and API Guard

- [ ] Add `aws2-s3vectors-lib = { module = "software.amazon.awssdk:s3vectors" }`.
- [ ] Add `aws2-s3control-lib = { module = "software.amazon.awssdk:s3control" }`.
- [ ] Keep both aliases on the existing AWS SDK BOM/version line; do not add a
      separate version.
- [ ] Use `libs.bluetape4k.aws`, `libs.aws2.s3vectors.lib`, and
      `libs.aws2.s3control.lib` in the new module.
- [ ] Verify compile against resolved classes:
      `S3VectorsOperations`, `S3AccessGrantsOperations`,
      `PutVectorsRequest`, `QueryVectorsRequest`,
      `ListCallerAccessGrantsRequest`, and `GetDataAccessRequest`.

## Task 1: Module Skeleton

**Complexity:** medium

**Files:**
- Create `aws/s3-vectors-access-grants/build.gradle.kts`
- Create `aws/s3-vectors-access-grants/src/main/resources/application.yml`
- Create test resource files under `aws/s3-vectors-access-grants/src/test/resources/`

- [ ] Create the Gradle build with Spring Boot MVC, validation, actuator,
      configuration processor, coroutines, `bluetape4k-aws`, `s3vectors`,
      `s3control`, Spring Boot test, MockK, and bluetape4k assertions.
- [ ] Add `application.yml` defaults:
      `bluetape4k.workshop.aws.s3-vectors-access.vector-bucket-name`,
      `index-name`, `account-id`, `access-grants-location-arn`,
      `document-prefix`, `local-mode=true`, and safe max sizes.
- [ ] Add `junit-platform.properties` and `logback-test.xml` consistent with
      neighboring modules.
- [ ] Run `./gradlew projects --console=plain` and verify
      `:aws-s3-vectors-access-grants`.
- [ ] Run `./gradlew :aws-s3-vectors-access-grants:compileKotlin --warning-mode all --console=plain`.

## Task 2: TDD Red Tests

**Complexity:** high

**Files:**
- Create service and controller tests under `aws/s3-vectors-access-grants/src/test/kotlin/io/bluetape4k/workshop/aws/s3vectorsaccess/`

- [ ] Add a failing test that document upsert builds the expected
      `PutVectorsRequest` bucket/index/vector key and returns a redacted report.
- [ ] Add a failing test that semantic query builds the expected
      `QueryVectorsRequest`, maps matches, and does not imply S3 object access.
- [ ] Add a failing test that selected-match retrieval is gated by a successful
      Access Grants decision before a document URI is returned.
- [ ] Add a failing test that caller grant listing builds
      `ListCallerAccessGrantsRequest` with account and target scope.
- [ ] Add a failing test that scoped data access builds `GetDataAccessRequest`
      with `Permission.READ`.
- [ ] Add a failing test that denied grant status skips `getDataAccess`.
- [ ] Add failing tests for S3 Vectors and Access Grants partial failures.
- [ ] Add a failing test proving credential fields are not present in reports
      or serialized JSON.
- [ ] Add a failing test proving credential-like field names and values are not
      written to captured application logs.
- [ ] Add failing tests proving `CancellationException` is rethrown from
      suspend service paths.
- [ ] Add controller tests for success, validation error, denied grant, and
      local profile wiring.
- [ ] Run `./gradlew :aws-s3-vectors-access-grants:test --warning-mode all --console=plain`
      and record expected red failures before implementation.

## Task 3: Domain Models and Properties

**Complexity:** medium

**Files:**
- Create `DocumentModels.kt`
- Create `AwsS3VectorsAccessProperties.kt`
- Create `DocumentAccessPolicy.kt`

- [ ] Add serializable data classes: `DocumentVectorRequest`,
      `DocumentSearchRequest`, `DocumentSearchReport`, `AccessGrantReport`,
      `VectorSearchMatch`, `OperationStatus`, and `DocumentAccessDecision`.
- [ ] Add `serialVersionUID` to every data class.
- [ ] Validate caller strings and collections with bluetape4k `require*`
      helpers.
- [ ] Model redacted access explicitly with `credentialsRedacted: Boolean`.
- [ ] Add properties with defaults and validation for vector bucket/index names,
      AWS account id, Access Grants location ARN, document prefix, max document
      id length, max metadata length, max vector dimensions, and local mode.
- [ ] Add `DocumentAccessPolicy` that accepts only configured document IDs and
      returns stable S3 URI + `Permission.READ`.

## Task 4: Local Fake Operation Beans

**Complexity:** medium

**Files:**
- Create `LocalS3VectorsAccessConfig.kt`
- Create `LocalS3VectorsOperations.kt`
- Create `LocalS3AccessGrantsOperations.kt`

- [ ] Provide `S3VectorsOperations` fake that captures vector upsert/query
      intent and returns deterministic matches.
- [ ] Provide `S3AccessGrantsOperations` fake that lists caller grants and can
      return allowed/denied scoped access without credential material.
- [ ] Keep captured state package-private/test-visible and avoid public
      production API expansion.
- [ ] Ensure local beans do not create AWS SDK clients or credentials providers.
- [ ] Guard optional real-AWS profile wiring behind explicit profile/property
      checks if added.

## Task 5: Service and HTTP Boundary

**Complexity:** high

**Files:**
- Create `DocumentSearchService.kt`
- Create `DocumentSearchController.kt`
- Create `S3VectorsAccessGrantsApplication.kt`

- [ ] Implement suspend service methods for upsert, query, grant listing, and
      scoped read-access intent.
- [ ] Implement selected-match retrieval as a separate service path that returns
      the document URI only after the Access Grants decision is allowed.
- [ ] Rethrow `CancellationException` before broad exception handling.
- [ ] Map AWS SDK request/response models to learner-friendly reports.
- [ ] Redact all access credential material from reports and logs.
- [ ] Add `POST /api/aws/s3-vectors/documents`.
- [ ] Add `POST /api/aws/s3-vectors/query`.
- [ ] Add `GET /api/aws/access-grants`.
- [ ] Add `POST /api/aws/access-grants/data-access`.
- [ ] Run `./gradlew :aws-s3-vectors-access-grants:test --warning-mode all --console=plain`.

## Task 6: README and Diagrams

**Complexity:** high

**Files:**
- Create `aws/s3-vectors-access-grants/README.md`
- Create `aws/s3-vectors-access-grants/README.ko.md`
- Modify root and AWS README locale pairs
- Create SVG/PNG diagrams under `docs/images/readme-diagrams/`

- [ ] Write English README with overview, run commands, endpoint examples,
      fake-local mode, optional real-AWS prerequisites, IAM/cost cleanup notes,
      and difference from existing S3 examples.
- [ ] Write source-equivalent Korean README with natural Korean technical prose.
- [ ] Verify README examples and diagrams do not include temporary access key,
      secret key, session token, credential JSON, or credential field names.
- [ ] Update AWS README locale tables and root README locale module tables.
- [ ] Create layered architecture diagram using current best-practices
      architecture family and official AWS icons only for real AWS services.
- [ ] Create best-practices sequence diagram with numbered labels,
      transparent `alt`/`else` bodies, branch-specific muted colors,
      activation bars, and color-matched arrowheads.
- [ ] Render each SVG with `~/.local/bin/cairosvg <svg> -o <png> -s 2`.
- [ ] Run `xmllint --noout` on new SVGs.
- [ ] Run `$bluetape4k-diagram` geometry, endpoint, mixed-corner, connector,
      sequence-style, marker-color, label-over-line, legend, icon, and visual
      checks as applicable.
- [ ] Open every touched PNG at full size for eye inspection.

## Task 7: CI and Smoke Registration

**Complexity:** medium

**Files:**
- Modify `.github/workflows/Examples.yml`
- Modify `scripts/smoke-validate.sh`

- [ ] Add path filters for `aws/s3-vectors-access-grants/**` in push and PR.
- [ ] Add `:aws-s3-vectors-access-grants:test` to credential-free AWS/example
      smoke coverage.
- [ ] Add test result artifact paths for the new module.
- [ ] Add the module to `scripts/smoke-validate.sh all-smoke` and the relevant
      AWS/storage group.
- [ ] Adjust stale-check expected project count after confirming
      `./gradlew projects`.
- [ ] Run `actionlint .github/workflows/Examples.yml`.
- [ ] Run `./scripts/smoke-validate.sh stale-check`.
- [ ] Run the edited smoke lane command or script group that includes the new
      module, and record evidence that `:aws-s3-vectors-access-grants:test` is
      included in the same path used by CI/Nightly smoke validation.

## Task 8: Verification and Review

**Complexity:** high

**Files:**
- Create `docs/review/2026-06-30-issue-318-implementation-review.md`
- Create `docs/lessons/2026-06-30-issue-318-s3-vectors-access-grants.md`

- [ ] Run `./gradlew :aws-s3-vectors-access-grants:compileKotlin --warning-mode all --console=plain`.
- [ ] Run `./gradlew :aws-s3-vectors-access-grants:compileTestKotlin --warning-mode all --console=plain`.
- [ ] Run `./gradlew :aws-s3-vectors-access-grants:test --warning-mode all --console=plain`.
- [ ] Run `./gradlew projects --console=plain`.
- [ ] Run `node scripts/validate-readme-language.mjs`.
- [ ] Run `node scripts/validate-readme-parity.mjs`.
- [ ] Run `node scripts/validate-readme-architecture-diagrams.mjs`.
- [ ] Run `node scripts/validate-sequence-diagrams.mjs`.
- [ ] Run `actionlint .github/workflows/Examples.yml`.
- [ ] Run `./scripts/smoke-validate.sh stale-check`.
- [ ] Run the targeted smoke-validation group that contains
      `:aws-s3-vectors-access-grants:test`.
- [ ] Run `rg -n "accessKey|secretKey|sessionToken|credentials|AccessKeyId|SecretAccessKey|SessionToken" aws/s3-vectors-access-grants README.md README.ko.md docs/images/readme-diagrams` and verify no credential material is documented or diagrammed.
- [ ] Run `git diff --check`.
- [ ] Run Step 6-R seven-tier implementation review and record P0=0/P1=0.
- [ ] Add and commit lessons before PR creation.

## Task 9: PR and CI

**Complexity:** medium

**Files:**
- PR body generated from `bluetape4k-workflow/templates/pr-body-step-dod.md`

- [ ] Commit with Lore protocol trailers.
- [ ] Push `feat/issue-318-s3-vectors-access-grants`.
- [ ] Create PR against `develop`, assigned to `debop`.
- [ ] Copy issue #318 milestone and labels to the PR.
- [ ] Ensure the final PR body section is `## DoD Status`.
- [ ] Verify live PR metadata with
      `gh pr view <number> --json assignees,labels,milestone,body`.
- [ ] Monitor required checks and fix failures before asking for merge.

## Self-Review

- Spec coverage: Tasks 1-9 map to every #318 acceptance criterion.
- API evidence: plan uses actual `bluetape4k-aws` facade names and AWS SDK v2
  service packages.
- Ordering: dependency/API guard and TDD red tests precede implementation.
- Default test boundary: no live AWS, credentials, LocalStack, or
  Testcontainers required.
- Documentation: module README pair, AWS README pair, root README pair, and
  diagram assets are explicit.
- CI/module coverage: `./gradlew projects`, Examples workflow, smoke script,
  and stale-check are explicit.
- Security: Access Grants credentials are redacted and destructive S3 Control
  operations stay out of scope.
