# Issue #872 S3 AES/RSA client-side encryption transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `aws/storage-abstraction`에 기존 세 profile을 유지하면서 AES/RSA
client-side encryption과 AWS SDK v2 TransferManager stream/file 소비자 예제를
추가한다.

**Architecture:** `S3AutoConfiguration`과 `S3TransferAutoConfiguration`의 전역
exclusion은 유지한다. `s3-encrypted-aes`와 `s3-encrypted-rsa` profile 전용
config가 Floci 기반 sync/async client, `S3TransferManager`, upstream CSE
provider/transfer template을 조립하고, `EncryptedS3StorageService`가 기존
`StorageService` byte 계약과 concrete file 계약을 제공한다. 암호화 primitive와
envelope parser는 upstream public API에 위임한다.

**Tech Stack:** Kotlin, Spring Boot 4, Kotlin Coroutines, AWS SDK v2
`S3Client`/`S3AsyncClient`/`S3TransferManager`, `bluetape4k-aws`
`2.0.0-SNAPSHOT` BOM line, Floci/Testcontainers, JUnit 5, MockK.

---

## 현재 근거와 고정 제약

- 승인된 spec은 `docs/superpowers/specs/2026-09-02-issue-872-s3-cse-transfer-design.md`이며 대상은 Issue #872와 `aws/storage-abstraction`이다.
- 현재 `local`, `s3`, `s3-presigned` profile과 27개 baseline test는 변경하지 않는다.
- `gradle/libs.versions.toml`에는 이미 versionless `aws2-s3-transfer-manager` alias가 있고, AWS SDK BOM은 `2.46.17`이다. 새 Bluetape 버전 pin이나 개별 Bluetape BOM은 추가하지 않는다.
- resolved upstream jar에서 다음 public API를 확인했다: `S3ClientSideEncryptionProviderTemplate.uploadEncrypted`, `downloadEncryptedBytesBounded`, `S3ClientSideEncryptionTransferTemplate.encryptedOutputStream`, `downloadEncryptedFile`, `S3TransferTemplate`, `S3AesProvider`, `S3RsaProvider`, `S3EncryptedOutputStream`, `S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES`.
- 두 encrypted profile은 Floci에서 JVM memory key를 생성하는 교육용 경로다. KMS/HSM, rotation, 실제 AWS credential/cost/multipart 운영 검증, encrypted presigned GET은 구현하지 않는다.
- ciphertext 임시 파일은 `NonCancellable + Dispatchers.IO` 정리하고, plaintext destination은 upstream의 인증 후 bounded write와 rollback 계약을 사용한다. 원자적 rename을 제공한다고 문서화하지 않는다.
- 기존 README 다이어그램은 `local`/`s3`/`s3-presigned` baseline 범위로 유지한다. 새 그림은 만들지 않으므로 diagram skill은 N/A다.

## 작업 파일과 책임

| 파일 | 책임 |
| --- | --- |
| `aws/storage-abstraction/build.gradle.kts` | versionless AWS SDK transfer manager alias 소비 |
| `aws/storage-abstraction/src/main/resources/application-s3-encrypted-aes.yml` | AES profile 속성, 낮은 test threshold, key identity |
| `aws/storage-abstraction/src/main/resources/application-s3-encrypted-rsa.yml` | RSA profile 속성, 낮은 test threshold, key identity |
| `aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/EncryptedS3Config.kt` | encrypted profile client/manager/provider/template bean과 close lifecycle |
| `aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageService.kt` | byte/file 암호화 경계, bounded read, cancellation cleanup |
| `aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/StorageService.kt` | 다섯 profile과 encrypted file capability KDoc |
| `aws/storage-abstraction/src/main/resources/application.yml` | auto-configuration exclusion 이유 주석 |
| `aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageServiceAesTest.kt` | AES wiring, byte/file round-trip, metadata/key/algorithm negative, rollback |
| `aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageServiceRsaTest.kt` | RSA wiring, byte round-trip, metadata/key negative |
| `aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage/S3EncryptedOutputStreamTest.kt` | threshold ciphertext path, terminal idempotency, failure/cancellation cleanup |
| `aws/storage-abstraction/README.md`, `README.ko.md` | profile 실행, API, dependency, key/lifecycle와 제외 범위 |
| `docs/coverage-matrix.md` | S3 CSE/transfer coverage와 남은 운영 gap |
| `.github/workflows/Examples.yml` | 기존 sequential AWS storage 주석의 범위 |
| `scripts/smoke-validate.sh` | CSE stale guard |
| `docs/ecosystem-reuse-train.json` | Issue #872 child scope와 allowed paths |
| `docs/lessons/2026-09-02-issue-872-s3-cse-transfer.md` | lifecycle/bounded-read/upstream reuse lesson |
| `docs/review/issue-872-plan-review.md` | Step 3-R 통합 plan review |
| `docs/review/issue-872-s3-cse-transfer-implementation-review.md` | Step 6-R 구현 review |
| `docs/review/issue-872-s3-cse-transfer-pre-pr-review.md` | PR 직전 통합 review |

