# Issue #288 - 이미지 OCR API 워크숍 계획

**날짜**: 2026-06-29
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/288
**사양**: `docs/superpowers/specs/2026-06-29-issue-288-image-ocr-api-design.md`
**모듈**: `image-processing/ocr-api` -> `:image-processing-ocr-api`
**상태**: 3-R단계 검토 초안

---

## 1. 인코딩된 결정

- `settings.gradle.kts`은(는) 이미 `image-processing/*`를 자동 검색합니다. 아니요
  설정 수정이 예상됩니다.
- 모듈은 기본적으로 연기로부터 안전합니다. 명시적으로 명시하지 않는 한 기본 OCR은 비활성화됩니다.
  선택했습니다.
- 저장소 종속성 권한은 `bluetape4k-dependencies`입니다. 아니요
  개별 이미지 BOM가 추가됩니다.
- 현재 OCR 결과 계약은 텍스트 전용이므로 응답 신뢰도 필드
  null이 가능하고 문서화되어 있습니다.
- 기본 설정 실패는 구조화된 `UNAVAILABLE` 응답으로 표시됩니다.
  원시 예외 페이로드가 아닙니다.
- 멀티파트 업로드는 워크숍을 위해 의도적으로 메모리에 로드되어 보호됩니다.
  `workshop.ocr.max-upload-bytes`으로 정렬하고 Spring 멀티파트 제한을 정렬했습니다.
- 기본 OCR 작업은 `workshop.ocr.timeout` 및 단일 비행으로 제한됩니다.
  네이티브 처형 가드. 기본 비활성화 대체는 여전히 선언된 유효성을 검사합니다.
  OCR을 건너뛰기 전 미디어 유형, 디코딩 가능성 및 디코딩된 픽셀 수.
- 네이티브 OCR이 구조화된 `FAILED` 반환을 시작한 후 유효한 요청 실패
  작업장 데이터 `200 OK`; 잘못된 upload/language/decode/dimension 실패
  위생 처리된 `400 Bad Request`를 반환합니다.

## 2. 추진과제

### T1 - 카탈로그 작성 및 비계 구축

- **파일**:
  - `gradle/libs.versions.toml`
  - `image-processing/ocr-api/build.gradle.kts`
  - `image-processing/ocr-api/src/main/resources/application.yml`
  - `image-processing/ocr-api/src/test/resources/junit-platform.properties`
  - `image-processing/ocr-api/src/test/resources/logback-test.xml`
- **행동**:
  - `bluetape4k-images-ocr`에 대한 버전 없는 별칭을 추가합니다.
  - 다음에 모듈 종속성을 추가합니다.
    - `implementation(libs.bluetape4k.core)`
    - `implementation(libs.bluetape4k.logging)`
    - `implementation(libs.bluetape4k.jackson3)`
    - `implementation(libs.bluetape4k.images)`
    - `implementation(libs.bluetape4k.images.ocr)`
    - `implementation(libs.kotlinx.coroutines.core.lib)`
    - `implementation(libs.spring.boot.autoconfigure.lib)`
    - `annotationProcessor(libs.spring.boot.autoconfigure.processor)`
    - `annotationProcessor(libs.spring.boot.configuration.processor)`
    - `runtimeOnly(libs.spring.boot.devtools)`
    - `implementation(libs.spring.boot.starter.validation)`
    - `implementation(libs.spring.boot.starter.webmvc.lib)`
  - `project(":shared")`, `bluetape4k-junit5`에 테스트 종속성을 추가합니다.
    `bluetape4k-assertions`, MockK, springmockk, 코루틴 테스트, 웹 MVC 테스트,
    그리고 Spring Boot 테스트.
  - Testcontainers, 네이티브 이미지, 지속성 또는 벤치마크를 추가하지 마세요.
    의존성.
  - `springBoot.mainClass`을 새 애플리케이션 클래스로 설정합니다.
  - `ocr.enabled`을 옵트인 시스템 속성으로만 테스트에 전달하세요. 기본값은
    장애가 있는.
  - `workshop.ocr.max-upload-bytes`에 대한 `application.yml` 기본값을 구성합니다.
    `workshop.ocr.max-image-pixels` 및 `workshop.ocr.timeout`; 맞추다
    `spring.servlet.multipart.max-file-size` 그리고
    `spring.servlet.multipart.max-request-size` 바이트 제한이 동일합니다.
- **DoD**:
  - `./gradlew :image-processing-ocr-api:dependencies --configuration testRuntimeClasspath`이 해결되었습니다.
  - 로컬 이미지 BOM 또는 명시적인 bluetape4k 이미지 버전이 모듈에 표시되지 않습니다.
  - native/package 설치 종속성은 도입되지 않습니다.

