# Issue #93 — Image Processing Advanced Workshop Plan

**Date**: 2026-05-24  
**Spec**: `docs/superpowers/specs/2026-05-24-issue-93-image-processing-advanced-design.md`  
**Target module**: `:image-processing-advanced-workflow`  
**Workflow lane**: Type A Full Design via `bluetape4k-workflow`

## Execution Rules

- Apply `bluetape4k-patterns`, `ecc-kotlin-patterns`, `ecc-springboot-kotlin`, and `ecc-kotlin-testing` before Kotlin implementation and tests.
- Keep implementation inside `image-processing/advanced-workflow`.
- Use Java 25 only for the new module; do not raise the root workshop Java baseline.
- Use Bluetape4k image APIs in the main happy path.
- Keep unsigned public URL generation explicit as workshop glue, not as a claimed Bluetape4k library feature.
- Keep #94 persistence out of scope.
- Use Spring MVC multipart with suspend service orchestration, explicit `Dispatchers.IO` native boundaries, request concurrency `2`, variant concurrency `2`, and workflow timeout `30s`.
- Apply best-effort cleanup of already-uploaded objects when variant processing/storage fails.

## Task Plan

### T1 — Module Registration and Dependencies

- **complexity**: medium
- **scope**:
  - Add `includeModules("image-processing", false, true)` to `settings.gradle.kts`.
  - Create `image-processing/advanced-workflow/build.gradle.kts`.
  - Add version-catalog aliases for:
    - `bluetape4k-images`
    - `bluetape4k-images-spring-boot`
    - `bluetape4k-images-vips-api`
    - `bluetape4k-images-vips-java25`
  - Configure Java/Kotlin toolchain 25 only in the module.
  - Disable AtomicFU transform unconditionally in the module. This follows the `bluetape4k-images-vips-java25` module evidence: Java 25 bytecode plus AtomicFU transform can be incompatible when the build JVM is Java 21.
  - Configure tests with `--enable-native-access=ALL-UNNAMED`, Java 25 launcher, `forkEvery = 1`, and `-Dvips.enabled` propagation.
  - Record CI toolchain behavior: root CI installs Java 21, and the Gradle Foojay resolver is already configured in `settings.gradle.kts` to provision the module's Java 25 toolchain.
- **expected files**:
  - `settings.gradle.kts`
  - `gradle/libs.versions.toml`
  - `image-processing/advanced-workflow/build.gradle.kts`
- **verification**:
  - `./gradlew projects`
  - `./gradlew :image-processing-advanced-workflow:dependencies --configuration runtimeClasspath`
  - `./gradlew :image-processing-advanced-workflow:compileKotlin`
- **rollback point**: revert T1 if dependency resolution fails before code is added.

### T2 — Configuration and Domain Model

- **complexity**: medium
- **patterns**: apply `bluetape4k-patterns`, `ecc-kotlin-patterns`, `ecc-springboot-kotlin`.
- **scope**:
  - Add `ImageProcessingAdvancedApplication`.
  - Add `ImageProcessingAdvancedProperties` with:
    - `publicBaseUrl`
    - `maxInputBytes`
    - `maxPixels`
    - `requestConcurrency`
    - `vipsConcurrency`
    - `variantConcurrency`
    - `processingTimeout`
    - `allowInsecurePublicBaseUrl`
    - `allowLocalStorageRemotePublicBaseUrl`
    - default variants `thumb-128`, `card-320`, `detail-1024`
  - Ensure all data classes implement `Serializable` and define `serialVersionUID`.
  - Add response DTOs for original metadata, variant metadata, and workflow response.
  - Add `application.yml` with local dev fallback and configurable public URL.
  - Set `spring.servlet.multipart.max-file-size=25MB`, `spring.servlet.multipart.max-request-size=25MB`, and matching `workshop.images.advanced.max-input-bytes=26214400`.
- **expected files**:
  - `src/main/kotlin/io/bluetape4k/workshop/imageprocessing/advanced/...`
  - `src/main/resources/application.yml`
- **verification**:
  - module compile after T4

### T3 — Validation, Key Naming, and URL Composition

- **complexity**: medium
- **patterns**: apply `bluetape4k-patterns`, `ecc-kotlin-testing`.
- **scope**:
  - Implement `UploadImageValidator`.
  - Use `UploadOptions` for content-type validation so Bluetape4k image rules apply.
  - Implement deterministic key factory:
    - `images/{imageId}/original/{safeFilename}`
    - `images/{imageId}/variants/{variantName}.webp`
  - Implement `PublicImageUrlResolver`.
  - Validate `publicBaseUrl`:
    - reject blank values
    - reject `..`, query strings, and fragments
    - require HTTPS by default
    - allow HTTP only for loopback local dev or explicit `allowInsecurePublicBaseUrl=true`
    - reject local-storage plus non-loopback remote URL unless `allowLocalStorageRemotePublicBaseUrl=true`
  - Preserve safe slash joining.
