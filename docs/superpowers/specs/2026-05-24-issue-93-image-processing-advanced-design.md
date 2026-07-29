# Issue #93 — 이미지 처리 고급 워크샵 디자인

**날짜**: 2026-05-24
**저장소**: `bluetape4k-workshop`
**대상 모듈**: `image-processing/advanced-workflow` (`:image-processing-advanced-workflow`)
**워크플로우 레인**: `bluetape4k-workflow`을 통한 유형 A 전체 설계

## 문제

Issue #93에서는 파생 상품에 대한 현실적인 업로드 작업 흐름을 보여주는 고급 이미지 처리 워크숍을 요청합니다.

- Spring Boot를 통한 멀티파트 이미지 업로드
- 콘텐츠 유형, 바이트 크기, 픽셀 제한 및 차원 유효성 검사
- 원본 이미지 저장
- 구성된 파생 생성(`thumb-128.webp`, `card-320.webp`, `detail-1024.webp`)
- S3 객체 키 및 서명되지 않은 공개 URL 응답
- 메트릭, 상태, 동시성 제한 및 기본 libvips 처리
- README 아키텍처 및 시퀀스 다이어그램

예제는 일반 Spring 또는 AWS 샘플이 아닌 Bluetape4k-first여야 합니다.

## 현재 증거

- `bluetape4k-image`은 `bluetape4k-images-vips-java25`에 `FfmVipsRuntime`, `ffmVipsImageOf`, `suspendFfmVipsImageOf`, `VipsImage.thumbnail()`, `VipsImage.resize()`, `VipsImage.toBytes()`을 제공합니다.
- `bluetape4k-images-vips-java25`에는 Java 25 및 `--enable-native-access=ALL-UNNAMED`이 필요합니다. 이 워크샵에서는 기본 초기화가 잘못 구성된 호스트에서 JVM를 종료할 수 있기 때문에 `-Dvips.enabled=true`와 함께 VIPS 통합 테스트를 선택하도록 유지합니다.
- `bluetape4k-images-spring-boot`은 `ImageStorage`에 대해 Spring Boot 4가지 자동 구성을 제공합니다.
  - `ImagesStorageAutoConfiguration`은 `bluetape4k.images.storage.backend=s3`와 `S3Operations`이 존재할 때 `S3ImageStorage`을 생성합니다.
  - `ImageStorage` Bean이 없으면 `LocalImageStorage`으로 대체됩니다.
  - `MetricImageStorage`은 Micrometer이 존재할 때 스토리지 빈을 래핑합니다.
- `ImageObjectKey`은 키의 유효성을 검사하고 `..` 세그먼트를 방지합니다.
- `UploadImageValidator`은 워크샵 업로드를 JPEG, PNG 및 WebP로 제한하고, 일치하는 매직 바이트를 확인하며, Bluetape4k 업로드 메타데이터 검증을 위해 `UploadOptions`을 계속 사용합니다.
- `S3PreSignedUrlSigner` 및 `CloudFrontUrlSigner`은 private/signed URL 대안으로 존재하지만 issue #93에는 서명되지 않은 공개 URL을 반환하는 기본 경로가 필요합니다.
- 워크샵 루트는 현재 Java 21로 기본 설정되어 있으며, Java 25 전용 모듈은 모듈 툴체인을 재정의하고 JVM 런처를 테스트할 수 있습니다.

## 구성 기본값

| 부동산 | 기본값 | 근거 |
|---|---:|---|
| `spring.servlet.multipart.max-file-size` | `25MB` | 서블릿 경계는 힙이 증가하기 전에 너무 큰 업로드를 거부합니다 |
| `spring.servlet.multipart.max-request-size` | `25MB` | 이 단일 파일 엔드포인트에 대한 파일 제한과 일치해야 합니다. |
| `bluetape4k.images.storage.max-size-bytes` | `26214400` | 서블릿 업로드 제한과 일치 |
| `workshop.images.advanced.max-input-bytes` | `26214400` | 멀티파트 구문 분석 후 서비스 수준 보호 |
| `workshop.images.advanced.max-pixels` | `100000000` | 감압폭탄 방어가 `FfmVipsRuntime`에 전달됨 |
| `workshop.images.advanced.request-concurrency` | `2` | 동시 네이티브 decode/encode 워크플로 경계 |
| `workshop.images.advanced.variant-concurrency` | `2` | 요청별 경계 파생 팬아웃 |
| `workshop.images.advanced.processing-timeout` | `30s` | 무제한 기본 처리 요청 방지 |
| `workshop.images.advanced.public-base-url` | `http://localhost:8080/public-images` | 로컬 개발 기본값만 |
| `workshop.images.advanced.allow-insecure-public-base-url` | `false` | HTTPS 루프백 로컬 개발을 제외하고 필수 |
| `workshop.images.advanced.allow-local-storage-remote-public-base-url` | `false` | 의도하지 않는 한 로컬 폴백을 위한 가짜 CDN URL을 방지합니다 |

