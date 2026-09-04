# #884 다중 페이지 TIFF structured OCR 실행 계획

1. **기준선 고정** — #883 exact head에서 `ocr-api` 테스트와 현재 multipart/response 계약을
   확인한다.
2. **설정·입력 경계** — TIFF content type/magic을 추가하고 `TiffMultiPageOcrProperties`와
   `application.yml`의 모든 resource budget을 연결한다.
3. **서비스 연결** — native 경로에서 structured engine과 `suspendRecognize`를 호출하고,
   기존 포맷은 `immutableImageOf` 경로를 유지한다. cancellation과 timeout을 보존한다.
4. **HTTP 오류 매핑** — TIFF validation/processing 예외를 sanitized ProblemDetail의
   reason/phase/pageIndex로 노출한다.
5. **회귀·TDD** — 3-page writer fixture, page order, page/result limit, disabled fallback,
   cancellation, controller error mapping, 기존 테스트를 검증한다.
6. **문서·등록** — 양국 module/root README, coverage matrix, stale-check/워크플로 확인,
   설계·review·lesson artifact를 갱신한다.
7. **검증·전달** — `git diff --check`, targeted/full test, detekt, README language/parity,
   smoke stale-check를 실행하고 Lore commit과 PR metadata/body를 준비한다.

## 파일·수용 기준 추적

| 기준 | 구현/검증 위치 |
|---|---|
| 3-page 순서 | `ImageOcrServiceImplTest`의 TIFF fixture와 `pages/blocks/lines` index 검증 |
| page/pixel/bytes/metadata/result 제한 | `ImageOcrProperties`, upstream limits mapping, preflight/result test |
| 단계별 안전 오류 | `ImageOcrExceptionHandler`, controller ProblemDetail test |
| cancellation/resource cleanup | service cancellation test와 upstream `suspendRecognize` contract |
| 기존 포맷/ABI | 기존 `ImageOcrServiceImplTest`, versionless catalog alias 유지 |
| 문서·등록 | module/root README, `docs/coverage-matrix.md`, `scripts/smoke-validate.sh` |
