# Bluetape4k Workshop Shared

[English](README.md) | 한국어

이 모듈은 workshop 예제가 사용하는 작은 HTTP client 및 integration-test helper를 제공합니다. 실행 가능한 application이 아니라, `RestClient`, `WebClient`, `WebTestClient` 호출을 간결하게 만들기 위한 shared utility dependency입니다.

## Utility Map

![Bluetape4k Workshop Shared utility map](../docs/images/readme-diagrams/shared-readme-architecture-01.png)

## Test Helper Flow

![Bluetape4k Workshop Shared WebTestClient flow](../docs/images/readme-diagrams/shared-readme-test-sequence-01.png)

## 핵심 기능

- **RestClientExtensions**: `RestClient` 확장 함수(GET, POST, PUT, PATCH, DELETE)
- **WebClientExtensions**: Reactive `WebClient` 확장 함수
- **WebTestClientExtensions**: `WebTestClient`용 테스트 확장 함수
- **AbstractSpringTest**: Spring 통합 테스트 base class
- **RedisTestSupport**: Redis Testcontainers와 Spring 동적 프로퍼티 등록 helper

## 모듈 구조

## 제공 유틸리티

### RestClientExtensions(동기 HTTP Client)

Spring `RestClient` method chain을 간결한 형태로 감싸는 확장 함수입니다.

| 함수 | HTTP Method | 설명 |
|---|---|---|
| `httpGet(uri, accept?)` | GET | 간단한 조회 |
| `httpHead(uri, accept?)` | HEAD | header-only 조회 |
| `httpPost(uri, value?, ...)` | POST | 단일 객체 전송 |
| `httpPost<T>(uri, publisher, ...)` | POST | Reactor Publisher stream 전송 |
| `httpPost<T>(uri, flow, ...)` | POST | Kotlin Flow stream 전송 |
| `httpPut(uri, value?, ...)` | PUT | 단일 객체 갱신 |
| `httpPatch(uri, value?, ...)` | PATCH | 부분 갱신 |
| `httpDelete(uri, accept?)` | DELETE | 삭제 |

### WebClientExtensions(비동기/Reactive HTTP Client)

Spring `WebClient`에 동일한 signature의 확장 함수를 제공합니다. `Publisher<T>`와 `Flow<T>` overload는 reactive 및 coroutine stream 요청을 단순화합니다.

### WebTestClientExtensions(통합 테스트용)

`httpStatus` 파라미터를 통해 내장 HTTP status assertion을 제공하는 `WebTestClient` 확장 함수입니다. `exchange()` + `expectStatus()` 호출을 한 줄로 줄입니다.

```kotlin
// Usage example
webTestClient.httpGet("/tasks/1", HttpStatus.OK)
    .expectBody<Task>().returnResult()

webTestClient.httpPost("/tasks", task, HttpStatus.CREATED)
```

### RedisTestSupport (Redis Testcontainers)

`RedisTestSupport`는 `RedisServer.Launcher.redis`를 재사용하고 다음
Spring `DynamicPropertyRegistry` 키를 등록합니다.

- `testcontainers.redis.host`
- `testcontainers.redis.port`
- `testcontainers.redis.url`

Redis 예제 테스트에서 사용하려면 소비 모듈에
`testImplementation(project(":shared"))`를 선언하세요. 소비 모듈은
Spring Test와 Testcontainers 실행 의존성도 계속 자체 선언하고, 선택적인
Spring bridge도 추가해야 합니다.

```kotlin
testImplementation(libs.bluetape4k.testcontainers.spring)
```

`registerRedisProperties`는 `bluetape4k-testcontainers-spring`의
`PropertyExportingServer.registerDynamicProperties`에 위임합니다. bridge는
세 key에 lazy supplier만 등록하며 container 시작·중지, readiness 대기, JVM
system property 기록을 수행하지 않습니다. `RedisTestSupport.redis`에
접근하면 기존과 동일하게 공유 `RedisServer.Launcher.redis` singleton으로
Redis가 시작될 수 있으므로 helper endpoint 테스트에는 Docker가 필요합니다.

~~~kotlin
import io.bluetape4k.workshop.shared.testcontainers.RedisTestSupport
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@JvmStatic
@DynamicPropertySource
fun redisProperties(registry: DynamicPropertyRegistry) {
    RedisTestSupport.registerRedisProperties(registry)
}
~~~

Docker가 필요 없는 bridge 계약은 다음과 같이 하나의 독립된 test
invocation으로 검증합니다.

```bash
./gradlew :shared:test \
  --tests '*PropertyExportingServerDynamicPropertyRegistryTest' \
  --tests '*PropertyExportingServerDynamicPropertyRegistryContextTest' \
  --tests '*RedisTestSupportBridgeContractTest'
```

`*RedisTestSupportTest`와 Redis consumer 전체 suite는 Docker가 가능한
환경에서 별도 실행하세요. `propertyKeys()`는 `Set`이므로 등록 순서는
계약이 아니며, 각 supplier는 Spring이 평가할 때 최신 `properties()` map을
조회합니다.

## 사용법

`shared` 모듈은 이 workshop 저장소 내부에서 쓰는 지원 코드입니다. 이 저장소의 예제들은 이미 Gradle로 해당 모듈을 연결합니다. 외부 프로젝트에서는 published dependency를 추가하는 대신 필요한 helper 패턴만 가져가서 사용하세요.

### AbstractSpringTest 확장

Spring WebFlux 통합 테스트 base class를 확장하면 `WebTestClient` 빈이 자동으로 주입됩니다.

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractSpringTest {
    @Autowired
    lateinit var webTestClient: WebTestClient
}

class MyControllerTest : AbstractSpringTest() {
    @Test
    fun `find tasks`() {
        webTestClient.httpGet("/tasks", HttpStatus.OK)
            .expectBodyList<Task>().hasSize(2)
    }
}
```

## 빌드

```bash
./gradlew :shared:build
./gradlew :shared:test
```
