# Flow Extensions Subject Bridge

[English](README.md) | 한국어

이 예제는 callback 스타일 API를 bluetape4k Subject 타입으로 `Flow`에 연결하는 방법을 보여줍니다.

Subject는 모든 Flow 코드의 기본 구조가 아니라, 외부 callback 경계를 안전하게 감싸는 bridge 도구로 사용하는 것이 좋습니다.

## 시나리오

![Scenario](../../docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-scenario-01.png)

장비 SDK가 이벤트, 상태 변경, 작업 요청을 callback으로 밀어 넣는 상황을 가정합니다. Bridge는 이 callback을 다음 Flow 스트림으로 노출합니다.

- 현재 구독자에게만 전달되는 이벤트 broadcast
- 늦게 구독해도 최신값을 받는 상태 스트림
- 늦은 구독자를 위한 이벤트 history replay
- 정해진 구독자 수를 기다리는 multicast fan-out
- 한 consumer만 처리하는 work queue
- 정상 완료, 오류 종료, `emitError(null)` no-op 동작

## Before: 직접 callback wiring

```kotlin
fun rawBridge(listener: DeviceSdkListener): Flow<DeviceEvent> = callbackFlow {
    listener.onEvent = { event -> trySend(event) }
    listener.onError = { cause -> close(cause) }
    listener.onComplete = { close() }
    awaitClose { listener.onEvent = null }
}
```

이 방식도 동작하지만 replay, 최신 상태, multicast, unicast, 종료 정책을 매번 직접 정해야 합니다.

## After: Subject 선택

```kotlin
val bridge = DeviceSubjectBridge(
    initialState = DeviceState("device-01", DeviceStatus.OFFLINE, "boot")
)

launch { bridge.events.collect(::handleEvent) }
bridge.awaitEventSubscribers()
bridge.publishEvent(DeviceEvent("event-01", "device-01", DeviceEventType.CONNECTED, "online"))
```

## 아키텍처

![Architecture](../../docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-architecture-01.png)

`DeviceSubjectBridge`는 Subject 변경 권한을 bridge 내부에 두고, caller에게는 읽기 전용 `Flow` view만 노출합니다.

## Subject 선택 가이드

| 필요한 동작 | Subject | 독자 계약 |
|---|---|---|
| 이벤트 전용 callback stream | `PublishSubject` | 늦은 구독자는 과거 이벤트를 받지 않습니다 |
| 최신 장비 상태 | `BehaviorSubject` | 늦은 구독자는 최신 상태를 먼저 받습니다 |
| 제한된 callback history | `ReplaySubject` | 늦은 구독자는 buffer에 남은 이벤트를 받습니다 |
| 조율된 fan-out | `MulticastSubject` | producer는 기대 구독자 수를 기다립니다 |
| 단일 consumer queue | `UnicastWorkSubject` | queue의 work는 한 번만 소비됩니다 |

## 도메인 모델

![Domain model](../../docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-erd-01.png)

모델은 의도적으로 작게 유지했습니다. 핵심 타입은 `DeviceEvent`, `DeviceState`, `WorkItem`입니다.

## 클래스 모델

![Class diagram](../../docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-class-diagram-01.png)

## 시퀀스 모델

![Sequence](../../docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-sequence-01.png)

## 사용한 Bluetape4k 기능

| 기능 | 사용처 |
|---|---|
| `PublishSubject` | 활성 구독자에게만 전달되는 이벤트 stream |
| `BehaviorSubject` | 최신 장비 상태 |
| `ReplaySubject` | 제한된 callback history |
| `MulticastSubject` | 두 구독자에게 조율된 fan-out |
| `UnicastWorkSubject` | 단일 consumer work queue |
| `awaitCollector(s)` | 테스트와 callback handoff 동기화 |

## 빌드와 테스트

```bash
./gradlew :kotlin-flow-extensions-subject-bridge:test
```

## 참고

- [bluetape4k-coroutines Subject API](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/subject/SubjectApi.kt)
- [PublishSubject](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/subject/PublishSubject.kt)
- [BehaviorSubject](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/subject/BehaviorSubject.kt)
- [ReplaySubject](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/subject/ReplaySubject.kt)
- [MulticastSubject](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/subject/MulticastSubject.kt)
- [UnicastWorkSubject](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/subject/UnicastWorkSubject.kt)
