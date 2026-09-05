# NATS JetStream Consumer Flow

[English](README.md) | 한국어

이 모듈은 stable `bluetape4k-nats:2.0.0`의 cold Flow adapter를 JetStream이 활성화된 실제
NATS Testcontainer에서 검증합니다.

## 소비자 경계에서 확인하는 계약

- Pull Flow는 collect할 때만 `ConsumerContext.iterate()`를 호출합니다.
- Push Flow는 collection마다 동기 subscription 하나를 만들고 정리합니다.
- 업무 처리 뒤 caller가 `ack()`, `nak()`, `term()` 중 하나를 명시적으로 선택합니다.
- `capacity`, pending message limit, pending byte limit을 모두 유한하게 유지합니다.
- Client pending queue drop은 `NatsConsumerFlowException`으로 실패하며 성공으로 축소하지 않습니다.
- 취소 시 adapter가 만든 iterable consumer 또는 subscription만 닫습니다. `Connection`, `JetStream`,
  durable `ConsumerContext`의 소유권은 caller에게 남습니다.

## Bounded memory 모델

Push consumer의 message 상한은 `pendingMessageLimit + capacity + 1`입니다. 각각 NATS client
pending queue, Flow buffer, receiver가 처리 중인 한 건입니다. Pull consumer는
`min(batchSize, capacity + 1)`만 요청합니다. `NatsFlowLimits`는 workshop의 유한 기본값을
명시합니다.

## 수동 승인 결정

| 결정 | 호출 | 의미 |
|---|---|---|
| `ACK` | `message.ack()` | 업무 처리가 성공적으로 끝남 |
| `NAK` | `message.nak()` | 재시도 가능한 실패이므로 재전달 요청 |
| `TERM` | `message.term()` | 영구 실패이므로 재전달 종료 |

Adapter는 application 대신 이 결정을 내리지 않습니다.

## 실행

```bash
./gradlew :messaging-nats-jetstream-flow:test --max-workers=1
```

테스트는 JetStream 옵션(`-js`)을 적용한 NATS 2.14.4 `NatsServer.Launcher`를 사용합니다.
Pull/push 순서, cold handle 생성, 순차 collection cleanup, `ack`/`nak`/`term`, redelivery와 실제
pending queue drop을 검증하며 외부 credential이나 live cluster는 필요하지 않습니다.
