# Transactional Outbox Pattern — Issue #99

## 배경

Transactional Outbox pattern을 사용해 domain event를 Kafka에 원자적으로 publish하는
방법을 보여주는 workshop 예제를 추가한다.

## 문제

순진한 접근은 DB에 쓴 뒤 Kafka에 publish하는 것이다. DB commit 이후 Kafka가
실패하면 event가 유실된다. Kafka send 이후 DB가 실패하면 duplicate event가 발생한다.

## 해결책(Outbox Pattern)

1. domain state + outbox event를 **하나의 DB transaction**에 쓴다.
2. background scheduler가 `outbox_events` table에서 PENDING event를 poll한다.
3. Kafka에 publish하고 event를 PUBLISHED로 표시한다.
4. 실패 시 retryCount를 증가시키고, MAX_RETRY 이후에는 DEAD_LETTER로 보낸다.

## 핵심 결정

- **`@MockkSpyBean` 대신 `@MockkBean(relaxed = true)`** — Spring Boot가
  auto-configure한 `KafkaTemplate`은 type erasure를 사용하므로 spy가
  `KafkaTemplate<String, String>`을 정확한 generic type으로 match하지 못한다.
- **`tools.jackson.databind.ObjectMapper`** — Jackson 3.x는 package를
  `com.fasterxml.jackson`에서 `tools.jackson`으로 변경했으므로 올바른 package에서
  import해야 한다.
- **`@Bean fun objectMapper()`** — Spring Boot 4 starter(`webmvc.lib` alias)가
  항상 `tools.jackson.databind.ObjectMapper`를 auto-configure하지는 않는다. 명시적
  bean은 `UnsatisfiedDependencyException`을 피한다.
- **단일 `.where { cond1 and cond2 }`** — Exposed v1 `andWhere {}`는
  `.map { it[...] }` receiver inference를 깨뜨리는 type을 반환한다. 모든 조건을
  하나의 `.where {}` block에 결합한다.

## 검증

```
7 tests passing (10.7s)
./gradlew :messaging-transactional-outbox:test
```
