package io.bluetape4k.workshop.redis.cluster

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.RedisClusterConnection
import org.springframework.data.redis.connection.RedisClusterNode
import org.springframework.data.redis.connection.RedisClusterServerCommands
import org.springframework.data.redis.connection.RedisConnection

@SpringBootTest(classes = [RedisClusterApplication::class])
abstract class AbstractRedisClusterTest {

    companion object : KLoggingChannel() {
        @JvmStatic
        val faker = Fakers.faker

        @JvmStatic
        fun randomKey(): String = Fakers.fixedString(32)

        @JvmStatic
        fun randomValue(): String = Fakers.fixedString(256)
    }

    protected fun RedisConnection.flushMasterDatabases() {
        val clusterConnection = this as? RedisClusterConnection
            ?: error("Redis connection is not a cluster connection: ${this::class.qualifiedName}")
        val masters = clusterConnection.clusterCommands().clusterGetNodes()
            .filter { RedisClusterNode.Flag.MASTER in it.flags }

        check(masters.isNotEmpty()) { "Redis Cluster has no master nodes" }

        val serverCommands: RedisClusterServerCommands = clusterConnection.serverCommands()
        masters.forEach { node -> serverCommands.flushDb(node) }
    }
}
