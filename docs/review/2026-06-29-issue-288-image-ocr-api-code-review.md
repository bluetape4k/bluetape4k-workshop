# Issue 288 image OCR API code review

## 범위

- Issue: #288, milestone 1.2.0.
- Module: `image-processing/ocr-api`.
- Artifacts: Spring Boot OCR API example, bilingual README, top-to-bottom architecture/sequence
  diagram, Examples workflow, smoke validation wiring.

## 리뷰 finding

여섯 개 독립 review lane이 performance, stability, security, operations, developer API quality,
learner experience를 확인했다.

- P0: 0.
- P1 before fixes: unique finding 4건.
- P1 after fixes: known 0건.
- P2 before fixes: upload-size guard timing, Spring native opt-in contract, sanitized diagnostic
  누락, data-class serializability, troubleshooting 누락, diagram text overflow.
- P2 after fixes: PR handoff 기준 known 0건.

## 리뷰 후 적용한 수정

- full raster decode 전에 oversized pixel budget을 거부하도록 `immutableImageOf` 이전에 PNG,
  JPEG, WebP pre-decode image dimension check를 추가했다.
- blocking native OCR execution을 `runInterruptible(Dispatchers.IO)`로 옮기고 native lane이
  release됨을 증명하는 timeout regression을 추가했다.
- `-Docr.enabled=true`가 `workshop.ocr.native-enabled=true`와 같은 Spring bean path를 통해
  native OCR engine을 만들도록 했다.
- `file.bytes` 이전에 controller-level `MultipartFile.size` rejection을 추가했다.
- request id, status, engine, language list, native flag, elapsed time, failure category만 담는
  sanitized outcome logging을 추가했다.
- README example이 repository에 존재하는 PNG와 default `eng` language를 사용하도록 갱신하고
  native troubleshooting guidance를 추가했다.
- overflow되는 diagram text를 나누고 PNG를 재생성했다.
- application entrypoint, web controller, exception handler에 KDoc을 추가했다.
- `ImageOcrProperties`를 serializable하게 만들었다.

## 검증 증거

- `./gradlew :image-processing-ocr-api:cleanTest :image-processing-ocr-api:test --no-build-cache --console=plain --no-daemon` 통과: 23 tests.
- `./gradlew :image-processing-ocr-api:cleanTest :image-processing-ocr-api:test -Docr.enabled=true --no-build-cache --console=plain --no-daemon` 통과: 23 tests.
- `./gradlew :image-processing-ocr-api:compileKotlin :image-processing-ocr-api:compileTestKotlin --warning-mode all --console=plain --no-daemon` 통과.
- `./scripts/smoke-validate.sh stale-check` 통과: active modules 81개, stale README ref 없음,
  broken README image link 없음.
- `./scripts/smoke-validate.sh all-smoke` 통과, `:image-processing-ocr-api:test` 포함.
- `node scripts/validate-readme-architecture-diagrams.mjs && node scripts/validate-sequence-diagrams.mjs && node scripts/validate-readme-parity.mjs && node scripts/validate-readme-language.mjs` 통과.
- `actionlint .github/workflows/Examples.yml` 통과.
- `git diff --check` 통과.
- `image-processing/ocr-api`에 대한 banned-pattern scan(`!!`, `runBlocking`, `runCatching`,
  `GlobalScope`, `Thread.sleep`, `@Synchronized`, `synchronized`) 통과.

## Rollback

이 example을 안전하게 제거하려면 다음을 수행한다.

1. `image-processing/ocr-api`를 삭제한다.
2. `scripts/smoke-validate.sh`에서 `image-processing-ocr-api`를 제거하고 expected module count를 복원한다.
3. `.github/workflows/Examples.yml`에서 `:image-processing-ocr-api:test` job entry와 artifact path를 제거한다.
4. root README와 README.ko의 module link를 제거한다.
5. 다른 module이 사용하지 않는다면 `gradle/libs.versions.toml`에서 `bluetape4k-images-ocr`를 제거한다.
6. `docs/images/readme-diagrams/image-ocr-api-readme-*`를 제거한다.
7. `./gradlew projects`, `./scripts/smoke-validate.sh stale-check`, README diagram validator,
   `git diff --check`를 다시 실행한다.
