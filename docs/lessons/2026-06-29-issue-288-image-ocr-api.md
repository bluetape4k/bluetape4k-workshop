# Issue 288 image OCR API lesson

## Context

Issue #288 added a Spring Boot OCR API workshop example around `bluetape4k-images-ocr`.

## Decision

The example keeps native OCR disabled by default, but still validates upload bytes, media type, image dimensions, decoded pixels, and language input before returning a deterministic `UNAVAILABLE` fallback.

## Outcome

Code review found three easy-to-miss workshop risks:

- Pixel budget must be checked from headers before full raster decode.
- Coroutine `withTimeout` alone does not bound blocking native calls; use an interruptible boundary and test lane release.
- README curl examples must use files that exist in the repository.

## Future Guidance

For native-library workshop examples, provide a no-native smoke path, a documented opt-in native path, and tests that prove both the fallback contract and the opt-in wiring. Keep learner commands copy-pasteable from the repository root.
