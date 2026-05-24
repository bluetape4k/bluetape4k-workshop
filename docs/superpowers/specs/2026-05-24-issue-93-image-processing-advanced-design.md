# Issue #93 — Image Processing Advanced Workshop Design

**Date**: 2026-05-24  
**Repository**: `bluetape4k-workshop`  
**Target module**: `image-processing/advanced-workflow` (`:image-processing-advanced-workflow`)  
**Workflow lane**: Type A Full Design via `bluetape4k-workflow`

## Problem

Issue #93 asks for an advanced image-processing workshop that demonstrates a realistic upload-to-derivatives workflow:

- multipart image upload through Spring Boot
- content type, byte-size, pixel-limit, and dimension validation
- original image storage
- configured derivative generation (`thumb-128.webp`, `card-320.webp`, `detail-1024.webp`)
- S3 object key and unsigned public URL responses
- metrics, health, concurrency limits, and native libvips handling
- README architecture and sequence diagrams

The example must be Bluetape4k-first, not a generic Spring or AWS sample.

## Current Evidence

- `bluetape4k-image` provides `bluetape4k-images-vips-java25` with `FfmVipsRuntime`, `ffmVipsImageOf`, `suspendFfmVipsImageOf`, `VipsImage.thumbnail()`, `VipsImage.resize()`, and `VipsImage.toBytes()`.
- `bluetape4k-images-vips-java25` requires Java 25 and `--enable-native-access=ALL-UNNAMED`; this workshop keeps VIPS integration tests opt-in with `-Dvips.enabled=true` because native initialization can terminate the JVM on misconfigured hosts.
- `bluetape4k-images-spring-boot` provides Spring Boot 4 auto-configuration for `ImageStorage`.
  - `ImagesStorageAutoConfiguration` creates `S3ImageStorage` when `bluetape4k.images.storage.backend=s3` and `S3Operations` is present.
  - It falls back to `LocalImageStorage` when no `ImageStorage` bean exists.
  - `MetricImageStorage` wraps storage beans when Micrometer is present.
- `ImageObjectKey` validates keys and prevents `..` segments.
- `UploadImageValidator` limits workshop uploads to JPEG, PNG, and WebP, checks matching magic bytes, and still uses `UploadOptions` for Bluetape4k upload metadata validation.
- `S3PreSignedUrlSigner` and `CloudFrontUrlSigner` exist for private/signed URL alternatives, but issue #93 requires the main path to return unsigned public URLs.
- The workshop root currently defaults to Java 21, while Java 25-only modules can override module toolchains and test JVM launchers.

## Configuration Defaults

| Property | Default | Rationale |
|---|---:|---|
| `spring.servlet.multipart.max-file-size` | `25MB` | Servlet boundary rejects oversized uploads before heap growth |
| `spring.servlet.multipart.max-request-size` | `25MB` | Must match the file limit for this single-file endpoint |
| `bluetape4k.images.storage.max-size-bytes` | `26214400` | Matches servlet upload limit |
| `workshop.images.advanced.max-input-bytes` | `26214400` | Service-level guard after multipart parsing |
| `workshop.images.advanced.max-pixels` | `100000000` | Decompression-bomb defense passed to `FfmVipsRuntime` |
| `workshop.images.advanced.request-concurrency` | `2` | Bounds concurrent native decode/encode workflows |
| `workshop.images.advanced.variant-concurrency` | `2` | Bounds per-request derivative fan-out |
| `workshop.images.advanced.processing-timeout` | `30s` | Avoids unbounded native processing requests |
| `workshop.images.advanced.public-base-url` | `http://localhost:8080/public-images` | Local dev default only |
| `workshop.images.advanced.allow-insecure-public-base-url` | `false` | HTTPS required except loopback local dev |
| `workshop.images.advanced.allow-local-storage-remote-public-base-url` | `false` | Prevents fake CDN URLs for local fallback unless deliberate |

## Scope

### In

- New Spring Boot 4 module `:image-processing-advanced-workflow`.
- Java 25 module-level toolchain because `bluetape4k-images-vips-java25` is the requested backend.
- Image upload controller:
  - `POST /api/images/derivatives`
  - multipart part name: `file`
  - JSON response with metadata, object keys, and unsigned URLs.
- Service workflow:
  1. validate upload bytes and MIME type
  2. initialize and use Java 25 FFM VIPS runtime
  3. inspect original dimensions
  4. store original through `ImageStorage`
  5. generate configured WebP variants through `VipsImage.thumbnail()`
  6. store all variants through `ImageStorage`
  7. compose public unsigned URLs from configurable `publicBaseUrl`
  8. record upload/process success and failure metrics
