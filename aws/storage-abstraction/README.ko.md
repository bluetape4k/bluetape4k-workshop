# Storage Abstraction Workshop

[English](README.md) | 한국어

## 스토리지 경계

이 예제는 coroutine 기반 `StorageService` 경계 뒤에 로컬 파일, S3 객체
저장소, S3 pre-signed GET URL, client-side encrypted S3 transfer profile 선택지를 둡니다. 애플리케이션 코드는 같은
인터페이스를 사용하고, Spring profile이 구현체를 선택합니다.

## 아키텍처

![Storage Abstraction Workshop architecture diagram](../../docs/images/readme-diagrams/aws-storage-abstraction-architecture-01.png)

핵심 경계는 `StorageService`입니다. `local`, `s3`, `s3-presigned` profile은
서로 다른 bean을 선택하고, `s3-encrypted-aes`와 `s3-encrypted-rsa`는 같은
byte 계약 위에 concrete `EncryptedS3StorageService` capability를 추가합니다.

## 요청 흐름

![Storage Abstraction Workshop request sequence](../../docs/images/readme-diagrams/aws-storage-abstraction-sequence-01.png)

`upload`, `download`, `delete`는 blocking filesystem 또는 AWS SDK 호출을
`Dispatchers.IO`에서 실행합니다. Object key는 backend 호출 전에 상대
forward-slash key로 trim/검증됩니다. `getUrl`은 `local` profile에서는 직접
파일 URL, `s3` profile에서는 endpoint-neutral `s3://bucket/key` object URI,
`s3-presigned`에서는 제한 시간 pre-signed GET URL을 반환합니다.

## 주요 기능

| 기능 | 설명 |
|---------|-------------|
| 스토리지 추상화 | `upload`, `download`, `getUrl`, `delete`를 제공하는 단일 `StorageService` 인터페이스 |
| 프로파일 전환 | `local` / `s3` / `s3-presigned` Spring profile이 구현체를 선택 |
| 로컬 백엔드 | `java.nio.file.Files` — 외부 의존성 없이 테스트를 즉시 시작 |
| S3 백엔드 | AWS SDK v2 `S3Client`를 `withContext(Dispatchers.IO)`로 감싸고 bluetape4k S3 bucket helper를 사용 |
| Pre-signed URL | `S3Presigner`가 제한 시간 GET URL을 생성(기본 15분, 900초) |
| Key guard | blank, absolute, backslash, `.` / `..` traversal key는 filesystem 또는 S3 접근 전에 실패 |
| 코루틴 | 모든 메서드가 `suspend`; 블로킹 I/O는 `Dispatchers.IO`에서 실행 |
| 로컬 AWS 에뮬레이터 | `FlociServer`(Testcontainers)가 LocalStack Community edition을 대체 |

## Client-side Encrypted S3 Transfer

`s3-encrypted-aes` profile은 AES-256 provider를 사용하고
`s3-encrypted-rsa` profile은 2048-bit RSA provider를 사용합니다. 두 profile은
upstream `S3ClientSideEncryptionProviderTemplate`과
`S3ClientSideEncryptionTransferTemplate`을 재사용합니다. 이 workshop은
consumer bean graph만 조립하며 암호화 envelope을 다시 구현하지 않습니다.

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3-encrypted-aes'
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3-encrypted-rsa'
```

`EncryptedS3StorageService`는 byte API를 유지하면서
`uploadFile(key, source, contentType)`과 `downloadFile(key, destination)`도 제공합니다.
Byte read는 ciphertext bounded reader를 사용합니다. File upload는 설정한
threshold를 넘으면 암호화 transfer stream으로 전환하고, file download는
ciphertext 임시 파일을 사용해 인증이 끝난 뒤에만 destination에 씁니다. 기존
destination에 쓰기 실패가 발생하면 rollback하지만 atomic rename을 보장하는
계약은 아닙니다.

AES key와 RSA key pair는 각 profile context에서 JVM memory에 생성합니다. 프로세스를
재시작하면 기존 object를 읽을 수 없으므로 이 profile은 로컬 학습과 테스트 전용입니다.
KMS/HSM 연동, key rotation, production multipart 검증, encrypted pre-signed
download는 이 예제의 범위에 포함하지 않습니다.

기존 아키텍처와 요청 흐름 다이어그램은 원래 `local`/`s3`/`s3-presigned`
baseline을 설명하므로 의도적으로 변경하지 않았습니다.

## 프로파일 전환

### local (기본 개발/테스트 — Docker 불필요)

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=local'
```

