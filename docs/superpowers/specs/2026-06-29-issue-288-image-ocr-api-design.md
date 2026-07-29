# Issue #288 - 이미지 OCR API 워크샵 디자인

**날짜**: 2026-06-29
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/288
**마일스톤**: 1.2.0
**상태**: 구현 계획 준비 완료

---

## 1. 목표

노출 방법을 가르치는 `image-processing/ocr-api` 워크숍 모듈을 추가합니다.
`bluetape4k-image` OCR Spring Boot 4 멀티파트 API를 만들지 않고
기본 연기 경로에는 기본 Tesseract 바이너리가 필수입니다.

모듈은 학습자에게 다음 방법을 보여주어야 합니다.

- JPEG, PNG 또는 WebP 이미지를 `POST /api/images/ocr`에 업로드합니다.
- 멀티파트 메타데이터, 디코딩된 이미지 콘텐츠, 크기 및 언어의 유효성을 검사합니다.
  옵션;
- `immutableImageOf`으로 이미지 바이트를 디코딩합니다.
- 네이티브 OCR가 명시적으로 활성화되면 `bluetape4k-images-ocr` `OcrEngine`을 호출합니다.
- 언어, null 허용 신뢰도, 텍스트가 포함된 구조화된 OCR 응답을 반환합니다.
  차단 및 경고;
- 네이티브 OCR이 비활성화된 경우 결정적 대체 응답을 노출하거나
  없는;
- 빠른 CI 스모크에 추가하지 않고 로컬에서 기본 Tesseract 실행을 선택합니다.

## 2. 출처 증거

| 소스 | 증거 |
|--------|----------|
| GitHub 발행 #288 | 실행 가능한 OCR API 워크샵, 구조화된 출력, 기본 OCR을 사용할 수 없는 경우 대체, 결정적 연기 테스트, README/README.ko 전제 조건 및 개별 이미지 가져오기 없음 BOM이 필요합니다. |
| `settings.gradle.kts` | `includeModules("image-processing", false, true)`은 `image-processing/*` 모듈을 Gradle 프로젝트로 자동 등록합니다. `image-processing/ocr-api`은 `:image-processing-ocr-api`에 매핑됩니다. |
| `image-processing/advanced-workflow` | Spring Boot 4가지 이미지 처리 모듈 모양, `@WebMvcTest` 비동기 디스패치 테스트 패턴, README 로케일 패리티 및 이미지 패키지 접두사 규칙을 제공합니다. |
| `bluetape4k-image/images` | `immutableImageOf(bytes: ByteArray)`은 Scrimage를 통해 멀티파트 바이트를 `ImmutableImage`로 디코딩합니다. |
| `bluetape4k-image/images-ocr` | `OcrEngine.recognize(image, options)`은 `OcrResult(text, options)`를 반환합니다. `OcrConfigurationException`은 기본 라이브러리, tessdata 또는 언어 팩 설정이 누락되었음을 나타냅니다. |
| `images-ocr/README.md` | 기본 OCR 테스트는 `-Docr.enabled=true`에 선택적으로 적용됩니다. 기본 테스트에서는 Tesseract 시스템이 필요하지 않습니다. |
| 워크샵 레포 규칙 | 새로운 예제에는 README 로캘 패리티, 생성된 PNG/SVG 다이어그램, 유효성 검사 매트릭스 업데이트 및 모듈이 연기에 안전한 경우 CI/smoke 적용 범위가 필요합니다. |

## 3. 논골

- `image-processing/advanced-workflow`을 다시 쓰지 마십시오.
- 업스트림 `bluetape4k-image` OCR 예제를 도매로 복사하지 마세요.
- Tesseract, tessdata, Docker OCR 컨테이너 또는 기본 패키지를 추가하지 마세요.
  기본 CI 연기 경로에 설치합니다.
- 지속성, S3 스토리지, 큐, 재시도 또는 파생 생성을 추가하지 마십시오.
- 현재로부터 단어 수준이나 블록 수준의 신뢰를 검증문하지 마십시오.
  `OcrResult`; 텍스트와 효과적인 옵션만 제공합니다.
- API 응답에서 원시 기본 예외 메시지나 스택 추적을 반환하지 마세요.
- 개별 `bluetape4k-image` BOM 또는 노골적인 bluetape4k 이미지를 추가하지 마세요.
  버전.

## 4. 옵션

