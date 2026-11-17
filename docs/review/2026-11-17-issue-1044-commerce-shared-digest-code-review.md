# Issue #1044 commerce 공통 계약 추출 코드 리뷰

## 검토 범위와 판정

- 대상: `refactor/commerce-shared-1044`의 Issue #1044 변경 전체
- 기준: `develop` (`d4358aa77aaddfa568e514e2d4807989bbcc3a30`)
- 범위: `commerce/shared`, 다섯 usage-billing consumer, Query partition-key 저장 경계,
  관련 English/Korean README, 테스트와 검증 산출물
- 설계 기준: provider-first 경계, 무상태 helper, schema/domain/persistence 정책의
  consumer 소유 유지

독립적인 contract/code reviewer, risk/security reviewer, test-engineer,
architecture reviewer, verifier가 각각 현재 working tree를 읽고 검토했다.
모든 reviewer는 P0/P1/P2를 찾지 못했으며, architecture reviewer만 향후 확장
가드에 해당하는 P3 두 건을 `WATCH`로 남겼다.

최종 판정은 **APPROVE — P0=0, P1=0, P2=0, P3=2 (비차단)** 이다.

## 여섯 관점 검토

### 1. 보안

- `PayloadDigest`는 `TinkDigesters.SHA256`에 위임하고 자체 암호 primitive를
  구현하지 않는다 (`commerce/shared/src/main/kotlin/io/bluetape4k/workshop/commerce/shared/integration/CommerceIntegrationContracts.kt:3-19`).
- KDoc와 shared README는 이 값이 accidental corruption과 contract drift를
  감지하는 checksum일 뿐 authentication이 아니며, 적대적 변조에는 MAC 또는
  signature가 필요하다고 명시한다 (`CommerceIntegrationContracts.kt:5-10`,
  `commerce/shared/README.md:25-33`).
- consumer는 원문 payload를 로그나 새 저장 경계로 보내지 않고 기존 digest
  검증·quarantine 경계를 유지한다. 신규 P0/P1 보안 finding은 없다.

### 2. 정확성·API/와이어 계약

- `PayloadDigest.of`/`matches`는 provider의 lowercase 64-character SHA-256
  표현과 canonical 검증을 그대로 보존한다 (`CommerceIntegrationContracts.kt:12-19`).
- `AggregatePartitionKey.of`는 기존
  `tenantId|aggregateType|aggregateId` 순서와 delimiter를 그대로 유지한다
  (`CommerceIntegrationContracts.kt:22-30`).
- 다섯 서비스의 envelope schema, 예외 분류, payload 저장 형식은 변경하지 않고
  계산 호출만 shared helper로 교체했다. Query persistence도 기존 column과
  repository 소유를 유지한다 (`QueryInboxPersistence.kt:27-43, 208-222`).

### 3. 성능·안정성

- 두 helper 모두 stateless이며 캐시, 직렬화, 추가 I/O 또는 새 동기화 계층을
  만들지 않는다.
- provider 호출과 문자열 결합을 한 단계 위임했을 뿐이어서 기존 비용과
  failure mode를 유지한다.
- consumer `check`와 usage-billing integration test, composition integration이
  통과했다. 성능·안정성 P0/P1/P2 finding은 없다.

### 4. 테스트·검증

- shared contract test는 독립 JDK `MessageDigest` oracle로 빈 문자열, UTF-8
  한국어/emoji, JSON payload의 provider parity를 검증한다
  (`CommerceIntegrationContractsTest.kt:11-20, 40-41`).
- canonical 검증은 uppercase, non-hex, truncated, wrong-payload를 거부하고,
  partition-key 순서와 delimiter 회귀도 고정한다
  (`CommerceIntegrationContractsTest.kt:22-38`).
- 기존 consumer envelope/decoder/partition-key 테스트와 다섯 module check,
  composition 12개 integration test, commerce smoke를 fresh run으로 확인했다.

### 5. 문서·운영

- `commerce/shared` README는 voucher compatibility와 integration contract의
  ownership, provider 위임, non-authenticating checksum 경계를 설명한다.
- 다섯 service와 composition README는 직접 provider 호출이 아니라
  `io.bluetape4k.workshop.commerce.shared.integration.PayloadDigest`에
  위임한다는 사실과 lowercase 64-character wire 형식을 설명한다.
- Korean README terminology audit는 변경된 8개 파일 모두 통과했다.
- 첫 전체 smoke 실행에서 세 모듈의 테스트 결과 binary 임시 파일에 대한
  `NoSuchFileException`이 발생했으나, 동일 세 모듈 재실행 369개 테스트와
  전체 commerce smoke 재실행이 모두 성공했다. 이는 코드 실패가 아닌
  Gradle test-result artifact race로 분류하며 P1로 승격하지 않는다.

### 6. 유지보수성·설계

- 의존성 방향은 다섯 consumer → `commerce-shared` → `bluetape4k-tink`로
  단방향이다. provider 교체점은 shared 한 곳으로 수렴했지만 schema, domain,
  persistence, adapter는 각 consumer에 남았다.
- generic envelope/codec 추상화를 추가하지 않아 서비스별 오류·schema 정책을
  숨기지 않는다.
- 기존 billing test의 pre-existing `MaxLineLength` 한 줄은 module detekt를
  차단하므로 의미 변경 없이 줄바꿈만 정리했다.

## 비차단 P3 관찰사항

1. `MeterCommands.ActivatePriceCommand.fingerprint()`도 현재 같은 SHA-256
   helper를 사용한다. 지금은 기존 계약과 일치하지만, 이후 integration payload에
   canonicalization/versioning을 추가할 때 domain idempotency fingerprint를
   함께 바꾸지 않도록 별도 의미 API를 분리해야 한다
   (`MeterCommands.kt:5, 27-29`).
2. partition key delimiter는 기존 `|` 조합을 보존하므로 구성요소에 delimiter가
   허용되면 identity ambiguity가 생길 수 있다. 새 identifier 종류를 추가할 때
   불허 검증 또는 versioned encoding을 별도로 설계하고 기존 helper를 조용히
   변경하지 않는다 (`CommerceIntegrationContracts.kt:22-30`).

두 항목은 현재 동작 결함이 아니며 승인된 provider-first 범위와 기존 wire 계약을
  보존하기 위한 future guard다.

## 검증 증거

| 검증 | 결과 |
|---|---|
| `:commerce-shared:test --tests '*CommerceIntegrationContractsTest' --rerun-tasks` | PASS — 3 tests |
| five consumer `check -x detekt` | PASS — `BUILD SUCCESSFUL` |
| five consumer `detekt` | PASS — `BUILD SUCCESSFUL` |
| composition `integrationTest` | PASS — 12 tests |
| three smoke modules rerun | PASS — 369 tests |
| `scripts/smoke-validate.sh commerce` (fresh rerun) | PASS — `BUILD SUCCESSFUL` |
| `git diff --check` | PASS |
| changed Korean README terminology audit | PASS — 8 files |
| independent reviews | PASS — P0/P1/P2 0; P3 2 non-blocking |

## 독립 리뷰 결론

- contract/code reviewer: **APPROVE**, P0/P1/P2 0
- risk/security reviewer: **APPROVE**, P0/P1/P2 0
- test-engineer: **APPROVE**, P0/P1/P2 0
- architecture reviewer: **WATCH**, P0/P1/P2 0, future-guard P3 2
- verifier: **PASS (local readiness)**, delivery readiness는 PR 권한 부재로 별도 보류

이 리뷰는 local implementation readiness를 증명하지만 PR 생성·원격 CI·merge
승인을 대신하지 않는다.
