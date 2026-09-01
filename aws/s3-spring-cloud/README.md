# Spring Cloud AWS S3 Demo

[한국어](README.ko.md) | English

## Local S3 Workflow

This example shows the smallest local S3 path for Spring Cloud AWS: configure an S3 client, create buckets, upload objects, list them, read an exact object, and resolve a single-bucket wildcard pattern through Bluetape4k's `ResourcePatternResolver`.

## Architecture

![Spring Cloud AWS S3 Demo architecture diagram](../../docs/images/readme-diagrams/aws-s3-spring-cloud-readme-architecture-01.png)

The diagram separates the Spring Boot sample, Spring Cloud AWS abstractions, AWS SDK calls, and the local S3-compatible runtime used by the tests.

## Request Flow

![Spring Cloud AWS S3 Demo sequence diagram](../../docs/images/readme-diagrams/aws-s3-spring-cloud-sequence-01.png)

This example uses [Spring Cloud AWS](https://github.com/awspring/spring-cloud-aws) and AWS SDK v2 to create buckets, upload objects, list objects, and read S3 resources through Spring's Resource abstraction. The Floci-backed test also proves that the resolver consumes all list pages, filters `config/**/*.yml`, and returns sorted read-only resources.

## Main features

| function | explanation |
|------|------|
| Create bucket | `S3Client.createBucket()` — Concise creation with bluetape4k extension function |
| file upload | `S3Template.upload(bucket, key, input, metadata)` — Spring Cloud AWS abstraction |
| View file list | `S3Client.listObjects { it.bucket(...) }` — Enumerate objects within a bucket |
| Read exact resource | `@Qualifier("s3ResourcePatternResolver") ResourcePatternResolver.getResource("s3://bucket/key")` — Bluetape4k read-only `S3Resource` |
| Find resources | `ResourcePatternResolver.getResources("s3://bucket/config/**/*.yml")` — single literal bucket, all paginator pages, sorted/deduplicated results |
| ResourceLoader compatibility | Existing Spring Cloud AWS `ResourceLoader` calls remain valid; use the qualified resolver for Bluetape4k exact/pattern behavior |
| local test | `FlociServer` (Testcontainers) — S3-compatible AWS emulator locally without actual AWS |

## How to set up

### Dependencies (`build.gradle.kts`)

```kotlin
implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
implementation("software.amazon.awssdk:s3")
implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
testImplementation("io.bluetape4k:bluetape4k-testcontainers")  // FlociServer
```

The repository's `bluetape4k-dependencies` BOM supplies the Bluetape4k version;
do not add an individual module version or a second Bluetape4k BOM.

### Register S3Client bean (Floci integration)

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

### Local emulator configuration

The sample intentionally creates a Floci-backed `S3Client` bean. The `application.yml`
values are local defaults for Spring Cloud AWS resource loading and should not be treated
as a real AWS runtime profile.

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

The transfer auto-configuration is excluded only because Spring Cloud AWS owns a
bean with the same `s3ObjectConverter` name. The S3 Resource auto-configuration
remains enabled. The sample uses the fixed `s3ResourcePatternResolver` qualifier;
`ApplicationContext.getResources(...)` is not intercepted by this pattern bean.

## Usage example

### Upload files and view list

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

### Reading an exact resource and matching a pattern

```kotlin
@Qualifier("s3ResourcePatternResolver")
lateinit var resources: ResourcePatternResolver

val exact = resources.getResource("s3://my-bucket/hello.txt")
val content = exact.inputStream.use { it.bufferedReader().readText() }

val configs = resources.getResources("s3://my-bucket/config/**/*.yml")
    .map { it.filename }
```

`s3://bucket/key` exact reads and `s3://bucket/config/**/*.yml` wildcard reads
return read-only `Resource` instances. Wildcards are limited to `*`, `?`, and
`**`; the bucket must be a literal authority and the pattern must have a
non-empty prefix. Results are collected from every `ListObjectsV2` page,
deduplicated, and sorted with `String.compareTo`. A pattern with no matches
returns an empty array without per-object HEAD/GET calls. Always close returned
streams with `use { ... }`; write/output streams are unsupported.

The following boundaries are intentionally rejected before an S3 request:
wildcard buckets, cross-bucket globs, `s3://bucket/*.yml` root/empty-prefix
patterns, and write/output-stream operations.

## Test prerequisites

- Requires Docker daemon to run (Testcontainers automatically starts the Floci container)
- No need for actual AWS credentials — Floci provides the local S3-compatible API
- Real AWS access is outside this sample's runtime path; keep this example local-first.

Run the focused example tests with:

```bash
./gradlew :aws-s3-spring-cloud:test
```