### 옵션 A - 네이티브 전용 OCR 엔드포인트

항상 `TesseractOcrEngine`을 구성하는 Spring Boot 엔드포인트를 생성하고
Tesseract 또는 tessdata가 없으면 실패합니다.

**거부됨**: #288 시스템 없이 결정론적 연기 테스트가 필요합니다OCR
네이티브 OCR을 사용할 수 없는 경우 바이너리 및 명시적 대체 동작.

### 옵션 B - 기본 엔진이 선택되어 있는 연기 방지 OCR 게이트웨이

작은 서비스 경계로 `image-processing/ocr-api`을 만듭니다. 서비스
기본 OCR이 비활성화된 경우 결정적 `UNAVAILABLE` 응답을 반환합니다.
`workshop.ocr.native-enabled=true` 또는 `TesseractOcrEngine`인 경우에만 `TesseractOcrEngine`을 사용합니다.
`-Docr.enabled=true`이 설정되었습니다. 테스트는 완료된 OCR에 대해 가짜 `OcrEngine`를 주입합니다.
경로.

**채택됨**: 이는 문제를 만족시키고 빠른 CI 안정성을 유지하며 여전히 가르칩니다.
실제 bluetape4k OCR API 경계.

### 옵션 C - 설비 전용 CLI/service 예

멀티파트 HTTP을 건너뛰고 고정 장치 기반 서비스 테스트를 통해 OCR을 시연합니다.

**거부됨**: #288은 OCR API 워크숍 예제와 Spring을 명시적으로 요청합니다.
부팅 4 엔드포인트 적용 범위.

## 5. 제안 모듈

```
image-processing/ocr-api/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/
    ImageOcrApiApplication.kt
    config/ImageOcrProperties.kt
    model/ImageOcrModels.kt
    service/ImageOcrService.kt
    service/ImageOcrServiceImpl.kt
    service/NativeOcrEngineConfig.kt
    web/ImageOcrController.kt
    web/ImageOcrExceptionHandler.kt
  src/main/resources/application.yml
  src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/
    service/ImageOcrServiceImplTest.kt
    web/ImageOcrControllerTest.kt
  src/test/resources/junit-platform.properties
  src/test/resources/logback-test.xml

docs/images/readme-diagrams/
  image-ocr-api-readme-architecture-01.svg
  image-ocr-api-readme-architecture-01.png
  image-ocr-api-readme-sequence-01.svg
  image-ocr-api-readme-sequence-01.png
```

README 이미지 링크는 `../../docs/images/readme-diagrams/...`를 사용해야 합니다.

### 런타임 종속성

기존 저장소 규칙을 통해서만 워크샵 종속성 BOM을 사용하십시오.
누락된 경우 버전 없는 로컬 카탈로그 별칭을 추가합니다.

- `bluetape4k-images-ocr`

예제 모듈은 다음에 의존해야 합니다.

- `bluetape4k-core`
- `bluetape4k-logging`
- `bluetape4k-jackson3`
- `bluetape4k-images`
- `bluetape4k-images-ocr`
- Kotlin 코루틴 코어
- Spring Boot 자동 구성, 프로세서 구성, 검증 및 웹 MVC
- `testImplementation(project(":shared"))`
- `bluetape4k-junit5`, `bluetape4k-assertions`, MockK, springmockk, 코루틴
  테스트, Spring Boot 웹 MVC 테스트, Spring Boot 테스트

네이티브 이미지, Testcontainers, 지속성 또는 벤치마킹을 추가하지 마세요.
의존성.

## 6. API 계약서

### 엔드포인트

`POST /api/images/ocr`

- `multipart/form-data` 소비
- 필수 멀티파트 이미지로 `file`을 허용합니다.
- 선택적 반복 또는 쉼표로 구분된 `language` 값을 허용하며 기본값은 다음과 같습니다.
  `eng`
- 완료된 OCR 및 fallback/unavailable OCR 응답에 대해 `200 OK`을 반환합니다.
- 빈 파일, 지원되지 않는 콘텐츠 유형, 유효하지 않은 경우 `400 Bad Request`을 반환합니다.
  언어 값, 디코딩할 수 없는 이미지 바이트, 지원되지 않는 이미지 하위 유형, 디코딩됨
  픽셀 제한 위반 또는 구성된 바이트 제한을 초과하는 파일

