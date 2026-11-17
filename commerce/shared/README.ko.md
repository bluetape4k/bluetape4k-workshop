# Commerce Shared

[English](README.md) | 한국어

이 저장소 내부 모듈은 commerce 예제 사이에서 공유하는 compatibility contract와 fixture를 제공합니다. production service, persistence, adapter, migration은 소유하지 않습니다.

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
