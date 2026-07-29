# Issue #93 — 이미지 처리 고급 워크샵 계획

**날짜**: 2026-05-24
**사양**: `docs/superpowers/specs/2026-05-24-issue-93-image-processing-advanced-design.md`
**대상 모듈**: `:image-processing-advanced-workflow`
**워크플로우 레인**: `bluetape4k-workflow`을 통한 유형 A 전체 설계

## 실행 규칙

- Kotlin 구현 및 테스트 전에 `bluetape4k-patterns`, `ecc-kotlin-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`을 적용하세요.
- `image-processing/advanced-workflow` 내부에 구현을 유지하세요.
- 새 모듈에만 Java 25를 사용하세요. 루트 워크샵Java 기준선을 높이지 마십시오.
- 기본 해피 경로에서 Bluetape4k 이미지 API를 사용하세요.
- 서명되지 않은 공개 URL 생성을 Bluetape4k 라이브러리 기능으로 검증하는 것이 아니라 워크숍 접착제로 명시적으로 유지합니다.
-  #94 지속성을 범위 밖으로 유지하세요.
- 일시 중지 서비스 오케스트레이션, 명시적 `Dispatchers.IO` 기본 경계, 요청 동시성 `2`, 변형 동시성 `2` 및 워크플로 시간 초과 `30s`와 함께 Spring MVC 멀티파트를 사용하세요.
- processing/storage 변형이 실패할 경우 이미 업로드된 객체를 최선을 다해 정리합니다.

## 작업 계획

### T1 — 모듈 등록 및 종속성

- **복잡성**: 중간
- **범위**:
  - `includeModules("image-processing", false, true)`을 `settings.gradle.kts`에 추가합니다.
  - `image-processing/advanced-workflow/build.gradle.kts`를 생성합니다.
  - 다음에 대한 버전-카탈로그 별칭을 추가합니다.
    - `bluetape4k-images`
    - `bluetape4k-images-spring-boot`
    - `bluetape4k-images-vips-api`
    - `bluetape4k-images-vips-java25`
  - 모듈에서만 Java/Kotlin 툴체인 25를 구성하세요.
  - 모듈에서 AtomicFU 변환을 무조건 비활성화합니다. 이는 `bluetape4k-images-vips-java25` 모듈 증거를 따릅니다. Java 25 바이트코드와 AtomicFU 변환은 빌드 JVM가 Java 21인 경우 호환되지 않을 수 있습니다.
  - `--enable-native-access=ALL-UNNAMED`, Java 25 실행기, `forkEvery = 1` 및 `-Dvips.enabled` 전파로 테스트를 구성합니다.
  - VIPS 통합 테스트는 기본적으로 건너뛰고 명시적인 `-Dvips.enabled=true` 옵트인으로만 실행됩니다.
  - CI 툴체인 동작 기록: 루트 CI는 Java 21을 설치하고 Gradle Foojay 리졸버는 모듈의 Java 25 툴체인을 프로비저닝하기 위해 `settings.gradle.kts`에 이미 구성되어 있습니다.
- **예상 파일**:
  - `settings.gradle.kts`
  - `gradle/libs.versions.toml`
  - `image-processing/advanced-workflow/build.gradle.kts`
- **확인**:
  - `./gradlew projects`
  - `./gradlew :image-processing-advanced-workflow:dependencies --configuration runtimeClasspath`
  - `./gradlew :image-processing-advanced-workflow:compileKotlin`
- **롤백 지점**: 코드가 추가되기 전에 종속성 해결에 실패하면 T1을 되돌립니다.

### T2 — 구성 및 도메인 모델