### T2 - 먼저 서비스 테스트 실패

- **파일**:
  - `image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImplTest.kt`
- **행동**:
  - 프로덕션 구현 전에 테스트를 작성합니다.
    - 기본 비활성화된 유효한 이미지는 `UNAVAILABLE`, `engine=disabled`을 반환합니다.
      유효한 언어, 비어 있는 text/blocks 및 OCR이 비활성화되었다는 경고;
    - 가짜 네이티브 엔진은 정규화된 `COMPLETED`, `engine=tesseract`을 반환합니다.
      전체 텍스트, 공백이 아닌 줄 기반 블록, 효과적인 언어 및 null 허용
      자신감 경고;
    - `OcrConfigurationException`은 삭제된 경고와 함께 `UNAVAILABLE`에 매핑됩니다.
    - 일반 `OcrException`은 삭제된 경고와 함께 `FAILED`에 매핑됩니다.
    - 잘못된 언어 값은 거부됩니다.
    - 빈 이미지 바이트는 엔진 호출 전에 거부됩니다.
    - 너무 큰 이미지 바이트는 엔진 호출 전에 거부됩니다.
    - 이미지 미디어 유형이 있는 비어 있지 않은 손상된 바이트는 삭제된 것으로 거부됩니다.
      OCR 호출 전 입력이 잘못되었습니다.
    - 기본 비활성화된 손상된 바이트도 삭제된 잘못된 입력으로 거부됩니다.
    - `maxImagePixels` 위의 디코딩된 이미지는 OCR 호출 전에 거부됩니다.
    - 가짜 엔진 `CancellationException`이(가) 다시 발생하고 매핑되지 않습니다.
      `FAILED` 또는 `UNAVAILABLE`;
    - 경고에서는 원시 기본 메시지, 스택 추적, tessdata 경로가 제외됩니다.
      로컬 파일 시스템 경로.
  - `BufferedImage`/`ImageIO`을 통해 생성된 작은 인메모리 PNG를 사용하여
    픽스처 파일이 아닌 성공적인 디코드 테스트.
  - 도움이 되는 경우 bluetape4k 어설션과 MockK을 사용하세요.
  - 기본 Tesseract, 절전 모드, 반복 테스트 및 컨테이너를 피하세요.
- **DoD**:
  - 서비스 및
    모델이 구현되지 않았습니다.

### T3 - 먼저 웹 테스트 실패

- **파일**:
  - `image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrControllerTest.kt`
- **행동**:
  - 전에 `@WebMvcTest(controllers = [ImageOcrController::class])` 테스트를 추가합니다.
    컨트롤러 구현:
    - multipart POST는 모의 서비스에서 JSON 응답을 반환합니다.
    - 반복되는 `language` 매개변수가 서비스에 도달합니다.
    - 쉼표로 구분된 언어 값, 반복+쉼표 혼합 값,
      중복 제거 순서, empty/default 언어 동작 및 유효하지 않음
      언어 400 매핑이 포함됩니다.
    - 빈 업로드는 예외 처리기를 통해 400에 매핑됩니다.
    - 이미지가 아닌 콘텐츠 유형은 예외 처리기를 통해 400에 매핑됩니다.
    - 지원되지 않는 이미지 하위 유형은 400에 매핑됩니다.
    - 스푸핑된 이미지 콘텐츠 유형이 삭제된 400으로 매핑된 손상된 바이트입니다.
  - 컨트롤러를 `suspend fun`으로 구현하고 MockMvc 멀티파트 사용
    `request().asyncStarted()`과 `asyncDispatch`이 포함된 요청, 일치
    기존 Spring MVC 코루틴 테스트 패턴.
  - `ImageOcrService`에는 `@MockkBean`을 사용하세요.
- **DoD**:
  - 누락으로 인해 초기 `./gradlew :image-processing-ocr-api:test`이(가) 여전히 실패합니다.
    구현이 추가될 때까지 controller/web 클래스.

### T4 - 모델, 속성 및 서비스 구현

