package io.bluetape4k.workshop.shared.testcontainers

import io.bluetape4k.testcontainers.storage.RedisServer
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
 * Spring Test와 Testcontainers 실행 의존성은 각 모듈이 선언해야 합니다.
 */
object RedisTestSupport {

    /**
     * 테스트 전체에서 재사용하는 Redis 서버입니다.
     */
    val redis: RedisServer = RedisServer.Launcher.redis

    /**
     * Redis 연결 정보를 Spring의 동적 프로퍼티 레지스트리에 등록합니다.
     *
     * 기존 Redis 예제의 프로퍼티 키와 supplier 평가 시점을 유지합니다.
     */
    fun registerRedisProperties(registry: DynamicPropertyRegistry) {
        registry.add("testcontainers.redis.host") { redis.host }
        registry.add("testcontainers.redis.port") { redis.port }
        registry.add("testcontainers.redis.url") { redis.url }
    }
}