- **복잡성**: 중간
- **패턴**: `bluetape4k-patterns`, `ecc-kotlin-patterns`, `ecc-springboot-kotlin`를 적용합니다.
- **범위**:
  - `ImageProcessingAdvancedApplication`을 추가합니다.
  - 다음을 사용하여 `ImageProcessingAdvancedProperties`을 추가합니다.
    - `publicBaseUrl`
    - `maxInputBytes`
    - `maxPixels`
    - `requestConcurrency`
    - `vipsConcurrency`
    - `variantConcurrency`
    - `processingTimeout`
    - `allowInsecurePublicBaseUrl`
    - `allowLocalStorageRemotePublicBaseUrl`
    - 기본 변형 `thumb-128`, `card-320`, `detail-1024`
  - 모든 데이터 클래스가 `Serializable`을 구현하고 `serialVersionUID`을 정의하는지 확인합니다.
  - 원본 메타데이터, 변형 메타데이터 및 워크플로 응답에 대한 응답DTO를 추가합니다.
  - 로컬 개발 폴백 및 구성 가능한 공개 URL을 사용하여 `application.yml`을 추가합니다.
  - `spring.servlet.multipart.max-file-size=25MB`, `spring.servlet.multipart.max-request-size=25MB` 및 일치하는 `workshop.images.advanced.max-input-bytes=26214400`을 설정합니다.
- **예상 파일**:
  - `src/main/kotlin/io/bluetape4k/workshop/imageprocessing/advanced/...`
  - `src/main/resources/application.yml`
- **확인**:
  - T4 이후 모듈 컴파일

### T3 — 검증, 키 명명 및 URL 구성

- **복잡성**: 중간
- **패턴**: `bluetape4k-patterns`, `ecc-kotlin-testing`을 적용합니다.
- **범위**:
  - `UploadImageValidator`을 구현합니다.
  - Bluetape4k 이미지 규칙이 적용되도록 업로드 메타데이터 유효성 검사를 위해 워크숍 JPEG/PNG/WebP 허용 목록, 매직 바이트 검사 및 `UploadOptions`을 사용하세요.
  - 결정적 키 팩토리 구현:
    - `images/{imageId}/original/{safeFilename}`
    - `images/{imageId}/variants/{variantName}.webp`
  - `PublicImageUrlResolver`을 구현합니다.
  - `publicBaseUrl` 확인:
    - 빈 값 거부
    - 거부 `..`, 쿼리 문자열 및 조각
    - 기본적으로 HTTPS이 필요합니다.
    - 루프백 로컬 개발 또는 명시적 `allowInsecurePublicBaseUrl=true`에 대해서만 HTTP을 허용합니다.
    - `allowLocalStorageRemotePublicBaseUrl=true`이 아니면 로컬 스토리지와 비루프백 원격 URL을 거부합니다.
  - 안전한 슬래시 결합을 유지합니다.
- **테스트**:
  - 잘못된 콘텐츠 유형이 실패함
  - 너무 큰 입력이 실패함
  - filename sanitizer는 경로 구분 기호 누출을 방지합니다.
  - URL 구성은 키 전체 경로에서 서명되지 않은 공개 URL을 반환합니다.
  - `publicBaseUrl` 검증은 안전하지 않은 remote/local 불일치를 거부합니다.
  - 멀티파트 및 서비스 최대 바이트 기본값이 일치합니다.
- **확인**:
  - `./gradlew :image-processing-advanced-workflow:test --tests "*Url*"`
  - 정확한 테스트 이름은 구현 후 조정될 수 있습니다.

### T4 — VIPS 파생 프로세서