- **tests**:
  - invalid content type fails
  - too-large input fails
  - filename sanitizer prevents path separator leakage
  - URL composition returns unsigned public URLs from key full path
  - `publicBaseUrl` validation rejects unsafe remote/local mismatches
  - multipart and service max-byte defaults match
- **verification**:
  - `./gradlew :image-processing-advanced-workflow:test -Dvips.enabled=false --tests "*Url*"`
  - exact test names may be adjusted after implementation

### T4 — VIPS Derivative Processor

- **complexity**: high
- **patterns**: apply `bluetape4k-patterns`, `ecc-kotlin-patterns`, `ecc-kotlin-testing`, `kotlin-coroutines-skill`.
- **scope**:
  - Implement `DerivativeProcessor` interface for testability.
  - Implement `FfmVipsDerivativeProcessor` using:
    - `FfmVipsRuntime.init(concurrency, maxPixels)`
    - `suspendFfmVipsImageOf(bytes)`
    - `VipsImage.thumbnail(maxDimension)`
    - `VipsImage.toBytes(VipsImageFormat.WEBP, VipsEncodeOptions.WebpOptions(quality = 82, effort = 4))`
  - Use lazy runtime initialization. `FfmVipsRuntime.init(concurrency, maxPixels)` is idempotent after the first successful init; tests run with `forkEvery = 1` to avoid cross-context parameter conflicts.
  - Wrap blocking/native decode and encode behind explicit `Dispatchers.IO` boundaries.
  - Use `Semaphore.withPermit` for variant concurrency.
  - Use a request-level `Semaphore.withPermit` and `withTimeout(processingTimeout)` in the workflow service.
  - Rethrow `CancellationException` before generic exception handling.
  - Do not call `FfmVipsRuntime.shutdown()` from Spring destroy hooks; library docs warn shutdown is terminal.
- **tests**:
  - VIPS integration test checks generated dimensions and WebP content markers.
  - VIPS test class skips explicitly when `-Dvips.enabled=false` or libvips init fails.
  - coroutine cancellation test verifies cancellation is not swallowed.
  - runtime guard test verifies `-Dvips.enabled=false` aborts VIPS tests explicitly.
- **verification**:
  - `./gradlew :image-processing-advanced-workflow:test -Dvips.enabled=false`
  - `./gradlew :image-processing-advanced-workflow:test --tests "*Vips*"` when libvips is available

### T5 — Workflow Service, Storage, Metrics, and API

- **complexity**: high
- **patterns**: apply `bluetape4k-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`.
- **scope**:
  - Implement `ImageDerivativeWorkflowService`.
  - Inject Bluetape4k `ImageStorage` so S3/local is selected by `images-spring-boot` auto-configuration.
  - Store original and all variants through `ImageStorage.upload`.
  - On failure after any upload, best-effort delete every previously-uploaded key through `ImageStorage.delete`; rethrow the original workflow exception.
  - Compose response keys and unsigned URLs.
  - Add workflow counters/timers:
    - upload accepted
    - processing success/failure
    - processing duration
    - variant generated
  - Use low-cardinality metric tags only: `contentType`, `result`, `stage`, and `variant`; never `imageId`, filename, or key.
  - Add `ImageDerivativesController` with `POST /api/images/derivatives`.
  - Add consistent error response mapping for validation and storage failures.
- **tests**:
  - recording storage verifies original + all variants uploaded
  - response contains `originalUrl`, `thumbnailUrl`, `variants[].url`, and keys
  - controller rejects invalid content type and too-large input
  - storage failure increments failure metric or returns expected error
  - partial variant failure attempts cleanup for original and prior variants
  - metric assertions verify low-cardinality tag names
- **verification**:
  - `./gradlew :image-processing-advanced-workflow:test -Dvips.enabled=false`

### T6 — README and Examples

- **complexity**: medium
- **scope**:
  - Add `image-processing/advanced-workflow/README.md`.
  - Add `image-processing/advanced-workflow/README.ko.md`.
  - Include Mermaid architecture diagram and sequence diagram in both files.
  - Include setup prerequisites for JDK 25 and libvips on macOS/Linux.
  - Include request examples, sample response JSON, generated key naming, local cleanup, S3 unsigned public URL setup, and security note.
  - Include troubleshooting for common libvips install and Java 25 native-access failures.
  - Include before/after snippets.
  - Include `Used Bluetape4k features` table with feature, artifact, code reference, and benefit.
  - Update root `README.md` and `README.ko.md` module list.
- **verification**:
  - `git diff --check`
  - grep rendered Mermaid fences and links

### T7 — Test Resources and Verification

