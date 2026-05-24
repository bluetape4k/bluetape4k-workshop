# 2026-05-24 Serialization/Messaging Basic — BT Feature Documentation (Issue #83)

## 작업 개요

5개 모듈 README에 bluetape4k(BT) 기능 표 및 Before/After 예제를 추가했다.

대상 모듈:
- `json/jackson-examples` — `Jackson.defaultJsonMapper`, `KLogging`, `Fakers.faker`
- `json/jsonview-examples` — `Jackson.defaultJsonMapper`, assertion 헬퍼
- `messaging/kafka` — `KafkaServer.Launcher`, `KLoggingChannel`
- `messaging/kafka-reply` — `CompletableFuture.onSuccess/onFailure`, `uninitialized()`
- `redis/redisson-examples` — `RedissonCodecs.LZ4ForyComposite`, `VirtualThreadExecutor`, `RedisServer.Launcher`, `MultithreadingTester`, `SuspendedJobTester`, `getLockId()`

## 주요 발견

### 1. Jackson.defaultJsonMapper (jackson-examples, jsonview-examples)

`bluetape4k-jackson3`의 `Jackson.defaultJsonMapper`는 KotlinModule + JavaTimeModule + Blackbird를 사전 등록한 `JsonMapper` 싱글톤이다.
`@Bean fun jsonMapper(): JsonMapper = Jackson.defaultJsonMapper` 한 줄로 모든 모듈 설정이 완료된다.
rebuild()로 추가 설정도 체이닝 가능하다.

### 2. KafkaServer.Launcher.kafka (messaging/kafka)

`bluetape4k-testcontainers`의 `KafkaServer.Launcher.kafka`는 Testcontainers Kafka 싱글톤이다.
`@SpringBootApplication` companion object에 선언하면 앱 시작 시 자동으로 Kafka 컨테이너가 구동된다.
`@DynamicPropertySource` + `KafkaContainer` 수동 관리 패턴이 한 줄로 대체된다.

### 3. @KafkaListener + suspend/Reactor 제약

`@KafkaListener`는 공식적으로 `suspend` 함수와 Reactor Publisher를 지원하지 않는다.
`CoroutineSimpleMessageHandler`는 `// @Component`로 비활성화된 참고용 코드다.
이 제약은 README에 명시적으로 문서화해야 한다 — 사용자가 비작동 코드를 오해하지 않도록.

### 4. RedissonCodecs.LZ4ForyComposite (redis/redisson-examples)

`bluetape4k-redis`의 `RedissonCodecs.LZ4ForyComposite`는 LZ4 압축 + Fory(구 Fury) 이진 직렬화 조합이다.
JSON 직렬화 대비 저장 공간을 50~70% 절감하고 직렬화/역직렬화 속도도 빠르다.
`Config.codec`에 직접 주입한다.

### 5. BT 동시성 테스트 헬퍼 (redis/redisson-examples)

`MultithreadingTester`, `StructuredTaskScopeTester`, `SuspendedJobTester` 세 가지 헬퍼가
각각 OS 스레드, Virtual Thread, 코루틴 환경에서 FencedLock 동시성을 재현 가능하게 검증한다.
수동 Thread 생성 방식보다 결정론적(deterministic) 검증이 가능하다.

### 6. `getLockId()` — 코루틴 안전 FencedLock ID

`bluetape4k-redis`의 `RedissonClient.getLockId(lockName)` 확장 함수는
코루틴 컨텍스트에서 RFencedLock의 ID를 안전하게 획득한다.
일반 `lockAndGetToken()` 패턴을 코루틴 `tryLockAsync().await()` 방식으로 대체할 때 필수다.

## 테스트 결과

```
:jackson-examples:test      BUILD SUCCESSFUL
:jsonview-examples:test     BUILD SUCCESSFUL
:messaging-kafka:test       BUILD SUCCESSFUL
:messaging-kafka-reply:test BUILD SUCCESSFUL
:redis-redisson-examples:test BUILD SUCCESSFUL
```

## 향후 가이드

- `bluetape4k-kafka` 아티팩트는 현재 `// implementation(libs.bluetape4k.kafka)`로 비활성화 상태다.
  Kafka Spring 통합은 아직 `bluetape4k-kafka`를 직접 사용하지 않고 Spring Kafka를 그대로 사용한다.
- Redisson 코루틴 lock에서 `getLockId()`는 2단계 취득 패턴 필수:
  1. `redisson.getLockId(lockName)` — mlockId 획득
  2. `lock.tryLockAsync(wait, lease, TimeUnit, mlockId).await()` — 코루틴 안전 lock 시도
  3. `lock.unlockAsync(mlockId).await()` — 명시적 mlockId로 해제
