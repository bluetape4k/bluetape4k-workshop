# Issue #872 S3 client-side encryption transfer 소비자 예제 교훈

## 배경

기존 `aws/storage-abstraction`은 `local`, `s3`, `s3-presigned` profile과
27개 회귀 테스트만 제공했다. Issue #872의 목표는
`bluetape4k 2.0.0-SNAPSHOT`에서 추가된 AES/RSA client-side encryption과
AWS SDK v2 TransferManager 경계를 기존 storage consumer가 실제로 사용하는
예제로 고정하는 것이었다.

기준 구현 commit은
[`fdcab6ad`](https://github.com/bluetape4k/bluetape4k-workshop/commit/fdcab6ad492d3169d459f830df25eb32c8a674cc)이다.

## 결정

- `s3-encrypted-aes`와 `s3-encrypted-rsa` profile을 별도 bean graph로 두고,
  `S3Config.floci`의 emulator endpoint만 재사용했다. 각 context에서 AES-256
  key 또는 2048-bit RSA key pair를 JVM memory에 생성한다.
- 암호화 primitive, envelope metadata, stream terminal state, ciphertext
  temporary download과 destination rollback은 upstream
  `S3ClientSideEncryptionProviderTemplate`/`S3ClientSideEncryptionTransferTemplate`
  에 위임했다. consumer service는 기존 byte 계약과 `uploadFile`/
  `downloadFile` concrete capability만 조립한다.
- byte download에는 `downloadEncryptedBytesBounded`와
  `MAX_CIPHERTEXT_BYTES`를 사용한다. file download는 upstream transfer
  template의 authoritative HEAD/ETag와 전역 ciphertext 상한에 위임한다.
  consumer가 별도 HEAD preflight를 추가하면 두 HEAD 사이 교체로 설정 상한이
  우회될 수 있으므로 per-call file 상한은 upstream API 확장 후로 남겼다. file upload는 고유 staging key에 먼저 쓴 뒤
  성공한 경우만 canonical key로 server-side copy하며, cancellation/실패에서는
  staging key만 `NonCancellable + Dispatchers.IO`로 cleanup한다. 따라서 기존
  canonical object를 삭제하지 않고 원래 예외와 cleanup 실패 suppression을
  보존한다. canonical 승격 뒤 staging 삭제는 best-effort로 오류 유형만 기록하며
  bounded reaper가 고아 object를 최종 정리해야 한다. plaintext temporary는 만들지 않는다.
- `s3-transfer-manager`는 versionless catalog alias로 소비하고, upstream
  reactive response adapter가 요구하는 `kotlinx-coroutines-reactive`만
  명시적 runtime dependency로 추가했다. KMS/HSM, key rotation, 실제 AWS
  multipart 운영, encrypted presigned download은 범위 밖이다.

## 검증

| 검증 | 결과 |
| --- | --- |
| 신규 AES/RSA/stream/cleanup targeted tests | 23개 PASS: byte/file 왕복, provider metadata/isolation, bounded read, algorithm/key/version/truncated·invalid-base64 metadata mismatch, 신규·기존 destination rollback, threshold ciphertext, stream/service completion·cancellation/failure cleanup 및 cleanup 실패 suppression |
| `./gradlew :aws-storage-abstraction:test --no-build-cache --no-daemon --max-workers=1 --console=plain` | 총 50개 PASS: 기존 27개 회귀 + 신규 23개 |
| `./gradlew :aws-storage-abstraction:build --no-build-cache --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL` |
| `./gradlew detekt --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL` |
| `MAX_WORKERS=1 bash scripts/smoke-validate.sh aws` | AWS group `BUILD SUCCESSFUL`; S3 storage 43개, S3 resource 5개, Kinesis 53개, SQS/SNS 11개, S3 Vectors 9개, settings 25개 등 통과 |
| `./gradlew :aws-storage-abstraction:dependencies --configuration testRuntimeClasspath ...` | `s3-transfer-manager:2.46.17`, `kotlinx-coroutines-reactive:1.11.0` 선택 확인 |
| `./gradlew projects --no-daemon --max-workers=1 --console=plain` | `:aws-storage-abstraction` 등록과 전체 project graph 확인, `BUILD SUCCESSFUL` |
| `node scripts/validate-readme-parity.mjs aws/storage-abstraction` | `failures: 0` |
| `audit-korean-terms.mjs` (두 README) | `findings=0` |
| `actionlint .github/workflows/Examples.yml` | PASS |
| `node -e "JSON.parse(...)"` 및 `git diff --check` | manifest JSON valid, diff check PASS |
| `bash scripts/smoke-validate.sh stale-check` | CSE config/service/test, 두 profile README/YAML, bounded limit, cancellation cleanup, lesson 및 manifest guard PASS |

## 다음 guard

- `scripts/smoke-validate.sh stale-check`가 CSE config/service/test, 두 profile
  README/YAML, `MAX_CIPHERTEXT_BYTES`, cancellation cleanup, lesson 파일을
  계속 요구하며 현재 guard는 PASS 상태다.
- JVM memory key는 재시작 뒤 기존 object를 복호화할 수 없으므로 이 예제를
  production key management로 승격하지 않는다. 운영 AWS multipart, KMS/HSM,
  rotation, encrypted presigned GET이 필요하면 별도 issue와 managed key
  lifecycle을 설계한다.
- Floci가 성공해도 실제 AWS IAM/signing, network retry, multipart 운영을
  보증하지 않는다. PR/merge 전에는 exact head CI와 review thread를 다시
  확인한다.
