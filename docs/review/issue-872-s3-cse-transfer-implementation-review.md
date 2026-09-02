# Issue #872 S3 AES/RSA client-side encryption transfer 구현 검토

- 검토일: 2026-09-02
- 저장소: `bluetape4k-workshop`
- 이슈: [#872](https://github.com/bluetape4k/bluetape4k-workshop/issues/872)
- 대상 브랜치: `feat/issue-872-s3-cse-transfer`
- 기준 구현: `3a1537b4` (현재 브랜치 HEAD)
- 검토 기준: upstream `S3ClientSideEncryptionProviderTemplate`/
  `S3ClientSideEncryptionTransferTemplate` public API와 consumer diff

## 검토 범위

`aws/storage-abstraction`이 기존 `local`, `s3`, `s3-presigned` 계약을 유지하면서
AES/RSA client-side encryption과 AWS SDK v2 TransferManager stream/file API를
소비자 예제로 조립했는지 확인했다. 암호화 primitive, envelope parser,
ciphertext temporary download, authenticated destination rollback은 upstream의
소유 범위로 두고, 이 저장소는 profile bean graph와 `StorageService` 경계를
검증한다.

## 6-lens 결과

| 관점 | 판정 | 근거 |
| --- | --- | --- |
| 코드/API 경계 | PASS | `EncryptedS3StorageService`는 기존 byte API를 유지하고 `uploadFile`/`downloadFile` concrete capability만 추가한다. byte read는 bounded upstream API로, file transfer는 upstream transfer template으로 위임한다. |
| 테스트/회귀 | PASS | AES 12개, RSA 4개, stream 3개, service cleanup 4개 신규 테스트와 기존 27개를 합쳐 50개를 실행한다. metadata 손상, key/version mismatch, destination rollback, threshold, cancellation/failure cleanup과 staging promotion을 고정한다. |
| 보안/데이터 경계 | PASS | AES-GCM/RSA-OAEP envelope을 재구현하지 않는다. upload는 staging ciphertext만 canonical로 승격하고 실패/취소 때 canonical을 삭제하지 않는다. download는 upstream의 인증 후 destination commit/rollback을 사용한다. 테스트와 로그에 key material/plaintext를 넣지 않는다. |
| 성능/안정성 | PASS | source copy는 8 KiB chunk와 `Dispatchers.IO`를 사용한다. byte API는 configured bound, file API는 upstream의 단일 authoritative HEAD/ETag와 전역 ciphertext bound를 사용한다. consumer의 별도 HEAD preflight로 인한 TOCTOU를 만들지 않는다. |
| 운영/lifecycle | WATCH | encrypted profile이 client/manager/template close lifecycle을 소유한다. 다만 staging 삭제 실패 시 bounded reaper/S3 lifecycle은 별도 운영 범위이며, JVM-memory key는 managed zeroization/key rotation 계약이 아니다. |
| 문서/사용성·의존성 | PASS | 양국 README, KDoc, coverage matrix, lesson, stale guard가 다섯 profile, staging semantics, byte/file bound ownership, JVM 재시작 경계와 제외 범위를 설명한다. transfer alias는 versionless이며 reactive runtime adapter만 명시적으로 추가한다. |

## 수용 기준 추적

| 기준 | 증거 |
| --- | --- |
| BOM 단일 원본/versionless transfer alias | `build.gradle.kts`는 versionless `libs.aws2.s3.transfer.manager`와 `libs.kotlinx.coroutines.reactive`만 소비하고 Bluetape 개별 BOM/version pin을 추가하지 않는다. |
| 기존 profile 회귀 없음 | `LocalStorageServiceTest` 8개, `S3StorageServiceTest` 9개, `S3PresignedStorageServiceTest` 10개와 encrypted 신규 suite를 같은 모듈 test task에서 실행한다. |
| AES/RSA provider 및 metadata | 각 profile context가 선택 provider만 생성하고 AES-256/RSA-2048 key와 provider/algorithm/key-id/key-version metadata, wrong-key mismatch를 검증한다. |
| bounded read와 metadata 손상 | byte download는 configured `max-ciphertext-bytes`를 사용하고, oversized ciphertext·reserved algorithm·invalid/truncated base64 metadata를 plaintext 반환 전에 거부한다. file bound는 upstream global contract로 명시한다. |
| threshold stream과 upload safety | encrypted stream completion 1회와 ciphertext-only delegate를 검증한다. file upload는 UUID staging key에 먼저 기록하고 성공 시 S3 server-side copy로 canonical key에 승격한 뒤 staging만 삭제한다. |
| 실패/취소 cleanup | cancellation/write failure에서 `close()`와 staging `deleteObject`를 한 번 수행하고 원래 오류를 재전파한다. cleanup 오류는 primary error의 suppressed로 보존한다. promotion 이후 staging 삭제 실패는 best-effort로 관측하고 canonical URI를 반환한다. |
| file destination safety | tampered ciphertext가 `S3ClientSideEncryptionException`을 발생시키며 새 destination을 만들지 않고 기존 destination을 보존한다. authoritative HEAD/ETag와 ciphertext temporary/rollback은 upstream이 소유한다. |
| 문서·운영 guard | 양국 README parity·용어 audit, coverage matrix, workflow comment, stale-check, manifest, lesson과 review artifact를 현재 구현 경계로 갱신한다. |

## 독립 review findings

| severity | 상태 | 처분 |
| --- | --- | --- |
| P0 | 0 | 없음 |
| P1 | 0 | architect가 지적한 consumer HEAD TOCTOU와 promotion 후 cleanup 예외 전파를 각각 upstream 단일 HEAD/ETag 위임과 best-effort staging cleanup으로 해소했다. |
| P2 | 3 | orphan staging bounded reaper/lifecycle, JVM-memory key의 managed zeroization/rotation, 운영 key lifecycle은 이 local-learning 예제의 명시적 후속 범위다. README와 coverage matrix에 기록했다. |
| P3/N/A | 기록 | 실제 AWS IAM/cost/network retry/multipart 운영, KMS/HSM, encrypted presigned GET은 검증 대상이 아니다. |

Architect 독립 lane은 `WATCH`(P0=0, P1=0, P2=3), code-reviewer lane은 구현·문서
기준 `CLEAR`(P0=0, P1=0, P2=3)로 판정했다. 두 lane 모두 merge 차단 finding은 없다.

## 검증 증거

| 명령 | 결과 |
| --- | --- |
| `./gradlew :aws-storage-abstraction:test --no-build-cache --no-daemon --max-workers=1 --console=plain` | `SUCCESS: Executed 50 tests`, `BUILD SUCCESSFUL` |
| `./gradlew :aws-storage-abstraction:build --no-build-cache --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL` |
| `./gradlew detekt --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL` |
| `MAX_WORKERS=1 bash scripts/smoke-validate.sh aws` | AWS group `BUILD SUCCESSFUL` |
| `bash scripts/smoke-validate.sh stale-check` | CSE contract/lesson and broken-image guards PASS |
| `node scripts/validate-readme-parity.mjs aws/storage-abstraction` | `failures: 0` |
| Korean terminology audit (두 README) | `findings=0` |
| `actionlint .github/workflows/Examples.yml` | exit 0 |
| manifest JSON parse 및 `git diff --check` | manifest valid, diff check PASS |
| `./gradlew projects --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, `:aws-storage-abstraction` registered |
| `./gradlew :aws-storage-abstraction:dependencies --configuration testRuntimeClasspath ...` | `s3-transfer-manager:2.46.17`, `kotlinx-coroutines-reactive:1.11.0` resolved by constraints |

## SPW/Kotlin DoD

- [x] `SPW-01`: Issue 목표, upstream 소유 경계, consumer 파일/테스트/문서 범위를 고정했다.
- [x] `SPW-02`: six-lens 결과, severity, 수용 기준, N/A와 검증 명령을 포함했다.
- [x] `SPW-03`: 한국어 technical register와 code/API token 보존을 적용하고 양국 README 계약을 유지했다.
- [x] `SPW-04`: resolved upstream API, source, 테스트 결과와 local diff를 대조해 stale claim을 보정했다.
- [x] `SPW-05`: 표·명령·DoD 구조와 독립 review 결과를 현재 scope로 정리했다.
- [x] `KT-FIN-01`~`KT-FIN-11`: Kotlin/Spring/coroutine/lifecycle/test/dependency 경계를 검토했다. Exposed/HTTP/DB trigger는 `N/A`다.

## 구현 검토 결론

**WATCH — P0=0, P1=0, P2=3.** 현재 consumer 구현은 merge 차단 finding 없이 수용
기준을 충족한다. 운영 승격 전에는 orphan staging reaper와 managed key lifecycle을
별도 이슈로 설계해야 하며, PR 생성 전에는 모든 로컬 검증과 hosted CI/review thread를
exact head 기준으로 다시 확인한다.