- **complexity**: medium
- **scope**:
  - Add `src/test/resources/junit-platform.properties`.
  - Add `src/test/resources/logback-test.xml`.
  - Run targeted tests and compile.
  - Run `./gradlew projects`.
  - Check CI/Nightly workflow impact:
    - `settings.gradle.kts` auto-includes the module.
    - CI `build -x test` compiles all modules.
    - Nightly full `./gradlew test` will include the module.
    - Smoke list changes only if scripts require manual enumeration.
    - Java 25 toolchain is provisioned by the existing Foojay resolver; no workflow YAML edit is expected unless verification proves otherwise.
- **verification**:
  - `./gradlew projects`
  - `./gradlew :image-processing-advanced-workflow:build -Dvips.enabled=false`
  - `./gradlew build -x test --parallel --continue`

### T8 — Review Gate, Lesson, Commit, PR

- **complexity**: medium
- **scope**:
  - Run code review gate per Step 6-R with Codex review plus Claude Code CLI advisor.
  - Add `docs/lessons/2026-05-24-issue-93-image-processing-advanced.md`.
  - Commit with Lore protocol.
  - Push branch and open PR against `develop`, assigned to `debop`, labels `documentation`, `enhancement`, `area:image-processing`, `area:storage` if available.
- **verification**:
  - `git status --short`
  - `git diff --stat origin/develop...HEAD`
  - `gh pr view --json number,title,url,assignees,labels`

## Requirement-to-Task Matrix

| Requirement | Task |
|---|---|
| New module/scenario | T1, T2 |
| Spring multipart upload | T5 |
| Bluetape4k VIPS Java 25 happy path | T4 |
| Storage abstraction and S3 path | T5, T6 |
| Unsigned public URLs | T3, T5, T6 |
| Metrics/health | T5, inherited storage health from `images-spring-boot` |
| libvips unavailable skip behavior | T1, T4, T7 |
| Invalid type / too large tests | T3, T5 |
| Resize/thumbnail tests | T4 |
| All configured variants stored | T5 |
| README architecture and sequence diagrams | T6 |
| Bluetape4k-first table | T6 |
| #94 persistence excluded | T6 notes and PR body |

## Plan Review Self-Check

| Check | Status | Notes |
|---|---|---|
| Every spec requirement maps to task | Done | See matrix |
| Task ordering implementable | Done | Build/module before code, code before tests/docs verification |
| Tests cover success/failure/backend capability | Done | Recording storage plus VIPS skip/integration tests |
| Coroutine cancellation covered | Done | T4 includes cancellation test |
| README and locale pair covered | Done | T6 |
| New module workflow covered | Done | T1, T7 |
| Java 25 preview/native risk recorded | Done | T1, T4, T6 |
| Lifecycle ownership explicit | Done | Do not shutdown VIPS runtime from Spring destroy hook |

## Step 3-R Review Notes

### Claude Code Opus Advisor

Initial artifact: `.omx/artifacts/claude-issue-93-plan-20260524162959.md`  
Rerun artifact after P0/P1 fixes: `.omx/artifacts/claude-issue-93-plan-rerun-20260524163313.md`

| Priority | Finding | Decision | Follow-up |
|---|---|---|---|
| P1 | AtomicFU transform decision vague | Accepted | T1 now disables it unconditionally based on Java 25 module evidence |
| P1 | VIPS lifecycle across Spring test contexts unclear | Accepted | T4 now defines lazy init, idempotency, and forked test JVM policy |
| P1 | Public URL security validation incomplete | Accepted | T3 now defines HTTPS, path, query, local/remote mismatch rules |
| P1 | Partial storage failure leaves orphans | Accepted | T5 now requires best-effort delete compensation |
| P2 | Multipart heap pressure | Accepted | Execution rules and T2/T5 bound request concurrency and byte limits |
| P2 | WebP encode options unspecified | Accepted | T4 pins quality 82 and effort 4 |
| P2 | CI Java 25 availability unclear | Accepted | T1/T7 now record existing Foojay toolchain resolver check |
| P2 | Dispatcher and metric-cardinality details | Accepted | T4/T5 now specify `Dispatchers.IO` and low-cardinality tags |

Latest integrated finding table: `P0=0`, `P1=0`.

## Step 3 Checklist Completion Report

| Item | Status | Notes |
|---|---|---|
| Plan path inside feature worktree | Done | This file is under the feature worktree |
| All tasks have complexity labels | Done | T1-T8 |
| Patterns assigned to code-bearing tasks | Done | T2-T5 |
| Test snippets avoid disallowed assertion APIs | Done | No snippets require JUnit assertions; implementation will use bluetape4k assertions |
| Thread/coroutine safety test approach recorded | Done | T4 uses coroutine cancellation; variant concurrency checked through service behavior |
| Verification commands included | Done | Per task |
| README and contributor artifacts included | Done | T6, T8 |
| Risky assumptions explicit | Done | Java 25/libvips/native access, S3 public URL policy |
| Spec + plan commit required before implementation | Done | T8 and workflow gate |
