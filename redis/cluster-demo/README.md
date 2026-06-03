# Redis Cluster Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Redis Cluster Demo** as a runnable Redis-backed coordination workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Redis Cluster Demo architecture diagram](../../docs/images/readme-diagrams/redis-cluster-demo-diagram-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.redis` as the source of truth when comparing this README with the code.

![Redis Cluster Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/redis-cluster-demo-readme-architecture-01.png)

## Flow Diagram

1. Prepare the local runtime required by `redis-cluster-demo`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

This example starts Redis Cluster automatically with `RedisClusterServer.Launcher` from `bluetape4k-testcontainers`,
then verifies Spring Data Redis Cluster Operations.

## Redis Cluster Topology

![Redis Cluster diagram](../../docs/images/readme-diagrams/redis-cluster-demo-diagram-01.png)

![Redis Cluster Demo Diagram 1](../../docs/images/readme-diagrams/redis-cluster-demo-readme-flow-01.png)

## Key Components

| Class / File | Role |
|---------------|------|
| `RedisClusterApplication.kt` | Spring Boot entry point — starts the `RedisClusterServer.Launcher.redisCluster` singleton |
| `NumberService.kt` | Stores and retrieves byte-serialized numbers through `clusterConnection` |
| `AbstractRedisClusterTest.kt` | Common base with `@SpringBootTest` + `KLoggingChannel` + `Fakers` |
| `BasicUsageTest.kt` | Basic key-value CRUD cluster test |
| `NumberServiceTest.kt` | Service-layer tests for `multiplyAndSave` / `get` |
| `application.yml` | Cluster node list + Lettuce adaptive refresh configuration |

## application.yml Configuration Example

```yaml
spring:
  data:
    redis:
      cluster:
        nodes: ${testcontainers.redis.cluster.nodes}  # Injected by Testcontainers
      lettuce:
        cluster:
          refresh:
            adaptive: true              # Automatically refresh topology on node failure
            dynamic-refresh-sources: true
        pool:
          enabled: true
          max-active: 16
          max-idle: 8
```

## Used bluetape4k Features

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| `RedisClusterServer.Launcher.redisCluster` | `bluetape4k-testcontainers` | `RedisClusterApplication` companion | Testcontainers Redis Cluster singleton that automatically starts and stops 6 nodes (3 masters + 3 replicas) |
| `Launcher.LettuceLib.clientResources(redisCluster)` | `bluetape4k-testcontainers` | `RedisClusterApplication.lettuceClientResource()` | One-step creation of Lettuce `ClientResources` matched to container ports |
| `LettuceClients` / `LettuceLongCodec` / `awaitSuspending()` | `bluetape4k-lettuce` | `Bluetape4kLettuceUsageTest` | Verifies typed codecs and coroutine-friendly async waiting on low-level Redis paths |
| `KLoggingChannel` | `bluetape4k-logging` | `RedisClusterApplication` companion, `NumberService` companion, `AbstractRedisClusterTest` companion | Structured logging with coroutine MDC context |
| `Fakers.faker` / `Fakers.fixedString` | `bluetape4k-junit5` | `AbstractRedisClusterTest` | Generates reproducible random keys and values |
| `bluetape4k-core` — `toByteArray()` / `toInt()` | `bluetape4k-core` | `NumberService` | Int-to-ByteArray conversion utilities used in binary cluster commands |

## bluetape4k Before / After

### `RedisClusterServer.Launcher` vs Manual Testcontainers Cluster Setup

```kotlin
// Before — manual Redis Cluster setup based on GenericContainer (6 containers, DynamicPropertySource)
@SpringBootTest
class RedisClusterTest {
    companion object {
        // 3 masters + 3 replicas = 6 containers managed manually
        val node1 = GenericContainer("redis:7").withExposedPorts(6379)
        val node2 = GenericContainer("redis:7").withExposedPorts(6379)
        // ... node3 through node6 omitted

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            // Manually compose each node port
            registry.add("spring.data.redis.cluster.nodes") {
                "${node1.host}:${node1.getMappedPort(6379)},..."
            }
        }
    }
}

// After — RedisClusterServer.Launcher.redisCluster singleton (one line)
@SpringBootApplication(proxyBeanMethods = false)
class RedisClusterApplication {
    companion object: KLoggingChannel() {
        @JvmStatic
        val redisCluster = RedisClusterServer.Launcher.redisCluster  // automatically starts the 6-node cluster
    }

    @Bean(destroyMethod = "shutdown")
    fun lettuceClientResource(): ClientResources =
        RedisClusterServer.Launcher.LettuceLib.clientResources(redisCluster)
}
```

### `Fakers.fixedString` — Reproducible Random Test Data

```kotlin
// Before — UUID-based (not reproducible)
val key = UUID.randomUUID().toString()
val value = RandomStringUtils.randomAlphanumeric(256)

// After — bluetape4k Fakers.fixedString (fixed seed, reproducible)
companion object: KLoggingChannel() {
    @JvmStatic
    fun randomKey(): String = Fakers.fixedString(32)

    @JvmStatic
    fun randomValue(): String = Fakers.fixedString(256)
}
```

## How NumberService Works

`NumberService` opens `clusterConnection` from `StringRedisTemplate` directly and stores numbers with binary serialization.
The hash slot is determined automatically by the key (the number's byte array), and the value is distributed to one of the master nodes in the cluster.

```kotlin
fun multiplyAndSave(number: Int) {
    operations.requiredConnectionFactory.clusterConnection.use { conn ->
        conn.stringCommands()[number.toByteArray()] = (number * 2).toByteArray()
    }
}
```

## Lettuce boundary

Spring Data Redis `clusterConnection` is responsible for proving Redis Cluster behavior.
`bluetape4k-lettuce` verifies typed codecs and the coroutine-friendly async bridge in separate low-level tests using `LettuceClients`, `LettuceLongCodec`, and `awaitSuspending()`.

## Cluster Slot Distribution

| Master Node | Slot Range | Replica |
|-------------|-----------|---------|
| Master 1 | 0-5460 | Replica 1 |
| Master 2 | 5461-10922 | Replica 2 |
| Master 3 | 10923-16383 | Replica 3 |

## Operational Notes

- **Mac AirPlay port conflict**: Redis Cluster uses ports 7000-7005 by default.
  If AirPlay Receiver mode is enabled on macOS, port 7000 may conflict.
  Disable AirPlay Receiver in System Settings, then try again.
- **Lettuce adaptive refresh**: Without `adaptive: true`, cluster topology is not refreshed automatically when a node fails, so connections may be dropped.
- **Testcontainers prerequisite**: Docker must be running. `RedisClusterServer.Launcher.redisCluster` starts containers on first access and cleans them up automatically when the JVM exits.

## Build and Test

```bash
./gradlew :redis-cluster-demo:test
```

## References

* [Spring Data Redis-Cluster Examples](https://github.com/spring-projects/spring-data-examples/tree/main/redis/cluster)
* [bluetape4k-testcontainers](https://github.com/bluetape4k/bluetape4k-projects)
* [Lettuce Cluster Topology Refresh](https://lettuce.io/core/release/reference/index.html#redis-cluster.topology-refresh)
