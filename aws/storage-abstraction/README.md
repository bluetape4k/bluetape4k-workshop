# Storage Abstraction Workshop

Demonstrates a pluggable storage strategy that switches between local filesystem,
AWS S3, and S3 pre-signed URL backends via Spring Profile — without changing application code.

## Architecture

![storage abstraction Architecture diagram](../../docs/images/readme-diagrams/aws-storage-abstraction-architecture-01.png)

## Key Features

| Feature | Description |
|---------|-------------|
| Storage abstraction | Single `StorageService` interface with `upload`, `download`, `getUrl`, `delete` |
| Profile switching | `local` / `s3` / `s3-presigned` Spring profiles select the implementation |
| Local backend | `java.nio.file.Files` — zero external dependencies, instant test startup |
| S3 backend | AWS SDK v2 `S3Client` wrapped in `withContext(Dispatchers.IO)` |
| Pre-signed URLs | `S3Presigner` generates time-limited GET URLs (default 15 min, 900 s) |
| Coroutines | All `suspend` methods; blocking I/O dispatched on `Dispatchers.IO` |
| Local AWS emulator | `FlociServer` (Testcontainers) replaces LocalStack Community edition |

## Profile Switching

### local (default dev/test — no Docker required)

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=local'
```

### s3 (LocalStack-compatible — requires Docker)

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3'
```

### s3-presigned (S3 with pre-signed GET URLs — requires Docker)

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3-presigned'
```

## StorageService Interface

```kotlin
interface StorageService {
    suspend fun upload(key: String, content: ByteArray, contentType: String): String
    suspend fun download(key: String): ByteArray
    suspend fun getUrl(key: String): String   // public URL or pre-signed URL
    suspend fun delete(key: String)
}
```

## Pre-signed URL Behaviour

When the `s3-presigned` profile is active, `getUrl()` generates a pre-signed
S3 GET URL using `S3Presigner`. The URL includes an `X-Amz-Expires=900`
query parameter (configurable via `storage.s3.presign-duration-minutes`).

```kotlin
// Example presigned URL (LocalStack / Floci):
// http://127.0.0.1:4566/bluetape4k-workshop-bucket/test/hello.txt
//   ?X-Amz-Algorithm=AWS4-HMAC-SHA256
//   &X-Amz-Date=...
//   &X-Amz-Expires=900
//   &X-Amz-SignedHeaders=host
//   &X-Amz-Signature=...
```

## Configuration

`src/main/resources/application.yml`:

```yaml
storage:
  local:
    base-path: /tmp/bluetape4k-workshop-storage   # local profile root directory
  s3:
    bucket-name: bluetape4k-workshop-bucket        # S3 bucket name
    presign-duration-minutes: 15                   # pre-signed URL TTL
```

## Dependencies

```kotlin
// build.gradle.kts
implementation(libs.bluetape4k.aws)           // bluetape4k-aws-spring-boot
implementation(libs.bluetape4k.coroutines)
implementation(libs.aws2.s3.lib)              // AWS SDK v2 S3
implementation(libs.bluetape4k.testcontainers)
implementation(libs.testcontainers.localstack) // Floci uses LocalStack-compatible API
```

## Running Tests

```bash
./gradlew :aws-storage-abstraction:test
```

Tests run all three profiles in a single Gradle task:

- `LocalStorageServiceTest` — 5 tests, `local` profile, no Docker
- `S3StorageServiceTest` — 5 tests, `s3` profile, Floci container (shared JVM singleton)
- `S3PresignedStorageServiceTest` — 7 tests, `s3-presigned` profile, Floci + presigned URL assertions

## Notes

- `S3AutoConfiguration` from `bluetape4k-aws-spring-boot` is excluded in `application.yml`
  because this module manages its own S3 beans via `S3Config`.
- `FlociServer.Launcher.floci` is a JVM-level singleton — the container starts once and is
  shared by all S3 test classes in the same Gradle test run.
- All blocking AWS SDK calls are wrapped in `withContext(Dispatchers.IO)` to comply with
  coroutine structured concurrency contracts.