- **복잡성**: 높음
- **패턴**: `bluetape4k-patterns`, `ecc-kotlin-patterns`, `ecc-kotlin-testing`, `kotlin-coroutines-skill`을 적용합니다.
- **범위**:
  - 테스트 가능성을 위해 `DerivativeProcessor` 인터페이스를 구현합니다.
  - 다음을 사용하여 `FfmVipsDerivativeProcessor`을 구현합니다.
    - `FfmVipsRuntime.init(concurrency, maxPixels)`
    - `suspendFfmVipsImageOf(bytes)`
    - `VipsImage.thumbnail(maxDimension)`
    - `VipsImage.toBytes(VipsImageFormat.WEBP, VipsEncodeOptions(quality = 82, effort = 4))`
  - 지연 런타임 초기화를 사용합니다. `FfmVipsRuntime.init(concurrency, maxPixels)`은 첫 번째 성공적인 초기화 이후 멱등성을 갖습니다. 테스트는 컨텍스트 간 매개변수 충돌을 피하기 위해 `forkEvery = 1`로 실행됩니다.
  - blocking/native 디코드를 래핑하고 명시적인 `Dispatchers.IO` 경계 뒤에 인코딩합니다.
  - 변형 동시성을 위해서는 `Semaphore.withPermit`을 사용하세요.
  - 워크플로 서비스에서 요청 수준 `Semaphore.withPermit` 및 `withTimeout(processingTimeout)`을 사용합니다.
  - 일반 예외 처리 전에 `CancellationException`을 다시 발생시킵니다.
  - Spring 파괴 후크에서 `FfmVipsRuntime.shutdown()`을 호출하지 마세요. 도서관 문서는 종료가 터미널임을 경고합니다.
- **테스트**:
  - VIPS 통합 테스트는 생성된 차원과 WebP 콘텐츠 마커를 확인합니다.
  - VIPS 테스트 클래스는 `-Dvips.enabled=true`이 제공되지 않는 한 명시적으로 건너뜁니다.
  - 코루틴 취소 테스트는 취소가 삼켜지지 않았는지 확인합니다.
  - 런타임 가드 테스트는 누락된 `-Dvips.enabled=true` 테스트를 명시적으로 중단하는지 확인합니다.
- **확인**:
  - `./gradlew :image-processing-advanced-workflow:test`
  - `./gradlew :image-processing-advanced-workflow:test --tests "*Vips*" -Dvips.enabled=true` libvips를 사용할 수 있는 경우

### T5 — 워크플로 서비스, 스토리지, 지표 및 API

- **복잡성**: 높음
- **패턴**: `bluetape4k-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`를 적용합니다.
- **범위**:
  - `ImageDerivativeWorkflowService`을 구현합니다.
  - Bluetape4k `ImageStorage`를 삽입하면 `images-spring-boot` 자동 구성에 의해 S3/local가 선택됩니다.
  - `ImageStorage.upload`을 통해 원본과 모든 변형을 저장합니다.
  - 업로드 후 실패하면 `ImageStorage.delete`을 통해 이전에 업로드한 모든 키를 삭제하는 것이 최선입니다. 원래 워크플로 예외를 다시 발생시킵니다.
  - 응답 키와 서명되지 않은 URL을 작성합니다.
  - 작업 흐름 추가counters/timers:
    - 업로드가 허용됨
    - 처리 success/failure
    - 처리 기간
    - 변형이 생성됨
  - 낮은 카디널리티 측정항목 태그만 사용하세요. 정규화된 허용 목록 `contentType`, `result`, `stage` 및 `variant`. 원시 헤더 값, `imageId`, 파일 이름 또는 키가 아닙니다.
  - `ImageDerivativesController`을 `POST /api/images/derivatives`에 추가합니다.
  - 검증 및 저장 실패에 대한 일관된 오류 응답 매핑을 추가합니다.
- **테스트**:
  - 녹음 저장소는 원본과 업로드된 모든 변형을 확인합니다.
  - 응답에는 `originalUrl`, `thumbnailUrl`, `variants[].url` 및 키가 포함됩니다.
  - 컨트롤러는 유효하지 않은 콘텐츠 유형, content-type/magic-byte 불일치 및 너무 큰 입력을 거부합니다.
  - 스토리지 실패는 실패 측정항목을 증가시키거나 예상되는 오류를 반환합니다.
  - 부분 변형 실패는 원본 변형과 이전 변형에 대한 정리를 시도합니다.
  - 메트릭 어설션은 카디널리티가 낮은 태그 이름을 확인합니다.
- **확인**:
  - `./gradlew :image-processing-advanced-workflow:test`

### T6 — README 및 예

