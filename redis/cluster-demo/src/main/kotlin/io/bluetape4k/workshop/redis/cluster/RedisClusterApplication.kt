package io.bluetape4k.workshop.redis.cluster

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.storage.RedisClusterServer
import io.lettuce.core.resource.ClientResources
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication(proxyBeanMethods = false)
class RedisClusterApplication {

    companion object : KLoggingChannel() {
        // NOTE: RedisCluster uses ports 7000-7005. macOS AirPlay Receiver can conflict with these ports.
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