## 수용 기준 추적성

| 수용 기준 | 구현 task | 검증 증거 |
| --- | --- | --- |
| BOM 단일 원본과 versionless transfer alias | Task 1 | dependency report와 diff |
| 기존 세 profile과 27 baseline 회귀 없음 | Task 4, 6 | module test fresh result |
| AES/RSA byte round-trip, provider/key/algorithm metadata | Task 2, 3, 4 | Floci metadata assertions와 mismatch tests |
| bounded ciphertext read와 plaintext 미생성 | Task 3, 4 | max 초과 negative test |
| threshold 초과 ciphertext-only transfer와 completion 1회 | Task 3, 4 | fake delegate + Floci round-trip |
| 인증 후 destination write와 rollback | Task 3, 4 | tampered object/existing destination test |
| cancellation/write failure/temp cleanup/no-secret log | Task 3, 4 | coroutine/fake cleanup 및 log capture |
| KDoc, 양국 README, 실행/제외 범위 | Task 5 | parity, link, terminology audit |
| coverage/CI/stale/manifest/lesson/review | Task 5, 7 | JSON, actionlint, smoke/stale, review artifact |

---

### Task 1: Transfer dependency와 encrypted profile 속성 추가

**Files:**

- Modify: `aws/storage-abstraction/build.gradle.kts:22-24`
- Create: `aws/storage-abstraction/src/main/resources/application-s3-encrypted-aes.yml`
- Create: `aws/storage-abstraction/src/main/resources/application-s3-encrypted-rsa.yml`
- Modify: `aws/storage-abstraction/src/main/resources/application.yml:4-8`

- [x] **Step 1: versionless transfer alias를 추가한다**

AWS SDK block을 다음처럼 만들고 `gradle/libs.versions.toml`의 기존 alias는
그대로 둔다. upstream async response adapter가 사용하는
`kotlinx-coroutines-reactive`도 명시적으로 runtime classpath에 둔다.

```kotlin
// AWS SDK v2 의존성
implementation(libs.aws2.s3.lib)
implementation(libs.aws2.s3.transfer.manager)
implementation(libs.kotlinx.coroutines.reactive)
```

`gradle/libs.versions.toml`의 alias는 다음 한 줄이어야 하며 module build
script에 버전 문자열을 쓰지 않는다.

```toml
aws2-s3-transfer-manager = { module = "software.amazon.awssdk:s3-transfer-manager" }
```

- [x] **Step 2: AES profile YAML을 추가한다**

다음 속성을 정확히 추가한다.

```yaml
bluetape4k:
  aws:
    s3:
      enabled: true
      transfer:
        enabled: true
        output-stream-threshold-bytes: 1024
        output-stream-part-size-bytes: 5242880
      client-side-encryption:
        enabled: true
        provider: AES
        key-id: workshop-aes
        key-version: v1
        encryption-context:
          workshop: storage-abstraction
          profile: aes

storage:
  s3:
    encrypted:
      max-ciphertext-bytes: 67108880
```

