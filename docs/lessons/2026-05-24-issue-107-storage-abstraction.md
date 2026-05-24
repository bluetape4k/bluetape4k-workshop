# Issue #107 — Storage Abstraction 워크샵 모듈 구현 회고

## 개요

`aws/storage-abstraction` 모듈을 신규 생성했습니다.
Spring Profile로 로컬 파일시스템 / AWS S3 / S3 Pre-signed URL 세 가지 스토리지 전략을 전환하는 예제입니다.

## 핵심 결정사항

### 1. LocalStackServer → FlociServer 전환

`bluetape4k-testcontainers 1.9.1`에서 `LocalStackServer`가 deprecated 되었습니다.
`FlociServer`(GraalVM Native Image 기반 경량 AWS 에뮬레이터)가 공식 대체재입니다.

API 차이:
- `LocalStackServer.endpoint` → `FlociServer.awsEndpoint`
- `LocalStackServer.accessKey` → `FlociServer.awsAccessKey`
- `LocalStackServer.secretKey` → `FlociServer.awsSecretKey`
- `LocalStackServer.region` → `FlociServer.regionName`

### 2. bluetape4k-aws S3AutoConfiguration 비활성화

`bluetape4k-aws-spring-boot`의 `S3AutoConfiguration`은 클래스패스에 S3가 있으면
무조건 활성화됩니다. `local` 프로파일에서는 AWS 리전 설정이 없으므로 컨텍스트 로딩에 실패합니다.

해결: `application.yml`에서 `spring.autoconfigure.exclude`로 명시적으로 제외합니다.

```yaml
spring:
  autoconfigure:
    exclude:
      - io.bluetape4k.aws.spring.s3.S3AutoConfiguration
      - io.bluetape4k.aws.spring.s3.S3TransferAutoConfiguration
```

### 3. S3Presigner — bluetape4k-aws에 없음

`bluetape4k-aws-java 0.2.1`에 `S3Presigner` 관련 확장 함수가 없습니다.
AWS SDK v2의 `S3Presigner`를 직접 사용했습니다.

```kotlin
val presignRequest = GetObjectPresignRequest.builder()
    .signatureDuration(Duration.ofMinutes(presignDurationMinutes))
    .getObjectRequest { req -> req.bucket(bucketName).key(key) }
    .build()
val url = s3Presigner.presignGetObject(presignRequest).url().toString()
```

### 4. withContext(Dispatchers.IO) 필수

`StorageService` 인터페이스는 `suspend` 메서드를 선언합니다.
`S3Client`와 `Files.*`는 블로킹 API이므로 반드시 `withContext(Dispatchers.IO)`로 감싸야 합니다.

### 5. FlociServer 싱글턴 공유

`FlociServer.Launcher.floci`는 JVM 수준 싱글턴입니다.
`S3StorageServiceTest`와 `S3PresignedStorageServiceTest`가 동일한 컨테이너 인스턴스를 공유합니다.
Floci는 모든 서비스를 기본 활성화하므로 `withServices("s3")` 호출은 no-op입니다.

## 테스트 결과

```
LocalStorageServiceTest:    5 tests, 0 failures (local profile)
S3StorageServiceTest:       5 tests, 0 failures (s3 profile)
S3PresignedStorageServiceTest: 7 tests, 0 failures (s3-presigned profile)
합계: 17 tests, 모두 통과
```

## 파일 구조

```
aws/storage-abstraction/
├── build.gradle.kts
├── README.md
└── src/
    ├── main/
    │   ├── kotlin/io/bluetape4k/workshop/storage/
    │   │   ├── StorageApplication.kt
    │   │   ├── StorageService.kt         (interface)
    │   │   ├── LocalStorageService.kt    (@Profile("local"))
    │   │   ├── S3Config.kt               (@Profile("s3 | s3-presigned"))
    │   │   ├── S3StorageService.kt       (@Profile("s3"))
    │   │   └── S3PresignedStorageService.kt (@Profile("s3-presigned"))
    │   └── resources/application.yml
    └── test/
        ├── kotlin/io/bluetape4k/workshop/storage/
        │   ├── AbstractStorageServiceTest.kt
        │   ├── LocalStorageServiceTest.kt
        │   ├── S3StorageServiceTest.kt
        │   └── S3PresignedStorageServiceTest.kt
        └── resources/
            ├── junit-platform.properties
            └── logback-test.xml
```
