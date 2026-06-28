# Issue #288 - Image OCR API Workshop Plan

**Date**: 2026-06-29
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/288
**Spec**: `docs/superpowers/specs/2026-06-29-issue-288-image-ocr-api-design.md`
**Module**: `image-processing/ocr-api` -> `:image-processing-ocr-api`
**Status**: Draft for Step 3-R review

---

## 1. Decisions Encoded

- `settings.gradle.kts` already auto-discovers `image-processing/*`; no
  settings edit is expected.
- The module is smoke-safe by default: native OCR is disabled unless explicitly
  opted in.
- The repository dependency authority is `bluetape4k-dependencies`; no
  individual image BOM is added.
- The current OCR result contract is text-only, so response confidence fields
  are nullable and documented.
- Native setup failures are represented as structured `UNAVAILABLE` responses,
  not raw exception payloads.
- Multipart uploads are intentionally loaded in memory for the workshop, guarded
  by `workshop.ocr.max-upload-bytes` and aligned Spring multipart limits.
- Native OCR work is bounded by `workshop.ocr.timeout` and a single-flight
  native execution guard. Native-disabled fallback still validates declared
  media type, decodability, and decoded pixel count before skipping OCR.
- Valid request failures after native OCR starts return structured `FAILED`
  workshop data with `200 OK`; invalid upload/language/decode/dimension failures
  return sanitized `400 Bad Request`.

## 2. Implementation Tasks

### T1 - Catalog and Build Scaffolding

- **Files**:
  - `gradle/libs.versions.toml`
  - `image-processing/ocr-api/build.gradle.kts`
  - `image-processing/ocr-api/src/main/resources/application.yml`
  - `image-processing/ocr-api/src/test/resources/junit-platform.properties`
  - `image-processing/ocr-api/src/test/resources/logback-test.xml`
- **Action**:
  - Add a versionless alias for `bluetape4k-images-ocr`.
  - Add module dependencies on:
    - `implementation(libs.bluetape4k.core)`
    - `implementation(libs.bluetape4k.logging)`
    - `implementation(libs.bluetape4k.jackson3)`
    - `implementation(libs.bluetape4k.images)`
    - `implementation(libs.bluetape4k.images.ocr)`
    - `implementation(libs.kotlinx.coroutines.core.lib)`
    - `implementation(libs.spring.boot.autoconfigure.lib)`
    - `annotationProcessor(libs.spring.boot.autoconfigure.processor)`
    - `annotationProcessor(libs.spring.boot.configuration.processor)`
    - `runtimeOnly(libs.spring.boot.devtools)`
    - `implementation(libs.spring.boot.starter.validation)`
    - `implementation(libs.spring.boot.starter.webmvc.lib)`
  - Add tests dependencies on `project(":shared")`, `bluetape4k-junit5`,
    `bluetape4k-assertions`, MockK, springmockk, coroutine test, Web MVC test,
    and Spring Boot test.
  - Do not add Testcontainers, native-image, persistence, or benchmark
    dependencies.
  - Set `springBoot.mainClass` to the new application class.
  - Pass `ocr.enabled` into tests only as an opt-in system property; default is
    disabled.
  - Configure `application.yml` defaults for `workshop.ocr.max-upload-bytes`,
    `workshop.ocr.max-image-pixels`, and `workshop.ocr.timeout`; align
    `spring.servlet.multipart.max-file-size` and
    `spring.servlet.multipart.max-request-size` with the same byte limit.
- **DoD**:
  - `./gradlew :image-processing-ocr-api:dependencies --configuration testRuntimeClasspath` resolves.
  - No local image BOM or explicit bluetape4k image version appears in the module.
  - No native/package installation dependency is introduced.

### T2 - Failing Service Tests First

- **Files**:
  - `image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImplTest.kt`
