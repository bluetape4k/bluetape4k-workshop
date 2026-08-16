package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

class JobConsoleSpringLifecycleTest {

    @Test
    fun `pre-destroy closes admission and is idempotent`() {
        val service = JobConsoleService(JobRepository(neverUsedDataSource), boundedWaitEnabled = false)
        val lifecycle = JobConsoleSpringLifecycle(service)

        lifecycle.stop()
        lifecycle.stop()

        val failure =
            assertFailsWith<JobRepositoryException> {
                service.submit(
                    DemoCallerScope("tenant-a", "submitter-a"),
                    "shutdown-key",
                    SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1),
                )
            }
        failure.code shouldBeEqualTo JobProblemCode.DEPENDENCY_UNAVAILABLE
        service.activeSubmissionCount() shouldBeEqualTo 0
    }

    private val neverUsedDataSource: DataSource =
        object : DataSource {
            override fun getConnection(): Connection = throw SQLException("connection must not be acquired")

            override fun getConnection(
                username: String?,
                password: String?,
            ): Connection = throw SQLException("connection must not be acquired")

            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")

            override fun isWrapperFor(iface: Class<*>?): Boolean = false

            override fun setLogWriter(out: PrintWriter?) = Unit

            override fun getLogWriter(): PrintWriter? = null

            override fun setLoginTimeout(seconds: Int) = Unit

            override fun getLoginTimeout(): Int = 0

            override fun getParentLogger(): Logger = Logger.getGlobal()
        }
}
