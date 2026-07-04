# Profile Image Moderation Workshop Design

**Date**: 2026-07-04
**Repository**: `bluetape4k-workshop`
**Target module**: `image-processing/profile-image-moderation` (`:image-processing-profile-image-moderation`)
**Workflow lane**: Type A Full Feature via `bluetape4k-workflow`

## Problem

사용자 프로필 이미지는 업로드 직후 바로 공개하면 부적절한 이미지가 노출될 수 있다. 반대로 moderation 결과를 기다리는 동안 아무 이미지도 제공하지 않으면 사용자 경험이 나빠진다. 이 예제는 다음 흐름을 Bluetape4k-first 방식으로 보여준다.

1. 사용자가 profile image를 multipart로 업로드한다.
2. 원본은 S3-compatible storage 또는 local fallback에 private/original object로 저장한다.
3. 원본에서 pending blurred derivative를 생성해 moderation이 끝날 때까지 제공한다.
4. image moderation provider가 1초 지연 후 `APPROVED` 또는 `REJECTED`를 반환한다.
5. 승인되면 공개 가능한 approved image URL로 전환한다.
6. 반려되면 기본 프로필 이미지 URL로 전환하고 원본은 공개하지 않는다.

## Current Evidence

- `image-processing/advanced-workflow` already demonstrates upload validation, `ImageStorage`, object key safety, public URL composition, WebP variants, Micrometer metrics, Exposed persistence, and README diagrams.
- `aws/s3-spring-cloud` and `aws/storage-abstraction` demonstrate S3-compatible storage patterns and Floci/LocalStack-backed tests.
- `spring-boot/text-moderation-api` demonstrates a deterministic moderation API shape with Spring MVC and bluetape4k assertions in tests.
- `AGENTS.md` says new/converted modules must update validation matrix, smoke/full workflow groups, stale-check scripts, and lessons in the same branch.
- Root README currently lists `image-processing-advanced-workflow` and `image-processing-ocr-api`; the new example must update `README.md` and `README.ko.md` together.
- `.github/workflows/Examples.yml` already includes `image-processing/ocr-api` paths and `:image-processing-ocr-api:test`; new module coverage must be explicitly checked.
- AWS Rekognition official documentation identifies `DetectModerationLabels` as the image moderation API for unsafe content detection; input can be JPEG/PNG bytes or S3 object references. This is a useful optional adapter target, but not the default implementation because workshop CI should not require AWS credentials or network calls.

## Goals

- Add a standalone advanced workshop module named `:image-processing-profile-image-moderation`.
- Demonstrate profile-image moderation as an application workflow, not a generic image library sample.
- Keep the default scenario fully local, deterministic, and CI-safe.
- Show temporary UX: blurred pending image while moderation is running, while clearly documenting that blur is not a complete safety boundary.
- Show safe final UX: approved image only after moderation approval, default image after rejection.
- Document how the local fake moderation provider can be replaced by AWS Rekognition or another provider.

## Non-Goals

- Do not call real AWS Rekognition by default.
- Do not require AWS credentials, real S3, or external network for tests.
- Do not duplicate the full derivative persistence saga from `image-processing-advanced-workflow` unless needed for this scenario.
- Do not implement authentication/authorization or tenant policy.
- Do not claim blur is a complete safety mechanism for harmful content.
- Do not make pending blurred originals publicly cacheable forever.

## Architecture

The module uses Spring Boot MVC for the HTTP boundary, Bluetape4k image/storage APIs for object safety and storage abstraction, and a deterministic moderation provider for workshop behavior.

```text
Client
  ├─ POST /api/users/{userId}/profile-image
  │    └─ ProfileImageController
  │         └─ ProfileImageService
  │              ├─ UploadImageValidator
  │              ├─ ProfileImageProcessor (original metadata + blur derivative)
  │              ├─ ImageStorage (local default, S3-compatible config documented)
  │              ├─ ProfileImageRepository (in-memory default for workshop simplicity)
  │              └─ ImageModerationProvider (fake 1s provider default)
  └─ GET /api/users/{userId}/profile-image
       └─ effective URL by status
```

### Components