- **Action**:
  - Write tests before production implementation:
    - native-disabled valid image returns `UNAVAILABLE`, `engine=disabled`,
      effective languages, empty text/blocks, and a warning that OCR is disabled;
    - fake native engine returns `COMPLETED`, `engine=tesseract`, normalized
      full text, nonblank line-based blocks, effective languages, and nullable
      confidence warning;
    - `OcrConfigurationException` maps to `UNAVAILABLE` with sanitized warning;
    - generic `OcrException` maps to `FAILED` with sanitized warning;
    - invalid language values are rejected;
    - empty image bytes are rejected before engine invocation;
    - oversized image bytes are rejected before engine invocation.
    - corrupt non-empty bytes with an image media type are rejected as sanitized
      bad input before OCR invocation;
    - native-disabled corrupt bytes are also rejected as sanitized bad input;
    - decoded images above `maxImagePixels` are rejected before OCR invocation;
    - fake engine `CancellationException` is rethrown and not mapped to
      `FAILED` or `UNAVAILABLE`;
    - warnings exclude raw native messages, stack traces, tessdata paths, and
      local filesystem paths.
  - Use a tiny in-memory PNG generated through `BufferedImage`/`ImageIO` for
    successful decode tests, not fixture files.
  - Use bluetape4k assertions and MockK where helpful.
  - Avoid native Tesseract, sleeps, repeated tests, and containers.
- **DoD**:
  - Initial `./gradlew :image-processing-ocr-api:test` fails because service and
    models are not implemented.

### T3 - Failing Web Tests First

- **Files**:
  - `image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrControllerTest.kt`
- **Action**:
  - Add `@WebMvcTest(controllers = [ImageOcrController::class])` tests before
    controller implementation:
    - multipart POST returns JSON response from mocked service;
    - repeated `language` parameters reach the service;
    - comma-separated language values, repeated+comma mixed values,
      de-duplication order, empty/default language behavior, and invalid
      language 400 mapping are covered;
    - empty upload maps to 400 via exception handler;
    - non-image content type maps to 400 via exception handler;
    - unsupported image subtype maps to 400;
    - corrupt bytes with a spoofed image content type map to sanitized 400.
  - Implement the controller as a `suspend fun` and use MockMvc multipart
    requests with `request().asyncStarted()` plus `asyncDispatch`, matching
    the existing Spring MVC coroutine test pattern.
  - Use `@MockkBean` for `ImageOcrService`.
- **DoD**:
  - Initial `./gradlew :image-processing-ocr-api:test` still fails for missing
    controller/web classes until implementation is added.

### T4 - Model, Properties, and Service Implementation

- **Files**:
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/config/ImageOcrProperties.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/model/ImageOcrModels.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrService.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImpl.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/NativeOcrEngineConfig.kt`
- **Action**:
  - Implement `@ConfigurationProperties("workshop.ocr")` with defaults:
    - `nativeEnabled=false`
    - `maxUploadBytes=5_242_880`
    - `maxImagePixels=12_000_000`
    - `timeout=10s`
    - `languages=["eng"]`
    - `tessdataPath=null`
  - Add `enum class OcrStatus` plus `ImageOcrRequest`, `ImageOcrResponse`, and
    `OcrTextBlock` data classes. DTO data classes implement `Serializable` and
    define `serialVersionUID`.
  - Add English KDoc for public classes and service methods.
  - Normalize languages by splitting comma-separated values, trimming,
    validating, and de-duplicating in order.
  - Validate image bytes before decoding.
  - Validate bytes, exact declared content type, decoded image bytes, and decoded
    pixel count before deciding fallback/native execution.
  - When native OCR is disabled, return `UNAVAILABLE` after validation without
    invoking `OcrEngine`.
  - When enabled, reuse the decoded image, build `OcrOptions`, and invoke injected
    `OcrEngine` inside a bounded native OCR boundary:
    - a single-flight semaphore around decode plus OCR;
    - `withTimeout(properties.timeout)`;
    - `Dispatchers.IO` for blocking byte/decode/OCR work.
  - Catch `CancellationException` first and rethrow it.
  - Map corrupt or unsupported decoded image failures to sanitized bad input.
  - Map `OcrConfigurationException` to `UNAVAILABLE` and `OcrException` to
    `FAILED` with sanitized warnings.
  - Split recognized text into nonblank line-based blocks.
  - Add nullable confidence warning when no confidence data exists.
  - Configure `TesseractOcrEngine` only when native OCR is enabled.
  - Prove with an application-context or conditional-bean test that the default
    native-disabled context starts without constructing `TesseractOcrEngine`.
  - Add sanitized diagnostic logging with `requestId`, status, engine,
    languages, native-enabled flag, elapsed time, and failure category only.
- **DoD**:
  - Service tests pass.
  - Production code contains no `!!`, `runBlocking`, `runCatching` around
    suspend calls, `Thread.sleep`, or deprecated imports.

### T5 - Web Layer Implementation

- **Files**:
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/ImageOcrApiApplication.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrController.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrExceptionHandler.kt`
- **Action**:
  - Add Spring Boot application with configuration properties scan.
  - Implement `POST /api/images/ocr` multipart endpoint.
  - Accept optional `language` request parameters.
  - Validate non-empty file and `image/*` content type before service call.
  - Return `ImageOcrResponse` with `application/json`.
  - Map `IllegalArgumentException` to RFC 9457 `ProblemDetail` 400.
  - Do not map service `UNAVAILABLE` to HTTP 503; fallback is a structured
    successful teaching response.
  - Map corrupt/undecodable image input and unsupported decoded image types to
    sanitized 400 responses.
