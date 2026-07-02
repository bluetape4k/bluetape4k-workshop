# Kafka Outbox Fallback

[English](README.md) | 한국어

이 예제는 전통적인 transactional outbox를 그대로 쓰지 않고, 주문 저장
트랜잭션의 부담을 줄이기 위해 Kafka를 먼저 시도하는 변형을 보여줍니다.
주문 생성 트랜잭션은 `orders` 행만 저장합니다. 커밋 이후 `OrderPlaced`
이벤트를 Kafka로 직접 발행하고, 실패하거나 timeout이 나거나 직접 발행이
비활성화된 경우에만 `event_publications` fallback 테이블에 저장합니다.

![Architecture](../../docs/images/readme-diagrams/kafka-outbox-fallback-readme-architecture-01.png)

## 무엇을 배우나

| 전통적인 transactional outbox | Kafka-first fallback |
|------------------------------|----------------------|
| domain row와 outbox row를 같은 트랜잭션에 저장합니다. | 트랜잭션은 domain row만 저장합니다. |
| relay가 항상 outbox 테이블을 읽어 나중에 발행합니다. | 커밋 이후 Kafka 직접 발행을 먼저 시도합니다. |
| outbox 테이블이 모든 이벤트의 출처입니다. | fallback 테이블은 실패하거나 복구된 이벤트만 저장합니다. |
| 유실 위험은 낮지만 hot transaction의 쓰기 비용이 큽니다. | hot transaction 비용은 낮지만 중복과 복구 위험이 커집니다. |

이 방식은 hot transaction 비용이 실제 병목이고, 소비자가 idempotent하게
동작할 수 있을 때만 고려해야 합니다. Kafka 발행 timeout은 결과가
불확실합니다. 호출자는 실패로 보지만 Kafka는 이미 레코드를 받았을 수
있습니다. 그래서 이 예제는 `order-placed:{orderId}:v1` 형태의 결정적인
event id를 계약으로 둡니다.

## 흐름

![Sequence](../../docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.png)

1. `POST /api/orders`가 요청을 검증합니다.
2. `TransactionalOrderWriter`는 `orders`만 저장합니다.
3. `OrderEventPublisher`가 주문 commit 이후 `OrderPlacedEvent`를 직렬화합니다.
4. `direct-publish-enabled=false`이면 Kafka를 호출하지 않습니다. 대신 `DIRECT_DISABLED` 사유로 `NOT_PUBLISHED` fallback row를 저장하고 `FALLBACK_STORED`를 반환합니다.
5. 직접 발행이 활성화되어 있고 성공하면 API는 `PUBLISHED_DIRECT`를 반환하고 fallback row는 남기지 않습니다.
6. 직접 발행이 활성화되어 있지만 실패하거나 timeout이면 3번까지 시도한 뒤 `NOT_PUBLISHED` row를 upsert합니다.
7. `EventPublicationRelay`가 fallback row를 claim하고 Kafka로 다시 보냅니다.
8. fallback 저장 자체가 실패한 경우 `PublicationReconciler`가 오래된 `orders` row에서 누락된 row를 재구성합니다.

## Fallback 생명주기

![Fallback publication lifecycle](../../docs/images/readme-diagrams/kafka-outbox-fallback-readme-state-01.png)

| 상태 | 의미 |
|------|------|
| `NO ROW` | Kafka 직접 발행이 성공했거나, 아직 reconciler가 누락 row를 복구하지 않은 상태입니다. |
| `NOT_PUBLISHED` | 실패, 비활성화, timeout, 재구성 때문에 relay 대상이 된 상태입니다. |
| `CLAIMED` | relay worker가 `claimedBy`, `claimedUntil`을 설정하고 전송 중입니다. |
| `FAILED` | relay 전송은 실패했지만 `relayMaxRetries`에 아직 도달하지 않았습니다. |
| `PUBLISHED` | Kafka가 relay 발행을 확인했고 `publishedAt`이 설정되었습니다. |
| `DEAD_LETTER` | retry 한도에 도달했습니다. 운영자가 확인해야 합니다. |

`CLAIMED`는 enum 값이 아니라 nullable claim 컬럼으로 표현합니다.

## REST API