| Component | Responsibility |
|---|---|
| `ProfileImageController` | Multipart upload and status lookup API boundary |
| `ProfileImageService` | Orchestrates validation, storage, derivative creation, bounded background moderation scheduling, status transition, and response assembly |
| `UploadImageValidator` | Validates content type, magic bytes, empty input, byte limits, pixel limits, dimensions, and rejects unsafe user ids. Filenames are not identities; unsafe filename characters are sanitized only for the final object-name segment. |
| `ProfileImageProcessor` | Reads image dimensions and creates both a downscaled blurred pending JPEG and an approved JPEG derivative at upload time; approved URL stays hidden until moderation approves the upload |
| `ImageStorage` | Stores original, pending blurred, and approved objects through Bluetape4k storage abstraction; local storage is the default and S3-compatible configuration is documented |
| `ProfileImageRepository` | Stores current profile image state; in-memory implementation is enough for this workshop module but transitions must be `userId + uploadId` compare-and-set guarded |
| `ProfileImageModerationRunner` | Owns bounded application-scope coroutine execution for moderation jobs; request cancellation must not cancel accepted background moderation, but application shutdown cancels outstanding jobs cleanly |
| `ImageModerationProvider` | Abstraction for moderation decision; default fake provider delays 1 second and decides from deterministic filename marker only |
| `ProfileImageUrlResolver` | Composes public URLs only for `pending/`, `public/`, and default image paths; it must never resolve `private/original` keys |
| `ProfileImageMetrics` | Emits low-cardinality upload, moderation, transition, timeout, stale-completion, storage-failure, and cleanup metrics |

## Data Model

```kotlin
enum class ProfileImageStatus {
    NO_IMAGE,
    PENDING_MODERATION,
    APPROVED,
    REJECTED,
    MODERATION_FAILED,
}

enum class ModerationDecision {
    APPROVED,
    REJECTED,
}

data class ProfileImageView(
    val userId: String,
    val uploadId: String?,
    val status: ProfileImageStatus,
    val effectiveImageUrl: String,
    val pendingImageUrl: String?,
    val approvedImageUrl: String?,
    val defaultImageUrl: String,
    val moderationReason: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

State transitions are one-way per upload and guarded by `userId + uploadId`:

```text
NO_IMAGE
  └─ new upload -> PENDING_MODERATION
                       ├─ completeModeration(userId, uploadId, APPROVED) -> APPROVED
                       ├─ completeModeration(userId, uploadId, REJECTED) -> REJECTED
                       └─ moderation timeout/failure after configured budget -> MODERATION_FAILED
