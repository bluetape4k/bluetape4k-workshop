# Issue #1044 commerce 공통 digest 계약 추출 교훈

## Context

다섯 usage-billing service가 같은 payload SHA-256 checksum 생성·검증과
`tenantId|aggregateType|aggregateId` partition-key 조합을 각각 직접 소유하고
있었다. provider 호출과 wire 계약이 분산되어 공통 변경점과 검증 기준이
소비자 수만큼 반복되는 상태였다.

## Decision or Finding

- `commerce/shared`는 provider-first 무상태 계약만 소유한다.
- `PayloadDigest`는 `TinkDigesters.SHA256.digestHex/matchesHex`를 위임하고
  lowercase 64자 hex wire 형식을 유지한다.
- `AggregatePartitionKey`는 기존 tenant/aggregate 순서와 delimiter를 보존한다.
- schema, exception, domain lifecycle, persistence column, adapter와 migration은
  각 usage-billing service에 남긴다.
- production은 shared helper를 사용하지만 test는 독립 JDK SHA-256 oracle을
  유지한다. 같은 provider를 test에서 다시 호출하면 provider 회귀를 놓칠 수 있다.

## Outcome

- 다섯 service의 direct `TinkDigesters` 의존성과 manual partition-key 조합을
  제거하고 `project(":commerce-shared")`로 수렴했다.
- wire payload, digest 저장 형식, schema/version, quarantine reason, persistence
  ownership은 변경하지 않았다.
- shared module은 voucher compatibility contract와 integration contract를 함께
  제공하지만 production service/persistence/transport는 소유하지 않는다.
- 기존 billing detekt 차단선은 동작 변경 없이 줄바꿈만 정리했다.

## Verification

- shared contract 3 tests: provider parity, canonical rejection, partition-key.
- 다섯 consumer `check -x detekt`, 다섯 consumer detekt: PASS.
- usage-billing composition integration: 12 tests PASS.
- 세 smoke module 재실행: 369 tests PASS.
- 전체 `scripts/smoke-validate.sh commerce` 재실행: PASS.
- 첫 smoke의 `in-progress-results-generic.bin` 오류는 동일 모듈 및 전체 smoke
  재실행 성공으로 transient Gradle test-result artifact race로 분리했다.

## Future Guidance

새 shared 후보는 다음 순서로 판단한다.

1. 서로 독립된 두 개 이상 consumer가 실제로 같은 stable contract를 사용하는지
   확인한다.
2. provider primitive를 먼저 확인하고, wrapper는 ownership·canonicalization·
   failure semantics를 명시할 때만 만든다.
3. domain fingerprint와 integration checksum처럼 현재 구현이 우연히 같은 값을
   쓰는 의미가 분리되는 순간에는 `CommandFingerprint` 같은 별도 API를 만든다.
4. 기존 delimiter 기반 partition key를 조용히 바꾸지 말고, 새 identifier 규칙은
   escaping 또는 versioned encoding을 별도 계약으로 검토한다.
5. checksum을 authentication으로 문서화하지 말고, 적대적 변조에는 keyed MAC
   또는 signature를 별도 경계로 둔다.
