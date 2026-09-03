package io.bluetape4k.workshop.shared.testcontainers

import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.testcontainers.spring.registerDynamicProperties
import org.springframework.test.context.DynamicPropertyRegistry

/**
 * Redis 예제 테스트에서 재사용하는 Testcontainers와 Spring 프로퍼티 등록 보조 객체입니다.
 *
 * RedisServer.Launcher.redis 싱글턴을 사용하며 다음 키를 등록합니다.
 * - testcontainers.redis.host
 * - testcontainers.redis.port
 * - testcontainers.redis.url
 *
 * Redis를 사용하는 소비 모듈은 이 객체를 테스트 코드에서 사용하고,
 * Spring Test, Testcontainers, 선택적 `bluetape4k-testcontainers-spring` 실행
 * 의존성은 각 모듈이 선언해야 합니다. [redis] singleton에 접근하면 기존과
 * 동일하게 Redis container가 시작될 수 있습니다.
 */
object RedisTestSupport {

    /**
     * 테스트 전체에서 재사용하는 Redis 서버입니다.
     */
    val redis: RedisServer = RedisServer.Launcher.redis

    /**
     * Redis 연결 정보를 Spring의 동적 프로퍼티 레지스트리에 등록합니다.
     *
     * upstream `registerDynamicProperties` bridge에 등록을 위임합니다. bridge는
     * supplier만 등록하며 container start/stop, readiness 대기, JVM system
     * property 변경을 수행하지 않습니다. 기존 Redis 예제의 프로퍼티 키와
     * singleton lifecycle은 유지됩니다.
     */
    fun registerRedisProperties(registry: DynamicPropertyRegistry) {
        redis.registerDynamicProperties(registry)
    }
}