```

A new upload for the same user replaces the current state with a fresh pending state. Delayed completion from an older upload must be ignored with a stale-completion metric when `uploadId` no longer matches the user's current upload. This compare-and-set rule is mandatory because the 1-second fake moderation delay can finish out of order.

## Storage Key Policy

```text
profile-images/{userId}/{uploadId}/private/original/{safeFilename}
profile-images/{userId}/{uploadId}/pending/blurred.jpg
profile-images/{userId}/{uploadId}/public/approved.jpg
profile-images/default/default-profile.jpg
```

Rules:

- `ImageObjectKey` validates every generated key.
- `userId` is rejected unless it matches `[A-Za-z0-9._-]{1,80}`; it is not silently sanitized because sanitized user-id collisions are identity bugs.
- Filenames are sanitized only for the filename segment and never used as identity.
- Original objects live under `private/original` and must not be served by `/public-images/**`; local static serving and S3/CDN setup must 403/404 private prefixes.
- `ProfileImageUrlResolver` refuses to resolve any `private/` key.
- The approved image URL points to the approved JPEG derivative generated at upload time, not the private original.
- The pending blurred object is public only as a workshop UX compromise, with short cache TTL/no-store in local responses and README warnings that stricter systems should return the default pending image.
- The default image URL is stable and safe for rejected, failed, or missing profiles.
- `uploadId` must use high-entropy IDs such as `Base58.randomString(16)` or UUIDv7 string output; examples use short IDs only when labeled illustrative.

## API Contract

### Upload

`POST /api/users/{userId}/profile-image`

Multipart part name: `file`.

Response when accepted: HTTP `202 Accepted`. The response is returned before the 1-second moderation delay completes. Clients poll `GET /api/users/{userId}/profile-image` to observe the later terminal state.

```json
{
  "userId": "user-123",
  "uploadId": "6vCZtAb9mN4pQ2rX",
  "status": "PENDING_MODERATION",
  "effectiveImageUrl": "http://localhost:8080/public-images/profile-images/user-123/6vCZtAb9mN4pQ2rX/pending/blurred.jpg",
  "pendingImageUrl": "http://localhost:8080/public-images/profile-images/user-123/6vCZtAb9mN4pQ2rX/pending/blurred.jpg",
  "approvedImageUrl": null,
  "defaultImageUrl": "http://localhost:8080/public-images/profile-images/default/default-profile.jpg",
  "moderationReason": null
}
```

### Lookup

`GET /api/users/{userId}/profile-image`

- `NO_IMAGE`: returns default `effectiveImageUrl`, `uploadId=null`, and no moderation reason.
- `PENDING_MODERATION`: returns blurred `effectiveImageUrl`.
- `APPROVED`: returns approved `effectiveImageUrl`.
- `REJECTED`: returns default `effectiveImageUrl` and moderation reason.
- `MODERATION_FAILED`: returns default `effectiveImageUrl` and failure reason so stuck pending states are visible.

No-upload example:

```json
{
  "userId": "user-123",
  "uploadId": null,
  "status": "NO_IMAGE",
  "effectiveImageUrl": "http://localhost:8080/public-images/profile-images/default/default-profile.jpg",
  "pendingImageUrl": null,
  "approvedImageUrl": null,
  "defaultImageUrl": "http://localhost:8080/public-images/profile-images/default/default-profile.jpg",
  "moderationReason": null
}
```

## Moderation Provider

The default provider is deterministic and local:

- Upload scheduling stores the pending state and derivatives, then enqueues moderation on `ProfileImageModerationRunner`; the request returns `202 Accepted` immediately.
- The runner uses an application-owned bounded coroutine scope, a configurable concurrency limit, and `withTimeout(moderationTimeout)`.
- It waits for `workshop.profile-image-moderation.decision-delay`, default `1s`.
- It approves by default.
- It rejects only when the original filename contains the configured marker such as `reject`; this is a demo hook, not a security control.
- It rethrows `CancellationException` before broad exception handling.
- Completion calls `completeModeration(userId, uploadId, decision)`; the repository updates state only when the upload is still current and pending. Stale completions increment a metric and do not mutate state.
- Timeout or provider failure after one attempt marks the current upload `MODERATION_FAILED`; this keeps the demo deterministic and avoids invisible stuck pending state.

Optional production adapter note:

- AWS Rekognition `DetectModerationLabels` can moderate JPEG/PNG images from bytes or S3 object references.
- A production adapter should map returned labels/confidence to a local policy, not blindly reject every label.
- The workshop module documents this replacement point but does not depend on real Rekognition.

## Pending Blur Policy

Blurred pending images improve perceived responsiveness but are not complete content safety. The module therefore:

- Enforces max bytes, max pixels, max dimensions, and a processing timeout before derivative generation.
- Generates a strongly downscaled blurred JPEG derivative and an approved JPEG derivative during upload.
- Returns only the blurred derivative while status is pending.
- Adds cache-control/no-store behavior for pending responses in local serving, and documents equivalent CDN/S3 policy.
- Documents a stricter option: return a generic pending/default image instead of a blurred derivative.
- Avoids exposing private original URLs before approval.
- Strips or ignores original metadata in generated JPEG derivatives so EXIF/GPS data is not propagated.

## Configuration Defaults

| Property | Default | Rationale |
|---|---:|---|
| `spring.servlet.multipart.max-file-size` | `10MB` | Profile images should be smaller than general image workflow uploads |
| `spring.servlet.multipart.max-request-size` | `10MB` | Single-file endpoint |
| `workshop.profile-image-moderation.max-input-bytes` | `10485760` | Service-level guard |
| `workshop.profile-image-moderation.max-pixels` | `25000000` | Decompression-bomb defense for profile images |
| `workshop.profile-image-moderation.max-width` | `6000` | Dimension guard |
| `workshop.profile-image-moderation.max-height` | `6000` | Dimension guard |
| `workshop.profile-image-moderation.processing-timeout` | `10s` | Bounds validation and derivative generation |
| `workshop.profile-image-moderation.request-concurrency` | `4` | Bounds concurrent upload processing |
| `workshop.profile-image-moderation.moderation-concurrency` | `2` | Bounds background moderation jobs |
| `workshop.profile-image-moderation.moderation-timeout` | `3s` | Prevents invisible stuck pending state |
| `workshop.profile-image-moderation.public-base-url` | `http://localhost:8080/public-images` | Local dev default |
| `workshop.profile-image-moderation.default-image-url` | `http://localhost:8080/public-images/profile-images/default/default-profile.jpg` | Rejected/failed/missing fallback |
| `workshop.profile-image-moderation.decision-delay` | `1s` | Demonstrates pending UX |
| `workshop.profile-image-moderation.rejected-filename-marker` | `reject` | Deterministic test/demo trigger; client metadata is not trusted in production |
| `workshop.profile-image-moderation.allow-insecure-public-base-url` | `false` | HTTPS required except loopback local dev |
| `bluetape4k.images.storage.backend` | `local` | CI-safe default storage backend |
| `bluetape4k.images.storage.max-size-bytes` | `10485760` | Aligns with upload max |
| `bluetape4k.images.storage.local.root-dir` | `${java.io.tmpdir}/bluetape4k-profile-images` | Local fallback root |
| `management.endpoints.web.exposure.include` | `health,metrics` | Workshop observability |

## Approach Comparison

### Approach A — Extend `image-processing/advanced-workflow`

- Pros: reuses existing upload/storage/variant/persistence code directly.
- Cons: mixes a general derivative workflow with profile moderation UX and makes the existing advanced example harder to read.
- Decision: rejected because the user explicitly wants a new example and the scenario has its own lifecycle/status story.

### Approach B — New module with local deterministic moderation and `ImageStorage`

- Pros: standalone, CI-safe, easy to understand, demonstrates the application pattern without real cloud credentials.
- Cons: duplicates a small amount of upload/blur/key/url glue from the advanced workflow.
- Decision: selected.

### Approach C — New module with real S3 + Rekognition as the main path

- Pros: closest to production AWS architecture.
- Cons: requires credentials, external network, IAM policy, and service availability; unsuitable as the default workshop test path.
- Decision: rejected as default; document as optional adapter direction.

## Failure Modes and Handling

| Failure | Handling |
|---|---|
| Unsupported content type | Reject before storage using JPEG/PNG/WebP upload allowlist and magic-byte checks |
| Empty or too-large upload | Reject before image processing/storage |
| Image decode failure | Reject with stable 400 response |
| Storage upload failure | Do not update effective image to a partial object; best-effort cleanup attempted |
| Moderation timeout/failure | Current upload becomes `MODERATION_FAILED`; effective URL becomes default image; metric and reason are visible |
| Stale moderation completion | `completeModeration` returns ignored result, emits stale metric, and does not mutate latest upload |
| Rejected image | Effective URL becomes default image; original remains private/non-effective |
| Replaced upload | Old pending/approved objects are best-effort deleted or left to documented local cleanup; stale completion cannot make them effective |
| Public URL misconfiguration | Reject unsafe non-loopback HTTP unless explicitly allowed; resolver rejects private prefixes |
| Private original direct fetch | Local controller/static mapping and S3/CDN docs must deny `/public-images/**/private/**` |
| Cancellation | Request cancellation after acceptance does not cancel background moderation; application shutdown cancels outstanding jobs and records visible failure when possible |

## Observability Contract

Metrics use low-cardinality tags only and never include `userId`, `uploadId`, filenames, or object keys.

| Metric | Type | Tags |
|---|---|---|
| `workshop.profile.images.upload.accepted` | Counter | `contentType` |
| `workshop.profile.images.upload.rejected` | Counter | `reason=size|type|decode|url|storage` |
| `workshop.profile.images.moderation.duration` | Timer | `result=approved|rejected|timeout|failure|stale|cancelled` |
| `workshop.profile.images.transition` | Counter | `from`, `to` |
| `workshop.profile.images.cleanup` | Counter | `result=success|failure` |

The README must show how to inspect `/actuator/health` and `/actuator/metrics/workshop.profile.images.moderation.duration` locally.

## Test Strategy

TDD order:

1. Write service tests for pending response and blurred effective URL.
2. Verify the tests fail because the new module/classes do not exist.
3. Implement minimal model/service/storage fakes.
4. Add approval and rejection transition tests.
5. Add controller tests.
6. Add configuration and URL/key validation tests.
7. Add README and module registration checks.

Required tests:

- Upload returns HTTP `202 Accepted`, `PENDING_MODERATION`, `uploadId`, and blurred effective URL before the fake provider's 1-second delay completes.
- Approved moderation transitions effective URL to approved URL after deterministic fake provider completes.
- Rejected moderation transitions effective URL to default URL and stores moderation reason.
- Moderation timeout/provider failure transitions to `MODERATION_FAILED`, default effective URL, visible reason, and metric.
- Two uploads for the same user cannot be corrupted by stale completion from the older upload.
- Pending state never exposes private original URL, and resolver/static serving refuses private prefixes.
- Invalid content type, oversized input, max-pixel/dimension violations, and decode failure are rejected before storage.
- Pending/approved derivative content type and magic bytes match their `.jpg` URL extension.
- URL resolver rejects unsafe public base URL settings and local-storage remote public URLs unless explicitly allowed.
- Default image URL resolves for no-upload, rejected, and failed states.
- Controller returns stable JSON for pending, approved, rejected, failed, no-upload, and invalid upload cases.
- Public image controller returns pending/default/approved objects, `Cache-Control: no-store` for pending objects, and 404 for private prefixes.
- Metrics are emitted with low-cardinality tags for upload, moderation result, stale completion, transition, storage failure, and cleanup outcomes.
- Storage upload failure does not publish a partial effective image and records a rejected upload metric.
- Cleanup failure is logged/metriced without hiding the original storage failure.

Concurrency helper rationale:

- The stale-completion replacement case is a correctness race and must be tested. Prefer deterministic fake moderation futures/channels over sleep. If a stress-style coroutine test is added, use `SuspendedJobTester`; otherwise record that deterministic ordering tests cover this workshop risk without stress loops.

## README Requirements

Module README pair:

- `image-processing/profile-image-moderation/README.md`
- `image-processing/profile-image-moderation/README.ko.md`

Both must include:

- language switch
- scenario overview
- generated PNG/SVG architecture and sequence diagrams using the exact README asset names `profile-image-moderation-readme-architecture-01.{png,svg}` and `profile-image-moderation-readme-sequence-01.{png,svg}`
- request/response examples for pending upload, approved lookup, rejected lookup, failed lookup, no-upload lookup, and invalid upload
- status transition explanation
- privacy note about blur vs true safety
- how to replace fake moderation with AWS Rekognition, including the warning that filename markers are demo-only and production moderation must inspect bytes or S3 objects
- run/test commands
- local storage cleanup, default-image provisioning check, actuator health/metrics checks, stuck-pending/failure interpretation, and S3/CDN public-read/private-prefix caveats
- Bluetape4k feature table

Root README pair must add the module to the Advanced examples table and command list if applicable.

## Module Registration and CI

The implementation must verify and update:

- `settings.gradle.kts` auto-registration under `image-processing/`.
- `AGENTS.md` module group must include `image-processing/` because the current repo-local table does not list it even though settings already includes it.
- `scripts/smoke-validate.sh all-smoke` must include `:image-processing-profile-image-moderation:test` because the module is local and deterministic.
- `scripts/smoke-validate.sh stale-check` must pass after registration, proving generated/stale module metadata is current.
- `docs/coverage-matrix.md` or the repository-equivalent validation matrix must include the new module when that file exists in the current branch.
- `.github/workflows/Examples.yml` path filters, test command, and artifact paths for the new example.
- `.github/workflows/nightly.yml` must continue to cover the module through `scripts/smoke-validate.sh all-smoke`; add explicit nightly coverage only if script coverage is insufficient.
- Run `rg "image-processing-profile-image-moderation|profile-image-moderation|:image-processing-profile-image-moderation:test" .github scripts README.md README.ko.md AGENTS.md settings.gradle.kts` before PR to verify registration.
- `./gradlew projects` discovers `:image-processing-profile-image-moderation`.

## Acceptance Criteria

- New module `:image-processing-profile-image-moderation` exists and builds.
- Upload API demonstrates pending blurred profile image behavior.
- Moderation approval switches to approved public image.
- Moderation rejection switches to default profile image.
- Private original URL is never returned as the effective URL and cannot be resolved through the public URL resolver or local public route.
- Tests cover pending, approved, rejected, failed moderation, no-upload, stale completion, invalid upload, default image, metrics, and URL safety paths.
- README.md and README.ko.md are source-equivalent, including matching section order and JSON fields.
- Root README.md and README.ko.md list the new example.
- CI/Nightly/smoke registration gaps are checked and updated where needed.

## Step 2 Draft Task List

1. Create worktree and spec/plan artifacts.
2. Create module skeleton and failing tests first.
3. Implement configuration, model, and URL/key policies.
4. Implement validator, processor, storage orchestration, moderation provider, and service.
5. Implement controller and exception handler.
6. Add README pair and root README entries.
7. Update workflow/smoke registration.
8. Run targeted tests, `./gradlew projects`, `git diff --check`, and `actionlint` if workflows changed.
9. Run 7-tier review, fix P0/P1, write lesson, create PR.