- [x] **Step 3: RSA profile YAML을 추가한다**

AES와 같은 transfer/limit 경계를 유지하고 `provider: RSA`, `key-id:
workshop-rsa`, `profile: rsa`만 바꾼다. RSA key pair는 config에서 2048 bit로
생성한다.

- [x] **Step 4: 전역 exclusion의 이유를 보강한다**

`application.yml`의 두 exclusion key는 유지하고, 기존 세 profile과 encrypted
profile이 필요한 S3 bean을 custom config가 소유한다는 주석만 갱신한다.

- [x] **Step 5: dependency resolution을 확인한다**

```bash
./gradlew :aws-storage-abstraction:dependencies \
  --configuration testRuntimeClasspath --no-daemon --max-workers=1 --console=plain
```

출력에서 `software.amazon.awssdk:s3-transfer-manager:2.46.17`이 보이고 module
build script에 직접 버전이 없는지 확인한다.

---

### Task 2: encrypted profile 전용 bean graph 구성

**File:**

- Create: `aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/EncryptedS3Config.kt`
- Create: `aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageServiceAesTest.kt` (wiring RED scaffold)
- Create: `aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageServiceRsaTest.kt` (wiring RED scaffold)

- [x] **Step 1: profile wiring RED test를 먼저 작성한다**

AES/RSA 통합 test가 각 context에서 `S3Client`, `S3AsyncClient`,
`S3TransferManager`, `S3ClientSideEncryptionProviderTemplate`,
`S3ClientSideEncryptionTransferOperations`를 정확히 하나씩 확인하게 한다.
선택된 provider만 한 개이고 다른 provider는 0개여야 한다. 기본 profile에는
encrypted bean이 없어야 하며 `S3Properties`의 transfer/CSE enabled 값도
`true`여야 한다. config를 만들기 전에 이 두 test 파일을 생성해 RED를
관찰한다.

- [x] **Step 2: Floci client와 manager를 소유하는 config를 구현한다**

`S3Config.floci`를 재사용하고 아래 순서와 lifecycle을 지킨다.

```kotlin
@Configuration(proxyBeanMethods = false)
@Profile("s3-encrypted-aes | s3-encrypted-rsa")
@EnableConfigurationProperties(S3Properties::class)
class EncryptedS3Config {
    private val floci = S3Config.floci

    @Bean(destroyMethod = "close")
    fun s3Client(): S3Client = S3Client.builder()
        .endpointOverride(floci.awsEndpoint)
        .region(Region.of(floci.regionName))
        .credentialsProvider(staticCredentialsProviderOf(floci.awsAccessKey, floci.awsSecretKey))
        .build()

    @Bean(destroyMethod = "close")
    fun s3AsyncClient(): S3AsyncClient = S3AsyncClient.builder()
        .endpointOverride(floci.awsEndpoint)
        .region(Region.of(floci.regionName))
        .credentialsProvider(staticCredentialsProviderOf(floci.awsAccessKey, floci.awsSecretKey))
        .build()

    @Bean(destroyMethod = "close")
    fun s3TransferManager(s3AsyncClient: S3AsyncClient): S3TransferManager =
        S3TransferManager.builder().s3Client(s3AsyncClient).build()

    @Bean
    fun s3TransferTemplate(manager: S3TransferManager, properties: S3Properties) =
        S3TransferTemplate(manager, properties)

    @Bean(destroyMethod = "close")
    fun s3ClientSideEncryptionProviderTemplate(
        async: S3AsyncClient,
        properties: S3Properties,
        aes: ObjectProvider<S3AesProvider>,
        rsa: ObjectProvider<S3RsaProvider>,
    ) = S3ClientSideEncryptionProviderTemplate(
        async, properties, aes.getIfUnique(), rsa.getIfUnique(), SecureRandom()
    )

    @Bean
    fun s3ClientSideEncryptionTransferTemplate(
        async: S3AsyncClient,
        provider: S3ClientSideEncryptionProviderTemplate,
        transfer: S3TransferTemplate,
    ): S3ClientSideEncryptionTransferOperations =
        S3ClientSideEncryptionTransferTemplate(
            async, provider, transfer, transfer, Dispatchers.IO
        )

    @Bean
    @Profile("s3-encrypted-aes")
    fun aesProvider(): S3AesProvider = S3AesProvider.of(
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    )

    @Bean
    @Profile("s3-encrypted-rsa")
    fun rsaProvider(): S3RsaProvider = S3RsaProvider.of(
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    )
}
```

