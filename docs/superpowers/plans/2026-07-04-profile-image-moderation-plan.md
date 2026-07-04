# Profile Image Moderation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a new `:image-processing-profile-image-moderation` workshop module that demonstrates upload → private storage → pending blurred profile image → asynchronous moderation → approved public image or default fallback.

**Architecture:** The module is a standalone Spring Boot MVC example under `image-processing/profile-image-moderation`. It uses local deterministic components by default: `ImageStorage` local backend, ImageIO-based JPEG derivative generation for CI safety, an in-memory repository, and a bounded coroutine moderation runner. Public URL resolution explicitly denies private/original keys and exposes only pending, approved, and default URLs.

**Tech Stack:** Kotlin 2.4, Java 21, Spring Boot 4 MVC, bluetape4k core/coroutines/logging/assertions/junit5/images-spring-boot, Micrometer, ImageIO, Gradle multi-module registration.

---

## File Structure

Create:

- `image-processing/profile-image-moderation/build.gradle.kts` — module dependencies and Spring Boot main class.
- `image-processing/profile-image-moderation/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/profile/ProfileImageModerationApplication.kt` — application entrypoint.
- `.../config/ProfileImageModerationProperties.kt` — validated configuration defaults.
- `.../model/ProfileImageModels.kt` — `ProfileImageStatus`, `ModerationDecision`, DTOs, `Serializable` data classes.
- `.../service/ProfileImageKeyFactory.kt` — user id validation, filename sanitizing, `ImageObjectKey` generation.
- `.../service/ProfileImageUrlResolver.kt` — public URL resolver; rejects private keys and unsafe base URLs.
- `.../service/UploadImageValidator.kt` — byte, MIME, magic-byte, dimension guards.
- `.../service/ProfileImageProcessor.kt` — ImageIO dimension read, pending blur and approved JPEG derivative bytes.
- `.../service/ProfileImageRepository.kt` — in-memory repository with `userId + uploadId` compare-and-set completion.
- `.../service/ImageModerationProvider.kt` — fake moderation provider with configurable delay and filename marker.
- `.../service/ProfileImageModerationRunner.kt` — bounded application-scope coroutine runner.
- `.../service/ProfileImageMetrics.kt` — low-cardinality Micrometer names/tags and helper methods.
- `.../service/ProfileImageService.kt` — upload orchestration, storage, metrics, cleanup, response assembly.
- `.../web/PublicProfileImageController.kt` — local public object serving for pending/default/approved paths, private-prefix denial, and pending cache headers.
- `.../web/ProfileImageController.kt` — `POST` and `GET` APIs.
- `.../web/ProfileImageExceptionHandler.kt` — stable ProblemDetail mapping.
- `image-processing/profile-image-moderation/src/main/resources/application.yml`.
- `image-processing/profile-image-moderation/src/test/resources/junit-platform.properties`.
- `image-processing/profile-image-moderation/src/test/resources/logback-test.xml`.
- Unit/controller tests under matching packages.
- `image-processing/profile-image-moderation/README.md` and `README.ko.md`.

Modify:

- `README.md`, `README.ko.md` — add advanced example row and command.
- `AGENTS.md` — add `image-processing/` module group if missing.
- `scripts/smoke-validate.sh` — include the new module in `all-smoke`.
- `.github/workflows/Examples.yml` — add path filters, test command, and artifact paths.
- `docs/coverage-matrix.md` — add the new module to the repository validation matrix.

## Task 1: Module skeleton and RED tests

**Files:** create module, resources, and initial tests.

- [ ] Create module directories.
- [ ] Create `build.gradle.kts` with explicit dependencies: `bluetape4k.core`, `bluetape4k.coroutines`, `bluetape4k.idgenerators`, `bluetape4k.logging`, `bluetape4k.micrometer`, `bluetape4k.images`, `bluetape4k.images.spring.boot`, `kotlinx.coroutines.core.lib`, `micrometer.core`, Spring Boot autoconfigure/configuration processor/actuator/validation/webmvc, `bluetape4k.assertions`, `bluetape4k.junit5`, `kotlinx.coroutines.test.lib`, `spring.boot.starter.webmvc.test`, and `spring.boot.starter.test` with Mockito/JUnit vintage exclusions.
- [ ] Write failing service tests in `ProfileImageServiceTest.kt` for:
  - pending upload returns `PENDING_MODERATION` and `uploadId` before moderation completion;
  - approved completion switches effective URL;
  - rejected completion switches to default URL;
  - stale completion from older upload is ignored;
  - timeout/failure becomes `MODERATION_FAILED`.
- [ ] Run `./gradlew :image-processing-profile-image-moderation:test --tests '*ProfileImageServiceTest'`.
- [ ] Expected: FAIL from unresolved new service/model classes after Gradle recognizes the auto-registered module; `project not found` is not an acceptable RED result.

## Task 2: Core model/config/key/url implementation

**Files:** `ProfileImageModels.kt`, `ProfileImageModerationProperties.kt`, `ProfileImageKeyFactory.kt`, `ProfileImageUrlResolver.kt`.

- [ ] Add `Serializable` to every new Kotlin `data class` with `serialVersionUID`, including DTO/state/value/config data classes; avoid `data class` when not appropriate.
- [ ] Add `ProfileImageStatus.NO_IMAGE`, `PENDING_MODERATION`, `APPROVED`, `REJECTED`, `MODERATION_FAILED`.
- [ ] Validate `userId` with `[A-Za-z0-9._-]{1,80}` and reject invalid values.
- [ ] Generate high-entropy upload ids with `Base58.randomString(16)`.
- [ ] Reject private keys in URL resolver.
- [ ] Add config tests for invalid concurrency/timeouts/base URLs and `allow-local-storage-remote-public-base-url=false` behavior.
- [ ] Use bluetape4k `require*` helpers for caller input validation; do not use `check` for user input. Test invalid userId/base URL/content constraints map to stable ProblemDetail.
- [ ] Run targeted config/key/url tests.

