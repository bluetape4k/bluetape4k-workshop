# #884 다중 페이지 TIFF structured OCR 설계

## 목표

`image-processing/ocr-api`가 bluetape4k 2.0.0의 `TiffMultiPageOcr.suspendRecognize`
기능을 실제 multipart 소비자 경계에 적용한다. 기존 JPEG·PNG·WebP 단일 이미지 계약은
그대로 두고, TIFF만 페이지 순서와 bounded preflight를 제공한다.

## 소비자 계약

- `image/tiff`, `image/tif`, `image/x-tiff`를 허용하고 bytes의 TIFF magic을 확인한다.
- native OCR이 꺼져 있으면 TIFF 형식만 검증한 뒤 기존 `UNAVAILABLE` 응답을 반환한다.
- native OCR이 켜져 있으면 `StructuredOcrEngine`을 요구하고
  `TiffMultiPageOcr.suspendRecognize`를 `Dispatchers.IO`에서 호출한다.
- `ImageOcrResponse.pages`, `blocks`, `lines`, `words`는 0부터 시작하는 입력 page index를
  보존하고, `text`는 페이지 사이를 빈 줄 두 개로 구분한다.
- `maxPages`, encoded bytes, page/전체 pixel, decoded side, metadata, text, entry budget은
  `workshop.ocr.tiff.*`로 설정한다. preflight 또는 page/result 실패 시 partial response는
  반환하지 않는다.

## 오류·보안 경계

`TiffMultiPageOcrValidationException`은 400, `TiffMultiPageOcrException`은 422
`ProblemDetail`로 매핑한다. 응답에는 `reason`, `phase`, 선택적 `pageIndex`만 포함하고
native cause, payload, 파일 경로는 노출하지 않는다. `CancellationException`은 서비스와
upstream resource cleanup을 거쳐 그대로 전파한다.

## 호환성·롤백

기존 `OcrEngine` plain fallback, response JSON 필드, Java source/ABI를 변경하지 않는다.
실패 시 새 TIFF 분기와 설정/문서만 되돌리면 기존 세 가지 포맷 경로로 즉시 복귀한다.
