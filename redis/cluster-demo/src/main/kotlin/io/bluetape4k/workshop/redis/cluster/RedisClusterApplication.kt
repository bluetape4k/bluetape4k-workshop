package io.bluetape4k.workshop.redis.cluster

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.storage.RedisClusterServer
import io.lettuce.core.resource.ClientResources
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class RedisClusterApplication {

    companion object: KLoggingChannel() {
        // NOTE: RedisCluster 는 7000 ~ 7005 포트를 사용합니다. Mac의 AirPlay 모드를 사용하면 포트 충돌이 발생할 수 있습니다.
        @JvmStatic
        val redisCluster = RedisClusterServer.Launcher.redisCluster
    }

    @Bean(destroyMethod = "shutdown")
    fun lettuceClientResource(): ClientResources {
        return RedisClusterServer.Launcher.LettuceLib.clientResources(redisCluster)
    }
}

fun main(vararg args: String) {
    runApplication<RedisClusterApplication>(*args)
}