`FAILED`은 유효한 이미지가 생성된 후 삭제된 OCR 런타임 실패를 위해 예약되어 있습니다.
기본 지원 서비스 경로를 입력했습니다. 구조화된 `200 OK`으로 반환됩니다.
학습자가 예외 없이 응답 계약을 검사할 수 있는 워크숍 데이터
손질; 잘못된 요청은 `400 Bad Request` 상태로 유지됩니다.

### 검증 요청

- `file`은(는) 비워둘 수 없습니다.
- `file.contentType`은(는) `image/`로 시작해야 합니다.
- `file.size`은(는) 다음보다 작거나 같아야 합니다.
  `workshop.ocr.max-upload-bytes`.
- 언어는 공백이 아닌 ASCII 식별자와 일치해야 합니다.
  `[A-Za-z][A-Za-z0-9_+-]*`.
- 순서를 유지하면서 유효한 언어 목록의 중복을 제거해야 합니다.

### 응답 모델

`ImageOcrResponse`:

- `requestId`: 예제 추적을 위해 안정적인 응답 ID 생성
- `status`: `COMPLETED`, `UNAVAILABLE` 또는 `FAILED`
- `engine`: `tesseract` 또는 `disabled`
- `languages`: 유효 언어 목록
- `confidence`: null 가능 `Double`; 현재 텍스트 전용 엔진의 경우 `null`
  계약
- `text`: 전체 정규화된 텍스트
- `blocks`: 공백이 아닌 줄 기반 `OcrTextBlock` 항목
- `warnings`: 학습자 대상 경고, 원시 스택 추적 없음

`OcrTextBlock`:

- `index`
- `text`
- `confidence`: null 허용 `Double`

서비스는 신뢰도가 `null`일 때마다 경고를 추가해야 합니다.
현재 `OcrResult`은 블록별 신뢰도를 노출하지 않습니다. 이 경고는 일부입니다.
학습 계약의 오류가 아닙니다.

### 기본 엔진 선택

기본 동작:

- `workshop.ocr.native-enabled=false`
- `-Docr.enabled=true`은 로컬 실행 시 기본 OCR을 활성화할 수도 있습니다. 기본 OCR은(는)
  Spring 속성 또는 시스템 속성이 `true`인 경우 활성화됩니다.
- 기본 비활성화 요청은 명시적인 경고와 함께 `UNAVAILABLE`을 반환하고
  Tesseract에 전화하지 마세요. 그들은 여전히 ​​바이트의 유효성을 검사하고, 미디어 유형을 선언하고, 디코딩했습니다.
  이미지 콘텐츠 및 디코딩된 픽셀 수로 인해 손상되거나 스푸핑된 업로드는 실패합니다.
  `400 Bad Request`를 소독했습니다.

네이티브 지원 동작:

- `immutableImageOf(bytes)`을 사용하여 다중 부분 바이트 디코딩
- 디코딩할 수 없는 바이트를 삭제된 `400 Bad Request`으로 거부
- `width * height`이(가) 초과하는 디코딩된 이미지를 거부합니다.
  `workshop.ocr.max-image-pixels`
- `OcrOptions(languages = ..., tessdataPath = property)` 빌드
- Spring이 소유한 조건부 싱글톤 `OcrEngine`을 호출합니다. 엔진이 아니다
  요청에 따라 생성되며 현재 API에 close/shutdown 후크가 없습니다.
- 제한된 네이티브 OCR 내에서 바이트 구체화, 이미지 디코드 및 OCR을 실행합니다.
  `workshop.ocr.timeout` 및 단일 비행 세마포어를 사용한 경계
- `OcrConfigurationException`를 `UNAVAILABLE`에 매핑
- 다른 OCR 실패를 `FAILED`에 매핑
- 일시 중지된 경우 광범위한 예외 처리 전에 `CancellationException`을 다시 발생시킵니다.
  코드가 사용됩니다
- 삭제된 진단만 작성: `requestId`, 상태, 엔진, 언어 목록,
  기본 활성화 플래그, 경과 시간 및 실패 범주. 업로드된 로그 없음
  바이트, OCR 텍스트, 원시 기본 메시지, tessdata 경로 또는 스택 추적.

## 7. README 및 다이어그램

`README.md` 및 `README.ko.md` 모두 다음을 포함해야 합니다.

