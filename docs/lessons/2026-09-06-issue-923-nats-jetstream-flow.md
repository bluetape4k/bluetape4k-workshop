# Issue #923 NATS JetStream Consumer Flow

## Context

dependencies 2.0.0은 pull `ConsumerContext`와 push `JetStream`을 cold `Flow<Message>`로
노출한다. Workshop에는 NATS 예제가 없어 bounded buffering, 수동 승인과 cancellation
lifecycle을 실제 broker에서 확인할 경계가 없었다.

## Decision or Finding

- Provider Flow를 복제하지 않고 `JetStreamConsumerFlows`에서 유한 capacity와 push pending limit만 조립한다.
- Pull의 durable `ConsumerContext`는 caller가 선행 생성하고 `IterableConsumer`만 collect 시점에 생성된다.
- `ack`/`nak`/`term`은 업무 의미이므로 adapter가 자동 선택하지 않고 caller decision으로 남긴다.
- 실제 drop 회귀는 첫 delivery barrier 뒤 core NATS publish burst를 사용한다. 동기 JetStream publish를 순차
  호출하면 collector가 따라잡아 drop을 재현하지 못할 수 있다.

## Outcome

Pull/push Flow의 cold handle, 순차 cleanup, manual ack/redelivery/finalization과 pending-drop
fail-fast를 stable 2.0.0 artifact와 실제 JetStream container에서 실행할 수 있다.

## Verification

- Production facade 부재에서 compile RED
- Cold handle과 `ack`/`nak`/`term` unit regression
- NATS Testcontainers pull/push ordering, ack drain, redelivery, term, actual pending drop
- `--max-workers=1` module test와 Examples container lane

## Future Guidance

Drop 회귀는 publish 처리량이 collector보다 충분히 빨라야 한다. 첫 delivery barrier, 유한 timeout,
background collector `cancelAndJoin()`을 유지하고 외부 cluster나 sleep만으로 lifecycle을 추정하지 않는다.
