# Issue #872 S3 AES/RSA client-side encryption transfer 소비자 예제 설계

- Issue: [#872](https://github.com/bluetape4k/bluetape4k-workshop/issues/872)
- Branch: `feat/issue-872-s3-cse-transfer`
- 대상 모듈: `aws/storage-abstraction`
- 대상 독자: Spring Boot와 coroutine 기반 S3 저장소를 운영하는 개발자
- 문서 언어: 한국어 (코드·API 이름·명령·URL은 원문 유지)

## 목표와 현재 경계

현재 `aws/storage-abstraction`은 `StorageService` 뒤에 local, sync S3,
presigned GET 구현만 제공한다. S3 객체를 대용량으로 전송할 때 사용할
`aws2-s3-transfer-manager` catalog alias는 이 모듈에서 아직 소비하지 않으며,
client-side encryption을 선택할 수 있는 profile도 없다.

Issue #872는 기존 unencrypted 계약을 깨지 않고 AES·RSA provider 기반 envelope
암호화와 TransferManager streaming/file 경계를 학습할 수 있는 opt-in 소비자
예제를 요구한다. 구현은 upstream `bluetape4k-aws`의 provider/transfer template을
호출하고, workshop에서 암호화 primitive나 envelope parser를 복제하지 않는다.

성공 조건은 다음과 같다.

- `local`, `s3`, `s3-presigned` profile의 upload/download/getUrl/delete 동작과
  기존 27개 baseline test가 변하지 않는다.
- `s3-encrypted-aes`와 `s3-encrypted-rsa` profile에서 동일한 저장 경계로
  byte round-trip과 file/stream transfer를 실행할 수 있다.
- ciphertext metadata와 provider/key identity를 검증하고, 인증·algorithm·key
  불일치가 발생하면 plaintext destination에 쓰지 않는다.
- cancellation, output stream 실패, 고유 staging 승격/정리, 임시 ciphertext 파일 정리와 no-key/plaintext
  log 경계를 Floci 및 local fake 테스트로 관찰한다.
- versionless module alias, 양국 README, coverage/CI/stale 등록과 lesson을
  같은 변경으로 갱신한다.

## 근거 ledger

| 근거 | 확인한 계약 | 설계 결정 |
| --- | --- | --- |
| [Issue #872](https://github.com/bluetape4k/bluetape4k-workshop/issues/872) | opt-in encrypted profile/service, AES·RSA round-trip, threshold transfer, metadata mismatch, cancellation/cleanup, multipart와 plaintext-safety 검증 | 기존 모듈 안에서 profile별 consumer service와 Floci 통합 테스트를 추가 |
| [bluetape4k-aws Issue #475](https://github.com/bluetape4k/bluetape4k-aws/issues/475) | AES secret key/RSA key pair provider, envelope metadata 검증, streaming/transfer 조합, 실패 시 plaintext 금지 | provider 선택과 key lifecycle을 upstream API에 위임하고 workshop은 호출 계약을 문서화 |
| [bluetape4k-aws PR #585](https://github.com/bluetape4k/bluetape4k-aws/pull/585) | `S3ClientSideEncryptionProviderTemplate`, `S3ClientSideEncryptionTransferTemplate`, authenticated AES-GCM payload, ciphertext-only temporary download, bounded destination commit | public template과 `S3EncryptedOutputStream`/`downloadEncryptedFile`을 그대로 소비 |
| 현재 Gradle resolution | `bluetape4k-dependencies:2.0.0-SNAPSHOT`이 관리되지만 AWS artifact는 현재 `1.0.0-SNAPSHOT`으로 해석되고, resolved jar에 CSE/transfer class가 존재 | upstream 구현이 실제 consumer classpath에 있는지 compileClasspath와 jar로 확인하고 새 Bluetape 버전 pin은 추가하지 않음 |
| 현재 `S3Config`, `StorageService`, 세 profile 테스트 | sync `S3Client`와 Floci singleton을 custom config가 소유하며 unencrypted service는 profile로 격리됨 | encrypted config를 별도 profile에 두고 기존 bean/exclusion 동작을 수정하지 않음 |
| `gradle/libs.versions.toml`, `Examples.yml`, `smoke-validate.sh` | transfer manager alias는 catalog에 이미 있고 storage module은 AWS smoke/full/stale guard에 등록됨 | module dependency만 versionless로 추가하고 기존 job/artifact에 encrypted test를 포함 |

upstream jar와 `develop` 소스에서 다음 public consumer API를 재확인했다.
`S3ClientSideEncryptionProviderTemplate`은 `S3AsyncClient`와 `S3Properties`,
선택적 `S3AesProvider`/`S3RsaProvider`를 받아 `uploadEncrypted`와
`downloadEncryptedBytes`를 제공한다. `S3ClientSideEncryptionTransferTemplate`은
`S3TransferOperations`와 `S3OutputStreamProvider`를 받아
`encryptedOutputStream`과 `downloadEncryptedFile`을 제공한다.
`S3TransferTemplate`의 `outputStream`은 `S3Properties.transfer`의 threshold/part
size를 소비하므로 workshop은 transfer manager 내부 구현을 복제하지 않는다.

## 선택지와 권고

### A — 별도 encrypted profile + upstream template 재사용 (권고)

`EncryptedS3Config`와 `EncryptedS3StorageService`를 `s3-encrypted-aes` 및
`s3-encrypted-rsa` profile로 격리한다. config는 Floci endpoint의 sync/async
client, `S3TransferManager`, `S3TransferTemplate`, provider key bean과
`S3ClientSideEncryptionProviderTemplate`/`S3ClientSideEncryptionTransferTemplate`을
조립한다. 서비스는 기존 `StorageService` byte API를 유지하고 concrete
`uploadFile`/`downloadFile`만 확장한다.

이 방식은 기존 custom S3 bean과 auto-configuration exclusion을 건드리지 않고,
provider 선택을 profile과 `bluetape4k.aws.s3.client-side-encryption.provider`
설정으로 명시한다. download file은 upstream의 ciphertext-only temporary path와
인증 후 bounded destination commit을 그대로 사용한다.

### B — 전역 `S3AutoConfiguration`/`S3TransferAutoConfiguration` 재활성화

기존 `application.yml`의 exclusion을 제거하고 upstream 자동 구성에 모든 client와
template을 맡긴다. 코드 양은 줄지만 기존 `S3Config`가 소유한 `S3Client`/presigner와
bean 조건이 profile별로 달라지며, unencrypted 테스트가 의도하지 않은 transfer
manager와 async client lifecycle을 함께 띄울 위험이 있다. 기존 profile의
backward compatibility를 보장하기 어려워 선택하지 않는다.

### C — workshop에서 AES/RSA envelope와 transfer를 직접 구현

JDK `Cipher`, metadata parser, multipart stream을 이 모듈에 복사하면 테스트를
세밀하게 제어할 수 있지만 upstream 보안 수정과 envelope format이 분기된다.
암호화 primitive를 재구현하지 않는 Issue #872 범위와 충돌하므로 선택하지 않는다.

## 구조와 데이터 흐름

```text
Spring profile
  ├─ local / s3 / s3-presigned
  │    └─ 기존 StorageService 구현 (변경 없음)
  └─ s3-encrypted-aes / s3-encrypted-rsa
       ├─ Floci S3Client + S3AsyncClient
       ├─ S3TransferManager → S3TransferTemplate
       ├─ AES SecretKey 또는 RSA KeyPair provider
       ├─ S3ClientSideEncryptionProviderTemplate
       ├─ S3ClientSideEncryptionTransferTemplate
       └─ EncryptedS3StorageService : StorageService
            ├─ upload/download bytes → provider template
            ├─ uploadFile → staging encryptedOutputStream → server-side copy → canonical key
            ├─ downloadFile → upstream authoritative HEAD/ETag/global bound
            │              → ciphertext temporary → authenticate/decrypt → destination commit
            ├─ getUrl → endpoint-neutral s3:// URI
            └─ delete → S3Client
```

### Profile 및 key 계약

- `application-s3-encrypted-aes.yml`은 provider `AES`, `key-id`
  `workshop-aes`, `key-version` `v1`, deterministic encryption context와
  낮은 test threshold를 지정한다.
- `application-s3-encrypted-rsa.yml`은 provider `RSA`, `key-id`
  `workshop-rsa`, `key-version` `v1`을 지정한다. RSA key pair는 최소 2048 bit
  로 생성한다.
- key material은 Floci 예제에서만 JVM memory에 생성한다. 실제 key storage,
  KMS/HSM, rotation service는 이 예제의 책임이 아니다.
- profile bean은 선택된 provider만 생성하고 template은 `@Bean(destroyMethod =
  "close")`로 닫아 upstream이 보관한 key material 복사본을 zeroize한다.
- key material은 프로세스 메모리에만 있으므로 애플리케이션을 재시작하면 같은
  `key-id`/`key-version`이어도 기존 객체를 복호화할 수 없다. 이 동작은 교육용
  예제의 경계이며 production key 관리 계약으로 해석하지 않는다.
- dedicated config가 만든 `S3Client`, `S3AsyncClient`, `S3TransferManager`는
  모두 `close` destroy method를 선언하고, provider template은 그보다 먼저
  닫히도록 bean 의존성을 명시한다. 암호화 profile에는 presigner를 만들지 않는다.
- 사용자 metadata가 reserved `bt4k-cek-*` metadata와 충돌하거나 duplicate key를
  포함하면 upstream merge guard의 예외를 그대로 반환한다.

Spring Boot relaxed binding으로 다음 속성을 profile YAML에 명시한다.
`bluetape4k.aws.s3.enabled`, `bluetape4k.aws.s3.transfer.enabled`,
`bluetape4k.aws.s3.transfer.output-stream-threshold-bytes`,
`bluetape4k.aws.s3.transfer.output-stream-part-size-bytes`,
`bluetape4k.aws.s3.client-side-encryption.enabled`, `provider`, `key-id`,
`key-version`, `encryption-context`를 사용하며, 암호화 profile에서는 transfer와
client-side encryption을 모두 `true`로 고정한다.

### 서비스 계약

`EncryptedS3StorageService`는 key validation과 endpoint-neutral URI 규칙을
기존 `StorageService`와 동일하게 적용한다. file upload는 staging key에 암호화한
뒤 canonical key로 server-side copy하고 staging만 정리한다. file download의
authoritative HEAD/ETag와 전역 ciphertext 상한은 upstream transfer template이
소유하며 consumer는 별도 preflight를 추가하지 않는다.

```kotlin
interface StorageService {
    suspend fun upload(key: String, content: ByteArray, contentType: String): String
    suspend fun download(key: String): ByteArray
    suspend fun getUrl(key: String): String
    suspend fun delete(key: String)
}

class EncryptedS3StorageService : StorageService {
    suspend fun uploadFile(key: String, source: Path, contentType: String? = null): String
    suspend fun downloadFile(key: String, destination: Path)
}
```

byte API는 `S3ClientSideEncryptionProviderTemplate`의
`downloadEncryptedBytesBounded`를 사용하고, file API는
`S3ClientSideEncryptionTransferOperations`를 사용한다. 서비스 생성자는
`maxCiphertextBytes`를 받아 `1..S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES`
범위로 검증하며 기본값은 upstream 상한이다. `uploadFile`은 평문
source를 chunk 단위로 encrypted output stream에 전달하며 평문 임시 파일을
만들지 않는다. file upload는 고유 staging key에 기록한 뒤 성공 시
server-side copy로 canonical key에 승격하고 실패/취소 cleanup은 staging key만
삭제한다. canonical 승격 뒤 staging 삭제는 best-effort로 오류 유형만 기록하고
bounded reaper가 최종 정리한다. `downloadFile`은 upstream public API의
authoritative HEAD/ETag와 전역 ciphertext bound에 위임하며 ciphertext 임시 파일을 먼저 받고,
authentication/decrypt가 성공한 뒤에만 destination을 변경한다. 기존 destination이
있으면 upstream bounded write + rollback 계약을 보존한다(원자적 rename을
제공한다는 뜻은 아니다).

`getUrl`은 encrypted object를 자동 복호화하는 presigned URL을 만들지 않고
`s3://bucket/key`를 반환한다. Issue에서 presigned encrypted download 자동화는
제외되었다.

## 실패 모드와 대응

| 실패 모드 | 관찰 가능한 계약 | 대응 |
| --- | --- | --- |
| provider 또는 key identity 불일치 | metadata provider/key id/version 검증이 조기에 실패하고 plaintext가 반환되지 않음 | AES/RSA profile 교차-read와 key-version mismatch를 assert |
| algorithm/encoding 또는 base64 metadata 손상 | authenticated decrypt 전에 bounded metadata 오류가 발생 | Floci object metadata를 변조한 fixture로 algorithm mismatch와 truncated metadata 검증 |
| GCM authentication 실패 | `S3ClientSideEncryptionException` 계열 오류가 발생하고 destination은 기존 상태 유지 | ciphertext byte 변조와 existing destination rollback을 assert |
| ciphertext 크기 초과 | byte bounded reader는 configured max+1 probe에서 실패하고, file bound는 upstream의 단일 global contract로 검사됨 | `MAX_CIPHERTEXT_BYTES + 1` 응답 fixture로 byte read를 검증하고 file 경로는 upstream authoritative HEAD/ETag·global bound 위임을 검토 |
| threshold 초과 stream | delegate에는 ciphertext만 전달되고 multipart completion은 한 번만 수행 | 낮은 threshold와 큰 fixture로 output stream/ETag/round-trip 검증 |
| upload cancellation 또는 write failure | encrypted stream delegate가 discard되고 고유 staging object만 정리되며 기존 canonical object는 보존됨 | cancelled coroutine, throwing delegate fake, staging 승격/삭제와 `complete`/`close` 중복 호출 테스트 |
| download cancellation/transfer failure | ciphertext temporary path가 `NonCancellable` cleanup되고 destination에 평문 partial write가 없음 | transfer fake와 Floci cancellation 후 파일 존재/내용 assert |
| metadata/log에 secret 노출 | key material·plaintext·ciphertext가 log와 예외 메시지에 포함되지 않음 | captured log/exception text에 payload와 key literal이 없는지 검사 |
| 기존 profile 회귀 | local/S3/presigned 27 baseline test와 URL 계약이 동일 | encrypted profile을 별도 context로 실행하고 기존 suite를 먼저/후에 반복 |

## 테스트 설계

TDD 순서로 consumer behavior를 먼저 실패시키고 최소 config/service를 추가한다.
Testcontainers/Floci 명령은 다른 container-backed module과 병렬 실행하지 않는다.

1. **Profile wiring**: AES/RSA context에 `S3Client`, `S3AsyncClient`,
   `S3TransferOperations`, provider template, transfer operations가 정확히 한
   개씩 생성되고 기본 profile에는 encrypted bean이 없는지 확인한다.
2. **AES/RSA byte round-trip**: 각 profile에서 upload/download bytes가 동일하고
   `getUrl`이 `s3://` URI를 반환하며 `headObject` metadata에 provider,
   algorithm, key identity/version이 존재하는지 확인한다. bounded max를 넘는
   응답은 plaintext를 반환하지 않고 실패해야 한다.
3. **Metadata and key boundaries**: reserved metadata 충돌, duplicate metadata,
   algorithm mismatch, provider/key-version mismatch, truncated/invalid base64
   metadata를 검증한다. 잘못된 metadata는 client payload를 plaintext로
   반환하지 않아야 한다.
4. **Streaming upload**: configured threshold보다 큰 plaintext를 encrypted
   output stream에 chunk 단위로 쓰고 `complete()`를 호출한다. fake delegate에서
   ciphertext-only file/multipart 경로와 completion 1회를 관찰하고, Floci에서
   ciphertext object와 download byte round-trip을 확인하며 close와 complete
   중복 호출이 안전한지 검증한다.
5. **File download safety**: `downloadFile`이 ciphertext temporary file을 사용하고
   인증 성공 후 destination을 commit하는지 확인한다. ciphertext 변조 또는
   wrong-key 상황에서 새 destination은 생성되지 않고 기존 destination은
   보존되는지 확인한다.
6. **Cancellation/cleanup fake**: local fake output/delegate와 coroutine cancellation
   시나리오에서 delegate discard, temporary deletion, original
   `CancellationException` 재전파를 확인한다. log capture에는 key/plaintext가
   없어야 한다.
7. **Existing regression**: 기존 `LocalStorageServiceTest`, `S3StorageServiceTest`,
   `S3PresignedStorageServiceTest`를 유지하고 전체 module test에서 profile
   context cache와 Floci singleton이 반복 실행 가능한지 확인한다.

## 범위 및 호환성

### 포함

- `aws/storage-abstraction`에 `aws2-s3-transfer-manager` versionless dependency,
  async/transfer/CSE config와 encrypted consumer service 추가
- AES 및 RSA profile YAML, byte/stream/file 테스트와 보안·cleanup assertions
- 기존 `StorageService` behavior/KDoc 및 양국 README의 encrypted usage와
  unsupported boundary 문서화
- `docs/coverage-matrix.md`, `scripts/smoke-validate.sh`, 필요한
  `.github/workflows/Examples.yml` 주석/검증 경계, `docs/ecosystem-reuse-train.json`,
  lesson과 final review artifact 갱신

### 제외

- KMS/HSM 또는 실제 key storage/rotation 정책
- presigned encrypted download 자동화
- 실제 AWS credential/cost/multipart service 검증
- upstream CSE provider, envelope format, TransferManager 구현 복제
- 기존 unencrypted profile의 auto-configuration 또는 `StorageService` method 변경

## 수용 기준과 DoD

- [ ] `bluetape4k-dependencies` BOM을 유일한 Bluetape 버전 원본으로 유지하고
  `aws2-s3-transfer-manager` alias를 versionless로 소비한다.
- [ ] 기존 세 profile의 동작과 27개 baseline test가 회귀하지 않는다.
- [ ] AES/RSA profile에서 byte round-trip, metadata/key/algorithm mismatch,
  truncated metadata, bounded ciphertext 초과와 authenticated decrypt 실패를
  검증한다.
- [ ] threshold 초과 encrypted streaming upload가 ciphertext-only delegate와
  transfer/multipart completion 1회를 관찰하고, file download가 인증 성공 뒤에만
  destination을 변경한다.
- [ ] cancellation/write failure/cleanup 및 no-key/plaintext log 경계를 테스트한다.
- [ ] KDoc, 양국 README, profile 실행 명령과 제외 범위가 source와 일치한다.
- [ ] coverage matrix, AWS smoke/full workflow, stale guard, ecosystem manifest,
  lesson/review artifact가 Issue #872 범위를 등록한다.
- [ ] module targeted test/build, detekt, `scripts/smoke-validate.sh aws`,
  `scripts/smoke-validate.sh stale-check`, `./gradlew projects`, README parity/
  language, terminology audit와 `git diff --check`가 통과한다.
- [ ] PR body의 `## DoD Status`가 exact head와 fresh CI/review evidence를 포함하고,
  merge는 이후 fresh user approval 뒤에만 수행한다. merge 후 root `develop`과
  `origin/develop`을 동기화하고 해당 feature worktree/branch만 제거한다.

## SPW·한국어 품질 게이트

| 게이트 | 결과 |
| --- | --- |
| SPW-01 목적·독자·근거 | 상단 metadata와 근거 ledger에 Issue/upstream/local source를 고정 |
| SPW-02 artifact contract | 목표, 선택지, 구조, 실패 모드, 테스트, 범위, 호환성, DoD를 포함 |
| SPW-03 Korean technical register | `bluetape-writer` Korean naturalness checklist KO-01~KO-07 적용 |
| SPW-04 traceability | 각 Issue acceptance를 구조·테스트·검증 명령에 연결 |
| SPW-05 read-back | placeholder/모순/범위 drift를 재검토하고 rendered Markdown을 재독 |

현재 문서에는 미완성 항목이나 확정되지 않은 profile 이름을 남기지 않는다. 구현 중 upstream
API 또는 Floci capability가 설계와 다르면 spec을 먼저 수정하고 해당 구간을
재승인한다.