## 범위

### In

- 새로운 Spring Boot 4 모듈 `:image-processing-advanced-workflow`.
- Java 25개 모듈 수준 툴체인 `bluetape4k-images-vips-java25`이 요청된 백엔드이기 때문입니다.
- 이미지 업로드 컨트롤러:
  - `POST /api/images/derivatives`
  - 다중 부분 부분 이름: `file`
  - JSON은 메타데이터, 객체 키, 서명되지 않은 URL로 응답합니다.
- 서비스 작업 흐름:
  1. 업로드 바이트 및 MIME 유형을 확인합니다.
  2. Java 25 FFM VIPS 런타임 초기화 및 사용
  3. 원래 치수 검사
  4. `ImageStorage`를 통해 원본 저장
  5. `VipsImage.thumbnail()`를 통해 구성된 WebP 변형 생성
  6. `ImageStorage`을 통해 모든 변형을 저장합니다.
  7. 구성 가능한 `publicBaseUrl`에서 서명되지 않은 공개 URL을 작성합니다.
  8. upload/process 성공 및 실패 지표 기록
- README.md 및 README.ko.md:
  - JDK 25 및 libvips에 대한 전제 조건 설정
  - run/test 명령
  - 요청 예시 및 샘플 응답 JSON
  - 명명 규칙
  - 원시 프레임워크와 Bluetape4k 지원 before/after 설명 비교
  - `Used Bluetape4k features` 테이블
  - 인어 아키텍처 다이어그램
  - 인어 시퀀스 다이어그램
  - 서명되지 않은 URL 보안 메모
- 테스트 대상:
  - Bluetape4k VIPS 경로의 resize/thumbnail 차원, libvips를 사용할 수 없는 경우 명시적으로 건너뛰기
  - 잘못된 콘텐츠 유형
  - 너무 큰 입력
  - 키 이름 지정 및 서명되지 않은 URL 구성
  - 구성된 모든 변형이 저장됩니다.
  - libvips 비활성화 건너뛰기 동작

## HTTP 및 코루틴 모델

워크숍 엔드포인트이 서블릿 멀티파트 업로드이기 때문에 모듈은 Spring MVC을 사용합니다. 컨트롤러와 서비스 메소드는 `suspend`로 유지되며 Spring MVC은 이들을 비동기식으로 연결할 수 있고 네이티브 또는 차단 작업은 명시적인 코루틴 경계 뒤에 유지됩니다.

- 멀티파트 구문 분석은 컨트롤러가 파일을 수신하기 전에 Spring 서블릿 멀티파트 제한에 의해 제한됩니다.
- 서비스 오케스트레이션은 일시 중단 우선입니다.
- `suspendFfmVipsImageOf` 및 명시적인 `withContext(Dispatchers.IO)` 경계는 기본 decode/encode 작업을 요청 스레드에서 유지합니다.
- 요청 수준 `Semaphore`은 동시 이미지 작업 흐름을 제한합니다. 변형 수준 `Semaphore`은 요청별 팬아웃을 제한합니다.
- `withTimeout(processingTimeout)`은 각 작업흐름의 경계를 정합니다.

### 밖으로

- PostgreSQL + Exposed metadata/history 지속성. 이는  #94에 의해 추적됩니다.
- Auth/authz 및 테넌트 정책 시행.
- 서명된 URL 기본 경로 동작. README는 `S3PreSignedUrlSigner` 및 `CloudFrontUrlSigner`을 개인 이미지 대안으로 문서화합니다.
- 공개 URL 구성을 위한 새로운 업스트림 Bluetape4k 라이브러리 API. 서명되지 않은 공개 URL은 bucket/CDN 정책에 따르기 때문에 워크숍에서는 이를 작은 애플리케이션 접착제로 유지할 것입니다.

## 건축학

이 모듈은 무거운 부품에 Bluetape4k 라이브러리를 사용합니다.