### s3 (LocalStack 호환 — Docker 필요)

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3'
```

### s3-presigned (pre-signed GET URL을 사용하는 S3 — Docker 필요)

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3-presigned'
```

## StorageService 인터페이스

```kotlin
interface StorageService {
    suspend fun upload(key: String, content: ByteArray, contentType: String): String
    suspend fun download(key: String): ByteArray
    suspend fun getUrl(key: String): String   // local file URL, s3:// URI, or pre-signed URL
    suspend fun delete(key: String)
}
```

Key는 raw local path가 아니라 portable object key입니다. `docs/readme.txt` 같은
상대 경로 값을 사용하세요. blank, absolute, backslash, traversal key는 거부됩니다.

## Pre-signed URL 동작

`s3-presigned` profile이 활성화되면 `getUrl()`은 `S3Presigner`를 사용해 pre-signed S3 GET URL을 생성합니다. URL에는 `X-Amz-Expires=900` 쿼리 파라미터가 포함됩니다(`storage.s3.presign-duration-minutes`로 설정 가능).

```kotlin
// Example presigned URL (LocalStack / Floci):
// http://127.0.0.1:4566/bluetape4k-workshop-bucket/test/hello.txt
//   ?X-Amz-Algorithm=AWS4-HMAC-SHA256
//   &X-Amz-Date=...
//   &X-Amz-Expires=900
//   &X-Amz-SignedHeaders=host
//   &X-Amz-Signature=...
```

## 설정

`src/main/resources/application.yml`:

```yaml
storage:
  local:
    base-path: /tmp/bluetape4k-workshop-storage   # local profile root directory
  s3:
    bucket-name: bluetape4k-workshop-bucket        # S3 bucket name
    presign-duration-minutes: 15                   # pre-signed URL TTL
```

## 의존성

```kotlin
// build.gradle.kts
implementation(libs.bluetape4k.aws)           // bluetape4k-aws-spring-boot
implementation(libs.bluetape4k.coroutines)
implementation(libs.aws2.s3.lib)              // AWS SDK v2 S3
implementation(libs.aws2.s3.transfer.manager) // AWS SDK v2 TransferManager
implementation(libs.bluetape4k.testcontainers)
implementation(libs.testcontainers.localstack) // Floci uses LocalStack-compatible API
implementation(libs.kotlinx.coroutines.reactive) // upstream async response adapter
```

## 테스트 실행

```bash
./gradlew :aws-storage-abstraction:test
```

테스트는 하나의 Gradle task에서 다섯 profile/capability를 모두 실행합니다.

- `LocalStorageServiceTest` — 8 tests, `local` profile, no Docker
- `S3StorageServiceTest` — 9 tests, `s3` profile, Floci container (shared JVM singleton)
- `S3PresignedStorageServiceTest` — 10 tests, `s3-presigned` profile, Floci + presigned URL assertions
- `EncryptedS3StorageServiceAesTest` — AES-256 byte/file 왕복, metadata, bounded read, key/version mismatch, destination rollback
- `EncryptedS3StorageServiceRsaTest` — 2048-bit RSA byte 왕복, metadata, provider isolation, wrong-key 거부
- `S3EncryptedOutputStreamTest` — threshold ciphertext spill, 1회 completion, write failure, cancellation, 임시 파일 정리

## 참고 사항

- `bluetape4k-aws-spring-boot`의 `S3AutoConfiguration`은 이 모듈이 `S3Config`로 자체 S3 bean을 관리하므로 `application.yml`에서 제외합니다.
- `FlociServer.Launcher.floci`는 JVM 수준 singleton입니다. 컨테이너는 한 번 시작되고 같은 Gradle test 실행 안의 모든 S3 테스트 클래스가 공유합니다.
- 모든 블로킹 AWS SDK 호출은 코루틴 구조적 동시성 계약을 지키기 위해 `withContext(Dispatchers.IO)`로 감쌉니다.