Spring bean method arguments must preserve dependency close order: provider
template before async client, transfer manager before async client. No presigner is
created in encrypted profiles.

---

### Task 3: encrypted storage service와 안전한 file 경계 구현

**Files:**

- Create: `aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageService.kt`
- Modify: `aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/StorageService.kt`

- [x] **Step 1: public contract과 validation을 고정한다**

`EncryptedS3StorageService : StorageService`는 기존 네 메서드를 유지하고 다음
concrete API만 추가한다.

```kotlin
suspend fun uploadFile(key: String, source: Path, contentType: String? = null): String
suspend fun downloadFile(key: String, destination: Path)
```

생성자는 `S3Client`, `S3ClientSideEncryptionProviderTemplate`,
`S3ClientSideEncryptionTransferOperations`, bucket property, max ciphertext
property를 받고, max가 `1..S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES`
범위인지 `init`에서 검증한다. 모든 key/content type/path는 기존
`storageObjectKey`, `storageBucketName` 규칙을 사용한다.

- [x] **Step 2: byte upload/download를 upstream template에 위임한다**

`upload`는 `ensureBucketExists` 후
`providerTemplate.uploadEncrypted(bucket, objectKey, content, type, emptyMap(), emptyMap())`
을 호출하고 `storageObjectUri`를 반환한다. `download`는
`downloadEncryptedBytesBounded(bucket, objectKey, emptyMap(), maxCiphertextBytes)`만
사용하여 bounded read를 보장한다. plaintext·key·ciphertext를 로그나 예외에
넣지 않는다.

- [x] **Step 3: encrypted stream upload의 terminal/cleanup 경계를 구현한다**

`uploadFile`은 regular source만 허용하고 source `InputStream`을 8 KiB buffer로
읽어 `transferOperations.encryptedOutputStream(...)`에 chunk 단위로 쓴다.
각 loop에서 `currentCoroutineContext().ensureActive()`를 호출한다. 정상 경로는
`complete()` 후 URI를 반환하며 `complete`/`close` 중복 호출은 upstream
idempotency에 맡긴다. 예외 또는 cancellation에서는 원래 예외를 보존하면서
`withContext(NonCancellable + Dispatchers.IO)`에서 upstream
`S3EncryptedOutputStream.close()`를 호출하고 최종 `s3Client.deleteObject`로
미완료 object를 제거한다. upstream stream 내부 discard를 직접 호출하지 않는다.
평문 임시 파일은 만들지 않는다.

- [x] **Step 4: encrypted file download의 destination safety를 위임한다**

`downloadFile`은 `transferOperations.downloadEncryptedFile(bucket, key,
destination, emptyMap())`을 호출한다. upstream이 ciphertext temporary에
다운로드하고 authentication/decrypt 성공 뒤 bounded write와 rollback을
수행하므로, service는 destination을 먼저 만들거나 별도 평문 buffer로
우회하지 않는다. 원자적 rename 보장은 문서화하지 않는다.

- [x] **Step 5: getUrl/delete와 KDoc를 정리한다**

`getUrl`은 key를 검증한 뒤 endpoint-neutral `s3://bucket/key`를 반환한다.
encrypted presigned URL은 제공하지 않는다. `delete`는 IO dispatcher의 sync
`S3Client` 호출로 멱등 처리하고 `NoSuchKeyException`을 무시한다. `StorageService`
KDoc에는 다섯 profile과 encrypted concrete file capability, ephemeral key
restart 경계를 명시한다.

---

### Task 4: AES/RSA 통합 테스트와 stream lifecycle 테스트

