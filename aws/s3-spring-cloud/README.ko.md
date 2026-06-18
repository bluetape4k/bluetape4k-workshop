# Spring Cloud AWS S3 Demo

[English](README.md) | 한국어

## 로컬 S3 워크플로우

이 예제는 Spring Cloud AWS로 로컬 S3 흐름을 가장 작게 실행합니다. S3 client 설정, 버킷 생성, 객체 업로드, 목록 조회, Spring `ResourceLoader`를 통한 객체 읽기를 한 번에 확인합니다.

## 아키텍처

![Spring Cloud AWS S3 Demo 아키텍처 다이어그램](../../docs/images/readme-diagrams/aws-s3-spring-cloud-readme-architecture-01.png)

다이어그램은 Spring Boot 샘플, Spring Cloud AWS 추상화, AWS SDK 호출, 테스트가 사용하는 로컬 S3 호환 런타임을 분리해서 보여 줍니다.

## Request Flow

![Spring Cloud AWS S3 Demo sequence diagram](../../docs/images/readme-diagrams/aws-s3-spring-cloud-sequence-01.png)

[Spring Cloud AWS](https://github.com/awspring/spring-cloud-aws)와 AWS SDK v2로 버킷 생성, 객체 저장, 목록 조회, Spring Resource 추상화를 통한 S3 리소스 읽기를 실행하는 예제입니다.

## 주요 기능

| function | explanation |
|------|------|
| 버킷 생성 | `S3Client.createBucket()` — bluetape4k 확장 함수로 간결하게 생성 |
| 파일 업로드 | `S3Template.store(bucket, key, content)` — Spring Cloud AWS 추상화 |
| 파일 목록 조회 | `S3Client.listObjects { it.bucket(...) }` — 버킷 안의 객체 열거 |
| 리소스 읽기 | `ResourceLoader.getResource("s3://bucket/key")` — Spring Resource 추상화 |
| 로컬 테스트 | `FlociServer` (Testcontainers) — 실제 AWS 없이 로컬에서 S3 호환 AWS 에뮬레이터 실행 |

## 설정 방법

### 의존성 (`build.gradle.kts`)

```kotlin
implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
implementation("software.amazon.awssdk:s3")
testImplementation("io.bluetape4k:bluetape4k-testcontainers")  // FlociServer
```

### S3Client 빈 등록 (Floci 통합)

```kotlin
private val s3Server = FlociServer.Launcher.floci.withServices("s3")

@Bean
fun s3Client(): S3Client =
    S3Client.builder()
        .endpointOverride(s3Server.awsEndpoint)
        .region(Region.of(s3Server.regionName))
        .credentialsProvider(staticCredentialsProviderOf(s3Server.awsAccessKey, s3Server.awsSecretKey))
        .build()
```

### 실제 AWS 연결 시 `application.yml` 설정

```yaml
spring:
  cloud:
    aws:
      credentials:
        access-key: ${AWS_ACCESS_KEY_ID}
        secret-key: ${AWS_SECRET_ACCESS_KEY}
      region:
        static: ap-northeast-2
      s3:
        enabled: true
```

## 사용 예제

### 파일 업로드와 목록 조회

```kotlin
// Create bucket (bluetape4k extension function)
s3Client.createBucket("my-bucket") {}

// Upload file (Spring Cloud AWS S3Template)
s3Template.store("my-bucket", "hello.txt", "Hello, S3!")

// Output object list
s3Client.listObjects { it.bucket("my-bucket") }
    .contents()
    .forEach { log.info { "key=${it.key()}" } }
```

### Spring Resource 추상화로 파일 읽기

```kotlin
val resource = resourceLoader.getResource("s3://my-bucket/hello.txt") as WritableResource
val content = resource.inputStream.bufferedReader().readText()
```

## 테스트 전제 조건

- Docker 데몬이 실행 중이어야 합니다. Testcontainers가 Floci 컨테이너를 자동으로 시작합니다.
- 실제 AWS 자격 증명은 필요하지 않습니다. Floci가 로컬에서 S3 호환 API를 제공합니다.