- 디코드, 차원 검사, 썸네일 생성, WebP 인코딩 및 기본 런타임 제한에 대한 `bluetape4k-images-vips-java25`입니다.
- 백엔드 중립 `VipsImage`, 형식 및 인코딩 옵션의 경우 `bluetape4k-images-vips-api`.
- `bluetape4k-images-spring-boot` 스토리지 추상화, S3/local 스토리지, 상태 및 스토리지 지표.
- 워크플로 수준 측정항목의 경우 `bluetape4k-micrometer`/Micrometer입니다.
- Spring Boot MVC HTTP 경계에만 적용됩니다.

### 구성요소

| 구성요소 | 책임 |
|---|---|
| `ImageDerivativesController` | 멀티파트 API 경계 및 HTTP 상태 매핑 |
| `ImageDerivativeWorkflowService` | 검증, 처리, 저장, 측정항목 및 응답 어셈블리 조율 |
| `UploadImageValidator` | MIME 유형, 매직 바이트, 빈 입력, 바이트 제한을 확인하고 픽셀 유효성 검사를 VIPS 디코딩에 위임 |
| `FfmVipsDerivativeProcessor` | `FfmVipsRuntime` 초기화, 차원 검사, 동시성 제한이 있는 WebP 변형 생성 |
| `ImageStorage` | Bluetape4k 스토리지 추상화; 구성된 배포의 S3, dev/test의 로컬 폴백 |
| `PublicImageUrlResolver` | `ImageObjectKey.fullKey`의 서명되지 않은 공개 URL 구성을 위한 워크샵 접착제 |
| `ImageProcessingAdvancedProperties` | 워크플로 변형, 제한 및 공개 URL 설정 |

### 수명주기 정책

`FfmVipsRuntime`은 첫 번째 프로세서 사용 시 느리게 초기화됩니다. 이렇게 하면 libvips가 설치되지 않은 시스템에서 워크숍 앱을 부팅할 수 있게 유지하는 동시에 첫 번째 처리 요청에서 명확한 기본 런타임 오류가 수신됩니다. 라이브러리 문서가 프로세스의 터미널로 종료되기 때문에 서비스는 Spring 소멸 후크에서 `FfmVipsRuntime.shutdown()`을 호출하지 않습니다. 테스트에서는 교차 컨텍스트 매개변수 충돌을 방지하기 위해 포크된 JVM과 명시적인 런타임 가드를 사용합니다.

## 주요 모델

```text
images/{imageId}/original/{safeFilename}
images/{imageId}/variants/thumb-128.webp
images/{imageId}/variants/card-320.webp
images/{imageId}/variants/detail-1024.webp
```

`ImageObjectKey.of(prefix, name)`은 모든 저장소 키의 유효성을 검사합니다. Workshop sanitizer는 경로 구분 기호와 지원되지 않는 문자를 제거하면서 예를 들어 파일 이름을 충분히 결정적으로 유지합니다.

파일 이름 삭제 규칙:

- `[A-Za-z0-9._-]`만 유지
- 다른 모든 문자를 `_`으로 바꿉니다.
- 검증 전 경로 구분 기호 제거
- 마지막 확장자가 있으면 보존합니다.
- 최종 파일 이름을 120자로 제한하세요.
- 안전한 기본 이름이 남아 있지 않으면 `upload.jpg`으로 대체합니다.

## 응답 모델

```json
{
  "imageId": "2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4",
  "original": {
    "key": "images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/original/photo.jpg",
    "url": "https://cdn.example.com/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/original/photo.jpg",
    "width": 1600,
    "height": 1200,
    "contentType": "image/jpeg",
    "sizeBytes": 245763
  },
  "thumbnailUrl": "https://cdn.example.com/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/variants/thumb-128.webp",
  "variants": [
    {
      "name": "thumb-128",
      "key": "images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/variants/thumb-128.webp",
      "url": "https://cdn.example.com/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/variants/thumb-128.webp",
      "width": 128,
      "height": 96,
      "contentType": "image/webp",
      "sizeBytes": 7612
    }
  ],
  "durationMillis": 84,
  "warnings": []
}
```

## 접근 방식 비교

### 접근 방식 A — 원시 Spring + AWS SDK + 임시 이미지 라이브러리

