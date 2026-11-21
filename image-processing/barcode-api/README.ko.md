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

## 업무 시나리오와 요구 사항

택배, 티켓, 상품 라벨 이미지를 업로드하면 호출자는 해독된 값을 원합니다.
하지만 service는 파일에 decoder CPU를 쓰기 전에 안전한지 판단해야 합니다.
처음 읽는 사람은 요청을 다음 네 가지 결정으로 나누어 보면 됩니다.

1. multipart payload가 존재하고 비어 있지 않으며 encoded-byte 제한 안에 있는가?
2. 선언한 content type과 image header가 일치하고 decoded dimension/pixel이 제한 안에 있는가?
3. 제한을 통과한 image를 provider-neutral `BarcodeReader`가 읽을 수 있는가?
4. barcode 목록, 명시적인 결과 없음, 안정적인 오류 중 무엇을 반환할 것인가?

애플리케이션 service가 1, 2, 4를 소유하고 ZXing은 3의 adapter일 뿐입니다.
따라서 provider를 바꾸어도 HTTP size 계약이 조용히 바뀌지 않으며, 카메라
이미지에 barcode가 없다는 것은 server failure가 아니라 정상적인 scan 결과입니다.

## 요청과 응답 예시

앱을 실행하고 실제 이미지를 보냅니다.

```bash
curl -i -F "file=@label.png;type=image/png" \
  http://localhost:8080/api/barcodes/extract
```

성공 응답은 provider와 무관한 형태를 유지합니다(실제 값은 입력 이미지에 따라 달라집니다).

```json
{
  "barcodes": [
    { "format": "QR_CODE", "text": "https://example.com/order/42" }
  ]
}
```

읽을 수 있는 code가 없는 image는 빈 결과를 명시적으로 반환합니다. 지원하지
않는 형식, 손상된 입력, 제한 초과 입력은 제한 없는 decoder를 호출하기 전에
거부하며 service의 encoded-size 사전 확인은 그 경계를 HTTP `413`으로 매핑합니다.
결정적인 fixture로 결과를 직접 확인할 수 있습니다.

```bash
curl http://localhost:8080/api/barcodes/sample
curl http://localhost:8080/api/barcodes/no-result
curl http://localhost:8080/api/barcodes/malformed
```

fixture route는 인증, virus scanning, 콘텐츠 보존, 카메라 품질 SLA를 대신하지 않습니다.

## 실패와 경계 표

| 경계 | 외부에서 보이는 결과 | 필요한 이유 |
|---|---|---|
| 비어 있는 multipart | `400 Bad Request` | 사용할 수 없는 upload는 image decoding을 호출하지 않습니다. |
| 지원하지 않는 multipart content type | `415 Unsupported Media Type` | body를 읽거나 decode하기 전에 선언된 media type을 거부합니다. |
| encoded bytes가 service 제한 초과 | `413 Payload Too Large` | decode 전에 request memory와 I/O를 보호합니다. |
| 알 수 없거나 큰 dimension/pixel | `413 Payload Too Large` | decompression bomb와 유사한 decoder CPU/heap 사용을 막습니다. |
| 사전 확인 뒤 malformed image | `400 Bad Request` | 그럴듯한 header가 실제로 decode 가능한 image라는 뜻은 아닙니다. |
| Provider가 barcode를 찾지 못함 | `200 OK`, `barcodes=[]` | “코드 없음”은 정상 scan 결과입니다. |
| Provider 오류 | provider-neutral `BarcodeException` 매핑 | 애플리케이션 계약을 ZXing과 분리합니다. |
| Coroutine cancellation | cancellation을 다시 전파 | 연결이 끊긴 요청을 잘못된 업무 실패로 바꾸지 않습니다. |

## 테스트 계약

| 테스트 | 설명하는 계약 |
|---|---|
| `BarcodeExtractionServiceTest` format cases | PNG/JPEG/WebP가 제한된 입력으로 provider-neutral reader에 전달됩니다. |
| `BarcodeExtractionServiceTest` size/dimension cases | encoded bytes, decoded side, pixel 제한이 provider 작업 전에 실패합니다. |
| `BarcodeExtractionServiceTest` malformed/provider cases | 잘못된 image는 안정적인 오류가 되고 provider 오류와 no-result가 구분됩니다. |
| `BarcodeExtractionServiceTest` cancellation cases | 넓은 catch로 삼키지 않고 structured concurrency를 보존합니다. |
| `BarcodeApiApplicationTest` | multipart binding, fixture route, success/no-result, HTTP 오류 매핑을 wire 경계에서 확인합니다. |

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