- **DoD**:
  - Controller tests pass.
  - `./gradlew :image-processing-ocr-api:test` passes.

### T6 - README, Korean README, and Root Catalog

- **Files**:
  - `image-processing/ocr-api/README.md`
  - `image-processing/ocr-api/README.ko.md`
  - `README.md`
  - `README.ko.md`
- **Action**:
  - Add language switches.
  - Add architecture and sequence diagram image references.
  - Document endpoint, curl examples, response examples, properties, fallback
    behavior, native Tesseract opt-in, and troubleshooting.
  - Include a visible workshop-boundary section: no auth, antivirus scanning,
    persistence, rate limiting, storage policy, queueing, audit workflow,
    PII/document-management guarantees, or production upload hardening.
  - Document nullable confidence explicitly.
  - Add default command `./gradlew :image-processing-ocr-api:test`.
  - Add native opt-in command
    `./gradlew :image-processing-ocr-api:test -Docr.enabled=true`.
  - Add default fallback `bootRun` and native-enabled `bootRun` examples with
    matching curl requests and expected `UNAVAILABLE`/`COMPLETED` or documented
    `UNAVAILABLE` outcomes.
  - Add macOS and Linux Tesseract install snippets, tessdata/language-pack
    checks, and `workshop.ocr.tessdata-path` diagnosis guidance.
  - State that default tests never prove real Tesseract success; real native
    validation is a manual opt-in runbook path unless the local prerequisites
    are installed.
  - Add warning that OCR text may contain sensitive data and this workshop does
    not log OCR text.
  - Update root image-processing catalog rows and project structure in both
    locales.
  - Keep README.md English and README.ko.md source-equivalent natural Korean.
  - Run a `$bluetape4k-blog`-style manual parity/naturalness check over Korean
    README sections covering native opt-in, warnings, troubleshooting, and both
    JSON examples.
- **DoD**:
  - English and Korean README have matching sections, commands, image links,
    warning callouts, and examples.
  - `node scripts/validate-readme-parity.mjs` and
    `node scripts/validate-readme-language.mjs` pass.

### T7 - README Diagrams

- **Files**:
  - `docs/images/readme-diagrams/image-ocr-api-readme-architecture-01.svg`
  - `docs/images/readme-diagrams/image-ocr-api-readme-architecture-01.png`
  - `docs/images/readme-diagrams/image-ocr-api-readme-sequence-01.svg`
  - `docs/images/readme-diagrams/image-ocr-api-readme-sequence-01.png`
- **Action**:
  - Create English-label SVG assets using `Architects Daughter` for headings and
    `Comic Mono` for detail text.
  - Make the architecture diagram flow top-to-bottom.
  - Show disabled short-circuit, native-enabled OCR path, bounded native
    execution, sanitized error mapping, and README-visible warnings.
  - Render PNGs with CairoSVG.
  - Inspect rendered PNGs before continuing.
