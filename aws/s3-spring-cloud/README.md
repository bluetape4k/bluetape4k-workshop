# Spring Cloud AWS S3 Demo

[한국어](README.ko.md) | English

## Local S3 Workflow

This example shows the smallest local S3 path for Spring Cloud AWS: configure an S3 client, create buckets, upload objects, list them, and read an object through Spring's `ResourceLoader`.

## Architecture

![Spring Cloud AWS S3 Demo architecture diagram](../../docs/images/readme-diagrams/aws-s3-spring-cloud-readme-architecture-01.png)

The diagram separates the Spring Boot sample, Spring Cloud AWS abstractions, AWS SDK calls, and the local S3-compatible runtime used by the tests.

## Request Flow

![Spring Cloud AWS S3 Demo sequence diagram](../../docs/images/readme-diagrams/aws-s3-spring-cloud-sequence-01.png)

This example uses [Spring Cloud AWS](https://github.com/awspring/spring-cloud-aws) and AWS SDK v2 to create buckets, store objects, list objects, and read S3 resources through Spring's Resource abstraction.

## Main features

| function | explanation |
|------|------|
| Create bucket | `S3Client.createBucket()` — Concise creation with bluetape4k extension function |
| file upload | `S3Template.store(bucket, key, content)` — Spring Cloud AWS abstraction |
| View file list | `S3Client.listObjects { it.bucket(...) }` — Enumerate objects within a bucket |
| Read Resources | `ResourceLoader.getResource("s3://bucket/key")` — Spring Resource abstraction |
| local test | `FlociServer` (Testcontainers) — S3-compatible AWS emulator locally without actual AWS |

## How to set up

### Dependencies (`build.gradle.kts`)

```kotlin
implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
implementation("software.amazon.awssdk:s3")
testImplementation("io.bluetape4k:bluetape4k-testcontainers")  // FlociServer
```

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
```

## Usage example

### Upload files and view list

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

### Reading files with Spring Resource abstraction

```kotlin
val resource = resourceLoader.getResource("s3://my-bucket/hello.txt") as WritableResource
val content = resource.inputStream.bufferedReader().readText()
```

## Test prerequisites

- Requires Docker daemon to run (Testcontainers automatically starts the Floci container)
- No need for actual AWS credentials — Floci provides the local S3-compatible API
- Real AWS access is outside this sample's runtime path; keep this example local-first.