## Task 3: Validator, processor, repository, and moderation runner

**Files:** service package.

- [ ] Implement upload validation: JPEG/PNG/WebP allowlist, magic bytes, max bytes, dimensions, max pixels.
- [ ] Implement ImageIO JPEG derivative generation: strongly downscaled blurred pending image and approved derivative. Assert content type/signature matches `.jpg` URL extension and EXIF/GPS metadata is not propagated.
- [ ] Implement in-memory repository with compare-and-set `completeModeration(userId, uploadId, decision)`.
- [ ] Implement fake moderation provider with delay and filename marker.
- [ ] Implement bounded `ProfileImageModerationRunner`; no `GlobalScope`, rethrow `CancellationException`, use controllable fake provider/channel in tests, and add `@PreDestroy` or `SmartLifecycle` shutdown that cancels outstanding jobs and releases permits.
- [ ] Add deterministic tests for moderation concurrency limit, processing timeout, request concurrency gate, stale completion, shutdown cancellation, and no real 1-second sleeps. Use `SuspendedJobTester` only if adding stress-style concurrency; otherwise record deterministic ordering rationale.
- [ ] Run service tests and repository tests.
- [ ] Add storage failure tests: original-written-then-pending/approved upload fails, cleanup is attempted, cleanup failure is logged/metriced, and no partial effective image is published.

## Task 4: Storage orchestration, controller, and errors

**Files:** `ProfileImageService.kt`, web package.

- [ ] Store original private key, pending blurred key, and approved key through `ImageStorage`.
- [ ] Return HTTP `202 Accepted` for upload before moderation completes.
- [ ] Implement `GET /api/users/{userId}/profile-image` for no-image/pending/approved/rejected/failed.
- [ ] Map invalid input to 400, oversized upload to 413 where applicable, storage/unexpected failure to stable ProblemDetail.
- [ ] Implement `PublicProfileImageController` or equivalent resource handler for pending/default/approved fetches.
- [ ] Add controller tests for pending upload, approved/rejected/failed lookup, no-upload, invalid upload, public pending/default/approved fetches, pending `Cache-Control: no-store`, private `/public-images/**/private/**` 404, and private URL resolver denial.
- [ ] Run controller tests.

## Task 5: Docs and registration

**Files:** README pair, root README pair, AGENTS, smoke script, Examples workflow.

- [ ] Write module README.md in English and README.ko.md in Korean with source-equivalent content.
- [ ] Include scenario, status transitions, curl examples, pending/approved/rejected/failed/no-upload/invalid JSON, polling timing, ProblemDetail fields, privacy warning, fake vs Rekognition adapter note, health/metrics checks, local cleanup, default image provisioning, and S3/CDN private-prefix warning.
- [ ] Add generated PNG/SVG README diagrams named `profile-image-moderation-readme-architecture-01.{png,svg}` and `profile-image-moderation-readme-sequence-01.{png,svg}` under the repo README diagram asset path, then validate README links.
- [ ] Update root README.md and README.ko.md advanced examples table and command list; verify bilingual section order, language switch, links, and JSON field parity.
- [ ] Update AGENTS module table to include `image-processing/` if absent.
- [ ] Update `settings.gradle.kts` only if auto-registration is insufficient; otherwise record that `includeModules("image-processing", false, true)` already covers the module.
- [ ] Update `scripts/smoke-validate.sh all-smoke`.
- [ ] Update `.github/workflows/Examples.yml` path filters, test command, artifact paths, and summary/coverage references for the new module.
- [ ] Verify `.github/workflows/nightly.yml` still covers the module through `./scripts/smoke-validate.sh all-smoke`; edit only if script coverage is insufficient.
- [ ] Update `docs/coverage-matrix.md` with the new module.
- [ ] Run `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml` and `rg "\\'" .github/workflows` after workflow edits.

## Task 6: Verification and review prep

- [ ] Run `./gradlew projects` and verify `:image-processing-profile-image-moderation` is listed.
- [ ] Run `./gradlew :image-processing-profile-image-moderation:test --console=plain`.
- [ ] Run `./scripts/smoke-validate.sh stale-check`.
- [ ] Run `./scripts/smoke-validate.sh all-smoke`; defer only for a concrete local blocker and record the blocker.
- [ ] Smoke-check README/runbook commands that are cheap locally: generated sample-image upload path through controller tests, default image fetch, private-prefix denial, actuator health, and metrics endpoint assertions.
- [ ] Run `git diff --check`.
- [ ] Run `rg "image-processing-profile-image-moderation|profile-image-moderation|:image-processing-profile-image-moderation:test" .github scripts README.md README.ko.md AGENTS.md settings.gradle.kts`.
- [ ] Create `docs/lessons/2026-07-04-profile-image-moderation.md` with concise context/decision/outcome/verification, or record an explicit no-lesson rationale before PR.
- [ ] Prepare Step 6-R 7-tier review evidence with P0/P1=0.

## Self-Review

- Spec coverage: plan covers module, upload/pending/approval/rejection/failure/no-upload/stale completion, private boundary, observability, docs, CI/smoke registration.
- Placeholder scan: no TBD/TODO placeholders intentionally left.
- Type consistency: use `ProfileImageStatus`, `ModerationDecision`, `ProfileImageView`, and `uploadId` consistently.
