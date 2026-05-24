# Issue 93 Image Processing Advanced Workflow

## Context

Issue #93 added an advanced image-processing workshop module that demonstrates
Bluetape4k image libraries with Spring Boot 4, Java 25 FFM VIPS, `ImageStorage`,
S3/local storage, unsigned public URLs, and workflow metrics.

## Decision

- Keep the example Bluetape4k-first: app code owns orchestration and URL policy,
  while Bluetape4k owns VIPS processing, object keys, storage, health, and upload
  metadata.
- Make native VIPS integration tests opt-in with `-Dvips.enabled=true`. A
  no-property native probe crashed the local JVM with a fatal native signal, so
  deterministic CI defaults skip native execution explicitly.
- Validate uploads with a JPEG/PNG/WebP allowlist plus magic bytes before VIPS
  decode, then rely on VIPS `maxPixels` and decode errors as the final image gate.
- Reject unsafe `publicBaseUrl` settings: non-HTTPS remote URLs, userinfo,
  query/fragment, parent path traversal, and local-storage remote URLs unless
  explicitly allowed.

## Outcome

The new `:image-processing-advanced-workflow` module includes the upload API,
variant workflow, public URL resolver, metrics, storage cleanup, README
architecture and sequence diagrams, and root README/smoke script integration.

## Verification

- `./gradlew :image-processing-advanced-workflow:build -Dvips.enabled=false --console=plain`
  passed: 21 tests, 1 native VIPS test skipped.
- `./scripts/smoke-validate.sh stale-check` passed.
- `./scripts/smoke-validate.sh all-smoke` passed earlier on the same branch.
- `./gradlew build -x test --parallel --continue --console=plain` passed earlier
  on the same branch.
- `git diff --check` passed.
- Claude review gate artifacts reached `P0=0 P1=0`:
  `.omx/artifacts/claude-issue-93-code-review-final2-20260524171319.md`.

## Future Notes

- If future work makes variant format configurable, remove the current WebP-only
  `contentType`/`extension` invariants and drive encoder format from config.
- Do not enable native VIPS tests by default without proving the host image
  stack cannot terminate the JVM during discovery or initialization.
