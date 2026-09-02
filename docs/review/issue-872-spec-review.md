# Issue #872 설계 문서 리뷰

- 검토 대상: `docs/superpowers/specs/2026-09-02-issue-872-s3-cse-transfer-design.md`
- 검토 기준: `bluetape-full-feature` Step 2-R, `artifact_kind=spec`
- 검토일: 2026-09-02
- 기준 브랜치: `feat/issue-872-s3-cse-transfer` (`origin/develop` 기반)

## 근거와 범위

다음 자료와 현재 checkout을 기준으로 여섯 관점을 독립적으로 검토했다.

- workshop Issue [#872](https://github.com/bluetape4k/bluetape4k-workshop/issues/872)
- upstream Issue [#475](https://github.com/bluetape4k/bluetape4k-aws/issues/475)
- upstream PR [#585](https://github.com/bluetape4k/bluetape4k-aws/pull/585)
- 현재 `S3Config`, `StorageService`, 세 기존 profile test
- resolved `bluetape4k-aws-spring-boot-1.0.0-SNAPSHOT.jar`의 public API
- 현재 `gradle/libs.versions.toml`, `Examples.yml`, `smoke-validate.sh`
- 기존 module baseline: `:aws-storage-abstraction:test` 27개 통과

검토 범위는 profile 격리, upstream API 소비, ciphertext/plaintext 경계,
key lifecycle, bounded read, streaming/file transfer, cancellation/cleanup,
기존 profile 호환성, README/CI/stale/lesson 추적성이다. 실제 코드 구현과
Gradle 변경은 아직 시작하지 않았으므로 구현 세부의 검증은 Step 2-R 범위에서
제외했다.

## 독립 관점 결과

| 우선순위 | 관점 | 근거 및 최초 finding | 조치 | 최신 결과 |
| --- | --- | --- | --- | --- |
| P2 | performance | threshold 초과 경로에서 AWS multipart 내부 part 수를 Floci만으로 직접 관찰한다고 해석할 여지가 있었다. | fake delegate에서는 file/multipart 경로와 completion 1회를 확인하고, Floci에서는 ciphertext round-trip만 확인하도록 spec을 구체화했다. | PASS, P0=0/P1=0 |
| P1 | stability | dedicated config의 client/manager lifecycle이 초기 spec에 명시되지 않아 async HTTP 자원 누수 위험이 있었다. | `S3Client`, `S3AsyncClient`, `S3TransferManager`의 `close` destroy method와 provider/template 의존 순서를 수용 기준에 추가했다. | PASS, P0=0/P1=0 |
| P1 | security | byte download이 unbounded `downloadEncryptedBytes`를 사용하면 ciphertext allocation 상한을 우회할 수 있었다. | `downloadEncryptedBytesBounded`와 upstream `MAX_CIPHERTEXT_BYTES` 기본 상한, 초과 응답 negative test를 서비스 계약과 DoD에 추가했다. | PASS, P0=0/P1=0 |
| P2 | operator/Ops | JVM 재시작 뒤 ephemeral key로 기존 object를 읽을 수 없다는 운영 경계가 초기 문서에서 약했다. | key material이 메모리에만 있고 재시작 시 기존 object가 복호화되지 않는다는 경고와 production key 관리 제외를 추가했다. | PASS, P0=0/P1=0 |
| P2 | developer/API | upstream public constructor와 method 조합을 추정하면 구현 시 잘못된 adapter를 만들 수 있었다. | resolved jar와 upstream `develop` source의 `ProviderTemplate`, `TransferTemplate`, `S3TransferTemplate` API를 ledger에 고정했다. | PASS, P0=0/P1=0 |
| P2 | user/caller | `downloadFile`의 destination write가 원자적 rename이라고 오해할 수 있었다. | upstream 계약을 bounded write + rollback으로 명시하고 atomic rename이 아님을 문서화했다. | PASS, P0=0/P1=0 |

## 통합 검토

### 경계 정합성

- 기존 `local`, `s3`, `s3-presigned` profile은 `S3Config`와 기존
  `StorageService` 계약을 그대로 유지한다.
- 암호화 profile은 `S3AutoConfiguration`과 `S3TransferAutoConfiguration`을
  다시 켜지 않고 별도 config에서 필요한 bean만 조립한다. 따라서 기존 sync
  client/presigner bean 조건과 충돌하지 않는다.
- provider와 envelope parser는 upstream public template이 소유한다. workshop은
  key provider 선택, profile wiring, caller-facing byte/file 예제와 검증만 맡는다.
- `getUrl`은 endpoint-neutral `s3://` URI이며 presigned encrypted download는
  범위에서 제외한다.

### 수용 기준 추적성

| Issue 요구 | 설계 위치 | 검증 위치 |
| --- | --- | --- |
| AES/RSA opt-in profile | Profile 및 key 계약, 구조와 데이터 흐름 | profile wiring, byte round-trip |
| provider/key/algorithm metadata | 서비스 계약, 실패 모드 | metadata/key boundary test |
| bounded ciphertext read | 서비스 계약, 실패 모드 | oversized byte/file negative test |
| threshold streaming/multipart | 서비스 계약, 테스트 설계 | fake delegate + Floci round-trip |
| plaintext destination safety | 서비스 계약, file download safety | tamper/wrong-key existing destination test |
| cancellation/cleanup/no-key log | 실패 모드, 테스트 설계 | fake delegate/coroutine/log capture |
| 기존 예제와 운영 문서 | 범위 및 호환성, DoD | baseline regression, README/CI/stale/matrix/lesson checks |

### SPW·한국어 품질

| 게이트 | 결과 | 근거 |
| --- | --- | --- |
| SPW-01 목적·독자·근거 | PASS | spec metadata, 근거 ledger, Issue/upstream/local anchors |
| SPW-02 artifact contract | PASS | alternatives, flow, failure modes, tests, scope, DoD |
| SPW-03 Korean technical register | PASS | `audit-korean-terms.mjs` 결과 `findings=0` |
| SPW-04 traceability | PASS | 위 수용 기준 추적성 표와 명령/파일 목록 |
| SPW-05 read-back | PASS | placeholder/미완성 항목 없음, `git diff --check` 통과, 전체 문서 재독 |

## 통합 판정

- P0: 0
- P1: 0
- P2: 0 (초기 finding 6건은 모두 spec에 반영하고 해당 관점을 재검토)
- P3: 0
- 판정: **PASS — 설계 승인 후 구현 계획 단계로 진행 가능**

구현 중 upstream API, Floci capability, bean lifecycle이 이 문서와 달라지면
구현을 멈추고 spec과 plan을 먼저 갱신한 뒤 재승인한다.