- README.md and README.ko.md with:
  - setup prerequisites for JDK 25 and libvips
  - run/test commands
  - request examples and sample response JSON
  - naming convention
  - raw framework vs Bluetape4k-supported before/after explanation
  - `Used Bluetape4k features` table
  - Mermaid architecture diagram
  - Mermaid sequence diagram
  - unsigned URL security note
- Tests for:
  - resize/thumbnail dimensions on the Bluetape4k VIPS path, skipped explicitly if libvips is unavailable
  - invalid content type
  - too-large input
  - key naming and unsigned URL composition
  - all configured variants are stored
  - libvips-disabled skip behavior

## HTTP and Coroutine Model

The module uses Spring MVC because the workshop endpoint is a servlet multipart upload. The controller and service methods remain `suspend` where Spring MVC can bridge them asynchronously, and native or blocking work stays behind explicit coroutine boundaries:

- Multipart parsing is bounded by Spring servlet multipart limits before the controller receives the file.
- Service orchestration is suspend-first.
- `suspendFfmVipsImageOf` and explicit `withContext(Dispatchers.IO)` boundaries keep native decode/encode work off request threads.
- A request-level `Semaphore` limits concurrent image workflows; a variant-level `Semaphore` limits per-request fan-out.
- `withTimeout(processingTimeout)` bounds each workflow.

### Out

- PostgreSQL + Exposed metadata/history persistence. That is tracked by #94.
- Auth/authz and tenant policy enforcement.
- Signed URL main-path behavior. README will document `S3PreSignedUrlSigner` and `CloudFrontUrlSigner` as private-image alternatives.
- A new upstream Bluetape4k library API for public URL composition. The workshop will keep this as small application glue because unsigned public URLs depend on bucket/CDN policy.

## Architecture

The module uses Bluetape4k libraries for the heavy parts:

- `bluetape4k-images-vips-java25` for decode, dimension inspection, thumbnail generation, WebP encoding, and native runtime limits.
- `bluetape4k-images-vips-api` for backend-neutral `VipsImage`, formats, and encode options.
- `bluetape4k-images-spring-boot` for storage abstraction, S3/local storage, health, and storage metrics.
- `bluetape4k-micrometer`/Micrometer for workflow-level metrics.
- Spring Boot MVC only for the HTTP boundary.

### Components

| Component | Responsibility |
|---|---|
| `ImageDerivativesController` | Multipart API boundary and HTTP status mapping |
| `ImageDerivativeWorkflowService` | Orchestrates validation, processing, storage, metrics, and response assembly |
| `UploadImageValidator` | Checks MIME type, magic bytes, empty input, byte limits, and delegates pixel validation to VIPS decode |
| `FfmVipsDerivativeProcessor` | Initializes `FfmVipsRuntime`, inspects dimensions, generates WebP variants with concurrency limit |
| `ImageStorage` | Bluetape4k storage abstraction; S3 in configured deployments, local fallback in dev/test |
| `PublicImageUrlResolver` | Workshop glue for unsigned public URL composition from `ImageObjectKey.fullKey` |
| `ImageProcessingAdvancedProperties` | Workflow variants, limits, and public URL settings |

### Lifecycle Policy

`FfmVipsRuntime` is initialized lazily on first processor use. This keeps the workshop app bootable on machines where libvips is not installed, while the first processing request receives a clear native-runtime error. The service never calls `FfmVipsRuntime.shutdown()` from Spring destroy hooks because the library documents shutdown as terminal for the process. Tests use forked JVMs and an explicit runtime guard to avoid cross-context parameter conflicts.

## Key Model

```text
images/{imageId}/original/{safeFilename}
images/{imageId}/variants/thumb-128.webp
images/{imageId}/variants/card-320.webp
images/{imageId}/variants/detail-1024.webp
```

`ImageObjectKey.of(prefix, name)` validates every storage key. The workshop sanitizer keeps filenames deterministic enough for examples while removing path separators and unsupported characters.

Filename sanitizing rules:

- keep only `[A-Za-z0-9._-]`
- replace all other characters with `_`
- strip path separators before validation
- preserve the last extension when present
- cap the final file name at 120 characters
- fall back to `upload.jpg` when no safe base name remains

## Response Model

```json
{
  "imageId": "2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4",
  "original": {
    "key": "images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/original/photo.jpg",
    "url": "https://cdn.example.com/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/original/photo.jpg",
    "width": 1600,
    "height": 1200,
    "contentType": "image/jpeg",
    "sizeBytes": 245763
  },
  "thumbnailUrl": "https://cdn.example.com/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/variants/thumb-128.webp",
  "variants": [
    {
      "name": "thumb-128",
      "key": "images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/variants/thumb-128.webp",
      "url": "https://cdn.example.com/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/variants/thumb-128.webp",
      "width": 128,
      "height": 96,
      "contentType": "image/webp",
      "sizeBytes": 7612
    }
  ],
  "durationMillis": 84,
  "warnings": []
}
```

