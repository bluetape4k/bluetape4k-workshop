# 이미지 Barcode API

[English](README.md) | 한국어

이 Spring Boot 예제는 `bluetape4k-dependencies` `2.1.0-SNAPSHOT` BOM을 통해
`bluetape4k-images` barcode API를 소비합니다. catalog에서는 개별 Bluetape 모듈 버전을
명시하지 않습니다. 애플리케이션 서비스는 provider-neutral `BarcodeReader` 계약만 사용하고,
ZXing 구현은 configuration 경계에서 주입합니다.

## 이 예제가 필요한 이유

Barcode 추출은 신뢰할 수 없는 업로드 경계에서 시작합니다. 클라이언트는 빠른
scan 결과를 원하지만 service는 크거나 잘못된 image를 decoder에 넘기기 전에
거부해야 합니다. 이 예제는 application service를 ZXing에 결합하지 않고 이런
작은 ingestion 업무의 HTTP 계약을 보여 줍니다.

설계는 provider 선택을 configuration에 두고, decoder 전에 bounded metadata와
pixel 검사를 수행하며, Bluetape strict helper를 최종 방어선으로 사용합니다.
결정적인 fixture route는 성공, 결과 없음, malformed input, HTTP `413` 동작을
확인하게 하지만 production upload, virus scanning, moderation policy를
제공하지는 않습니다.

아래 `test` 명령으로 실행하는 `BarcodeExtractionServiceTest`와
`BarcodeApiApplicationTest`가 이 경계의 test-backed 검증입니다. format, limit,
malformed input, provider failure, cancellation, HTTP 계약을 확인하며 모든
camera나 label에 대한 scanner 정확도를 주장하지 않습니다.

## 학습 범위

- `BarcodeReader`, `BarcodeResult`와 `ImmutableImage.extractBarcodes(reader)` 사용
- provider가 픽셀을 확인하기 전에 `immutableExternalImageOf(bytes, ImageDecodeLimits)`를
  최종 bounded decode 경계로 적용. 이 helper는 제한 없는 decoder 호출 전에 encoded input
  초과와 알 수 없거나 초과한 dimension을 거부
- 기존 HTTP `413` 응답 계약을 보존하기 위한 bounded dimension/metadata 사전 확인. 사전
  확인을 통과해도 strict helper가 최종 방어선으로 동작
- PNG/JPEG/WebP 디코드, malformed/unknown-dimension 거부, cancellation-safe multipart 읽기
- smoke test를 위한 결정적인 `/sample`, `/no-result`, `/malformed` 경로

## 설계 경계와 검증

요청 경로는 디코딩 전에 업로드 메타데이터와 이미지 크기를 확인하고, 제한된 decoder를 거친
이미지만 주입된 `BarcodeReader`에 전달합니다. 이 구조는 HTTP 413 응답과 ZXing adapter를
분리합니다. `BarcodeExtractionServiceTest`는 지원 이미지 형식, 크기 제한, 손상 입력, provider
오류와 취소 처리를 확인하고, `BarcodeApiApplicationTest`는 HTTP 계약을 확인합니다.

## 실행

```bash
./gradlew :image-processing-barcode-api:test
./gradlew :image-processing-barcode-api:bootRun
```

HTTP API는 multipart `file` 파트를 받는 `POST /api/barcodes/extract`입니다. fixture 경로는
학습과 계약 테스트를 위한 결정적 입력이며 production upload endpoint가 아닙니다. 서비스가
직접 수행하는 크기 사전 확인은 HTTP `413`으로 매핑하고, provider-neutral `BarcodeException`과
coroutine cancellation은 그대로 보존합니다.
