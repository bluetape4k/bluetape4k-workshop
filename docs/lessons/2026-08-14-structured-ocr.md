# Structured OCR API 구현 교훈

## 배경

Issue #736은 기존 OCR API가 반환하던 plain text를 유지하면서
`StructuredOcrEngine`의 page/line/word 결과를 workshop HTTP API에 노출하는 작업이다.
소비자는 native Tesseract 설치 여부와 engine 구현 세부사항을 알지 못하므로, 응답
계약이 capability 차이를 안전하게 설명해야 했다.

## 결정

- 요청의 `structuredDetail`은 기본값을 `PLAIN_TEXT`로 두고, `LINE`·`WORD`는
  runtime `StructuredOcrEngine` capability가 있을 때만 사용한다.
- plain engine에는 새 인터페이스를 강제하지 않는다. 구조화를 요청해도 기존 text를
  반환하고 `effectiveStructuredDetail=PLAIN_TEXT`, 빈 구조화 목록, 명시적 warning을
  함께 제공한다.
- page/line/word의 `confidence`와 `boundingBox`가 원본에서 없을 수 있으므로 nullable
  값을 그대로 보존한다. top-level confidence를 평균으로 추정하지 않는다.
- 기존 `blocks`는 유지해 plain-text consumer의 migration 비용을 낮춘다.

## 결과

구조화 engine의 실제 계층은 DTO로 전달되고, plain engine은 별도 adapter 없이 fallback한다.
controller는 multipart `structuredDetail`을 선택적으로 받으며, 두 locale README에
enum/default/capability/fallback과 JSON 응답 예시를 함께 기록했다. 서비스 테스트는
LINE 매핑, WORD 매핑, plain fallback, nullable metadata, 기존 validation·timeout·
cancellation·sanitized failure 계약을 고정한다.

## 놀라움과 실패에서 얻은 교훈

module 전용 `:image-processing-ocr-api:detekt` task는 저장소 Gradle 구성에 존재하지
않았다. 명령이 없다는 사실을 성공으로 숨기지 않고 root `./gradlew detekt` fallback이
통과했다는 점과 module-level gap을 최종 리뷰에 남겼다. 이후 workshop 모듈의 정적 분석
명령을 추가할 때는 실제 task 존재 여부를 먼저 확인하고, 대체 검증을 사용한 경우 범위를
분리해 보고해야 한다.

## 향후 guard

새 OCR engine을 추가할 때는 다음 회귀 계약을 함께 유지한다.

1. `PLAIN_TEXT`, `LINE`, `WORD` 요청이 engine capability에 맞는 실제 수준을 보고한다.
2. capability가 없는 engine은 text fallback과 `effectiveStructuredDetail`로 결과를
   명시한다.
3. nullable confidence/bounding box를 임의 값으로 채우지 않는다.
4. `README.md`와 `README.ko.md`의 request parameter와 JSON key parity를 검사한다.
5. native dependency가 없는 deterministic fake-engine 테스트와 native opt-in 경로를
   분리한다.
