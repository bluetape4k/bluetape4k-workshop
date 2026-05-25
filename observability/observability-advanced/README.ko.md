# observability-advanced

HTTP(WebFlux), 코루틴 서비스, H2 데이터베이스(Exposed JDBC), Redis 캐시에 걸쳐
다계층 스팬 계측을 보여주는 전체 스택 Observability 워크샵 예제.

## 아키텍처

![observability advanced Architecture diagram](../../docs/images/readme-diagrams/observability-observability-advanced-architecture-01.png)

## 스팬 트리

**캐시 미스 경로:**
```
http.server.requests              (자동)
  └─ user.service.get             (수동)
       ├─ user.cache.get          (수동 — null 반환)
       ├─ user.db.find            (수동)
       └─ user.cache.put          (수동)
```

**캐시 히트 경로:**
```
http.server.requests              (자동)
  └─ user.service.get             (수동)
       └─ user.cache.get          (수동 — User 반환)
```

## 핵심 개념

| 개념 | 구현 |
|------|------|
| 다계층 스팬 | 서비스 + 캐시 계층에 `observed()` 헬퍼 적용 |
| 디스패처 경계 | `withObservation { withContext(IO) { transaction { } } }` (Observation OUTER) |
| Redis Soft-fail | catch + log.warn, DB 폴백 |
| Cache-aside 패턴 | get → miss → DB → put |
| 긍정 테스트 어설션 | `TestObservationRegistryAssert.assertThat(testRegistry).hasObservationWithNameEqualTo(...)` |
| 부정 테스트 어설션 | `TestObservationRegistryAssert.assertThat(testRegistry).hasNumberOfObservationsWithNameEqualTo(name, 0)` |

## 사용한 Bluetape4k 기능

| 기능 | 모듈 / Artifact | 코드 위치 | 이점 |
|------|-----------------|-----------|------|
| Micrometer Observation starter | `bluetape4k-micrometer` | `ObservationSupport.observed()`가 `ObservationRegistry.start(name)` 사용 | `Observation.Context`를 직접 조립하지 않고 bluetape4k Observation factory 재사용 |
| 코루틴 친화 로깅 | `bluetape4k-logging` | `UserService`, `UserRepository`, `UserCacheRepository`, 테스트 base | lazy Kotlin logging과 trace/span MDC 출력 일관성 |
| Redis/Redisson DSL | `bluetape4k-redisson`, `bluetape4k-redis` | `RedissonConfig` | `redissonClient {}` 설정 경로로 Redisson client 생성 |
| Redis Testcontainer singleton | `bluetape4k-testcontainers` | `AbstractAdvancedTest` | 임의 `GenericContainer` 대신 `RedisServer.Launcher.redis` 재사용 |
| 코루틴 테스트 runner | `bluetape4k-junit5` | `UserServiceTest`, `UserControllerTest` | 테스트 본문에서 `runBlocking` 없이 `runSuspendIO {}`로 suspend 통합 테스트 실행 |
| Assertion DSL | `bluetape4k-assertions` | `UserServiceTest`, `UserControllerTest` | JUnit assertion API 대신 Kotlin 스타일 null/value assertion 사용 |

## Before / After

### Raw Micrometer 방식

```kotlin
val observation = Observation.createNotStarted("user.service.get", registry)
observation.start()
try {
    observation.openScope().use {
        withContext(Dispatchers.IO) {
            transaction { /* DB query */ }
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    observation.error(e)
    throw e
} finally {
    observation.stop()
}
```

### Bluetape4k 지원 워크샵 방식

```kotlin
suspend fun getById(id: Long): User? =
    observed("user.service.get", observationRegistry) {
        val cached = cache.get(id)
        cached ?: observed("user.db.find", observationRegistry) {
            repo.findById(id)
        }
    }
```

`observed()`는 `bluetape4k-micrometer`의 `ObservationRegistry.start(name)` 경로를 유지하면서,
코루틴 context element로 Micrometer scope를 열고, `CancellationException`은 그대로 다시 던지며,
실제 오류만 span error로 기록하고, 항상 span을 stop합니다. 그래서 수동
`start/openScope/error/stop` 보일러플레이트 없이도 `withContext(Dispatchers.IO)` 경계에서
부모-자식 span tree가 유지됩니다.

## 테스트 커버리지

- `UserServiceTest`: 캐시 미스/히트 스팬, null 결과, 생성 스팬, 명시적 캐시 삭제로 DB 조회 강제
- `UserControllerTest`: HTTP POST 생성, GET 캐시 미스, GET 캐시 히트

캐시 미스 서비스 테스트는 parent-child propagation도 검증합니다.

```
user.service.get
  ├─ user.cache.get
  ├─ user.db.find
  └─ user.cache.put
```

## Smoke / Load 확인

### 대상 smoke

```bash
./gradlew :observability-advanced:test
./gradlew :observability-advanced:bootRun
```

사전 조건:

- 통합 테스트의 Redis Testcontainer를 위해 Docker가 필요합니다.
- Gradle이 사용할 수 있는 JDK 21+ toolchain이 필요합니다.

### 유지하는 load/performance 예제

이 모듈은 tracing/correlation 증명에 집중합니다. 부하 동작은 아래 성능 지향 모듈에 남깁니다.
해당 모듈들은 지원되는 bluetape4k runtime helper를 보여주기 때문입니다.

| 모듈 | 명령 | 중단 조건 |
|------|------|-----------|
| `gatling/virtualthread-simulation` | `./gradlew :gatling-virtualthread-simulation:gatlingRun` | Gatling assertion이 README 임계값 안에서 p95 latency와 success rate를 유지해야 함 |
| `virtualthreads/spring-mvc-tomcat` | `./gradlew :virtualthreads-spring-mvc-tomcat:gatlingRun` | 문서화된 ramp profile에서 virtual-thread request handling이 악화되지 않아야 함 |
| `virtualthreads/spring-webflux` | `./gradlew :virtualthreads-spring-webflux:gatlingRun` | dispatcher scenario가 error-rate regression 없이 끝나야 함 |

로컬 load run은 error rate가 1%를 넘거나, p95 latency가 scenario README 임계값을 넘거나,
컨테이너 CPU/메모리가 포화되거나, 애플리케이션 로그에 연결 실패가 반복되면 중단합니다.

## 의존성

- `bluetape4k-micrometer` — 로컬 `observed()` 코루틴 래퍼 (finally-safe)
- `bluetape4k-redisson` — `redissonClient {}` DSL (`io.bluetape4k.redis.redisson`)
- `micrometer-context-propagation` — 디스패처 경계 스팬 연속성
- `jetbrains-exposed-spring-boot4-starter` — Exposed 자동 구성