- **파일**:
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/config/ImageOcrProperties.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/model/ImageOcrModels.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrService.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImpl.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/NativeOcrEngineConfig.kt`
- **행동**:
  - 기본값으로 `@ConfigurationProperties("workshop.ocr")`을 구현합니다.
    - `nativeEnabled=false`
    - `maxUploadBytes=5_242_880`
    - `maxImagePixels=12_000_000`
    - `timeout=10s`
    - `languages=["eng"]`
    - `tessdataPath=null`
  - `enum class OcrStatus`에 `ImageOcrRequest`, `ImageOcrResponse`를 추가하고
    `OcrTextBlock` 데이터 클래스. DTO 데이터 클래스는 `Serializable`을 구현하고
    `serialVersionUID`을 정의하세요.
  - 공개 수업 및 서비스 방법에 대한 영문 KDoc을 추가합니다.
  - 쉼표로 구분된 값을 분할하고 트리밍하여 언어를 정규화합니다.
    유효성 검사 및 중복 제거를 순서대로 수행합니다.
  - 디코딩하기 전에 이미지 바이트의 유효성을 검사합니다.
  - 바이트 유효성 검사, 정확한 선언된 콘텐츠 유형, 디코딩된 이미지 바이트 및 디코딩
    fallback/native 실행을 결정하기 전의 픽셀 수입니다.
  - 기본 OCR이 비활성화된 경우 검증 없이 `UNAVAILABLE`을 반환합니다.
    `OcrEngine`을 호출합니다.
  - 활성화되면 디코딩된 이미지를 재사용하고 `OcrOptions`을 빌드하고 주입된 호출을 수행합니다.
    `OcrEngine` 제한된 기본 OCR 경계 내부:
    - 디코드 플러스 OCR 주변의 단일 비행 세마포어;
    - `withTimeout(properties.timeout)`;
    - byte/decode/OCR 작업을 차단하는 `Dispatchers.IO`입니다.
  - `CancellationException`을 먼저 잡아서 다시 던져보세요.
  - 손상되었거나 지원되지 않는 디코딩 이미지 오류를 삭제된 잘못된 입력에 매핑합니다.
  - `OcrConfigurationException`을 `UNAVAILABLE`에 매핑하고 `OcrException`를 `OcrException`에 매핑합니다.
    `FAILED` 삭제된 경고 포함.
  - 인식된 텍스트를 공백이 아닌 줄 기반 블록으로 분할합니다.
  - 신뢰도 데이터가 없을 때 null 허용 신뢰도 경고를 추가합니다.
  - 기본 OCR이 활성화된 경우에만 `TesseractOcrEngine`을 구성하세요.
  - 애플리케이션 컨텍스트 또는 조건부 Bean 테스트를 통해 기본값이
    기본 비활성화 컨텍스트는 `TesseractOcrEngine`을 구성하지 않고 시작됩니다.
  - `requestId`, 상태, 엔진으로 삭제된 진단 로깅을 추가합니다.
    언어, 기본 지원 플래그, 경과 시간 및 오류 범주만 해당됩니다.
- **DoD**:
  - 서비스 테스트를 통과했습니다.
  - 프로덕션 코드에는 `!!`, `runBlocking`, `runCatching`가 없습니다.
    호출, `Thread.sleep` 또는 더 이상 사용되지 않는 가져오기를 일시 중지합니다.

### T5 - 웹 계층 구현

- **파일**:
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/ImageOcrApiApplication.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrController.kt`
  - `image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrExceptionHandler.kt`
- **행동**:
  - 구성 속성 검사를 통해 Spring Boot 애플리케이션을 추가합니다.
  - `POST /api/images/ocr` 멀티파트 엔드포인트를 구현합니다.
  - 선택적 `language` 요청 매개변수를 승인합니다.
  - 서비스 호출 전에 비어 있지 않은 파일 및 `image/*` 콘텐츠 유형을 검증하십시오.
  - `application/json`을 사용하여 `ImageOcrResponse`을 반환합니다.
  - `IllegalArgumentException`을 RFC 9457 `ProblemDetail` 400으로 매핑합니다.
  - `UNAVAILABLE` 서비스를 HTTP 503에 매핑하지 마세요. 폴백은 구조화되어 있습니다.
    성공적인 교육 반응.
  - corrupt/undecodable 이미지 입력 ​​및 지원되지 않는 디코딩된 이미지 유형을 다음에 매핑합니다.
    400개의 응답을 정리했습니다.
- **DoD**:
  - 컨트롤러 테스트를 통과했습니다.
  - `./gradlew :image-processing-ocr-api:test` 통과.

### T6 - README, 한국어 README 및 루트 카탈로그

- **파일**:
  - `image-processing/ocr-api/README.md`
  - `image-processing/ocr-api/README.ko.md`
  - `README.md`
  - `README.ko.md`