**Files:**

- Modify: `aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageServiceAesTest.kt`
- Modify: `aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageServiceRsaTest.kt`
- Create: `aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage/S3EncryptedOutputStreamTest.kt`

- [x] **Step 1: AES profile 성공·실패 경로를 작성한다**

`@SpringBootTest @ActiveProfiles("s3-encrypted-aes")` context에서 다음을
검증한다.

1. byte upload/download round-trip과 `s3://` URL.
2. `headObject` metadata의 `bt4k-cek-provider=aes`,
   `bt4k-cek-alg=AES/GCM/NoPadding`, `bt4k-cek-key-id=workshop-aes`,
   `bt4k-cek-key-version=v1`.
3. 8 KiB source의 `uploadFile`/`downloadFile` round-trip.
4. ciphertext byte는 유지하고 metadata의 algorithm을 변조한 뒤 기존
   destination `keep-me`가 유지되는지, `S3ClientSideEncryptionException`이
   발생하는지.
5. 다른 AES key로 만든 provider template이 복호화하지 못하는지.
6. AES provider/client/manager/template bean count와 RSA provider 부재.

metadata 변조는 새 ciphertext를 만들지 않고 existing object에 metadata만
교체한다. 모든 object와 temp path는 `finally`에서 정리한다.

- [x] **Step 2: RSA profile metadata와 provider isolation을 작성한다**

`@ActiveProfiles("s3-encrypted-rsa")`에서 byte round-trip, `provider=rsa`,
`bt4k-cek-wrap-alg=RSA/ECB/OAEPWithSHA-1AndMGF1Padding`,
`key-id=workshop-rsa`, `key-version=v1`, RSA provider count 1/AES count 0을
검증한다. key pair는 2048 bit 이상이며 context close가 template/client를
정리한다.

- [x] **Step 3: bounded, metadata, destination negative tests를 추가한다**

AES test property override `storage.s3.encrypted.max-ciphertext-bytes=64`로
64 bytes보다 큰 encrypted object를 저장하고 `download`가 실패하며 plaintext
배열을 반환하지 않는지 확인한다. 별도 fixtures에서 provider/key-version,
algorithm, truncated/invalid base64 metadata와 reserved metadata 충돌도
upstream non-retryable 오류로 관찰한다. file 경로는 destination이 새로
생성되거나 부분 plaintext를 남기지 않아야 한다.

- [x] **Step 4: `S3EncryptedOutputStream` fake lifecycle을 작성한다**

MockK `S3TransferOperations`와 threshold 64 bytes의 `S3OutputStream`을
구성하고 JDK `AES/GCM/NoPadding` cipher를 주입한다. 4 KiB plaintext 후
`complete()`를 두 번 호출하여 다음을 확인한다.

```kotlin
coVerify(exactly = 1) { operations.uploadFile(any(), any(), any(), any()) }
capturedUploadBytes.contentEquals(plaintext) shouldBeEqualTo false
capturedUploadBytes.size shouldBeGreaterThan plaintext.size
Files.list(temporaryDirectory).use { it.noneMatch(Files::isRegularFile) }.shouldBeTrue()
```

fake delegate에는 plaintext가 아닌 ciphertext path가 전달되고 completion은
한 번이어야 한다. logical EOF와 1-byte final chunk, terminal 이후 write가
실패하거나 재전송되지 않는지도 확인한다. Floci test는 큰 payload를 실제
`uploadFile`/`downloadFile`로 왕복시켜 복호화만 확인하며 AWS multipart part
count는 주장하지 않는다.

- [x] **Step 5: cancellation/write failure cleanup을 고정한다**

fake upload가 `awaitCancellation()`이면 `complete()` job을 cancel하고 원래
`CancellationException`이 재전파되며 temporary directory가 비어 있는지
확인한다. fake upload가 `IOException("upload failed")`를 던지면 같은 예외가
반환되고 `coVerify(exactly = 1)` 및 temp cleanup이 성립해야 한다. log/exception
text에 key material/plaintext/ciphertext literal이 없는지도 capture한다.

