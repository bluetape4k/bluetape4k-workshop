# Issue #1044 `commerce/shared` 공통 계약 추출 설계

## 문제와 목표

현재 `commerce/shared`는 voucher campaign 두 구현이 함께 사용하는 black-box
계약을 소유한다. 한편 `commerce/usage-billing-{meter,usage,billing,invoice,query}-service`
는 같은 integration wire 계약을 구현하면서 SHA-256 payload digest와
`tenantId|aggregateType|aggregateId` partition key 조합을 각각 반복한다.
이 반복은 현재 동작을 깨뜨리지는 않지만 digest 인코딩·검증 방식과 partition key
구성이 바뀔 때 다섯 consumer를 따로 수정해야 하므로 cross-example 계약의 소유자가
분산된다.

목표는 안정적인 두 개 이상의 독립 consumer가 이미 존재하는 순수 계약만
`commerce/shared`로 이동하고, 각 서비스의 envelope schema, codec registry, inbox
정책, persistence와 domain 동작은 서비스에 남기는 것이다.

## 근거와 범위

- 이슈: [#1044](https://github.com/bluetape4k/bluetape4k-workshop/issues/1044)
- 현재 기준: `develop` `d4358aa77`
- 반복 consumer: 다섯 usage-billing service의 integration envelope와 decoder
- 기존 provider capability: `io.github.bluetape4k.tink.digest.TinkDigesters.SHA256`
- wire 계약: UTF-8 payload의 lowercase 64-character hexadecimal SHA-256
- partition 계약: `tenantId|aggregateType|aggregateId`
- 기존 shared 경계: voucher black-box contract는 그대로 유지

`TinkDigesters`는 이미 released `bluetape4k-tink` API이므로 암호 primitive를 새로
만들지 않는다. `commerce/shared`는 저장소 내부 cross-example 계약의 이름과
검증 경계를 제공하고, 실제 digest primitive는 provider에 위임한다.

## 대안

### A. 각 서비스에 현재 구현을 유지한다

변경량은 가장 작지만 다섯 consumer의 digest 호출과 partition key 조합이 계속
분산된다. 새 service가 추가될 때 wire 계약을 다시 복제해야 하므로 이슈의
공통화 목표를 충족하지 못한다.

### B. `commerce/shared`에 두 개의 무상태 계약 helper를 둔다 — 선택

`io.bluetape4k.workshop.commerce.shared.integration.PayloadDigest`는
`TinkDigesters.SHA256.digestHex`와 `matchesHex`를 감싸고, `AggregatePartitionKey`
는 기존 문자열 조합을 한 곳에서 소유한다. 반환 형식은 기존 `String`을 유지해
DB column, Kafka JSON, HTTP와 persistence model을 변경하지 않는다. 다섯 consumer는
`implementation(project(":commerce-shared"))`만 사용하고 provider 직접 의존성을
제거한다.

이 방식은 새 cryptography abstraction을 만들지 않으면서 이미 검증된 cross-example
계약을 shared가 직접 테스트할 수 있다.

### C. 공통 base envelope 또는 generic codec을 만든다

표면상 중복은 더 많이 제거되지만 Billing, Meter, Usage, Invoice, Query의 schema
허용 목록과 오류 분류가 서로 다르다. generic base가 domain 정책과 persistence
경계를 섞고, 예제별 차이를 숨길 위험이 있어 이번 범위에서 제외한다.

## 선택 설계

### Shared API

```kotlin
package io.bluetape4k.workshop.commerce.shared.integration

object PayloadDigest {
    fun of(payload: String): String
    fun matches(payload: String, digest: String): Boolean
}

object AggregatePartitionKey {
    fun of(tenantId: String, aggregateType: String, aggregateId: String): String
}
```

두 helper는 상태를 보유하지 않는다. `PayloadDigest`는 provider의 lowercase
hex 결과와 canonical 검증을 그대로 반환하며, 입력 검증이나 예외 타입을 새로
추가하지 않는다. `AggregatePartitionKey`도 기존 delimiter와 순서를 그대로
유지한다.

### Consumer 변경

- 다섯 integration envelope의 `digestOf`, `hasValidPayloadDigest`,
  `partitionKey`를 shared helper 호출로 바꾼다.
- billing/invoice/usage/query decoder와 meter command fingerprint의 직접
  `TinkDigesters` 호출을 `PayloadDigest`로 바꾼다.
- 다섯 build 파일에서 직접 `bluetape4k-tink` 의존성을 제거하고
  `implementation(project(":commerce-shared"))`를 추가한다.
- 기존 `TinkDigestCompatibilityTest`는 shared contract test로 이동해 JDK
  SHA-256 oracle과 함께 provider parity를 한 번만 검증한다.
- 기존 envelope schema, exception, database column, Kafka field와 service-local
  idempotency semantics는 변경하지 않는다.

### 문서

`commerce/shared` English/Korean README에 helper 계약과 provider 위임을 기록하고,
commerce module map과 다섯 service 및 composition README의 digest 설명을
`PayloadDigest` 기준으로 맞춘다. 수치, wire 형식, 비인증 checksum이라는 보안
한계는 그대로 유지한다.

## 실패 모드와 대응

1. **대문자 또는 잘못된 길이 digest가 수신됨** — `PayloadDigest.matches`가
   provider의 canonical 검증 결과를 반환하고 각 service가 기존 permanent
   rejection/quarantine 경로를 유지한다.
2. **partition key delimiter가 바뀜** — shared contract test가 기존
   `tenant-a|Invoice|invoice-7` 형태를 고정하고 다섯 envelope test가 caller
   결과를 확인한다.
3. **shared dependency가 module graph에 누락됨** — `:commerce-shared:test`와
   다섯 consumer compile/test를 한 Gradle invocation에서 실행하고 smoke
   module validation에서 등록을 확인한다.
4. **provider API가 예상과 다름** — resolved catalog artifact를 compile 단계와
   shared contract test로 확인한다. provider API를 복제하거나 새 dependency를
   추가하지 않는다.

## 호환성·보안 경계

이 저장소는 예제 consumer이므로 ABI/backward compatibility는 사용자 제약에 따라
범위에서 제외한다. 다만 이 변경은 의도적으로 DB/Kafka/HTTP scalar 계약을 유지해
불필요한 wire drift를 만들지 않는다. digest는 accidental corruption과 contract
drift를 감지하는 checksum이며 authentication이 아니다. 적대적 변조에는 MAC 또는
signature가 필요하다는 기존 설명을 유지한다.

## 수용 기준과 DoD

- [ ] `commerce/shared`가 `PayloadDigest`와 `AggregatePartitionKey`를 제공하고
      provider parity, malformed digest, partition key 회귀 테스트가 통과한다.
- [ ] 다섯 usage-billing service와 composition 문서가 shared API를 사용하며
      source에 직접 `TinkDigesters` 호출이 남지 않는다.
- [ ] 기존 lowercase 64-hex payload digest와 partition key 테스트 결과가
      동일하다.
- [ ] 다섯 service test, `commerce-shared` test, composition integration test,
      static check와 `git diff --check`가 통과한다.
- [ ] English/Korean README가 동일한 helper 이름, wire 계약, 보안 한계를 설명한다.
- [ ] 변경된 source와 docs에 P0/P1 결함이 없고, PR 생성/merge는 별도 권한 경계로
      남긴다.

설계 writer gate: SPW-01 PASS, SPW-02 PASS, SPW-03 PASS, SPW-04 PASS,
SPW-05 PASS.