- **복잡성**: 중간
- **범위**:
  - `image-processing/advanced-workflow/README.md`을 추가합니다.
  - `image-processing/advanced-workflow/README.ko.md`을 추가합니다.
  - 두 파일 모두에 Mermaid 아키텍처 다이어그램과 시퀀스 다이어그램을 포함합니다.
  - JDK 25 및 macOS/Linux의 libvips에 대한 설정 전제 조건을 포함합니다.
  - 요청 예제, 샘플 응답 JSON, 생성된 키 이름 지정, 로컬 정리, S3 서명되지 않은 공개 URL 설정 및 보안 참고 사항을 포함합니다.
  - 일반적인 libvips 설치 및 Java 25개의 기본 액세스 실패에 대한 이슈 해결을 포함합니다.
  - before/after 조각을 포함합니다.
  - 기능, 아티팩트, 코드 참조 및 이점이 포함된 `Used Bluetape4k features` 테이블을 포함합니다.
  - 루트 `README.md` 및 `README.ko.md` 모듈 목록을 업데이트합니다.
- **확인**:
  - `git diff --check`
  - grep으로 렌더링된 인어 울타리 및 링크

### T7 — 테스트 리소스 및 검증

- **복잡성**: 중간
- **범위**:
  - `src/test/resources/junit-platform.properties`을 추가합니다.
  - `src/test/resources/logback-test.xml`을 추가합니다.
  - 타겟 테스트를 실행하고 컴파일합니다.
  - `./gradlew projects`를 실행하세요.
  - CI/Nightly 워크플로 영향을 확인합니다.
    - `settings.gradle.kts`은 모듈을 자동 포함합니다.
    - CI `build -x test`은 모든 모듈을 컴파일합니다.
    - 야간 전체 `./gradlew test`에는 모듈이 포함됩니다.
    - 연기 목록은 스크립트에 수동 열거가 필요한 경우에만 변경됩니다.
    - Java 기존 Foojay 리졸버는 25개의 툴체인을 제공합니다. 확인 결과가 달리 입증되지 않는 한 작업 흐름 YAML 편집은 예상되지 않습니다.
- **확인**:
  - `./gradlew projects`
  - `./gradlew :image-processing-advanced-workflow:build`
  - `./gradlew build -x test --parallel --continue`

### T8 — 검토 게이트, 강의, 커밋, PR

- **복잡성**: 중간
- **범위**:
  - Codex 검토와 Claude 코드 CLI Advisor를 사용하여 6-R단계에 따라 코드 검토 게이트를 실행합니다.
  - `docs/lessons/2026-05-24-issue-93-image-processing-advanced.md`을 추가합니다.
  - Lore 프로토콜로 커밋합니다.
  - 분기를 푸시하고 `debop`에 할당된 `develop`에 대해 PR을 엽니다. 가능한 경우 레이블 `documentation`, `enhancement`, `area:image-processing`, `area:storage`입니다.
- **확인**:
  - `git status --short`
  - `git diff --stat origin/develop...HEAD`
  - `gh pr view --json number,title,url,assignees,labels`

## 요구사항-작업 매트릭스

| 요구사항 | 작업 |
|---|---|
| 신규 module/scenario | T1, T2 |
| Spring 멀티파트 업로드 | T5 |
| Bluetape4k VIPS Java 25개의 행복한 길 | T4 |
| 스토리지 추상화 및 S3 경로 | T5, T6 |
| 서명되지 않은 공개 URL | T3, T5, T6 |
| Metrics/health | T5, `images-spring-boot`에서 스토리지 상태 상속 |
| libvips를 사용할 수 없는 건너뛰기 동작 | T1, T4, T7 |
| 잘못된 유형/너무 큰 테스트 | T3, T5 |
| Resize/thumbnail 테스트 | T4 |
| 구성된 모든 변형이 저장됨 | T5 |
| README 아키텍처 및 시퀀스 다이어그램 | T6 |
| Bluetape4k-첫 번째 테이블 | T6 |
| #94 지속성 제외 | T6 메모 및 PR 본문 |

## 계획 검토 자가 점검