- [x] **Step 6: 기존 회귀와 신규 suite를 함께 GREEN으로 확인한다**

```bash
./gradlew :aws-storage-abstraction:test \
  --no-build-cache --no-daemon --max-workers=1 --console=plain
```

27개 baseline과 신규 AES/RSA/stream tests가 모두 통과하고 Floci resource leak,
실패 report의 secret literal이 없어야 한다.

---

### Task 5: README, coverage, CI stale guard, manifest 갱신

**Files:**

- Modify: `aws/storage-abstraction/README.md`
- Modify: `aws/storage-abstraction/README.ko.md`
- Modify: `docs/coverage-matrix.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`
- Modify: `docs/ecosystem-reuse-train.json`

- [x] **Step 1: 양국 README를 source-equivalent로 갱신한다**

기존 세 profile 설명 뒤에 양국 모두 같은 heading, command code fence, 표/목록
순서로 encrypted section을 추가한다. 포함할 사실은 다음과 같다.

- `s3-encrypted-aes`는 AES-256, `s3-encrypted-rsa`는 2048-bit RSA이고 두 profile이
  upstream `S3ClientSideEncryptionProviderTemplate`/`S3ClientSideEncryptionTransferTemplate`을 재사용한다.
- 실행 명령은 `./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3-encrypted-aes'`와 RSA 변형이다.
- byte API는 bounded read, file API는 ciphertext temporary와 인증 후 bounded destination write/rollback이며 atomic rename이 아니다.
- key는 JVM memory에만 있고 재시작 후 기존 object가 unreadable하므로 local learning/test 전용이다. KMS/HSM, rotation, encrypted presigned download은 제외한다.
- dependency 예제에는 `implementation(libs.aws2.s3.transfer.manager)`를 넣고 test 수는 실제 실행 결과로만 갱신한다.

기존 다이어그램은 baseline 범위라는 설명을 두 README에 남긴다.

- [x] **Step 2: README/link/terminology를 검증한다**

```bash
node scripts/validate-readme-parity.mjs aws/storage-abstraction
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  aws/storage-abstraction/README.md aws/storage-abstraction/README.ko.md
```

parity `failures=0`, terminology `findings=0`, 내부 링크/image link가 모두
유효해야 한다.

- [x] **Step 3: coverage matrix를 현재 coverage와 gap으로 갱신한다**

AWS S3 행의 Existing example에 `aws-s3-spring-cloud`,
`aws/storage-abstraction`을 함께 기록하고 AES/RSA byte/stream/file,
ciphertext metadata, bounded rollback을 current coverage로 적는다. Gap은
실제 AWS multipart 운영, KMS/HSM, key rotation, encrypted presigned download로
남기고 Issue 열에 `#871, #872`를 기록한다. `S3 multipart upload` summary는
transfer 경계를 보완하지만 real AWS 운영 검증은 제외한다고 명시한다.

- [x] **Step 4: workflow는 주석만 갱신한다**

기존 `aws-storage-abstraction` 주석을 `Floci-backed S3 storage abstraction
plus AES/RSA client-side encryption transfer` 의미로 바꾼다. path filter,
smoke/full task, artifact job은 중복 추가하지 않는다.

- [ ] **Step 5: stale guard를 추가한다**

`stale-check`에 다음 파일 존재와 패턴을 검사하는 블록을 추가한다.

