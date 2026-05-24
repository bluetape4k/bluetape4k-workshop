# 2026-05-24 Spring Boot Basic — BT Feature Documentation (Issue #82)

## 작업 개요

4개 `spring-boot/` 모듈 README에 bluetape4k(BT) 기능 표 및 Before/After 예제를 추가했다.

대상 모듈:
- `spring-boot/cache-caffeine` — `caffeine { }` DSL, `VirtualThreadExecutor`
- `spring-boot/cache-redis` — `RedisBinarySerializers.LZ4Kryo`, Virtual Thread AsyncTaskExecutor
- `spring-boot/problem` — `KLogging`, `bluetape4k-resilience4j` AdviceTrait
- `spring-boot/webflux-coroutines` — `Dispatchers.VT`, `Flow<T>.async`, `Runtimex`

## 적용된 주요 패턴

### 1. caffeine { } DSL (cache-caffeine)

`io.bluetape4k.cache.caffeine.caffeine` 빌더 DSL은 Kotlin Duration을 직접 사용할 수 있어
Java API의 `TimeUnit` 변환 없이 `5.minutes.toJavaDuration()` 형태로 TTL을 지정할 수 있다.
`VirtualThreadExecutor`를 `executor()`에 넘겨 Caffeine의 lazy loading을 Virtual Thread로 실행한다.

### 2. RedisBinarySerializers.LZ4Kryo (cache-redis)

`bluetape4k-spring-boot4-redis`가 제공하는 `RedisBinarySerializers.LZ4Kryo`는
LZ4 압축 + Kryo 이진 직렬화를 조합해 GenericJackson2JsonRedisSerializer 대비 Redis 저장 공간을 50~70% 절감한다.
`RedisTemplate.setDefaultSerializer()`와 `valueSerializer`에 동일하게 설정한다.

### 3. Dispatchers.VT + Flow.async (webflux-coroutines)

`bluetape4k-coroutines`의 `Dispatchers.VT`는 Virtual Thread per task ExecutorService를
CoroutineDispatcher로 래핑한 싱글톤이다. 클래스 수준 `CoroutineScope(Dispatchers.VT)`로 주입하면
컨트롤러 전체가 Virtual Thread에서 실행된다.

`Flow<T>.async { transform }` 연산자는 `.map { }` 대신 각 요소를 병렬로 변환한다.
순차 `flow { repeat(n) { emit(...) } }` 대비 N배 처리량 향상.

### 4. Resilience4jTrait 믹스인 (problem)

`bluetape4k-resilience4j`는 Resilience4j 예외(`CallNotPermittedException`, `BulkheadFullException` 등)를
RFC 9457 Problem JSON으로 자동 변환하는 `AdviceTrait` 인터페이스를 제공한다.
`@ControllerAdvice` 클래스에 `, Resilience4jTrait`를 추가하는 것만으로 모든 CB/Bulkhead/RateLimit 예외가 처리된다.

## 테스트 결과

```
:spring-boot-cache-caffeine:test   BUILD SUCCESSFUL
:spring-boot-cache-redis:test      BUILD SUCCESSFUL
:spring-boot-problem:test          BUILD SUCCESSFUL
:spring-boot-webflux-coroutines:test BUILD SUCCESSFUL
```

## 향후 가이드

- `cache-redis`의 `RedisBinarySerializers`는 반드시 `Serializable`을 구현한 DTO에서만 작동한다 — `Country` data class에 `Serializable` 선언 확인 필수.
- `Dispatchers.VT`는 싱글톤이므로 앱 전역에서 공유해도 무방하다. 직접 `Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()`를 생성하면 리소스 누수 위험이 있다.
- `caffeine { }` DSL 내부에서 `recordStats()`를 활성화하면 Micrometer와 연동해 캐시 hit/miss 메트릭을 자동으로 수집할 수 있다.
