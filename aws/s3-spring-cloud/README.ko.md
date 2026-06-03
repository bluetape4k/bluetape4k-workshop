# Spring Cloud AWS S3 Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Cloud AWS S3 Demo**를 실행 가능한 AWS 통합 워크샵 조각으로 다룹니다. 개발자가 가장 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리/프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Spring Cloud AWS S3 Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/aws-s3-spring-cloud-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README를 코드와 비교할 때는 `io.bluetape4k.workshop.aws` 패키지를 기준으로 삼습니다.

## 흐름 다이어그램

1. `aws-s3-spring-cloud`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, 트레이스 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여 줍니다. 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![Spring Cloud AWS S3 Demo sequence diagram](../../docs/images/readme-diagrams/aws-s3-spring-cloud-sequence-01.png)

[Spring Cloud AWS](https://github.com/awspring/spring-cloud-aws))로 S3 서비스를 사용하는 예제입니다.

![Spring Cloud AWS S3 Demo diagram](../../docs/images/readme-diagrams/aws-s3-spring-cloud-sequence-01.png)

## 주요 기능

| function | explanation |
|------|------|
| 버킷 생성 | `S3Client.createBucket()` — bluetape4k 확장 함수로 간결하게 생성 |
| 파일 업로드 | `S3Template.store(bucket, key, content)` — Spring Cloud AWS 추상화 |
| 파일 목록 조회 | `S3Client.listObjects { it.bucket(...) }` — 버킷 안의 객체 열거 |
| 리소스 읽기 | `ResourceLoader.getResource("s3://bucket/key")` — Spring Resource 추상화 |
| 로컬 테스트 | `LocalStackServer` (Testcontainers) — 실제 AWS 없이 로컬에서 S3 에뮬레이션 |

## 설정 방법

### 의존성 (`build.gradle.kts`)

```kotlin
implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
implementation("software.amazon.awssdk:s3")
testImplementation("io.bluetape4k:bluetape4k-testcontainers")  // LocalStackServer
```

### S3Client 빈 등록 (LocalStack 통합)

```kotlin
@Bean
fun s3Client(): S3Client {
    return S3Client.builder()
.endpointOverride(s3Server.endpoint) // LocalStack endpoint
        .region(Region.of(s3Server.region))
        .credentialsProvider(
            staticCredentialsProviderOf(s3Server.accessKey, s3Server.secretKey)
        )
        .build()
}
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

- Docker 데몬이 실행 중이어야 합니다. Testcontainers가 LocalStack 컨테이너를 자동으로 시작합니다.
- 실제 AWS 자격 증명은 필요하지 않습니다. LocalStack이 로컬에서 S3 API를 에뮬레이션합니다.
