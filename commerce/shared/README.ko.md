# Commerce Shared

[English](README.md) | 한국어

이 저장소 내부 모듈은 commerce 예제 사이에서 공유하는 compatibility
contract와 fixture를 제공합니다. production service, persistence, adapter,
migration은 소유하지 않습니다.

## 제공 contract

`VoucherCampaignBlackBoxContract`는 normalized-state voucher campaign 예제와
event-sourced voucher campaign 예제가 함께 검증하는 backend-neutral request,
normalized result, replay, allocation, lifecycle 시나리오를 정의합니다.

contract의 package는 다음과 같습니다.

```kotlin
io.bluetape4k.workshop.commerce.shared.voucher
```

consumer compatibility test는 다음 의존성을 선언합니다.

```kotlin
testImplementation(project(":commerce-shared"))
```

## 빌드와 테스트

```bash
./gradlew :commerce-shared:test
./gradlew :commerce-shared:build
```

이 모듈은 외부 인프라가 필요하지 않습니다.
