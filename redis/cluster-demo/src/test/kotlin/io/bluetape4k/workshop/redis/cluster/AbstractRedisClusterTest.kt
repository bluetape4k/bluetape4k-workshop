package io.bluetape4k.workshop.redis.cluster

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.RedisClusterConnection
import org.springframework.data.redis.connection.RedisClusterNode
import org.springframework.data.redis.connection.RedisClusterServerCommands
import org.springframework.data.redis.connection.RedisConnection
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(classes = [RedisClusterApplication::class])
abstract class AbstractRedisClusterTest {

    companion object: KLoggingChannel() {
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

        await atMost 10.seconds withPollInterval 100.milliseconds untilAsserted {
            val masters = clusterConnection.clusterCommands().clusterGetNodes()
                .filter { RedisClusterNode.Flag.MASTER in it.flags }

            masters.shouldNotBeEmpty()

            val serverCommands: RedisClusterServerCommands = clusterConnection.serverCommands()
            masters.forEach { node ->
                serverCommands.info(node, "replication")
                    .getProperty("role")
                    .shouldBeEqualTo("master")
                serverCommands.flushDb(node)
            }
        }
    }
}
