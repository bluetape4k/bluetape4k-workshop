# Issue #288 - Image OCR API Workshop Design

**Date**: 2026-06-29
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/288
**Milestone**: 1.2.0
**Status**: Ready for implementation planning

---

## 1. Goal

Add an `image-processing/ocr-api` workshop module that teaches how to expose
`bluetape4k-image` OCR through a Spring Boot 4 multipart API without making
native Tesseract binaries mandatory for the default smoke path.

The module should show a learner how to:

- upload a JPEG, PNG, or WebP image to `POST /api/images/ocr`;
- validate multipart metadata, decoded image content, dimensions, and language
  options;
- decode image bytes with `immutableImageOf`;
- call the `bluetape4k-images-ocr` `OcrEngine` when native OCR is explicitly enabled;
- return a structured OCR response with language, nullable confidence, text
  blocks, and warnings;
- expose a deterministic fallback response when native OCR is disabled or
  unavailable;
- opt into native Tesseract execution locally without adding it to fast CI smoke.

## 2. Source Evidence

| Source | Evidence |
|--------|----------|
| GitHub issue #288 | Requires a runnable OCR API workshop, structured output, fallback when native OCR is unavailable, deterministic smoke tests, README/README.ko prerequisites, and no individual image BOM imports. |
| `settings.gradle.kts` | `includeModules("image-processing", false, true)` automatically registers `image-processing/*` modules as Gradle projects. `image-processing/ocr-api` maps to `:image-processing-ocr-api`. |
| `image-processing/advanced-workflow` | Provides Spring Boot 4 image-processing module shape, `@WebMvcTest` async-dispatch testing pattern, README locale parity, and image package prefix conventions. |
| `bluetape4k-image/images` | `immutableImageOf(bytes: ByteArray)` decodes multipart bytes into `ImmutableImage` through Scrimage. |
| `bluetape4k-image/images-ocr` | `OcrEngine.recognize(image, options)` returns `OcrResult(text, options)`. `OcrConfigurationException` represents missing native library, tessdata, or language-pack setup. |
| `images-ocr/README.md` | Native OCR tests are opt-in with `-Docr.enabled=true`; default tests avoid requiring system Tesseract. |
| Workshop repo rules | New examples need README locale parity, generated PNG/SVG diagrams, validation matrix updates, and CI/smoke coverage when the module is smoke-safe. |

## 3. Non-Goals

- Do not rewrite `image-processing/advanced-workflow`.
- Do not copy the upstream `bluetape4k-image` OCR example wholesale.
- Do not add Tesseract, tessdata, Docker OCR containers, or native package
  installation to the default CI smoke path.
- Do not add persistence, S3 storage, queues, retries, or derivative generation.
- Do not claim word-level or block-level confidence from the current
  `OcrResult`; it provides text and effective options only.
- Do not return raw native exception messages or stack traces in API responses.
- Do not add an individual `bluetape4k-image` BOM or explicit bluetape4k image
  version.

## 4. Options

### Option A - Native-only OCR endpoint

Create a Spring Boot endpoint that always constructs `TesseractOcrEngine` and
fails when Tesseract or tessdata is missing.

**Rejected**: #288 requires deterministic smoke tests without system OCR
binaries and explicit fallback behavior when native OCR is unavailable.

### Option B - Smoke-safe OCR gateway with opt-in native engine

Create `image-processing/ocr-api` with a small service boundary. The service
returns a deterministic `UNAVAILABLE` response when native OCR is disabled, and
uses `TesseractOcrEngine` only when `workshop.ocr.native-enabled=true` or
`-Docr.enabled=true` is set. Tests inject a fake `OcrEngine` for completed OCR
paths.

**Adopted**: This satisfies the issue, keeps fast CI stable, and still teaches
the actual bluetape4k OCR API boundary.

### Option C - Fixture-only CLI/service example

Skip multipart HTTP and demonstrate OCR through a fixture-driven service test.

**Rejected**: #288 explicitly asks for an OCR API workshop example and Spring
Boot 4 endpoint coverage.

## 5. Proposed Module

```
image-processing/ocr-api/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/
    ImageOcrApiApplication.kt
    config/ImageOcrProperties.kt
    model/ImageOcrModels.kt
    service/ImageOcrService.kt
    service/ImageOcrServiceImpl.kt
    service/NativeOcrEngineConfig.kt
    web/ImageOcrController.kt
    web/ImageOcrExceptionHandler.kt
  src/main/resources/application.yml
  src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/
    service/ImageOcrServiceImplTest.kt
    web/ImageOcrControllerTest.kt
  src/test/resources/junit-platform.properties
  src/test/resources/logback-test.xml

docs/images/readme-diagrams/
  image-ocr-api-readme-architecture-01.svg
  image-ocr-api-readme-architecture-01.png
  image-ocr-api-readme-sequence-01.svg
  image-ocr-api-readme-sequence-01.png
```

README image links must use `../../docs/images/readme-diagrams/...`.

### Runtime dependencies