- **DoD**:
  - `node scripts/validate-readme-architecture-diagrams.mjs` passes.
  - `node scripts/validate-sequence-diagrams.mjs` passes.
  - Full-size PNG visual inspection shows no overlap or unreadable labels.

### T8 - Smoke, Examples, and Registration Validation

- **Files**:
  - `scripts/smoke-validate.sh`
  - `.github/workflows/Examples.yml`
  - `.github/workflows/nightly.yml` read-only evidence
- **Action**:
  - Add `:image-processing-ocr-api:test` to `all-smoke`.
  - Update stale-check expected project count from `80` to `81`.
  - Add `image-processing/ocr-api/**` to Examples push and pull request paths.
  - Add `:image-processing-ocr-api:test` to the existing H2/default Examples
    smoke Gradle command.
  - Keep `.github/workflows/Examples.yml` `smoke-examples.timeout-minutes: 25`
    unchanged.
  - Add image OCR test result artifact paths under the existing
    `smoke-example-test-results` upload block.
- **DoD**:
  - `./scripts/smoke-validate.sh all-smoke` passes.
  - `./gradlew projects --console=plain | rg "Project ':image-processing-ocr-api'"` proves auto registration.
  - `./scripts/smoke-validate.sh stale-check` output includes
    `Active modules: 81 (expected: 81)`, `No stale refs found.`, and
    `No broken image links found.`; any `WARNING:` is a failure for this issue.
  - `actionlint .github/workflows/Examples.yml` passes.
  - `test -z "$(rg -n "\\\\'" .github/workflows || true)"` passes.
  - Workflow diff shows no timeout increase.
  - `rg -n "smoke-validate.sh all-smoke" .github/workflows/nightly.yml` proves
    Nightly reaches the new module through `all-smoke`.

### T9 - Final Verification and Review Artifacts

- **Files**:
  - `docs/review/2026-06-29-issue-288-image-ocr-api-code-review.md`
  - `docs/lessons/2026-06-29-issue-288-image-ocr-api.md`
- **Action**:
  - Run targeted module tests, README validators, diagram validators, workflow
    lint, and `git diff --check`.
  - Reuse the T8 `all-smoke` evidence unless source, build, smoke script, or
    workflow files change after that run.
  - Record Step 6-R review evidence and lessons.
  - Record rollback/runbook evidence:
    - no runtime/data migration;
    - rollback removes `image-processing/ocr-api/`;
    - rollback removes unused `bluetape4k-images-ocr` alias;
    - rollback removes smoke script and Examples workflow entries;
    - rollback restores stale-check count;
    - rollback removes root README entries and diagram assets.
  - Record contributor diagnostics locations:
    - `image-processing/ocr-api/build/reports/tests/test/index.html`;
    - `image-processing/ocr-api/build/test-results/test/*.xml`;
    - GitHub `smoke-example-test-results` artifact paths.
- **PR readiness**:
  - `gh issue view 288 --json assignees,labels,milestone` confirms live issue metadata.
  - PR body includes `Closes #288`.
  - `gh pr view <pr> --json assignees,labels,milestone,body` proves assignee,
    label, milestone, and body parity before reporting completion.
- **DoD**:
  - P0/P1 findings are zero after final review.
  - Commit uses Lore protocol.
  - PR mirrors issue assignee, milestone, and labels.

## 3. Verification Command Set

```bash
./gradlew :image-processing-ocr-api:test
./gradlew :image-processing-ocr-api:compileKotlin :image-processing-ocr-api:compileTestKotlin --warning-mode all
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
actionlint .github/workflows/Examples.yml
test -z "$(rg -n "\\\\'" .github/workflows || true)"
git diff --check
```

## 4. Risks

- `all-smoke` may expose unrelated existing failures; if so, rerun the focused
  module test and record the unrelated failure with evidence.
- Native OCR can behave differently across local machines because trained data
  differs; default tests must stay fake-engine or fallback based.
- Service response can look like a production API, but this workshop does not
  add auth, storage, rate limiting, antivirus scanning, or upload persistence.
  README must state the example boundary clearly.
- Diagram validators may reject new assets if geometry or metadata does not
  match local conventions; build diagrams against local validators and inspect
  PNGs.
