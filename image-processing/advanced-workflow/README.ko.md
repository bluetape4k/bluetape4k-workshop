# image-processing-advanced-workflow

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **image-processing-advanced-workflow** 모듈을 실행 가능한 이미지 처리 워크플로우 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

![image-processing-advanced-workflow 시나리오 다이어그램](../../docs/images/readme-diagrams/image-processing-advanced-workflow-scenario-01.png)

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

업로드된 이미지를 검증하고, 원본을 저장하고, Java 25 libvips로 WebP 파생 이미지를 생성한 뒤, 모든 객체를 Bluetape4k `ImageStorage`로 저장하고 unsigned public URL을 반환하는 Spring Boot 4 고급 예제입니다.

## 워크플로우

![Image Upload Workflow](../../docs/images/readme-diagrams/image-processing-advanced-workflow-scenario-01.png)

## 아키텍처

![image-processing-advanced-workflow Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/image-processing-advanced-workflow-readme-architecture-01.png)

![Image Processing Architecture](../../docs/images/readme-diagrams/image-processing-advanced-workflow-architecture-01.png)

## 사용한 Bluetape4k 기능

| 기능 | 모듈/artifact | 코드 위치 | 이점 |
|---|---|---|---|
| Java 25 VIPS runtime | `bluetape4k-images-vips-java25` | `FfmVipsDerivativeProcessor` | 픽셀 한도를 둔 빠른 native 썸네일 생성 |
| 공통 VIPS API | `bluetape4k-images-vips-api` | `VipsImage`, `VipsEncodeOptions`, `VipsImageFormat` | 백엔드 중립 resize/encode 계약 |
| 저장소 추상화 | `bluetape4k-images-spring-boot` | `ImageDerivativeWorkflowService`가 `ImageStorage` 주입 | 같은 workflow가 S3와 local storage에서 동작 |
| S3/local 자동 구성 | `bluetape4k-images-spring-boot` | `application.yml` `bluetape4k.images.storage.*` | 운영은 S3, 개발/테스트는 local fallback |
| 객체 key 검증 | `ImageKeyFactory` + `ImageObjectKey` | `ImageKeyFactory` | 파일명을 sanitize하고 검증된 object key 생성 |
| 업로드 검증 | `UploadOptions` | `UploadImageValidator` | 제한된 image content-type allowlist와 magic-byte 검사 적용 |
| 저장소 health/metrics | `images-spring-boot` health/metrics auto-config | Actuator endpoints | 별도 wrapper 없이 저장소 상태와 upload/download timing 확인 |
| Workflow metrics | Micrometer + `bluetape4k-micrometer` ecosystem | `ImageDerivativeWorkflowService` | 낮은 cardinality의 upload, duration, failure, variant counter |

## 사전 준비

이 모듈은 `bluetape4k-images-vips-java25`를 사용하므로 Java 25가 필요합니다.

```bash
# macOS
brew install vips

# Ubuntu/Debian
sudo apt-get install libvips-tools libvips-dev
```

Gradle test task는 `--enable-native-access=ALL-UNNAMED`를 추가합니다. 앱을 직접 실행할 때도 같은 JVM flag를 넣어야 합니다.

## 실행

```bash
./gradlew :image-processing-advanced-workflow:bootRun \
  --args='--workshop.images.advanced.public-base-url=http://localhost:8080/public-images'
```

이미지 업로드:

```bash
curl -F "file=@photo.jpg;type=image/jpeg" \
  http://localhost:8080/api/images/derivatives
```

## 응답 예시

```json
{
  "imageId": "2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4",
  "original": {
    "key": "images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/original/photo.jpg",
    "url": "http://localhost:8080/public-images/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/original/photo.jpg",
    "width": 1600,
    "height": 1200,
    "contentType": "image/jpeg",
    "sizeBytes": 245763
  },
  "thumbnailUrl": "http://localhost:8080/public-images/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/variants/thumb-128.webp",
  "variants": [
    {
      "name": "thumb-128",
      "key": "images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/variants/thumb-128.webp",
      "url": "http://localhost:8080/public-images/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/variants/thumb-128.webp",
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

## 객체 이름 규칙

```text
images/{imageId}/original/{safeFilename}
images/{imageId}/variants/thumb-128.webp
images/{imageId}/variants/card-320.webp
images/{imageId}/variants/detail-1024.webp
```

## S3 Public URL 설정

메인 경로는 unsigned public URL을 반환합니다. S3 storage와 public bucket, S3 website endpoint, CDN domain을 설정합니다.

```yaml
bluetape4k:
  images:
    storage:
      backend: s3
      bucket: public-image-bucket
      key-prefix: workshop

workshop:
  images:
    advanced:
      public-base-url: https://cdn.example.com/workshop
```

Unsigned URL은 bucket/CDN policy와 object ownership 설정이 public read를 허용해야 합니다. 비공개 이미지나 사용자 민감 이미지는 이 public URL resolver 대신 `S3PreSignedUrlSigner` 또는 `CloudFrontUrlSigner`를 사용하세요.

## Before / After

Raw framework 방식은 object key 검증, S3 upload, 이미지 decode limit, WebP encoding, cleanup, metrics를 반복 구현하기 쉽습니다.

```kotlin
val key = "images/$id/variants/thumb-128.webp"
s3Client.putObject(request, RequestBody.fromBytes(bytes))
timer.recordCallable { resizeWithNativeLibrary(bytes) }
```

이 예제는 framework glue를 작게 유지하고 Bluetape4k 계약을 사용합니다.

```kotlin
val key = ImageObjectKey.of("images/$imageId/variants", "thumb-128.webp")
val variant = image.thumbnail(128).use { it.suspendToBytes(VipsImageFormat.WEBP, options) }
storage.upload(key, variant, UploadOptions(contentType = "image/webp"))
```

## 검증과 제한

지원 업로드 타입은 JPEG, PNG, WebP입니다. Validator는 지원하지 않는 MIME type과 선언된 content type에 맞지 않는 magic byte payload를 거부합니다.

| 제한 | 기본값 |
|---|---:|
| Multipart file size | 25 MB |
| Service input bytes | 25 MB |
| Pixel budget | 100,000,000 pixels |
| Concurrent requests | 2 |
| Concurrent variants per request | 2 |
| Processing timeout | 30 seconds |

## 테스트

```bash
# Runs deterministic unit tests and explicitly skips native VIPS tests by default.
./gradlew :image-processing-advanced-workflow:test

# Runs VIPS integration tests when Java 25 and libvips are available.
./gradlew :image-processing-advanced-workflow:test -Dvips.enabled=true
```

Local storage 기본 위치는 `${java.io.tmpdir}/bluetape4k-workshop-images`입니다. 깨끗한 local 실행이 필요하면 이 디렉터리를 삭제하세요.
