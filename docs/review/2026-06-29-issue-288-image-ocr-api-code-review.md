# Issue 288 image OCR API code review

## Scope

- Issue: #288, milestone 1.2.0.
- Module: `image-processing/ocr-api`.
- Artifacts: Spring Boot OCR API example, bilingual README, top-to-bottom architecture and sequence diagrams, Examples workflow, smoke validation wiring.

## Review Findings

Six independent review lanes checked performance, stability, security, operations, developer API quality, and learner experience.

- P0: 0.
- P1 before fixes: 4 unique findings.
- P1 after fixes: 0 known.
- P2 before fixes: upload-size guard timing, Spring native opt-in contract, missing sanitized diagnostics, data-class serializability, missing troubleshooting, diagram text overflow.
- P2 after fixes: 0 known for PR handoff.

## Fixes Applied After Review

- Added pre-decode image dimension checks for PNG, JPEG, and WebP before `immutableImageOf` to reject oversized pixel budgets before full raster decode.
- Moved blocking native OCR execution to `runInterruptible(Dispatchers.IO)` and added a timeout regression proving the native lane is released.
- Made `-Docr.enabled=true` create the native OCR engine through the same Spring bean path as `workshop.ocr.native-enabled=true`.
- Added controller-level `MultipartFile.size` rejection before `file.bytes`.
- Added sanitized outcome logging with request id, status, engine, language list, native flag, elapsed time, and failure category only.
- Updated README examples to use an existing repository PNG and default `eng` language, then added native troubleshooting guidance.
- Split overflowing diagram text and regenerated the PNG.
- Added KDoc for the application entrypoint, web controller, and exception handler.
- Made `ImageOcrProperties` serializable.

## Verification Evidence

- `./gradlew :image-processing-ocr-api:cleanTest :image-processing-ocr-api:test --no-build-cache --console=plain --no-daemon` passed: 23 tests.
- `./gradlew :image-processing-ocr-api:cleanTest :image-processing-ocr-api:test -Docr.enabled=true --no-build-cache --console=plain --no-daemon` passed: 23 tests.
- `./gradlew :image-processing-ocr-api:compileKotlin :image-processing-ocr-api:compileTestKotlin --warning-mode all --console=plain --no-daemon` passed.
- `./scripts/smoke-validate.sh stale-check` passed: 81 active modules, no stale README refs, no broken README image links.
- `./scripts/smoke-validate.sh all-smoke` passed and included `:image-processing-ocr-api:test`.
- `node scripts/validate-readme-architecture-diagrams.mjs && node scripts/validate-sequence-diagrams.mjs && node scripts/validate-readme-parity.mjs && node scripts/validate-readme-language.mjs` passed.
- `actionlint .github/workflows/Examples.yml` passed.
- `git diff --check` passed.
- Banned-pattern scan for `!!`, `runBlocking`, `runCatching`, `GlobalScope`, `Thread.sleep`, `@Synchronized`, and `synchronized` over `image-processing/ocr-api` passed.

## Rollback

To remove this example safely:

1. Delete `image-processing/ocr-api`.
2. Remove `image-processing-ocr-api` from `scripts/smoke-validate.sh`, and restore the expected module count.
3. Remove the `:image-processing-ocr-api:test` job entry and artifact paths from `.github/workflows/Examples.yml`.
4. Remove root README and README.ko module links.
5. Remove `bluetape4k-images-ocr` from `gradle/libs.versions.toml` if no other module uses it.
6. Remove `docs/images/readme-diagrams/image-ocr-api-readme-*`.
7. Re-run `./gradlew projects`, `./scripts/smoke-validate.sh stale-check`, README diagram validators, and `git diff --check`.
