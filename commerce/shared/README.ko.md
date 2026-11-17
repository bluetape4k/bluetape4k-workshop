# Commerce Shared

[English](README.md) | 한국어

이 저장소 내부 모듈은 commerce 예제 사이에서 공유하는 compatibility contract와 fixture를 제공합니다. production service, persistence, adapter, migration은 소유하지 않습니다.

## 이 모듈이 필요한 이유

Voucher와 usage-billing 예제는 event identity, replay 입력, partition key,
payload digest에 합의해야 하지만 각자의 application 동작은 독립적으로
소유해야 합니다. 이 규칙을 예제마다 복사하면 producer와 consumer가
호환되는 것처럼 보여도 경계 조건이 서로 달라질 수 있습니다.

따라서 이 모듈은 양쪽에서 확인할 수 있는 최소한의 provider-neutral contract와
fixture만 제공합니다. database, schema, transport, 상태 전이의 기준 데이터는
소유하지 않으며, 각 예제가 자신의 persistence와 delivery 경계를 계속
책임집니다. stateless helper와 독립적인 test oracle을 사용하는 이유도
fixture가 숨은 shared runtime으로 변하지 않으면서 contract drift를 드러내기
위해서입니다.

아래 `:commerce-shared:test`와 `:commerce-shared:build` 명령이 이 경계의
test-backed 검증입니다. 외부 인프라 없이 shared contract와 fixture 동작을
확인하며, 이를 소비하는 서비스의 durability나 delivery 동작까지 증명하지는
않습니다.

## 제공 contract

`VoucherCampaignBlackBoxContract`는 normalized-state voucher campaign 예제와 event-sourced voucher campaign 예제가 함께 검증하는 backend-neutral request, normalized result, replay, allocation, lifecycle 시나리오를 정의합니다.

contract의 package는 다음과 같습니다.

```kotlin
io.bluetape4k.workshop.commerce.shared.voucher
```

consumer compatibility test는 다음 의존성을 선언합니다.

```kotlin
testImplementation(project(":commerce-shared"))
```

## Integration contract

`PayloadDigest`는 usage-billing payload checksum 계약을 소유합니다.
`TinkDigesters.SHA256`에 위임하면서 UTF-8 payload의 lowercase 64자 hex
표현을 유지합니다. `AggregatePartitionKey`는 Kafka partition과 local
outbox/inbox 경계가 공유하는 결정적인
`tenantId|aggregateType|aggregateId` key를 소유합니다.

두 helper는 stateless하며 schema, database, transport를 소유하지 않습니다.
checksum은 우발 손상과 contract drift를 탐지하지만 authentication을
제공하지 않습니다. 적대적 변조가 threat model에 포함되면 MAC 또는
signature를 사용해야 합니다.

Usage-billing service는 provider를 직접 호출하지 말고 이 모듈에 의존해야
합니다. 독립적인 JDK SHA-256 test oracle은 shared contract를 검증하기 위해
각 test에 유지합니다.

## 빌드와 테스트

```bash
./gradlew :commerce-shared:test
./gradlew :commerce-shared:build
```

이 모듈은 외부 인프라가 필요하지 않습니다.
