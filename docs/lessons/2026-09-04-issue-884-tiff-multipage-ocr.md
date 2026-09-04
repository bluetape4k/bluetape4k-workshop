# #884 다중 페이지 TIFF structured OCR lesson

## 배운 점

1. 다중 페이지 입력은 기존 `immutableImageOf` 단일 이미지 검증으로 확장하지 말고,
   upstream `TiffMultiPageOcr.suspendRecognize`의 metadata preflight와 page 경계를 그대로
   소비해야 한다.
2. `maxUploadBytes`와 별도로 page/전체 pixel·metadata·result budget을 설정해야 partial
   response와 resource 폭주를 동시에 막을 수 있다.
3. upstream exception의 message에는 phase가 있지만 native cause가 섞일 수 있으므로 HTTP
   경계에서는 reason/phase/pageIndex만 allow-list로 재구성해야 한다.

## 다음 guard

- 새 multi-page 이미지 소비자는 format magic, declared content type, preflight budget,
  cancellation cleanup을 같은 테스트에 고정한다.
- 새 예제는 root `bluetape4k-dependencies` BOM만 사용하고, README/coverage/stale 등록을
  같은 PR에서 검증한다.