## Approach Comparison

### Approach A — Raw Spring + AWS SDK + ad hoc image library

- Pros: no dependency on Java 25-specific Bluetape4k image module.
- Cons: repeats content-type validation, path/key validation, storage exception mapping, S3 upload handling, metrics, and native runtime handling. It does not satisfy the Bluetape4k-first requirement.
- Decision: rejected.

### Approach B — Spring Boot upload + Bluetape4k VIPS + Bluetape4k ImageStorage

- Pros: demonstrates realistic app workflow while using Bluetape4k for decode/resize/encode, storage, health, metrics, and validation boundaries.
- Cons: Java 25 and libvips prerequisites must be explicit; CI may need toolchain download and libvips-aware skip behavior.
- Decision: selected.

### Approach C — Pure local file example without S3 abstraction

- Pros: simplest to run.
- Cons: misses the issue's S3 unsigned URL requirement and does not demonstrate the Spring Boot image storage abstraction.
- Decision: rejected. Local storage remains only as the dev/test fallback from `images-spring-boot`.

## Failure Modes and Handling

| Failure | Handling |
|---|---|
| Unsupported content type | reject before storage using the workshop JPEG/PNG/WebP allowlist, magic-byte checks, and `UploadOptions` |
| Empty or too-large upload | reject before decode/storage |
| Pixel limit exceeded | `FfmVipsRuntime`/decode validation raises a validation error |
| libvips unavailable | runtime init failure surfaces in app startup/use; VIPS-dependent tests skip explicitly |
| S3 unavailable | `ImageStorageException` propagates as service-unavailable style API error; storage health reports status |
| Public URL misconfiguration | startup property validation rejects blank base URL; README explains bucket/CDN public-read requirement |
| Partial variant failure | service performs best-effort delete for already-uploaded original/variant keys, returns an error, and increments failure metrics |
| Private image accidentally exposed | README warns unsigned URLs are only for public images and points to signed URL signers |

## Security Notes

- Main path returns unsigned URLs because issue #93 requires it.
- Unsigned URLs are acceptable only when every stored image is public.
- Bucket/CDN policy, object ownership, and cache policy must allow public reads.
- Private or user-sensitive images should use `S3PreSignedUrlSigner` or `CloudFrontUrlSigner`; this remains documented as an alternate path.
- SVG uploads remain unsupported because `UploadOptions` excludes SVG for XSS risk.
- `publicBaseUrl` must be HTTPS by default, with an exception only for loopback local development (`localhost`, `127.0.0.1`, `[::1]`) or an explicit `allowInsecurePublicBaseUrl=true`.
- `publicBaseUrl` must not contain userinfo, `..`, query strings, or fragments.
- When the active storage backend is local, non-loopback public base URLs require `allowLocalStorageRemotePublicBaseUrl=true` so accidental fake CDN responses are visible during configuration.
- Header content type is normalized to a bounded image allowlist, magic bytes must match that content type, and VIPS decode/maxPixels validation remains the final content gate.

## Observability Contract

Workflow metrics use low-cardinality tags only. They never include `imageId`, filename, or raw object key.

| Metric | Type | Tags |
|---|---|---|
| `workshop.images.upload.accepted` | Counter | normalized allowlisted `contentType` |
| `workshop.images.processing.duration` | Timer | `result=success|timeout|cancelled|validation|failure` |
| `workshop.images.processing.failures` | Counter | `stage=timeout|cancelled|validation|vips|storage|unknown` |
| `workshop.images.variant.generated` | Counter | `variant` |

## Test Strategy

- Unit tests use a recording `ImageStorage` implementation to verify key naming, URL composition, and variant storage without external S3.
- Controller/service tests verify invalid MIME type, MIME/magic-byte mismatch, unsafe public URL settings, and too-large input errors.
- VIPS integration tests run on Java 25 with `--enable-native-access=ALL-UNNAMED`; they skip by default and require explicit opt-in with `-Dvips.enabled=true`.
- Test images are generated with `ImageIO` to avoid depending on unpublished test fixtures.
- Tests assert multipart and service byte limits both use the 25 MiB default.
- Tests assert best-effort cleanup is attempted after partial storage failure.
- Tests assert metric names and tags are low-cardinality.
- Targeted verification:
  - `./gradlew projects`
  - `./gradlew :image-processing-advanced-workflow:test`
  - `./gradlew :image-processing-advanced-workflow:test -Dvips.enabled=true` when Java 25 and libvips are available
  - `./gradlew :image-processing-advanced-workflow:build`
  - `./gradlew build -x test --parallel --continue` if local toolchain allows