```bash
echo "=== AWS S3 client-side encryption storage example guard ==="
cse_build="aws/storage-abstraction/build.gradle.kts"
cse_config="aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/EncryptedS3Config.kt"
cse_service="aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageService.kt"
cse_tests="aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage"
cse_readme="aws/storage-abstraction/README.md"
cse_readme_ko="aws/storage-abstraction/README.ko.md"
cse_aes="aws/storage-abstraction/src/main/resources/application-s3-encrypted-aes.yml"
cse_rsa="aws/storage-abstraction/src/main/resources/application-s3-encrypted-rsa.yml"
cse_lesson="docs/lessons/2026-09-02-issue-872-s3-cse-transfer.md"
if contains_pattern 'libs\\.aws2\\.s3\\.transfer\\.manager' "$cse_build" && \
   contains_pattern 'S3ClientSideEncryptionProviderTemplate' "$cse_config" "$cse_service" "$cse_tests" && \
   contains_pattern 'S3ClientSideEncryptionTransferTemplate' "$cse_config" "$cse_service" "$cse_tests" && \
   contains_pattern 'downloadEncryptedBytesBounded' "$cse_service" "$cse_tests" && \
   contains_pattern 's3-encrypted-aes' "$cse_aes" "$cse_readme" "$cse_readme_ko" "$cse_tests" && \
   contains_pattern 's3-encrypted-rsa' "$cse_rsa" "$cse_readme" "$cse_readme_ko" "$cse_tests" && \
   contains_pattern 'MAX_CIPHERTEXT_BYTES' "$cse_service" "$cse_tests" && \
   contains_pattern 'CancellationException' "$cse_service" "$cse_tests" && \
   [ -f "$cse_lesson" ]; then
  echo "AWS S3 client-side encryption transfer example and lesson are registered."
else
  echo "ERROR: AWS S3 client-side encryption transfer example contract is missing or stale."
  exit 1
fi
```

- [x] **Step 6: ecosystem manifest child scope를 등록한다**

`docs/ecosystem-reuse-train.json`의 child 배열에 `scope_id:
issue-872-aws-s3-cse-transfer`, `expected_head_ref:
feat/issue-872-s3-cse-transfer`, `expected_base_ref: develop`,
`issue_numbers: [872]`를 추가한다. allowed paths는 위 작업 파일과
`gradle/libs.versions.toml`, `scripts/smoke-validate.sh`, 두 spec/review/plan,
lesson, workflow만 포함하고 `review_artifact`는
`docs/review/issue-872-s3-cse-transfer-implementation-review.md`로 고정한다.

---

### Task 6: 순차 검증과 behavior 정리

**Files:** Task 1–5에서 이미 열거한 파일만 test-backed repair 대상으로 한다.

- [x] **Step 1: module test와 build를 순서대로 실행한다**

```bash
./gradlew :aws-storage-abstraction:test \
  --no-build-cache --no-daemon --max-workers=1 --console=plain
./gradlew :aws-storage-abstraction:build \
  --no-build-cache --no-daemon --max-workers=1 --console=plain
```

각 raw output에서 test failure, resource leak, warning을 읽는다. 실패 시 해당
RED behavior로 돌아가 수정하고 재시도 PASS만으로 lifecycle failure를 지우지
않는다.

- [x] **Step 2: project/metadata/parity를 검증한다**

```bash
./gradlew projects --no-daemon --max-workers=1 --console=plain
node scripts/validate-readme-parity.mjs aws/storage-abstraction
node -e "JSON.parse(require('fs').readFileSync('docs/ecosystem-reuse-train.json','utf8')); console.log('manifest JSON valid')"
git diff --check
```

`aws-storage-abstraction` 등록, README parity `failures=0`, JSON parse 성공,
diff check 통과가 필요하다.

- [ ] **Step 3: AWS smoke와 stale guard를 순차 실행한다**

```bash
bash scripts/smoke-validate.sh aws
bash scripts/smoke-validate.sh stale-check
```

기존 AWS group과 새 CSE guard가 성공하고 Floci/Testcontainers Gradle lane은
동시에 다른 container-backed module을 시작하지 않아야 한다.

- [x] **Step 4: workflow/static/language 검증을 실행한다**

```bash
actionlint .github/workflows/Examples.yml
./gradlew detekt --no-daemon --max-workers=1 --console=plain
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/plans/2026-09-02-issue-872-s3-cse-transfer-plan.md \
  docs/superpowers/specs/2026-09-02-issue-872-s3-cse-transfer-design.md \
  docs/review/issue-872-plan-review.md
```

---

### Task 7: lesson과 구현/PR 전 review 증거 기록

**Files:**