Use the workshop dependency BOM only through the existing repository convention.
Add a versionless local catalog alias when missing:

- `bluetape4k-images-ocr`

The example module should depend on:

- `bluetape4k-core`
- `bluetape4k-logging`
- `bluetape4k-jackson3`
- `bluetape4k-images`
- `bluetape4k-images-ocr`
- Kotlin coroutines core
- Spring Boot autoconfigure, configuration processor, validation, and Web MVC
- `testImplementation(project(":shared"))`
- `bluetape4k-junit5`, `bluetape4k-assertions`, MockK, springmockk, coroutine
  test, Spring Boot Web MVC test, and Spring Boot test

Do not add native-image, Testcontainers, persistence, or benchmarking
dependencies.

## 6. API Contract

### Endpoint

`POST /api/images/ocr`

- consumes `multipart/form-data`
- accepts `file` as the required multipart image
- accepts optional repeated or comma-separated `language` values, defaulting to
  `eng`
- returns `200 OK` for completed OCR and fallback/unavailable OCR responses
- returns `400 Bad Request` for empty files, unsupported content types, invalid
  language values, undecodable image bytes, unsupported image subtypes, decoded
  pixel-limit violations, or files above the configured byte limit

`FAILED` is reserved for sanitized OCR runtime failures after a valid image has
entered the native-enabled service path. It is returned as structured `200 OK`
workshop data so learners can inspect the response contract without exception
handling; invalid requests stay `400 Bad Request`.

### Request validation

- `file` must not be empty.
- `file.contentType` must start with `image/`.
- `file.size` must be less than or equal to
  `workshop.ocr.max-upload-bytes`.
- Languages must be nonblank ASCII identifiers matching
  `[A-Za-z][A-Za-z0-9_+-]*`.
- The effective language list must be de-duplicated while preserving order.

### Response model

`ImageOcrResponse`:

- `requestId`: generated stable response ID for tracing examples
- `status`: `COMPLETED`, `UNAVAILABLE`, or `FAILED`
- `engine`: `tesseract` or `disabled`
- `languages`: effective language list
- `confidence`: nullable `Double`; `null` for the current text-only engine
  contract
- `text`: full normalized text
- `blocks`: nonblank line-based `OcrTextBlock` entries
- `warnings`: learner-facing warnings, never raw stack traces

`OcrTextBlock`:

- `index`
- `text`
- `confidence`: nullable `Double`

The service should add a warning whenever confidence is `null` because the
current `OcrResult` does not expose per-block confidence. This warning is part
of the learning contract, not an error.

### Native engine selection

Default behavior:

- `workshop.ocr.native-enabled=false`
- `-Docr.enabled=true` may also enable native OCR in local runs; native OCR is
  enabled when either the Spring property or system property is `true`
- native-disabled requests return `UNAVAILABLE` with an explicit warning and do
  not call Tesseract. They still validate bytes, declared media type, decoded
  image content, and decoded pixel count so corrupt or spoofed uploads fail as
  sanitized `400 Bad Request`.

Native-enabled behavior:

- decode multipart bytes with `immutableImageOf(bytes)`
- reject undecodable bytes as sanitized `400 Bad Request`
- reject decoded images whose `width * height` exceeds
  `workshop.ocr.max-image-pixels`
- build `OcrOptions(languages = ..., tessdataPath = property)`
- call the Spring-owned conditional singleton `OcrEngine`; the engine is not
  created per request and has no close/shutdown hook in the current API
- run byte materialization, image decode, and OCR inside a bounded native OCR
  boundary with `workshop.ocr.timeout` and a single-flight semaphore
- map `OcrConfigurationException` to `UNAVAILABLE`
- map other OCR failures to `FAILED`
- rethrow `CancellationException` before broad exception handling if suspend
  code is used
- write only sanitized diagnostics: `requestId`, status, engine, language list,
  native-enabled flag, elapsed time, and failure category. Never log uploaded
  bytes, OCR text, raw native messages, tessdata paths, or stack traces.

## 7. README And Diagrams

Both `README.md` and `README.ko.md` must include:

- language switch directly below the title;
- architecture diagram PNG with matching SVG source;
- sequence diagram PNG with matching SVG source;
- endpoint summary and curl multipart example;
- response JSON example for native-disabled fallback;
- response JSON example for completed OCR using a fake/deterministic test path;
- property table for `workshop.ocr.native-enabled`,
  `workshop.ocr.max-upload-bytes`, `workshop.ocr.max-image-pixels`,
  `workshop.ocr.timeout`, `workshop.ocr.languages`, and
  `workshop.ocr.tessdata-path`;
- Spring multipart limit note for `spring.servlet.multipart.max-file-size` and
  `spring.servlet.multipart.max-request-size`, aligned with
  `workshop.ocr.max-upload-bytes`;
- native Tesseract/tessdata prerequisites and local opt-in command:
  `./gradlew :image-processing-ocr-api:test -Docr.enabled=true`;
