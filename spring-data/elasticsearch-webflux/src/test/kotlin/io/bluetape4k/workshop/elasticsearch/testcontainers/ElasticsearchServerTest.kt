package io.bluetape4k.workshop.elasticsearch.testcontainers

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.ElasticsearchOssServer
import io.bluetape4k.testcontainers.storage.ElasticsearchServer
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.data.elasticsearch.client.elc.rest5_client.Rest5Clients

@Execution(ExecutionMode.SAME_THREAD)
class ElasticsearchServerTest {

    companion object: KLogging()


    @Test
    fun `launch elasticsearch`() {
        ElasticsearchOssServer().use { es ->
            es.start()
            es.isRunning.shouldBeTrue()
        }
    }

    @Test
    fun `launch elastic search with ssl`() {
        ElasticsearchServer(password = "wow-world").use { es ->
            es.start()
            es.isRunning.shouldBeTrue()

            val config = ElasticsearchServer.Launcher.getClientConfiguration(es)

            val client = Rest5Clients.getRest5Client(config)

            println((client.httpClient as CloseableHttpAsyncClient).status)
            // client.isRunning.shouldBeTrue()
        }
    }

    @Test
    fun `launch elasticsearch with default port`() {
        ElasticsearchOssServer(useDefaultPort = true).use { es ->
            es.start()
            es.isRunning.shouldBeTrue()
            es.port shouldBeEqualTo ElasticsearchServer.PORT
        }
    }
}