- **행동**:
  - 언어 스위치를 추가합니다.
  - 아키텍처 및 시퀀스 다이어그램 이미지 참조를 추가합니다.
  - 문서 엔드포인트, 컬 예제, 응답 예제, 속성, 대체
    동작, 기본 Tesseract 옵트인 및 이슈 해결.
  - 눈에 보이는 작업장 경계 섹션 포함: 인증 없음, 바이러스 백신 검사,
    지속성, 속도 제한, 스토리지 정책, 대기열, 감사 워크플로,
    PII/document-management 보증 또는 프로덕션 업로드 강화.
  - Null 허용 신뢰도를 명시적으로 문서화합니다.
  - 기본 명령 `./gradlew :image-processing-ocr-api:test`을 추가합니다.
  - 기본 옵트인 명령 추가
    `./gradlew :image-processing-ocr-api:test -Docr.enabled=true`.
  - 다음을 사용하여 기본 대체 `bootRun` 및 기본 활성화 `bootRun` 예제를 추가합니다.
    컬 요청과 예상 `UNAVAILABLE`/`COMPLETED` 일치 또는 문서화됨
    `UNAVAILABLE` 결과.
  - macOS 및 Linux Tesseract 설치 스니펫 tessdata/language-pack을 추가합니다.
    점검 및 `workshop.ocr.tessdata-path` 진단지도를 실시합니다.
  - 기본 테스트는 결코 실제 Tesseract 성공을 입증하지 못한다고 명시합니다. 진짜 원주민
    유효성 검사는 로컬 필수 구성 요소가 아닌 한 수동 옵트인 Runbook 경로입니다.
    설치되어 있습니다.
  - OCR 텍스트에 민감한 데이터가 포함될 수 있으며 이 워크샵에는 민감한 데이터가 포함될 수 있다는 경고를 추가합니다.
    OCR 텍스트를 기록하지 마세요.
  - 루트 이미지 처리 카탈로그 행과 프로젝트 구조를 모두 업데이트합니다.
    로케일.
  - README.md는 영어로, README.ko.md는 소스와 동등한 자연스러운 한국어를 유지합니다.
  - `$bluetape4k-blog`식 매뉴얼parity/naturalness을 실행하여 한국어를 확인합니다.
    기본 선택, 경고, 이슈 해결 등을 모두 다루는 README 섹션
    JSON 예.
- **DoD**:
  - 영어와 한국어 README에는 일치하는 섹션, 명령, 이미지 링크,
    경고 문구 및 예시.
  - `node scripts/validate-readme-parity.mjs` 그리고
    `node scripts/validate-readme-language.mjs` 합격.

### T7 - README 다이어그램

- **파일**:
  - `docs/images/readme-diagrams/image-ocr-api-readme-architecture-01.svg`
  - `docs/images/readme-diagrams/image-ocr-api-readme-architecture-01.png`
  - `docs/images/readme-diagrams/image-ocr-api-readme-sequence-01.svg`
  - `docs/images/readme-diagrams/image-ocr-api-readme-sequence-01.png`
- **행동**:
  - 제목에 `Architects Daughter`을 사용하여 영어 라벨 SVG 자산을 생성하고
    자세한 텍스트는 `Comic Mono`입니다.
  - 아키텍처 다이어그램이 위에서 아래로 흐르도록 만듭니다.
  - 비활성화된 단락, 기본 활성화 OCR 경로, 제한된 기본 표시 표시
    실행, 삭제된 오류 매핑 및 README 표시 경고.
  - CairoSVG을 사용하여 PNG를 렌더링합니다.
  - 계속하기 전에 렌더링된 PNG을 검사하세요.
- **DoD**:
  - `node scripts/validate-readme-architecture-diagrams.mjs` 통과.
  - `node scripts/validate-sequence-diagrams.mjs` 통과.
  - 전체 크기 PNG 육안 검사 결과 겹치거나 읽을 수 없는 라벨이 없는 것으로 나타났습니다.

### T8 - 연기, 예제 및 등록 검증

- **파일**:
  - `scripts/smoke-validate.sh`
  - `.github/workflows/Examples.yml`
  - `.github/workflows/nightly.yml` 읽기 전용 증거
- **행동**:
  - `:image-processing-ocr-api:test`을 `all-smoke`에 추가합니다.
  - 오래된 확인 예상 프로젝트 수를 `80`에서 `81`로 업데이트합니다.
  - 예제 푸시 및 풀 요청 경로에 `image-processing/ocr-api/**`를 추가합니다.
  - 기존 H2/default 예시에 `:image-processing-ocr-api:test` 추가
    연기 Gradle 명령.
  - `.github/workflows/Examples.yml` `smoke-examples.timeout-minutes: 25` 유지
    변하지 않은.
  - 기존 아래에 이미지 OCR 테스트 결과 아티팩트 경로를 추가합니다.
    `smoke-example-test-results` 업로드 블록.