- 장점: Java 25 특정 Bluetape4k 이미지 모듈에 종속되지 않습니다.
- 단점: 콘텐츠 유형 유효성 검사, path/key 유효성 검사, 저장소 예외 매핑, S3 업로드 처리, 메트릭 및 기본 런타임 처리를 반복합니다. Bluetape4k-first 요구 사항을 충족하지 않습니다.
- 결정: 거부됨.

### 접근 방식 B - Spring Boot 업로드 + Bluetape4k VIPS + Bluetape4k ImageStorage

- 장점: decode/resize/encode, 스토리지, 상태, 메트릭 및 검증 경계에 Bluetape4k를 사용하는 동안 현실적인 앱 워크플로를 보여줍니다.
- 단점: Java 25 및 libvips 전제 조건이 명시적이어야 합니다. CI에는 툴체인 다운로드 및 libvips 인식 건너뛰기 동작이 필요할 수 있습니다.
- 결정: 선택됨.

### 접근법 C — S3 추상화가 없는 순수 로컬 파일 예

- 장점: 실행이 가장 간단합니다.
- 단점: 문제의 S3 서명되지 않은 URL 요구 사항을 놓치고 Spring Boot 이미지 저장소 추상화를 보여주지 않습니다.
- 결정: 거부됨. 로컬 저장소는 `images-spring-boot`의 dev/test 대체 버전으로만 유지됩니다.

## 실패 모드 및 처리

| 실패 | 취급 |
|---|---|
| 지원되지 않는 콘텐츠 유형 | 워크샵 JPEG/PNG/WebP 허용 목록, 매직 바이트 확인 및 `UploadOptions`을 사용하여 저장하기 전에 거부 |
| 비어 있거나 너무 큰 업로드 | decode/storage 이전에 거부 |
| 픽셀 한도 초과 | `FfmVipsRuntime`/decode 유효성 검사로 인해 유효성 검사 오류가 발생함 |
| libvips를 사용할 수 없음 | 앱 startup/use에서 런타임 초기화 실패가 발생합니다. VIPS종속 테스트는 명시적으로 건너뜁니다. |
| S3 이용 불가 | `ImageStorageException`은 서비스를 사용할 수 없는 스타일 API 오류로 전파됩니다. 스토리지 상태 보고서 상태 |
| 공개 URL 구성 오류 | 시작 속성 유효성 검사는 빈 기본 URL을 거부합니다. README는 bucket/CDN 공개 읽기 요구 사항을 설명합니다 |
| 부분 변형 실패 | 서비스는 이미 업로드된 original/variant 키에 대해 최선의 삭제를 수행하고 오류를 반환하며 실패 지표를 증가시킵니다. |
| 비공개 이미지가 실수로 노출됨 | README 서명되지 않은 URL은 공개 이미지 전용이며 서명된 URL 서명자를 가리킨다고 경고 |

## 보안 참고사항

- issue #93에서 요구하므로 기본 경로는 서명되지 않은 URL을 반환합니다.
- 서명되지 않은 URL은 저장된 모든 이미지가 공개된 경우에만 허용됩니다.
- Bucket/CDN 정책, 객체 소유권 및 캐시 정책은 공개 읽기를 허용해야 합니다.
- 개인용 이미지 또는 사용자에게 민감한 이미지는 `S3PreSignedUrlSigner` 또는 `CloudFrontUrlSigner`을 사용해야 합니다. 이는 대체 경로로 문서화되어 있습니다.
- SVG 업로드는 `UploadOptions`이 XSS 위험에서 SVG을 제외하기 때문에 지원되지 않습니다.
- 루프백 로컬 개발(`localhost`, `127.0.0.1`, `[::1]`) 또는 명시적인 `allowInsecurePublicBaseUrl=true`의 경우를 제외하고 `publicBaseUrl`은 기본적으로 HTTPS여야 합니다.
- `publicBaseUrl`에는 사용자 정보, `..`, 쿼리 문자열 또는 조각이 포함되어서는 안 됩니다.
- 활성 스토리지 백엔드가 로컬인 경우 루프백이 아닌 공개 기본 URL에는 `allowLocalStorageRemotePublicBaseUrl=true`이 필요하므로 구성 중에 우발적인 가짜 CDN 응답이 표시됩니다.
- 헤더 콘텐츠 유형은 제한된 이미지 허용 목록으로 정규화되고 매직 바이트는 해당 콘텐츠 유형과 일치해야 하며 VIPS decode/maxPixels 유효성 검사는 최종 콘텐츠 게이트로 유지됩니다.

## 관찰 가능성 계약

