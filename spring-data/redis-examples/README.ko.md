# Redis Examples

[English](README.md) | 한국어

이 모듈은 `spring.data.redis.*` 설정을 통해 Redis Testcontainers launcher에 연결하는
Spring Data Redis 예제 모음입니다. 실행 앱이라기보다 테스트 중심 워크샵이며,
repository, command, reactive operation, stream 테스트에서 동작을 확인합니다.

## 아키텍처

![Redis examples architecture](../../docs/images/readme-diagrams/spring-data-redis-examples-readme-architecture-01.png)

루트 모듈은 synchronous repository, Redis command, reactive operation, stream 테스트를
포괄합니다. Stream 패키지는 producer, consumer group, listener 흐름이 더 상세하므로
별도 README에서 설명합니다.

## Redis Data Model

![Redis examples data model](../../docs/images/readme-diagrams/spring-data-redis-examples-readme-model-01.png)

Spring Data Redis는 repository aggregate를 Redis hash로 저장하고, secondary index는
Redis set으로 유지합니다. Reference는 key로 저장되므로 관계 조회가 필요한 테스트는
명시적인 hash-id field를 사용하거나 `@Reference` 조회 제한을 검증합니다.

## 예제 영역

| 영역 | Source | 보여주는 내용 |
|---|---|---|
| Person repository | `repositories/*`, `PersonRepositoryTest` | `@RedisHash`, `@Indexed`, embedded address field, reference, query method, QBE. |
| Movie repository | `movie/*`, `MovieRepositoryTest` | Movie/actor hash, secondary index, explicit reference record, 관계 조회 tradeoff. |
| Commands | `commands/*` | Redis template 기반 key 및 geo operation. |
| Reactive operations | `reactive/*` | Reactive Redis 설정, value/list operation, JSON serialization. |
| Streams | `stream/*` | Sync/reactive Redis Stream API 테스트. 자세한 흐름은 stream README를 참고합니다. |

## Runtime Notes

`src/main/resources/application.yml`은 Redis host와 port를
`testcontainers.redis.host`, `testcontainers.redis.port`에서 읽습니다. 예제는 테스트
suite에서 사용하는 shared Testcontainers Redis launcher와 함께 실행되도록 구성되어
있습니다.

## 빌드와 테스트

```bash
./gradlew :spring-data:redis-examples:test
```

## 관련 모듈

- [`redis/redisson-examples`](../../redis/redisson-examples): Redisson 기반 예제.
- [`spring-data/redis-examples/src/main/kotlin/io/bluetape4k/workshop/redis/stream`](src/main/kotlin/io/bluetape4k/workshop/redis/stream): Redis Stream 전용 walkthrough.
