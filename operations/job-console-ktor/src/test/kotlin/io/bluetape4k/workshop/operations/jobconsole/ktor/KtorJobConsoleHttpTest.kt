package io.bluetape4k.workshop.operations.jobconsole.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleV1LiveContract
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KtorJobConsoleHttpTest {
    private val fixture = JobConsoleContainerFixture.shared()
    private var port: Int = 0
    private var stopServer: (() -> Unit)? = null

    @BeforeAll
    fun startServer() {
        fixture.createSchema()
        port = ServerSocket(0).use { it.localPort }
        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = fixture.jdbcUrl
                    username = fixture.databaseUsername
                    password = fixture.databasePassword
                    schema = fixture.schema
                },
            )
        val server = embeddedServer(Netty, port = port) {
            jobConsoleModule(dataSource, demoEnabled = true, boundedWaitEnabled = true)
        }
        server.start(wait = false)
        stopServer = { server.stop(500, 2_000) }
    }

    @AfterAll
    fun stopServer() {
        stopServer?.invoke()
        fixture.dropSchema()
    }

    @Test
    fun `live REST submit is completed by the owned worker lifecycle`() {
        JobConsoleV1LiveContract(URI("http://127.0.0.1:$port")).verifyOwnedWorkerLifecycle("ktor-key")
    }

    @Test
    fun `live problem request IDs use UUID version seven`() {
        JobConsoleV1LiveContract(URI("http://127.0.0.1:$port")).verifyProblemRequestIdUsesUuidV7()
    }
}
