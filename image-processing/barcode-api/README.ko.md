# 이미지 Barcode API

이 Spring Boot 예제는 `bluetape4k-dependencies` 1.4.0 BOM을 통해 배포된
`bluetape4k-images` 0.4.0의 barcode API를 소비합니다. 애플리케이션 서비스는
provider-neutral `BarcodeReader` 계약만 사용하고, ZXing 구현은 configuration 경계에서
주입합니다.

## 학습 범위

- `BarcodeReader`, `BarcodeResult`와 `ImmutableImage.extractBarcodes(reader)` 사용
- provider를 호출하기 전 encoded byte, decoded side, decoded pixel 제한 적용
- PNG/JPEG/WebP content type 검증과 cancellation-safe multipart 읽기
- smoke test를 위한 결정적인 `/sample`, `/no-result`, `/malformed` 경로

## 실행

```bash
./gradlew :image-processing-barcode-api:test
./gradlew :image-processing-barcode-api:bootRun
```

HTTP API는 multipart `file` 파트를 받는 `POST /api/barcodes/extract`입니다. fixture 경로는
학습과 계약 테스트를 위한 결정적 입력이며 production upload endpoint가 아닙니다.