- **DoD**:
  - `./scripts/smoke-validate.sh all-smoke` 통과.
  - `./gradlew projects --console=plain | rg "Project ':image-processing-ocr-api'"`은 자동 등록을 증명합니다.
  - `./scripts/smoke-validate.sh stale-check` 출력에는 다음이 포함됩니다.
    `Active modules: 81 (expected: 81)`, `No stale refs found.` 및
    `No broken image links found.`; 이 문제에 대한 모든 `WARNING:`은(는) 실패입니다.
  - `actionlint .github/workflows/Examples.yml` 통과.
  - `test -z "$(rg -n "\\\\'" .github/workflows || true)"` 통과.
  - 워크플로 차이점에 시간 초과 증가가 표시되지 않습니다.
  - `rg -n "smoke-validate.sh all-smoke" .github/workflows/nightly.yml`이(가) 증명합니다.
    Nightly는 `all-smoke`을 통해 새 모듈에 도달합니다.

### T9 - 최종 검증 및 아티팩트 검토

- **파일**:
  - `docs/review/2026-06-29-issue-288-image-ocr-api-code-review.md`
  - `docs/lessons/2026-06-29-issue-288-image-ocr-api.md`
- **행동**:
  - 대상 모듈 테스트, README 유효성 검사기, 다이어그램 유효성 검사기, 작업 흐름 실행
    린트 및 `git diff --check`.
  - 소스, 빌드, 스모크 스크립트 또는
    워크플로 파일은 실행 후 변경됩니다.
  - 6-R단계의 검토 증거와 교훈을 기록하십시오.
  - rollback/runbook 증거 기록:
    - runtime/data 마이그레이션 없음;
    - 롤백은 `image-processing/ocr-api/`을 제거합니다.
    - 롤백은 사용되지 않은 `bluetape4k-images-ocr` 별칭을 제거합니다.
    - 롤백은 연기 스크립트 및 예제 워크플로 항목을 제거합니다.
    - 롤백은 오래된 검사 횟수를 복원합니다.
    - 롤백은 루트 README 항목과 다이어그램 자산을 제거합니다.
  - 기여자 진단 위치 기록:
    - `image-processing/ocr-api/build/reports/tests/test/index.html`;
    - `image-processing/ocr-api/build/test-results/test/*.xml`;
    - GitHub `smoke-example-test-results` 아티팩트 경로.
- **PR 준비 상태**:
  - `gh issue view 288 --json assignees,labels,milestone` 발행일 메타데이터를 확인합니다.
  - PR 본문에는 `Closes #288`이(가) 포함됩니다.
  - `gh pr view <pr> --json assignees,labels,milestone,body`은 양수인을 증명하고,
    완료를 보고하기 전에 라벨, 마일스톤 및 바디 패리티를 확인합니다.
- **DoD**:
  - P0/P1 최종 검토 결과 결과는 0입니다.
  - 커밋은 Lore 프로토콜을 사용합니다.
  - PR은 이슈 담당자, 마일스톤 및 레이블을 반영합니다.

## 3. 검증 명령어 세트

```bash
./gradlew :image-processing-ocr-api:test
./gradlew :image-processing-ocr-api:compileKotlin :image-processing-ocr-api:compileTestKotlin --warning-mode all
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
actionlint .github/workflows/Examples.yml
test -z "$(rg -n "\\\\'" .github/workflows || true)"
git diff --check
```

## 4. 위험

- `all-smoke`은 관련되지 않은 기존 오류를 노출할 수 있습니다. 그렇다면 집중된 작업을 다시 실행하세요.
  모듈을 테스트하고 관련 없는 오류를 증거와 함께 기록합니다.
- 기본 OCR은 훈련된 데이터로 인해 로컬 컴퓨터에서 다르게 동작할 수 있습니다.
  다르다; 기본 테스트는 가짜 엔진 또는 대체 기반을 유지해야 합니다.
- 서비스 응답은 프로덕션 API처럼 보일 수 있지만 이 워크샵은 그렇지 않습니다.
  인증, 저장소, 속도 제한, 바이러스 백신 검색 또는 업로드 지속성을 추가합니다.
  README는 예시 경계를 명확하게 명시해야 합니다.
- 도형이나 메타데이터가 그렇지 않은 경우 다이어그램 유효성 검사기는 새 자산을 거부할 수 있습니다.
  지역 규칙과 일치합니다. 로컬 유효성 검사기에 대한 다이어그램 작성 및 검사
  PNG.