주문 생성:

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1001","product":"coffee-beans","quantity":2}'
```

응답 예:

```json
{
  "id": 1,
  "customerId": "customer-1001",
  "product": "coffee-beans",
  "quantity": 2,
  "status": "PENDING",
  "publicationStatus": "PUBLISHED_DIRECT",
  "createdAt": "2026-06-29T12:00:00",
  "updatedAt": "2026-06-29T12:00:00"
}
```

`publicationStatus`는 호출자에게 보여주는 결과입니다.

| 상태 | 의미 |
|------|------|
| `PUBLISHED_DIRECT` | 커밋 이후 Kafka 직접 발행이 성공했습니다. |
| `FALLBACK_STORED` | 직접 발행이 비활성화되었거나, 실패하거나, timeout되어 fallback row를 저장했습니다. |
| `FALLBACK_STORE_FAILED` | 직접 발행도 실패했고 fallback 저장도 실패했습니다. 이후 reconciler가 `orders`에서 누락 row를 복구할 수 있습니다. |
| `UNKNOWN` | 조회 API는 내부 publication 상태를 노출하지 않습니다. |

payload 없이 fallback row 확인:

```bash
curl -s http://localhost:8080/api/publications
```

publication 응답은 `payload` 컬럼을 빼고 `eventId`, `status`, retry count,
sanitized error summary, timestamp 같은 안전한 metadata만 반환합니다.

Demo admin endpoint는 기본적으로 꺼져 있습니다.

```yaml
workshop:
  kafka-outbox-fallback:
    demo-admin-endpoints-enabled: true
```

활성화한 뒤에는 다음 호출로 relay와 reconciliation을 수동 실행할 수
있습니다.

```bash
curl -s -X POST http://localhost:8080/api/publications/relay
curl -s -X POST http://localhost:8080/api/publications/reconcile
```

이 endpoint는 workshop demo용입니다. 운영 환경에서는 인증, 권한, rate
limit 뒤에 두어야 합니다.

## 설정

```yaml
workshop:
  kafka-outbox-fallback:
    topic: order-events
    direct-publish-attempts: 3
    direct-publish-timeout: 500ms
    direct-publish-total-timeout: 1600ms
    relay-max-retries: 3
    relay-batch-size: 25
    relay-fixed-delay: 2000ms
    relay-claim-ttl: 30s
    reconciler-grace: 30s
    max-payload-bytes: 8192
    direct-publish-enabled: true
    relay-enabled: true
    reconciler-enabled: true
    demo-admin-endpoints-enabled: false
```

예제는 테스트와 다이어그램의 의미가 흔들리지 않도록 `topic`을
`order-events`, `direct-publish-attempts`를 `3`으로 고정합니다.

`direct-publish-enabled: false`로 설정하면 fallback-only 분기를 확인할 수
있습니다. 이 모드에서 `OrderEventPublisher`는 `KafkaTemplate.send(...)`를
건너뛰고, `DIRECT_DISABLED` 사유의 `NOT_PUBLISHED` row를 저장한 뒤
`FALLBACK_STORED`를 반환합니다.

## Metrics와 Health

Micrometer counter:

| Metric | Tags |
|--------|------|
| `workshop.outbox.direct.publish.attempts` | `result=success|timeout|failure` |
| `workshop.outbox.fallback.stored` | `result=success|failure` |
| `workshop.outbox.relay.events` | `result=published|failure|dead-letter` |
| `workshop.outbox.reconciler.events` | `result=reconstructed` |

Actuator는 `health`, `info`, `prometheus`를 노출합니다. probe-aware 환경에서는
readiness와 liveness health endpoint도 사용할 수 있습니다. 에러 응답에는
거절된 요청 값, raw payload, stack trace, secret처럼 보이는 문자열을 그대로
내보내지 않습니다.

## 실행

애플리케이션은 `src/main/resources/application.yml`의 PostgreSQL과 Kafka
endpoint를 사용합니다. 테스트는 bluetape4k Testcontainers launcher로
PostgreSQL과 Kafka를 시작합니다.

```bash
./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1
```

집중 검증:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --tests '*direct publish*' --max-workers=1
./gradlew :messaging-kafka-outbox-fallback:test --tests '*relay*' --tests '*reconciler*' --max-workers=1
```

## 운영 메모

- Kafka 직접 발행은 주문 트랜잭션 이후에 실행합니다. 이 예제를 전통적인 transactional outbox로 되돌릴 의도가 아니라면 다시 트랜잭션 안으로 넣지 마십시오.
- timeout은 Kafka 결과를 알 수 없다는 뜻입니다. event id는 결정적으로 유지하고 consumer는 idempotent하게 만들어야 합니다.
- `FALLBACK_STORE_FAILED`는 의도적으로 보이는 degraded state입니다. 주문은 존재하지만 reconciler가 복구하기 전까지 durable publication row가 없습니다.
- `DEAD_LETTER` row는 운영자가 확인할 수 있도록 테이블에 남깁니다. relay loop가 자동 삭제하면 안 됩니다.
- relay와 reconciler는 eligibility, 정렬, limit, missing-row 탐지를 SQL에서 처리합니다. reconciler 자체는 예제를 위해 여전히 단순하게 유지했으므로, 운영 환경에서는 더 좁은 범위, 인덱스, 운영자 제어가 필요합니다.