워크플로 측정항목은 낮은 카디널리티 태그만 사용합니다. 여기에는 `imageId`, 파일 이름 또는 원시 개체 키가 포함되지 않습니다.

| 미터법 | 유형 | 태그 |
|---|---|---|
| `workshop.images.upload.accepted` | 카운터 | 정규화된 허용 목록 `contentType` |
| `workshop.images.processing.duration` | 타이머 | `result=success|timeout|cancelled|validation|failure` |
| `workshop.images.processing.failures` | 카운터 | `stage=timeout|cancelled|validation|vips|storage|unknown` |
| `workshop.images.variant.generated` | 카운터 | `variant` |

## 테스트 전략

- 단위 테스트는 기록 `ImageStorage` 구현을 사용하여 키 이름 지정, URL 구성 및 외부 S3 없이 변형 저장소를 확인합니다.
- Controller/service 테스트에서는 잘못된 MIME 유형, MIME/magic-byte 불일치, 안전하지 않은 공개 URL 설정 및 너무 큰 입력 오류를 확인합니다.
- VIPS 통합 테스트는 `--enable-native-access=ALL-UNNAMED`을 사용하여 Java 25에서 실행됩니다. 기본적으로 건너뛰고 `-Dvips.enabled=true`을 사용하여 명시적으로 선택해야 합니다.
- 테스트 이미지는 게시되지 않은 테스트 픽스처에 의존하지 않도록 `ImageIO`을 사용하여 생성됩니다.
- 테스트에서는 멀티파트 및 서비스 바이트 제한이 모두 25 MiB 기본값을 사용한다고 검증문합니다.
- 테스트에서는 부분적인 저장 오류가 발생한 후 최선의 정리 작업이 시도되었다고 검증문합니다.
- 테스트에서는 지표 이름과 태그가 낮은 카디널리티임을 확인합니다.
- 타겟 검증:
  - `./gradlew projects`
  - `./gradlew :image-processing-advanced-workflow:test`
  - `./gradlew :image-processing-advanced-workflow:test -Dvips.enabled=true` Java 25 및 libvips를 사용할 수 있는 경우
  - `./gradlew :image-processing-advanced-workflow:build`
  - `./gradlew build -x test --parallel --continue` 로컬 툴체인이 허용하는 경우

## 수락 기준 매핑

| Issue #93 기준 | 디자인 범위 |
|---|---|
| 파생상품에 업로드 추가 module/scenario | 새로운 `:image-processing-advanced-workflow` |
| JDK 25 및 libvips README 전제조건 | README 작업 |
| before/after 조각 | README 작업 |
| 예시 및 샘플 요청 JSON | README 작업 |
| 생성된 명명 규칙 | README 및 테스트 |
| 시퀀스 및 구성 요소 다이어그램 | README 인어 다이어그램 |
| resize/thumbnail 치수 테스트 | VIPS 통합 테스트 |
| 잘못된 콘텐츠 유형 및 너무 큰 테스트 | controller/service 테스트 |
| libvips 비활성화 건너뛰기 동작 | `AbstractFfmVipsWorkshopTest` |
| 서명되지 않은 S3 공개 URL | `PublicImageUrlResolver` 및 응답 모델 |
| S3/local 개발자 프로필 | `ImageStorage` 자동 구성 문서 및 `application.yml` |
| 서명되지 않은 URL에 대한 보안 참고 사항 | README 작업 |
| Bluetape4k-첫 번째 기능 테이블 | README 작업 |
| 다중 부분 최대 크기 경계 | `application.yml` 및 구성 바인딩 테스트 |
| 제한된 동시성 및 시간 초과 | 워크플로 서비스 속성 및 테스트 |

## 완료의 정의

- 사양과 계획은 구현 전에 커밋됩니다.
- Claude spec/plan 및 코드 검토에 대한 Advisor Gate는 P0=0 및 P1=0으로 표시됩니다.
- 새 모듈이 `settings.gradle.kts`에 등록되었습니다.
- 카탈로그 별칭 외에 중앙에서 관리되는 버전을 복제하지 않고 새 종속성 별칭이 추가됩니다.
- 모듈 README.md 및 README.ko.md는 GitHub 인어 다이어그램을 렌더링합니다.
- 루트 README.md 및 README.ko.md는 새로운 고급 모듈을 나열합니다.
- 테스트는 명시적인 VIPS 건너뛰기 동작으로 승인 기준을 다룹니다.
- 다중 부분 제한, 서비스 바이트 제한, `publicBaseUrl` 유효성 검사, 정리 정책 및 메트릭 이름이 테스트에 포함됩니다.
- 대상 Gradle 검증 통과 또는 환경 관련 격차가 기록됩니다.
- `docs/lessons/2026-05-24-issue-93-image-processing-advanced.md`이 추가되었습니다.
- PR는 `debop`에 할당된 `develop`에 대해 관련 레이블과 함께 열립니다.

