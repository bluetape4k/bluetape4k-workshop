# Spring Data Redis Streams

[English](README.md) | 한국어

이 패키지는 Spring Data Redis의 sync API와 reactive API로 Redis Streams를 다루는
예제입니다. 예제는 하나의 stream key인 `my-stream`과 `SensorData`가 만드는 sensor
record를 사용합니다.

## 아키텍처

![Redis stream architecture](../../../../../../../../../../docs/images/readme-diagrams/spring-data-redis-examples-src-main-kotlin-io-bluetape4k-workshop-redis-stream-readme-architecture-01.png)

`RedisStreamConfiguration`은 imperative API용 `StreamMessageListenerContainer`와
reactive API용 `StreamReceiver`를 모두 생성합니다. 두 listener 인프라는 같은 Redis
Testcontainer에 연결됩니다.

## Stream Flow

![Redis stream flow](../../../../../../../../../../docs/images/readme-diagrams/spring-data-redis-examples-src-main-kotlin-io-bluetape4k-workshop-redis-stream-readme-sequence-01.png)

테스트는 같은 stream 동작을 두 API 스타일로 확인합니다.

1. `XADD`가 `1234-0`, `1234-1` 같은 fixed ID를 기록합니다.
2. 더 최신 record가 존재한 뒤 더 낮은 timestamp ID를 추가하면 Redis가 거부합니다.
3. Auto-generated ID는 다음 timestamp/sequence 값을 붙입니다.
4. `XREAD`는 `my-stream`의 처음부터 읽거나, 알려진 ID 이후부터 재개할 수 있습니다.
5. Continuous read는 `StreamMessageListenerContainer.receive(...)` 또는
   `StreamReceiver.receive(...)`를 사용합니다.

## 주요 클래스

| Class | 역할 |
|---|---|
| `SensorData` | `sensor-id`, `temperature`, optional `checksum`을 가진 `StringRecord`를 만듭니다. |
| `RedisStreamConfiguration` | Sync/reactive stream listener 인프라를 제공합니다. |
| `CapturingStreamListener` | Sync listener가 받은 record를 assertion용으로 수집합니다. |
| `SyncStreamApiTest` | `StringRedisTemplate.opsForStream()`을 검증합니다. |
| `ReactiveStreamApiTest` | `ReactiveStringRedisTemplate.opsForStream()`과 `StreamReceiver`를 검증합니다. |

## 빌드와 테스트

```bash
./gradlew :spring-data:redis-examples:test --tests '*StreamApiTest'
```
