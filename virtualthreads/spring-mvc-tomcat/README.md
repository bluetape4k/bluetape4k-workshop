# Spring Boot MVC + Virtual Thread + Embedded Tomcat 예제

Spring Boot MVC 에서 Virtual Thread 를 사용하는 예제입니다.

## Virtual Thread 처리 모델

![Virtual Thread diagram](../../docs/images/readme-diagrams/virtualthreads-spring-mvc-tomcat-diagram-01.png)

## 환경 설정

[Kotlin + Spring Boot, Virtual Thread 적용하기](https://jsonobject.tistory.com/631) 를 참고하여 JDK 25를 설치한다. 현 예제는 JDK 25 를 사용합니다.

### Spring Boot 설정

Application 환경 설정

다음과 같이 Spring Boot 환경설정에서 `application.yml` 에 다음과 같이 `spring.threads.virtual.enabled=true` 를 추가한다.

```yaml
spring:
    threads:
        virtual:
            enabled: true   # Virtual Thread 사용 여부
```

Spring MVC 에서 Virtual Thread 를 사용할 때, Tomcat 을 사용하고 있다면, Tomcat 의 설정을 변경해야 한다.

```kotlin
/**
 * Tomcat ProtocolHandler의 executor를 Virtual Thread를 사용하는 Executor를 사용하도록 설정
 */
@Configuration
class TomcatConfig {

    /**
     * Tomcat ProtocolHandler의 executor 를 Virtual Thread 를 사용하는 Executor를 사용하도록 설정
     */
    @Bean
    fun protocolHandlerVirtualThreadExecutorCustomizer(): TomcatProtocolHandlerCustomizer<*> {
        return TomcatProtocolHandlerCustomizer<ProtocolHandler> { protocolHandler ->
            protocolHandler.executor = Executors.newVirtualThreadPerTaskExecutor()
        }
    }
}
```

Spring Configuration에 `@Async` 작업을 할 때, Virtual Thread 를 사용할 수 있도록 설정합니다. (참고: config/AsyncConfig.kt)

```kotlin
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {

    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    fun asyncTaskExecutor(): AsyncTaskExecutor {
        val factory = Thread.ofVirtual().name("async-vt-exec-", 0).factory()
        return TaskExecutorAdapter(Executors.newThreadPerTaskExecutor(factory)).apply {
            setTaskDecorator(LoggingTaskDecorator())
        }
    }
}
```

#### Kotlin Coroutines

Kotlin Coroutines 를 사용할 때, Virtual Thread 를 사용하고자 할 때에는 Virtual Thread를 CoroutineContext 로 사용할 수 있도록 설정합니다. (참고:
coroutines/CoroutinesSupport.kt)

```kotlin
val Dispatchers.VirtualThread: CoroutineDispatcher
    get() = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
```

## bluetape4k 활용 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `structuredTaskScopeAll` | `bluetape4k-virtualthread-api` | `VirtualThreadController.multipleTasks()` | JDK `StructuredTaskScope.ShutdownOnFailure` 보일러플레이트 제거, 예외 자동 집계 |
| `virtualFutureAll` | `bluetape4k-virtualthread-api` | `VirtualThreadController.multipleTasksWithVirtualFuture()` | `CompletableFuture.allOf` 대비 Virtual Thread 기반 병렬 실행을 한 줄로 |
| `KLoggingChannel` | `bluetape4k-logging` | `VirtualThreadController`, `AsyncConfig` | 코루틴 context-aware 로거 companion object |
| `KLogging` | `bluetape4k-logging` | `AsyncConfig` | SLF4J companion object 로거 |
| Hibernate 확장 | `bluetape4k-hibernate` | JPA Entity/Repository | Spring Boot 4 + Hibernate 6/7 자동 설정 |
| 캐시 지원 | `bluetape4k-cache-core` | 캐시 설정 | Caffeine + JCache 통합 |
| Testcontainers 래퍼 | `bluetape4k-testcontainers` | `AbstractVirtualThreadMvcTest` | MySQL 컨테이너 싱글턴 자동 시작·재사용 |

## bluetape4k Before / After

### 다수의 Virtual Thread Task 병렬 실행

```kotlin
// Before — JDK StructuredTaskScope 직접 사용
fun multipleTasks(): String {
    val taskSize = 100
    val factory = Thread.ofVirtual().name("vt-multi-", 0).factory()

    StructuredTaskScope.ShutdownOnFailure().use { scope ->
        repeat(taskSize) {
            scope.fork {
                Thread.sleep(Random.nextLong(500, 1000))
            }
        }
        scope.join().throwIfFailed()
    }
    return "Done $taskSize tasks"
}

// After — bluetape4k structuredTaskScopeAll
import io.bluetape4k.concurrent.virtualthread.structuredTaskScopeAll

fun multipleTasks(): String {
    val taskSize = 100
    structuredTaskScopeAll("multi", factory) { scope ->
        repeat(taskSize) {
            scope.fork {
                Thread.sleep(Random.nextLong(500, 1000))
                log.debug { "Task $it done. (${Thread.currentThread()})" }
            }
        }
        scope.join().throwIfFailed()
        Unit
    }
    return "Run multiple[$taskSize] tasks. (${Thread.currentThread()})"
}
```

### CompletableFuture 기반 병렬 Virtual Thread 실행

```kotlin
// Before — CompletableFuture.allOf + VirtualThread executor 수동 관리
fun multipleTasksWithFuture(): String {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val futures = List(100) { i ->
        CompletableFuture.runAsync({
            Thread.sleep(1000)
        }, executor)
    }
    CompletableFuture.allOf(*futures.toTypedArray()).get()
    executor.shutdown()
    return "Done"
}

// After — bluetape4k virtualFutureAll
import io.bluetape4k.concurrent.virtualthread.virtualFutureAll

fun multipleTasksWithVirtualFuture(): String {
    val tasks = List(100) {
        { Thread.sleep(1000) }
    }
    virtualFutureAll(tasks, executor).await()
    return "Run multiple[100] tasks. (${Thread.currentThread()})"
}
```

## 성능 측정

### Find Member by Id API

`/api/members/{id}` API 를 호출하여 Member 정보를 조회하는 API 입니다.

![gatling](doc/FindMemberById.png)

### JPA Find All Teams API

`/api/teams` API 를 호출하여 Team 정보를 조회하는 API 입니다.

![gatling](doc/JpaFindAllTeams.png)

## 참고 자료

### Spring Boot with Virtual Threads

- [Kotlin + Spring Boot, Virtual Thread 적용하기](https://jsonobject.tistory.com/631)
- [A guide to using virtual threads with Spring Boot](https://bell-sw.com/blog/a-guide-to-using-virtual-threads-with-spring-boot/)
- [Virtual Threads in Springboot 3.2](https://medium.com/nerd-for-tech/virtual-threads-in-springboot-3-2-9a7250429809?)
-

### Gatling

- [gatling/gatling-gradle-plugin-demo-kotlin](https://github.com/gatling/gatling-gradle-plugin-demo-kotlin)
- [Stress Testing with Gatling & Kotlin - Part 2](https://medium.com/@mdportnov/stress-testing-with-gatling-kotlin-part-2-1eb13d489dc9)
- [boot-vt-benchmark](https://github.com/olegonsoftware/boot-vt-benchmark)
- [Gatling Gradle Plugin](https://docs.gatling.io/reference/extensions/build-tools/gradle-plugin/)
- [Kotlin Gatling Tutorial](https://github.com/mdportnov/kotlin-gatling-tutorial)
