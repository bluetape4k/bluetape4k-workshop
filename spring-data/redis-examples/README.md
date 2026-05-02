# Redis Examples

## 아키텍처 다이어그램

```mermaid
classDiagram
    class Movie {
        +String name
        +String genre
        +Int year
        +String? hashId
        +String description
    }
    class Actor {
        +String firstname
        +String lastname
        +String? hashId
        +String description
    }
    class MovieActor {
        +String movieId
        +String actorId
        +String? hashId
    }
    class MovieActorReference {
        +String movieId
        +String actorId
        +String? hashId
    }
    class Person {
        +String id
        +String firstname
        +String lastname
        +Address address
        +Gender gender
    }
    class Address {
        +String city
        +String country
    }
    class SensorData {
        +String sensorId
        +Double value
        +Instant timestamp
    }

    Movie --> MovieActor : 참조
    Actor --> MovieActor : 참조
    Movie --> MovieActorReference : 참조
    Person --> Address : 포함
```

```mermaid
flowchart TB
    subgraph Redis 데이터 구조
        HASH[Hash\nMovie, Actor, Person]
        STREAM[Stream\nSensorData 이벤트]
        PUBSUB[Pub/Sub\n채널 메시지]
        STRING[String/List/Set/ZSet\n기본 자료구조]
    end

    subgraph 애플리케이션 레이어
        REPO[Spring Data Redis\nRepository]
        TMPL[RedisTemplate\n직접 조작]
        LSTN[StreamListener\n소비자 그룹]
        SCRIPT[RedisScript\nLua 원자적 실행]
    end

    subgraph 트랜잭션
        TX[MULTI/EXEC\n트랜잭션]
    end

    REPO --> HASH
    TMPL --> STRING
    TMPL --> PUBSUB
    LSTN --> STREAM
    SCRIPT --> STRING
    TX --> STRING
```

Spring Data Redis를 활용하는 다양한 데이터 구조 예제 모음입니다.
Testcontainers로 Redis 컨테이너를 자동으로 구동하여 통합 테스트를 수행합니다.

## 예제 범주

- **Redis Stream** (`stream/`) — Consumer Group 기반 메시지 스트림 발행·소비
- **Redis Hash / String / List / Set / ZSet** — 기본 자료구조 CRUD
- **Pub/Sub** — 채널 기반 메시지 발행·구독
- **Transaction** — `MULTI`/`EXEC` 트랜잭션 처리
- **Lua Script** — `RedisScript`를 이용한 원자적 스크립트 실행

## 참고

- [Spring Data Redis 공식 문서](https://docs.spring.io/spring-data/redis/reference/)
- Redisson 기반 예제는 [`redis/redisson-examples`](../../redis/redisson-examples) 모듈 참고