## 1단계 / 1-R 체크리스트 완료 보고서

| 아이템 | 상태 | 메모 |
|---|---|---|
| 대상 저장소 확인됨 | 완료 | `bluetape4k-workshop`, 분기 `feat/issue-93-image-processing-advanced` |
| Memory/GNO 검색됨 | 완료 | `images-vips` design/plan 발견 및  #77 분류 참조 발행 |
| 현재 저장소 및 생태계 재사용 검색 | 완료 | CodeGraph 및 VIPS에 대한 소스 검사 및 이미지 저장 |
| External/current API 증거 확인 | 완료 | `FfmVipsRuntime`, `ffmVipsImageOf`, `ImageStorage`, `S3ImageStorage`, `LocalImageStorage`의 로컬 소스 |
| 확인된 기술적 제약 | 완료 | Java 25 모듈, libvips 기본 종속성, 서명되지 않은 공개 URL 보안 |
| 사용자 의도 명확 | 완료 | 사용자가 명시적으로 선택한 #93 및 README 다이어그램 요구사항 |

## 2단계 체크리스트 완료 보고서

| 아이템 | 상태 | 메모 |
|---|---|---|
| 아키텍처 사전 설계 실행 | 완료 | 위의 비교 및 ​​구성 요소 경계에 접근 |
| 1-R 단계 연구 통합 | 완료 | 증거 및 선택된 접근법은 현재 소스를 참조합니다 |
| 기능 작업 트리 내부의 사양 경로 | 완료 | 이 파일은 새로운 기능 작업 트리 |
| Risks/failure 모드 포함 | 완료 | 오류 모드 테이블 포함 |
| 접근 방식 비교 포함 | 완료 | 세 가지 접근 방식 비교 |
| 사용자 승인 | 완료 | 사용자가 이미  #93 선택하고 workflow/README 다이어그램을 지정했습니다. 물질적 모호성은 남아 있지 않습니다 |
| 초안 작업 목록이 반환됨 | 완료 | 승인 매핑 및 DoD 계획 입력 정의 |

## 2-R단계 검토 노트

### Claude 코드 오퍼스 어드바이저

초기 아티팩트: `.omx/artifacts/claude-issue-93-spec-20260524162959.md`
P0/P1 수정 후 아티팩트 다시 실행: `.omx/artifacts/claude-issue-93-spec-rerun-20260524163313.md`

| 우선순위 | 찾기 | 결정 | 후속 조치 |
|---|---|---|---|
| P0 | HTTP 레이어 및 coroutine/blocking 모델이 불명확함 | 수락됨 | MVC+suspend/coroutine 경계 정책 추가 |
| P0 | 다중 부분 크기 제한 누락 | 수락됨 | servlet/storage/service 25 MiB 기본값 및 테스트 추가 |
| P0 | 동시성 및 제한시간이 지정되지 않음 | 수락됨 | request/variant 세마포어 및 30초 시간 제한 추가 |
| P1 | 콘텐츠 유형 신뢰 경계가 불명확함 | 수락됨 | 헤더 검증과 VIPS 디코드를 최종 게이트로 추가 |
| P1 | 고아 정리 누락 | 수락됨 | 최선의 삭제 정책을 추가했습니다 |
| P1 | VIPS 초기화 시기가 불분명함 | 수락됨 | 지연 초기화 수명 주기 정책이 추가되었습니다 |
| P1 | 픽셀 제한이 지정되지 않음 | 수락됨 | 최대 100M 픽셀 기본값 추가 |
| P1 | 공개 URL 검증이 부족하게 지정됨 | 수락됨 | 구성표, 경로 및 local/remote 불일치 확인이 추가됨 |
| P1 | 파일 이름 소독제가 모호함 | 수락됨 | 결정적 살균제 규칙이 추가되었습니다 |
| P1 | 측정항목 이름이 지정되지 않음 | 수락됨 | 관찰 가능성 계약 추가됨 |

최신 통합 결과 테이블: `P0=0`, `P1=0`.
