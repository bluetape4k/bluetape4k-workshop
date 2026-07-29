# Issue 288 image OCR API lesson

## 배경

Issue #288은 `bluetape4k-images-ocr` 중심의 Spring Boot OCR API workshop example을
추가했다.

## 결정

example은 native OCR을 기본 비활성 상태로 유지한다. 하지만 deterministic
`UNAVAILABLE` fallback을 반환하기 전에 upload byte, media type, image dimension,
decoded pixel, language input을 검증한다.

## 결과

code review는 놓치기 쉬운 workshop risk 세 가지를 발견했다.

- full raster decode 전에 header에서 pixel budget을 확인해야 한다.
- coroutine `withTimeout`만으로는 blocking native call을 제한하지 못한다. interruptible
  boundary를 사용하고 lane release를 테스트한다.
- README curl example은 repository에 실제 존재하는 file을 사용해야 한다.

## 향후 지침

native-library workshop example에는 no-native smoke path, 문서화된 opt-in native path,
fallback contract와 opt-in wiring을 모두 증명하는 test를 제공한다. learner command는
repository root에서 copy-paste 가능하게 유지한다.