- 제목 바로 아래에서 언어를 전환합니다.
- SVG 소스와 일치하는 아키텍처 다이어그램 PNG;
- SVG 소스와 일치하는 시퀀스 다이어그램 PNG;
- 엔드포인트 요약 및 컬 멀티파트 예시;
- 기본 비활성화 폴백에 대한 응답 JSON 예;
- fake/deterministic 테스트 경로를 사용하여 완료된 OCR에 대한 응답 JSON 예;
- `workshop.ocr.native-enabled`에 대한 속성 테이블,
  `workshop.ocr.max-upload-bytes`, `workshop.ocr.max-image-pixels`,
  `workshop.ocr.timeout`, `workshop.ocr.languages` 및
  `workshop.ocr.tessdata-path`;
- `spring.servlet.multipart.max-file-size`에 대한 Spring 다중 부분 제한 참고 사항 및
  `spring.servlet.multipart.max-request-size`, 정렬됨
  `workshop.ocr.max-upload-bytes`;
- 기본 Tesseract/tessdata 전제 조건 및 로컬 옵트인 명령:
  `./gradlew :image-processing-ocr-api:test -Docr.enabled=true`;
- 기본 대체 `bootRun` 명령, 기본 활성화 `bootRun` 명령
  `workshop.ocr.native-enabled=true` 또는 `-Docr.enabled=true`, tessdata 경로
  예, 두 모드 모두에 대해 일치하는 컬 명령;
- macOS 및 Linux Tesseract 설치 예제와 tessdata/language-pack
  검증 단계;
- 기본 연기 명령:
  `./gradlew :image-processing-ocr-api:test`;
- 기본 CI 연기에는 Tesseract가 필요하지 않다는 설명입니다.
- 현재 OCR 엔진 때문에 신뢰도가 null이 가능하다는 설명
  계약은 텍스트 전용 결과를 반환합니다.
- 네이티브 라이브러리 누락, 언어 팩 누락, 손상 이슈 해결
  이미지 바이트, 스푸핑된 콘텐츠 유형, 지원되지 않는 이미지 하위 유형 및 디코딩됨
  픽셀 제한 거부;
- 보이는 작업장 경계 설명: 이 로컬 예에는 인증, 바이러스 백신이 없습니다.
  스캐닝, 지속성, 속도 제한, 스토리지 정책, 대기열, 감사
  워크플로우, PII/document-management 보증 또는 프로덕션 업로드 강화;
- OCR 텍스트에 민감한 데이터가 포함될 수 있으므로 기록해서는 안 된다는 경고
  수정 정책 없이 프로덕션 시스템에서 반환되었습니다.
- 안정적인 샘플을 사용하여 결정론적 폴백 및 완성된 JSON 예제
  `requestId`, `status`, `engine`, `languages`, null 허용 신뢰도, 블록,
  그리고 경고;
- bluetape4k 버전은 다음에 의해 관리된다는 종속성 참고 사항
  `bluetape4k-dependencies`.

다이어그램 레이블은 영어로 유지되므로 동일한 자산을 README에서 공유할 수 있습니다.
파일. 아키텍처 다이어그램은 현재 다이어그램과 일치하도록 위에서 아래로 흘러야 합니다.
아키텍처 다이어그램 방향 기본 설정.

## 8. CI 및 검증 매트릭스

기본 실행은 기본적으로 비활성화되어 있고 결정적이기 때문에:

- `:image-processing-ocr-api:test`을 `scripts/smoke-validate.sh all-smoke`에 추가;
- 업데이트 부실 확인 예상 Gradle 현재 관찰된 프로젝트 수
  `80` to `81`;
- `.github/workflows/Examples.yml` 푸시에 `image-processing/ocr-api/**` 추가
  풀 요청 경로 필터;
- 기존 H2/default 예에 `:image-processing-ocr-api:test` 추가
  연기 Gradle 명령;
- 기존 `smoke-examples` 시간 초과를 변경하지 않고 유지합니다.
- `image-processing/ocr-api/build/test-results/test/*.xml`을 포함하고
  연기 예제의 `image-processing/ocr-api/build/reports/tests/test/`
  아티팩트 업로드;
- 루트 `README.md` 및 `README.ko.md` 이미지 처리 모듈 카탈로그 업데이트
  항목.

Nightly는 이미 `scripts/smoke-validate.sh을 통해 연기 방지 모듈에 도달했습니다.
all-smoke`이므로 스크립트 업데이트가 기본 Nightly 통합 지점입니다.

