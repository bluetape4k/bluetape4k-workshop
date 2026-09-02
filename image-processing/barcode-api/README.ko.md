# 이미지 Barcode API

[English](README.md) | 한국어

이 Spring Boot 예제는 `bluetape4k-dependencies` `2.0.0` BOM을 통해
`bluetape4k-images` barcode API를 소비합니다. catalog에서는 개별 Bluetape 모듈 버전을
명시하지 않습니다. 애플리케이션 서비스는 provider-neutral `BarcodeReader` 계약만 사용하고,
ZXing 구현은 configuration 경계에서 주입합니다.

## 학습 범위

- `BarcodeReader`, `BarcodeResult`와 `ImmutableImage.extractBarcodes(reader)` 사용
- provider가 픽셀을 확인하기 전에 `immutableExternalImageOf(bytes, ImageDecodeLimits)`를
  최종 bounded decode 경계로 적용. 이 helper는 제한 없는 decoder 호출 전에 encoded input
  초과와 알 수 없거나 초과한 dimension을 거부
- 기존 HTTP `413` 응답 계약을 보존하기 위한 bounded dimension/metadata 사전 확인. 사전
  확인을 통과해도 strict helper가 최종 방어선으로 동작
- PNG/JPEG/WebP 디코드, malformed/unknown-dimension 거부, cancellation-safe multipart 읽기
- smoke test를 위한 결정적인 `/sample`, `/no-result`, `/malformed` 경로

## 실행

```bash
./gradlew :image-processing-barcode-api:test
./gradlew :image-processing-barcode-api:bootRun
```

HTTP API는 multipart `file` 파트를 받는 `POST /api/barcodes/extract`입니다. fixture 경로는
학습과 계약 테스트를 위한 결정적 입력이며 production upload endpoint가 아닙니다. 서비스가
직접 수행하는 크기 사전 확인은 HTTP `413`으로 매핑하고, provider-neutral `BarcodeException`과
coroutine cancellation은 그대로 보존합니다.