## Acceptance Criteria Mapping

| Issue #93 criterion | Design coverage |
|---|---|
| Add upload-to-derivatives module/scenario | new `:image-processing-advanced-workflow` |
| JDK 25 and libvips README prerequisites | README task |
| before/after snippets | README task |
| request examples and sample JSON | README task |
| generated naming convention | README and tests |
| sequence and component diagrams | README Mermaid diagrams |
| resize/thumbnail dimension tests | VIPS integration tests |
| invalid content type and too-large tests | controller/service tests |
| libvips-disabled skip behavior | `AbstractFfmVipsWorkshopTest` |
| unsigned S3 public URLs | `PublicImageUrlResolver` and response model |
| S3/local dev profile | `ImageStorage` auto-config docs and `application.yml` |
| security note for unsigned URLs | README task |
| Bluetape4k-first feature table | README task |
| multipart max-size boundary | `application.yml` and configuration binding tests |
| bounded concurrency and timeout | workflow service properties and tests |

## Definition of Done

- Spec and plan are committed before implementation.
- Claude advisor gates for spec/plan and code review show P0=0 and P1=0.
- New module is registered in `settings.gradle.kts`.
- New dependency aliases are added without duplicating centrally governed versions beyond catalog aliases.
- Module README.md and README.ko.md render GitHub Mermaid diagrams.
- Root README.md and README.ko.md list the new advanced module.
- Tests cover acceptance criteria with explicit VIPS skip behavior.
- Multipart limits, service byte limits, `publicBaseUrl` validation, cleanup policy, and metric names are covered by tests.
- Targeted Gradle verification passes or any environment-gated gap is recorded.
- `docs/lessons/2026-05-24-issue-93-image-processing-advanced.md` is added.
- PR is opened against `develop`, assigned to `debop`, with relevant labels.

## Step 1 / 1-R Checklist Completion Report

| Item | Status | Notes |
|---|---|---|
| Target repository confirmed | Done | `bluetape4k-workshop`, branch `feat/issue-93-image-processing-advanced` |
| Memory/qmd searched | Done | Found `images-vips` design/plan and issue #77 classification references |
| Current repo and ecosystem reuse searched | Done | CodeGraph and source inspection for VIPS and image storage |
| External/current API evidence checked | Done | Local source for `FfmVipsRuntime`, `ffmVipsImageOf`, `ImageStorage`, `S3ImageStorage`, `LocalImageStorage` |
| Technical constraints identified | Done | Java 25 module, libvips native dependency, unsigned public URL security |
| User intent clear | Done | User explicitly selected #93 and README diagram requirements |

## Step 2 Checklist Completion Report

| Item | Status | Notes |
|---|---|---|
| Architecture pre-design ran | Done | Approach comparison and component boundaries above |
| Step 1-R research incorporated | Done | Evidence and selected approach reference current sources |
| Spec path inside feature worktree | Done | This file is under the new feature worktree |
| Risks/failure modes included | Done | Failure-mode table included |
| Approach comparison included | Done | Three approaches compared |
| User approval | Done | User already selected #93 and mandated workflow/README diagrams; no material ambiguity remains |
| Draft task list returned | Done | Acceptance mapping and DoD define planning input |

## Step 2-R Review Notes

### Claude Code Opus Advisor

Initial artifact: `.omx/artifacts/claude-issue-93-spec-20260524162959.md`  
Rerun artifact after P0/P1 fixes: `.omx/artifacts/claude-issue-93-spec-rerun-20260524163313.md`

| Priority | Finding | Decision | Follow-up |
|---|---|---|---|
| P0 | HTTP layer and coroutine/blocking model unclear | Accepted | Added MVC+suspend/coroutine boundary policy |
| P0 | Multipart size limits missing | Accepted | Added servlet/storage/service 25 MiB defaults and tests |
| P0 | Concurrency and timeout unspecified | Accepted | Added request/variant semaphores and 30s timeout |
| P1 | Content-type trust boundary unclear | Accepted | Added header validation plus VIPS decode as final gate |
| P1 | Orphan cleanup missing | Accepted | Added best-effort delete policy |
| P1 | VIPS initialization timing unclear | Accepted | Added lazy init lifecycle policy |
| P1 | Pixel limit unspecified | Accepted | Added 100M max pixel default |
| P1 | Public URL validation underspecified | Accepted | Added scheme, path, and local/remote mismatch validation |
| P1 | Filename sanitizer vague | Accepted | Added deterministic sanitizer rules |
| P1 | Metric names unspecified | Accepted | Added observability contract |

Latest integrated finding table: `P0=0`, `P1=0`.