## 9. 합격기준

- [ ] `image-processing/ocr-api`은(는) 다음으로 등록됩니다.
  `:image-processing-ocr-api` 기존 자동 모듈 규칙을 따릅니다.
- [ ] 모듈은 `POST /api/images/ocr`을 Spring Boot 4 멀티파트로 노출합니다.
  엔드포인트.
- [ ] 기본 연기 테스트에는 시스템 Tesseract 또는 tessdata가 필요하지 않습니다.
- [ ] 기본 비활성화 요청은 구조화된 `UNAVAILABLE` 출력을 반환합니다.
  명확한 경고.
- [ ] 기본 지원 서비스 테스트는 가짜 `OcrEngine`를 삽입하고 반환할 수 있습니다.
  구조화된 `COMPLETED` 출력.
- [ ] `OcrConfigurationException`은 구조화된 `UNAVAILABLE` 출력에 매핑됩니다.
- [ ] 유효성 검사에서는 빈 파일, 이미지가 아닌 콘텐츠 유형, 크기가 큰 파일,
  그리고 유효하지 않은 언어.
- [ ] 검증에서는 디코딩할 수 없는 바이트가 있는 스푸핑된 `image/*` 페이로드를 거부합니다.
- [ ] 유효성 검사에서는 JPEG, PNG 및 WebP 외부에서 지원되지 않는 이미지 하위 유형을 거부합니다.
- [ ] 유효성 검사에서는 `workshop.ocr.max-image-pixels` 위의 디코딩된 이미지를 거부합니다.
- [ ] Spring 멀티파트 제한은 `workshop.ocr.max-upload-bytes`에 맞춰 정렬됩니다.
- [ ] 기본 OCR 실행에는 시간 초과 및 단일 비행 동시성 보호 기능이 있습니다.
- [ ] 기본 비활성화 폴백은 업로드 바이트와 디코딩된 이미지 형태를 검증합니다.
  유효성 검사가 성공한 후에만 OCR을 건너뜁니다.
- [ ] 광범위한 예외 처리 전에 취소가 다시 발생합니다.
- [ ] 응답에는 언어, Null 허용 신뢰도, 텍스트 블록 및
  경고.
- [ ] API 응답은 원시 기본 예외 스택 추적을 노출하지 않습니다.
- [ ] 로그에는 업로드된 바이트, OCR 텍스트, 원시 기본 메시지,
  tessdata 경로 또는 스택 추적.
- [ ] 저장소는 bluetape4k에 `bluetape4k-dependencies` BOM만 사용합니다.
  버전.
- [ ] README/README.ko 문서 전제조건, 로컬 선택, 기본값 skip/fallback
  동작 및 집중 테스트 명령.
- [ ] README 다이어그램에는 SVG 소스, 렌더링된 PNG 및 시각적 QA 증거가 있습니다.
- [ ] CI/smoke 검증에는 `Examples.yml`의 새로운 연기 방지 모듈이 포함됩니다.
  `smoke-validate.sh all-smoke` 및 오래된 확인 예상 개수입니다.
- [ ] 기여자 검증에는 다음이 포함됩니다.
  `./gradlew :image-processing-ocr-api:test`,
  `./scripts/smoke-validate.sh all-smoke`,
  `./scripts/smoke-validate.sh stale-check`, README 유효성 검사기, 다이어그램
  유효성 검사기, 워크플로 린트 및 `git diff --check`.

## 10. 위험

- Tesseract 동작은 설치된 언어 데이터 및 플랫폼에 따라 다릅니다. 토종의
  OCR은(는) 로컬 선택 상태로 유지되며 기본 연기 경로에서 검증문되지 않습니다.
- `OcrResult`에는 현재 신뢰 메타데이터가 부족합니다. 작업장은 만들어야 한다
  값을 만들어내는 것이 아니라 명시적인 null 허용 신뢰도입니다.
- 초보자에게 친숙한 모듈을 위해 멀티파트 파일이 메모리에 복사됩니다. 바이트
  한계는 가드레일입니다.
- `all-smoke`은 관련되지 않은 기존 오류를 노출할 수 있습니다. 그렇다면 집중된 작업을 다시 실행하세요.
  모듈을 테스트하고 관련 없는 오류를 증거와 함께 기록합니다.