- Create: `docs/lessons/2026-09-02-issue-872-s3-cse-transfer.md`
- Create: `docs/review/issue-872-s3-cse-transfer-implementation-review.md`
- Create: `docs/review/issue-872-s3-cse-transfer-pre-pr-review.md`

- [ ] **Step 1: lesson을 실제 결과로 작성한다**

lesson은 한국어 `배경`, `결정`, `검증`, `다음 guard` section을 갖고, 실제 commit
SHA, test/build, smoke/stale, parity, detekt, actionlint, terminology 결과를
기록한다. 숫자와 SHA는 검증 전 추정하지 않는다. 작성 뒤 Task 5 stale guard를
다시 실행한다.

- [ ] **Step 2: Step 6-R 구현 review를 수행한다**

`bluetape-full-feature`의 performance, stability, security, operator/Ops,
developer/API, user/caller 여섯 관점과 `step-3r-plan-review`의 해당 conditional
checks를 fresh diff/test evidence에 적용한다. implementation review에 P0/P1/P2/P3
표, acceptance evidence, SPW-01~05를 기록하고 P0=0/P1=0일 때만 PR 준비로
진행한다. P2/P3는 수정하거나 후속 issue/rationale를 기록한다.

- [ ] **Step 3: PR 직전 review와 DoD body를 준비한다**

pre-PR review에 exact head SHA, clean diff, fresh CI/local validation,
unresolved thread count, README/manifest/stale evidence를 기록한다. PR body의
`## DoD Status`에는 같은 증거와 unchecked gate를 적고, base/head 권한을 live
`gh`로 재확인한다.

---

## 실행 순서와 승인/커밋 경계

1. 이 plan의 사용자 승인이 있기 전에는 Task 1–7의 구현/문서 mutation을 시작하지 않는다.
2. 승인 후 Task 1 dependency/profile → Task 2 Step 1 wiring RED test → Task 2 Step 2 config → Task 3 service → Task 4 remaining tests 순서로 TDD RED/GREEN을 진행한다.
3. Task 4가 GREEN인 뒤 Task 5 문서/CI/manifest를 수정하고 Task 6을 순차 실행한다.
4. Step 6-R과 Step 7 review가 통합 PASS인 뒤 lesson/review를 포함해 feature branch에 Lore commit을 만든다. PR은 명시된 repository/base/head 권한이 확인된 뒤 생성한다.
5. merge는 exact live head, CI, review thread를 다시 읽고 그 head에 대한 새 사용자 승인을 받은 뒤에만 실행한다. merge 후 root `develop`/`origin/develop` sync와 해당 feature worktree/branch cleanup을 증명한다.

## 계획 자체 self-review

| 검사 | 결과 |
| --- | --- |
| spec coverage | PASS — profile, bounded read, stream/file, cleanup, docs/CI/lesson, verification이 추적성 표와 Task 1–7에 연결됨 |
| unfinished-token scan | PASS — 확정된 파일·명령·기대 결과만 사용하고 미완성 토큰을 남기지 않음 |
| type consistency | PASS — upstream public class/interface와 호출 인자, `MAX_CIPHERTEXT_BYTES`를 일관되게 사용함 |
| ordering | PASS — dependency/profile → wiring RED test → config → service → remaining tests → docs/CI → verification/review 순서이며 후속 산출물 선행 의존 없음 |
| SPW-01 | PASS — 승인된 spec, Issue/upstream/local 근거와 대상 독자를 상단에 고정 |
| SPW-02 | PASS — file map, ordered tasks, code/test commands, rollback/cleanup 경계를 포함 |
| SPW-03 | PASS — 한국어 technical register와 source-equivalent README 계약을 명시 |
| SPW-04 | PASS — acceptance-to-task-to-command traceability를 포함 |
| SPW-05 | PASS — 저장 후 미완성 토큰/heading/code fence/read-back와 terminology audit을 실행해 결과를 갱신함 |

Plan is complete and saved to `docs/superpowers/plans/2026-09-02-issue-872-s3-cse-transfer-plan.md`. Implementation starts only after this plan receives explicit user approval.