- default fallback `bootRun` command, native-enabled `bootRun` command with
  `workshop.ocr.native-enabled=true` or `-Docr.enabled=true`, tessdata path
  example, and matching curl commands for both modes;
- macOS and Linux Tesseract install examples plus tessdata/language-pack
  verification steps;
- default smoke command:
  `./gradlew :image-processing-ocr-api:test`;
- statement that default CI smoke does not require Tesseract;
- explanation that confidence is nullable because the current OCR engine
  contract returns text-only results;
- troubleshooting for missing native library, missing language packs, corrupt
  image bytes, spoofed content type, unsupported image subtype, and decoded
  pixel-limit rejection;
- visible workshop boundary statement: this local example has no auth, antivirus
  scanning, persistence, rate limiting, storage policy, queueing, audit
  workflow, PII/document-management guarantee, or production upload hardening;
- warning that OCR text may contain sensitive data and should not be logged or
  returned from production systems without a redaction policy;
- deterministic fallback and completed JSON examples with stable sample
  `requestId`, `status`, `engine`, `languages`, nullable confidence, blocks,
  and warnings;
- dependency note stating that bluetape4k versions are governed by
  `bluetape4k-dependencies`.

Diagram labels stay English so the same assets can be shared by both README
files. The architecture diagram must flow top-to-bottom, matching the current
architecture-diagram direction preference.

## 8. CI And Validation Matrix

Because default execution is native-disabled and deterministic:

- add `:image-processing-ocr-api:test` to `scripts/smoke-validate.sh all-smoke`;
- update stale-check expected Gradle project count from the current observed
  `80` to `81`;
- add `image-processing/ocr-api/**` to `.github/workflows/Examples.yml` push
  and pull request path filters;
- add `:image-processing-ocr-api:test` to the existing H2/default Examples
  smoke Gradle command;
- keep the existing `smoke-examples` timeout unchanged;
- include `image-processing/ocr-api/build/test-results/test/*.xml` and
  `image-processing/ocr-api/build/reports/tests/test/` in the smoke examples
  artifact upload;
- update root `README.md` and `README.ko.md` image-processing module catalog
  entries.

Nightly already reaches smoke-safe modules through `scripts/smoke-validate.sh
all-smoke`, so the script update is the primary Nightly integration point.

## 9. Acceptance Criteria

- [ ] `image-processing/ocr-api` is registered as
  `:image-processing-ocr-api` through existing auto module conventions.
- [ ] The module exposes `POST /api/images/ocr` as a Spring Boot 4 multipart
  endpoint.
- [ ] Default smoke tests do not require system Tesseract or tessdata.
- [ ] Native-disabled requests return structured `UNAVAILABLE` output with a
  clear warning.
- [ ] Native-enabled service tests can inject a fake `OcrEngine` and return
  structured `COMPLETED` output.
- [ ] `OcrConfigurationException` maps to structured `UNAVAILABLE` output.
- [ ] Validation rejects empty files, non-image content types, oversized files,
  and invalid languages.
- [ ] Validation rejects spoofed `image/*` payloads with undecodable bytes.
- [ ] Validation rejects unsupported image subtypes outside JPEG, PNG, and WebP.
- [ ] Validation rejects decoded images above `workshop.ocr.max-image-pixels`.
- [ ] Spring multipart limits are aligned with `workshop.ocr.max-upload-bytes`.
- [ ] Native OCR execution has a timeout and single-flight concurrency guard.
- [ ] Native-disabled fallback validates upload bytes and decoded image shape,
  then skips OCR only after validation succeeds.
- [ ] Cancellation is rethrown before broad exception handling.
- [ ] Response includes language, nullable confidence, text blocks, and
  warnings.
- [ ] API responses never expose raw native exception stack traces.
- [ ] Logs never include uploaded bytes, OCR text, raw native messages,
  tessdata paths, or stack traces.
- [ ] The repo uses only the `bluetape4k-dependencies` BOM for bluetape4k
  versions.
- [ ] README/README.ko document prerequisites, local opt-in, default skip/fallback
  behavior, and focused test commands.
- [ ] README diagrams have SVG source, rendered PNG, and visual QA evidence.
- [ ] CI/smoke validation includes the new smoke-safe module in `Examples.yml`,
  `smoke-validate.sh all-smoke`, and stale-check expected count.
- [ ] Contributor validation includes
  `./gradlew :image-processing-ocr-api:test`,
  `./scripts/smoke-validate.sh all-smoke`,
  `./scripts/smoke-validate.sh stale-check`, README validators, diagram
  validators, workflow lint, and `git diff --check`.

## 10. Risks

- Tesseract behavior differs by installed language data and platform; native
  OCR remains local opt-in and is not asserted in the default smoke path.
- `OcrResult` currently lacks confidence metadata; the workshop must make
  nullable confidence explicit rather than inventing values.
- Multipart files are copied to memory for a beginner-friendly module; the byte
  limit is the guardrail.
- `all-smoke` may expose unrelated existing failures; if so, rerun the focused
  module test and record the unrelated failure with evidence.