| 확인 | 상태 | 메모 |
|---|---|---|
| 모든 사양 요구 사항은 작업 | 완료 | 매트릭스 참조 |
| 작업 순서 구현 가능 | 완료 | Build/module 코드 전, tests/docs 확인 전 코드 |
| 테스트에서는 success/failure/backend 기능 | 완료 | 녹화 저장 플러스 VIPS skip/integration 테스트 |
| 코루틴 취소 적용 | 완료 | T4에는 취소 테스트가 포함되어 있습니다 |
| README 및 로케일 쌍 포함 | 완료 | T6 |
| 새로운 모듈 워크플로우 적용 | 완료 | T1, T7 |
| Java 25 preview/native 위험이 기록됨 | 완료 | T1, T4, T6 |
| 수명 주기 소유권 명시적 | 완료 | Spring 파괴 후크에서 VIPS 런타임을 종료하지 마십시오 |

## 3-R단계 검토 노트

### Claude 코드 오퍼스 어드바이저

초기 아티팩트: `.omx/artifacts/claude-issue-93-plan-20260524162959.md`
P0/P1 수정 후 아티팩트 다시 실행: `.omx/artifacts/claude-issue-93-plan-rerun-20260524163313.md`

| 우선순위 | 찾기 | 결정 | 후속 조치 |
|---|---|---|---|
| P1 | AtomicFU 변환 결정이 모호함 | 수락됨 | T1는 이제 Java 25 모듈 증거에 따라 무조건 비활성화합니다.
| P1 | Spring 테스트 컨텍스트 전체의 VIPS 라이프사이클이 불명확함 | 수락됨 | T4는 이제 지연 초기화, 멱등성 및 분기 테스트 JVM 정책을 정의합니다. |
| P1 | 공개 URL 보안 유효성 검사가 완료되지 않았습니다. | 수락됨 | T3는 이제 HTTPS, 경로, 쿼리, local/remote 불일치 규칙을 정의합니다. |
| P1 | 부분적인 저장 오류로 인해 고아가 발생함 | 수락됨 | T5은 이제 최선의 삭제 보상이 필요합니다 |
| P2 | 다중 부분 힙 압력 | 수락됨 | 실행 규칙 및 T2/T5 바운드 요청 동시성 및 바이트 제한 |
| P2 | WebP 인코딩 옵션이 지정되지 않음 | 수락됨 | T4 핀 `VipsEncodeOptions(quality = 82, effort = 4)` |
| P2 | CI Java 25 가용성 불분명 | 수락됨 | T1/T7 이제 기존 Foojay 툴체인 리졸버 확인 기록 |
| P2 | 디스패처 및 메트릭 카디널리티 세부정보 | 수락됨 | T4/T5 이제 `Dispatchers.IO` 및 낮은 카디널리티 태그 지정 |

최신 통합 결과 테이블: `P0=0`, `P1=0`.

## 3단계 체크리스트 완료 보고서

| 아이템 | 상태 | 메모 |
|---|---|---|
| 기능 작업 트리 내부 경로 계획 | 완료 | 이 파일은 기능 작업 트리 |
| 모든 작업에는 복잡성 레이블이 있습니다 | 완료 | T1-T8 |
| 코드 포함 작업에 할당된 패턴 | 완료 | T2-T5 |
| 테스트 조각은 허용되지 않는 검증 API를 방지합니다 | 완료 | JUnit 어설션이 필요한 스니펫은 없습니다. 구현에서는 bluetape4k 어설션을 사용합니다 |
| Thread/coroutine 안전 테스트 접근 기록 | 완료 | T4은 코루틴 취소를 사용합니다. 서비스 동작을 통해 변형 동시성 확인 |
| 확인 명령이 포함됨 | 완료 | 작업별 |
| README 및 기여자 아티팩트 포함 | 완료 | T6, T8 |
| 위험한 가정이 명시적임 | 완료 | Java 25/libvips/native 액세스, S3 공개 URL 정책 |
| 구현 전에 사양 + 계획 커밋 필요 | 완료 | T8 및 워크플로 게이트 |
