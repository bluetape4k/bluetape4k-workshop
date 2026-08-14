# Structured OCR API 설계 명세

상태: 설계 승인 완료(접근 1), 구현 계획 승인 전

작성일: 2026-08-14

대상: issue #736, `image-processing/ocr-api`

## 1. 독자와 목적

이 문서는 `image-processing/ocr-api`가 `bluetape4k-image` 0.4.0의
`StructuredOcrEngine`을 선택적으로 사용해 page/block/line/word OCR 결과를
반환하도록 확장하는 경계를 고정한다. 구현자는 이 명세와 후속 구현 계획이
승인된 뒤에만 소스와 테스트를 변경한다.

주 독자는 이 워크숍 예제의 API 소비자와 구현자다. 설명은 한국어로 작성하되
Kotlin 타입, JSON 필드, 명령, URL, 예외명은 원래 표기를 유지한다.

## 2. 근거와 현재 상태

### 2.1 외부 계약

`bluetape4k-image` 0.4.0의 현재 계약은 다음과 같다.

- [`OcrEngine.kt`](https://github.com/bluetape4k/bluetape4k-image/blob/0.4.0/images-ocr/src/main/kotlin/io/bluetape4k/images/ocr/OcrEngine.kt)는 기존 `recognize(image, options): OcrResult`와 선택 능력인 `StructuredOcrEngine.recognizeStructured(image, options): OcrStructuredResult`를 정의한다.
- [`OcrOptions.kt`](https://github.com/bluetape4k/bluetape4k-image/blob/0.4.0/images-ocr/src/main/kotlin/io/bluetape4k/images/ocr/OcrOptions.kt)는 `structuredDetail`을 `PLAIN_TEXT`, `LINE`, `WORD`로 정의한다. `LINE`은 block과 line을, `WORD`는 block·line·word를 요청한다.
- 같은 파일의 `OcrStructuredResult`는 `pages`를 필수로 보장하고, `blocks`, `lines`, `words`와 각 항목의 nullable `confidence`, nullable `boundingBox`를 제공한다. 좌표가 없을 때 zero-sized box를 만들지 않고 `null`을 사용한다.
- 0.4.0의 `TesseractOcrEngine`은 `StructuredOcrEngine`을 구현하지만, 공급자 타입은 여전히 `OcrEngine`으로 주입할 수 있다. 따라서 소비자 쪽의 능력 확인은 `is StructuredOcrEngine`으로 한다.

### 2.2 현재 워크숍 구현

현재 [`ImageOcrServiceImpl`](../../../image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImpl.kt)은
`OcrOptions`를 만들 때 `structuredDetail`을 지정하지 않고 항상
`OcrEngine.recognize`를 호출한다. 결과의 `text`를 비어 있지 않은 줄로 나눠
기존 `blocks`를 만들며, top-level 및 block `confidence`는 항상 `null`이다.
`OcrEngineProvider`는 `OcrEngine?`을 반환하므로 기존 fake engine과 native
비활성화 경로를 유지할 수 있다.

현재 응답 소비자는 `requestId`, `status`, `engine`, `languages`, `confidence`,
`text`, `blocks`, `warnings`를 사용한다. native disabled, configuration
failure, OCR failure, timeout, cancellation 매핑도 기존 테스트가 고정한다.

### 2.3 사용자 요구

이슈 [#736](https://github.com/bluetape4k/bluetape4k-workshop/issues/736)은
`ImageOcrRequest`에서 `OcrOptions.structuredDetail`을 요청하고,
`StructuredOcrEngine`이 제공하는 page/block/line/word, confidence,
bounding box를 응답에 노출하되 plain-text 및 native unavailable/failure
소비자 계약을 깨지 않도록 요구한다. native 설치와 Tesseract language pack
배포는 범위에서 제외한다.

## 3. 설계 목표와 비목표

### 목표

1. 기존 `OcrEngineProvider`와 `OcrEngine` 주입 계약을 유지한다.
2. HTTP와 서비스 요청에서 `PLAIN_TEXT`, `LINE`, `WORD`를 선택할 수 있게 한다.
3. 구조화 능력이 있는 엔진에서는 source의 page/block/line/word 항목을 그대로 매핑한다.
4. confidence와 bounding box가 없는 source 값은 `null`로 유지하고 임의의 평균·zero-sized 값을 만들지 않는다.
5. 구조화 능력이 없는 엔진은 기존 plain-text 인식으로 안전하게 fallback하고 실제 적용 detail을 응답과 warning으로 명시한다.
6. native disabled, unavailable, failure, timeout, cancellation 및 기존 JSON 필드를 보존한다.
7. English/Korean README의 요청 파라미터와 응답 예제를 함께 갱신한다.

### 비목표

- Tesseract, native library, traineddata를 설치하거나 배포하지 않는다.
- OCR 결과를 저장하거나 비동기 queue, 인증, PII redaction을 추가하지 않는다.
- `bluetape4k-image` 라이브러리의 `StructuredOcrEngine` 구현 자체를 수정하지 않는다.
- top-level `confidence`를 정의되지 않은 평균값으로 계산하지 않는다.
- 기존 `blocks` 필드의 이름과 필수 기본 필드를 제거하거나 다른 JSON 필드로 교체하지 않는다.

## 4. 선택지와 결정

### 접근 1: 능력 확인 + plain-text fallback (선택)

현재 `OcrEngineProvider`를 유지하고 요청 detail이 `PLAIN_TEXT`가 아니면서
실제 엔진이 `StructuredOcrEngine`일 때만 `recognizeStructured`를 호출한다.
그 외에는 기존 `recognize`를 호출해 현재 line-based `blocks`를 반환하고,
`effectiveStructuredDetail=PLAIN_TEXT`와 명시적인 fallback warning을 함께
반환한다.

- 장점: 기존 fake/provider/consumer와 native 오류 매핑을 보존하고, 구조화
  능력이 없는 엔진을 런타임에 안전하게 지원한다.
- 단점: plain engine으로는 word/box를 복구할 수 없으며, 요청한 detail과
  실제 detail이 달라질 수 있다.
- 수용 조건: 응답의 effective detail과 warning을 기계적으로 확인할 수 있어야
  하며, 파생 line에는 confidence와 box를 추가하지 않는다.

### 접근 2: provider 반환형을 `StructuredOcrEngine?`으로 변경

모든 provider가 구조화 엔진을 반환하도록 타입을 바꾼다.

- 장점: 서비스 분기가 단순하다.
- 거부 이유: 기존 `OcrEngine` fake와 소비자 provider의 소스·바이너리 계약을
  불필요하게 깨고, 구조화 능력이 없는 엔진을 표현할 수 없다.

### 접근 3: 항상 `recognizeStructured`를 호출하고 빈 구조를 합성

plain engine을 adapter로 감싸거나 빈 page/word 구조를 합성한다.

- 장점: 응답 모양을 한 경로로 통일할 수 있다.
- 거부 이유: `OcrEngine`에는 해당 메서드가 없고, 실제 source가 제공하지 않은
  page/box/confidence를 있는 것처럼 표현한다. 이는 issue의 nullable 계약과
  소비자 신뢰 경계를 위반한다.

## 5. API 계약

### 5.1 요청

`ImageOcrRequest`에 다음 trailing property를 추가한다.

```kotlin
val structuredDetail: OcrStructuredDetail = OcrStructuredDetail.PLAIN_TEXT
```

기본값은 기존 호출의 source 호환성을 보존한다. HTTP adapter는 선택적
`structuredDetail` request parameter를 받아 같은 enum을 사용하며, 생략하면
`PLAIN_TEXT`를 사용한다. 기존 `language` parameter와 validation 규칙은
변경하지 않는다.

### 5.2 응답

기존 필드는 그대로 유지하고 다음 필드를 trailing default로 추가한다.

```kotlin
val effectiveStructuredDetail: OcrStructuredDetail = OcrStructuredDetail.PLAIN_TEXT
val pages: List<OcrPage> = emptyList()
val lines: List<OcrTextLine> = emptyList()
val words: List<OcrWord> = emptyList()
```

워크숍 DTO의 `OcrPage`, `OcrTextLine`, `OcrWord`는 source의 같은 이름 타입을
직접 노출하지 않고 JSON 경계용 DTO로 정의한다. 각 DTO는 source의
`pageIndex`, `text`, nullable `confidence`, nullable `boundingBox`를 보존한다.
`OcrBoundingBox`는 `x`, `y`, `width`, `height`를 가진다.

기존 `OcrTextBlock`은 다음처럼 확장한다.

```kotlin
data class OcrTextBlock(
    val index: Int,
    val text: String,
    val confidence: Double?,
    val pageIndex: Int? = null,
    val boundingBox: OcrBoundingBox? = null,
)
```

`index`, `text`, `confidence`는 기존 JSON 계약을 유지한다. plain-text
fallback에서 파생한 block은 `pageIndex`와 `boundingBox`를 `null`로 둔다.
구조화 결과에서 매핑한 block은 source의 page index와 box를 채운다.

top-level `confidence`는 기존처럼 `null`을 유지한다. source 항목의 confidence를
평균하거나 최고값으로 집계하는 규칙은 이 API에 정의되어 있지 않으므로
계산하지 않는다.

### 5.3 detail별 결과

| 요청 detail | 구조화 엔진 | plain `OcrEngine` | `effectiveStructuredDetail` |
|---|---|---|---|
| `PLAIN_TEXT` | 기존 `recognize` 결과와 text, legacy `blocks` | 기존 `recognize` 결과와 text, legacy `blocks` | `PLAIN_TEXT` |
| `LINE` | `recognizeStructured`; pages, blocks, lines 매핑 | `recognize`; legacy `blocks`만 line 분할 | `PLAIN_TEXT` |
| `WORD` | `recognizeStructured`; pages, blocks, lines, words 매핑 | `recognize`; legacy `blocks`만 line 분할 | `PLAIN_TEXT` |

구조화 엔진의 `WORD` 응답은 source가 반환한 pages/blocks/lines/words를
그대로 매핑한다. `LINE` 응답에는 words를 포함하지 않는다. source의
`confidence`나 `boundingBox`가 `null`이면 DTO에서도 `null`이다.

plain engine fallback의 `pages`, `lines`, `words`는 비워 둔다. 기존
`blocks`의 line 분할은 legacy 호환을 위한 파생 표현이며 구조화 결과로
주장하지 않는다. fallback warning은 요청 detail과 실제 engine capability를
명시한다.

## 6. 처리 흐름

```text
HTTP structuredDetail(optional)
  -> ImageOcrRequest(default PLAIN_TEXT)
  -> OcrOptions(structuredDetail = request.structuredDetail)
  -> provider.get(): OcrEngine?
       -> PLAIN_TEXT: engine.recognize
       -> LINE/WORD + engine is StructuredOcrEngine:
            engine.recognizeStructured
       -> LINE/WORD + plain OcrEngine:
            engine.recognize + explicit fallback warning
  -> ImageOcrResponse(existing fields + effective detail + structured DTOs)
```

이미지 검증, `nativeSemaphore`, `withTimeout`, `runInterruptible`, 예외 매핑은
현재 순서를 유지한다. `StructuredOcrEngine` 호출도 동일한 bounded native lane
안에서 실행한다.

## 7. 상태·실패·취소 계약

| 상황 | 상태 | detail/구조화 필드 | warning 및 호환성 |
|---|---|---|---|
| native disabled | `UNAVAILABLE` | `PLAIN_TEXT`, 모든 새 목록 empty | 기존 disabled warning 유지 |
| provider가 `null`이거나 `OcrConfigurationException` | `UNAVAILABLE` | `PLAIN_TEXT`, 모든 새 목록 empty | 기존 sanitized unavailable warning 유지 |
| `OcrException` | `FAILED` | `PLAIN_TEXT`, 모든 새 목록 empty | 기존 sanitized failure warning 유지 |
| timeout | `FAILED` | `PLAIN_TEXT`, 모든 새 목록 empty | 기존 timeout warning 유지 |
| coroutine cancellation | 예외 재전파 | 응답 생성 안 함 | 기존 cancellation contract 유지 |
| plain engine으로 `LINE`/`WORD` 요청 | `COMPLETED` | `PLAIN_TEXT`, pages/lines/words empty, legacy blocks 유지 | 요청 detail과 fallback 사유를 추가 warning으로 명시 |
| structured engine이 반환한 nullable metadata | `COMPLETED` | source 값 매핑, null은 null 유지 | zero/default metadata를 생성하지 않음 |

구조화 매핑 중 source 계약 위반으로 예외가 발생하면 기존 `OcrException` 또는
`OcrConfigurationException` 분류를 그대로 따른다. 새 변환 계층에서 예외를
삼켜 성공 응답으로 바꾸지 않는다.

## 8. 호환성과 변경 경계

- `ImageOcrRequest`의 새 trailing default는 기존 3개 인자 source 호출을 유지한다.
- `ImageOcrResponse`의 기존 필드명과 의미, 특히 `blocks`의 line fallback,
  `text`, `status`, `warnings`를 유지한다.
- 새 JSON 필드는 additive다. 기존 소비자는 알 수 없는 필드를 무시한다.
- 기존 `OcrEngineProvider`와 plain fake engine 테스트는 그대로 사용할 수 있다.
- HTTP에서 `structuredDetail`을 생략하면 기존 `PLAIN_TEXT` 응답 경로를 사용한다.
- native 설치와 traineddata는 이 변경에서 자동화하지 않는다.

## 9. 검증 전략과 수용 기준

### 수용 기준

1. `PLAIN_TEXT` 요청은 기존 text와 line-based `blocks`를 반환하고 기존
   disabled/unavailable/failure/timeout/cancellation 테스트가 계속 통과한다.
2. 구조화 fake engine의 `LINE` 응답은 page/block/line, `WORD` 응답은
   page/block/line/word와 source confidence/box를 JSON DTO로 정확히 반환한다.
3. plain fake engine에서 `LINE`/`WORD` 요청 시 기존 text/blocks를 보존하고
   `effectiveStructuredDetail=PLAIN_TEXT`와 fallback warning을 반환한다.
4. source confidence와 box가 `null`인 항목은 `null`이며, top-level
   confidence도 정의되지 않은 집계값으로 채우지 않는다.
5. HTTP `structuredDetail` 생략, `LINE`, `WORD` 요청이 서비스에 올바른
   `ImageOcrRequest`를 전달한다.
6. English/Korean README가 같은 request parameter, response fields,
   fallback semantics, 테스트 명령을 설명한다.
7. `./gradlew :image-processing-ocr-api:test`, `detekt`, `git diff --check`
   및 적용 가능한 모듈 build가 통과한다.

### 테스트 시나리오

- 구조화 fake `OcrEngine`을 `StructuredOcrEngine`으로 구현하고 `LINE` 및
  `WORD`의 options mapping과 결과 매핑을 검증한다.
- confidence와 bounding box가 있는 항목 및 없는 항목을 각각 검증한다.
- plain fake engine에서 `LINE`/`WORD` fallback warning, effective detail,
  legacy blocks 보존을 검증한다.
- native disabled, provider unavailable, `OcrException`, timeout,
  cancellation의 새 목록과 detail 기본값이 비어 있음을 회귀 검증한다.
- controller에서 생략/`LINE`/`WORD` request parameter가 서비스 요청에
  전달되는지 검증한다.
- 기존 controller 응답 JSON 필드와 기존 소비자 생성 코드가 유지되는지
  확인한다.

## 10. 위험과 완화

| 위험 | 영향 | 완화 |
|---|---|---|
| 구조화 엔진 타입 검사가 빠짐 | plain engine에서 구조화 메서드 호출 또는 런타임 실패 | `LINE`/`WORD` 분기에서 `StructuredOcrEngine` capability를 명시적으로 검사 |
| source metadata를 기본값으로 합성 | 소비자가 잘못된 좌표/신뢰도를 사용 | nullable DTO와 source-to-DTO 직접 매핑, top-level 집계 금지 |
| 새 필드 추가로 기존 JSON 테스트가 흔들림 | consumer compatibility 회귀 | 기존 필드·생성자 인자를 유지하고 새 필드는 trailing default로 추가 |
| HTTP enum 값과 service enum 값이 불일치 | 요청 detail이 조용히 `PLAIN_TEXT`로 변환됨 | controller binding 테스트와 README의 정확한 `PLAIN_TEXT`/`LINE`/`WORD` 표기 |
| 구조화 호출이 timeout/cancellation 경계를 벗어남 | native lane 점유 또는 취소 전파 회귀 | 기존 `withTimeout`/`runInterruptible` 내부에서 호출하고 전용 회귀 테스트 추가 |

## 11. 구현 전 확인되지 않은 사항

- `bluetape4k-image` 0.4.0의 source DTO는 위 URL에서 확인했다. 이 명세는
  해당 release contract를 기준으로 하며, 구현 중 resolved dependency가 다른
  API를 선택하면 구현을 중단하고 명세를 먼저 갱신한다.
- native Tesseract가 실제 설치된 환경에서의 OCR 품질은 이 예제의 결정적
  테스트 범위가 아니다. fake structured result와 기존 native failure mapping으로
  API 경계를 검증한다.

## 12. Writer DoD

- SPW-01: 완료 — issue #736, local service/model/controller/test 경로와
  `bluetape4k-image` 0.4.0 공식 source URL, 독자·언어·목적을 고정했다.
- SPW-02: 완료 — 목표/비목표, 세 가지 선택지와 결정, 요청·응답 계약,
  처리 흐름, 실패·호환성, 수용 기준, 테스트, 위험을 포함했다.
- SPW-03: 완료 — 한국어 기술 문체와 용어를 검토했고 code token, URL,
  enum, 숫자, 불확실성을 보존했다. `references/korean-naturalness-checklist.md`
  KO-01~KO-06을 적용했다.
- SPW-04: 완료 — 공식 `OcrEngine.kt`, `OcrOptions.kt`, `TesseractOcrEngine.kt`
  및 현재 워크숍 구현·테스트를 대조해 source-to-claim 경계를 확인했다.
- SPW-05: 완료 — Markdown 전체를 read-back했고 링크 대상 local source,
  heading/table/code fence 구조, placeholder 부재, `git diff --check` 결과를
  확인했다. 구현 계획 승인 전까지 이 명세를 수정하지 않는다.

## 13. Step 2-R 설계 검토

동일한 issue·공식 source·현재 워크숍 구현을 기준으로 여섯 관점의 독립
검토를 수행했다. P0/P1은 발견되지 않았다.

| 우선순위 | 관점 | 근거 | 조치 |
|---|---|---|---|
| N/A | Performance | 구조화 호출은 기존 native semaphore와 timeout 안에서 한 번 실행하며, 추가 네트워크 왕복이나 재시도는 없다. | 워크숍 범위에서는 별도 benchmark를 만들지 않고 mapping/timeout 회귀 테스트로 고정한다. |
| N/A | Stability | `runInterruptible`, `withTimeout`, cancellation 재전파와 provider 예외 매핑을 기존 경계에서 유지한다. | timeout·cancellation·lane release 테스트를 구현 계획에 포함한다. |
| N/A | Security | 새 입력은 enum과 기존 이미지 검증 경계 안에 있고, native path나 OCR text를 warning으로 노출하지 않는다. | HTTP binding과 sanitized failure 회귀 테스트를 유지한다. |
| N/A | Operator/Ops | native disabled/unavailable/failure는 기존 status와 warning을 유지하고, 실제 detail은 `effectiveStructuredDetail`로 관찰할 수 있다. | 로그 형식과 native 설치 범위는 변경하지 않는다. |
| N/A | Developer/API | 기존 `blocks`와 생성자 인자를 보존하면서 새 구조 DTO를 additive로 추가하고 plain capability fallback을 명시했다. | DTO mapping 및 기존 controller/service consumer 회귀 테스트를 계획한다. |
| N/A | User/caller | README에서 세 detail, nullable metadata, unsupported fallback을 같은 의미로 설명할 수 있다. | English/Korean README parity 검사를 구현 계획에 포함한다. |

통합 검토 결과, 명세의 경계·실패 모드·대안·수용 기준·호환성·운영 동작이
서로 모순되지 않는다. P2/P3 후속 항목도 없으며, 구현 계획에서는 위 조치를
각 파일과 테스트 단계로 구체화한다.

구현 계획과 구현은 이 명세에 대한 사용자 검토·승인 후 진행한다.
