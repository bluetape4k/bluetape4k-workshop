# Spring Cloud AWS S3 Demo

[English](README.md) | 한국어

## 로컬 S3 워크플로우

이 예제는 Spring Cloud AWS로 로컬 S3 흐름을 가장 작게 실행합니다. S3 client 설정, 버킷 생성, 객체 업로드, 목록 조회, exact 객체 읽기와 Bluetape4k `ResourcePatternResolver`를 통한 단일 버킷 wildcard 조회를 한 번에 확인합니다.

## 아키텍처

![Spring Cloud AWS S3 Demo 아키텍처 다이어그램](../../docs/images/readme-diagrams/aws-s3-spring-cloud-readme-architecture-01.png)

다이어그램은 Spring Boot 샘플, Spring Cloud AWS 추상화, AWS SDK 호출, 테스트가 사용하는 로컬 S3 호환 런타임을 분리해서 보여 줍니다.

## Request Flow

![Spring Cloud AWS S3 Demo sequence diagram](../../docs/images/readme-diagrams/aws-s3-spring-cloud-sequence-01.png)

[Spring Cloud AWS](https://github.com/awspring/spring-cloud-aws)와 AWS SDK v2로 버킷 생성, 객체 업로드, 목록 조회, Spring Resource 추상화를 통한 S3 리소스 읽기를 실행하는 예제입니다. Floci 기반 테스트는 `config/**/*.yml`을 대상으로 모든 목록 페이지를 소비하고 정렬된 읽기 전용 resource를 반환하는지 함께 증명합니다.

## 주요 기능

| function | explanation |
|------|------|
| 버킷 생성 | `S3Client.createBucket()` — bluetape4k 확장 함수로 간결하게 생성 |
| 파일 업로드 | `S3Template.upload(bucket, key, input, metadata)` — Spring Cloud AWS 추상화 |
| 파일 목록 조회 | `S3Client.listObjects { it.bucket(...) }` — 버킷 안의 객체 열거 |
| exact 리소스 읽기 | `@Qualifier("s3ResourcePatternResolver") ResourcePatternResolver.getResource("s3://bucket/key")` — Bluetape4k 읽기 전용 `S3Resource` |
| 리소스 검색 | `ResourcePatternResolver.getResources("s3://bucket/config/**/*.yml")` — literal 단일 버킷, 모든 paginator page, 정렬·중복 제거 결과 |
| ResourceLoader 호환성 | 기존 Spring Cloud AWS `ResourceLoader` 호출은 유지되며, Bluetape4k exact/pattern 동작에는 qualifier resolver를 사용 |
| 로컬 테스트 | `FlociServer` (Testcontainers) — 실제 AWS 없이 로컬에서 S3 호환 AWS 에뮬레이터 실행 |

## 설정 방법

### 의존성 (`build.gradle.kts`)

```kotlin
implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
implementation("software.amazon.awssdk:s3")
implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
testImplementation("io.bluetape4k:bluetape4k-testcontainers")  // FlociServer
```

저장소의 `bluetape4k-dependencies` BOM이 Bluetape4k 버전을 제공합니다. 개별 모듈
버전이나 두 번째 Bluetape4k BOM을 추가하지 않습니다.

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

### 로컬 에뮬레이터 설정

이 샘플은 의도적으로 Floci 기반 `S3Client` bean을 생성합니다. `application.yml` 값은
Spring Cloud AWS resource loading을 위한 로컬 기본값이며, 실제 AWS runtime profile로
사용하지 않습니다.

```yaml
spring:
  cloud:
    aws:
      credentials:
        access-key: noop
        secret-key: noop
      region:
        static: us-east-1
      s3:
        enabled: true
  autoconfigure:
    exclude:
      - io.bluetape4k.aws.spring.s3.S3TransferAutoConfiguration
```

Spring Cloud AWS가 같은 이름의 `s3ObjectConverter` bean을 소유하므로 transfer
자동 구성만 제외합니다. S3 Resource 자동 구성은 계속 활성화되어 있습니다. 샘플은
고정된 `s3ResourcePatternResolver` qualifier를 사용하며,
`ApplicationContext.getResources(...)`는 이 pattern bean이 가로채지 않습니다.

## 사용 예제

### 파일 업로드와 목록 조회

```kotlin
// Create bucket (bluetape4k extension function)
s3Client.createBucket("my-bucket") {}

// Upload file (Spring Cloud AWS S3Template)
val content = "Hello, S3!".toByteArray()
s3Template.upload(
    "my-bucket",
    "hello.txt",
    content.inputStream(),
    ObjectMetadata.builder()
        .contentLength(content.size.toLong())
        .contentType("text/plain")
        .build(),
)

// Output object list
s3Client.listObjects { it.bucket("my-bucket") }
    .contents()
    .forEach { log.info { "key=${it.key()}" } }
```

### exact 리소스 읽기와 pattern 검색

```kotlin
@Qualifier("s3ResourcePatternResolver")
lateinit var resources: ResourcePatternResolver

val exact = resources.getResource("s3://my-bucket/hello.txt")
val content = exact.inputStream.use { it.bufferedReader().readText() }

val configs = resources.getResources("s3://my-bucket/config/**/*.yml")
    .map { it.filename }
```

`s3://bucket/key` exact 읽기와 `s3://bucket/config/**/*.yml` wildcard 읽기는 읽기 전용
`Resource`를 반환합니다. wildcard는 `*`, `?`, `**`만 지원하며 bucket은 literal
authority여야 하고 pattern 앞에는 비어 있지 않은 prefix가 있어야 합니다. 결과는
모든 `ListObjectsV2` page에서 수집하고 `String.compareTo` 기준으로 중복 제거·정렬합니다.
match가 없으면 객체별 HEAD/GET 없이 빈 배열을 반환합니다. 반환 stream은 항상
`use { ... }`로 닫고 write/output stream은 사용하지 않습니다.

다음 경계는 S3 요청 전에 거부합니다. wildcard bucket, cross-bucket glob,
`s3://bucket/*.yml` root/empty-prefix pattern, write/output-stream 연산입니다.

## 테스트 전제 조건

- Docker 데몬이 실행 중이어야 합니다. Testcontainers가 Floci 컨테이너를 자동으로 시작합니다.
- 실제 AWS 자격 증명은 필요하지 않습니다. Floci가 로컬에서 S3 호환 API를 제공합니다.
- 실제 AWS 접근은 이 샘플의 runtime path가 아닙니다. 이 예제는 local-first로 유지합니다.

집중 예제 테스트 실행:

```bash
./gradlew :aws-s3-spring-cloud:test
```
