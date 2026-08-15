package io.bluetape4k.workshop.operations.jobconsole.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.operations.jobconsole.api.JobProblem
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.application.BoundedJobEventFanout
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.application.JobOutboxPoller
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleUi
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigration
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigrationRunner
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.signal.LettuceCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.signal.NoOpCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.worker.DeterministicJobWorkload
import io.bluetape4k.workshop.operations.jobconsole.worker.JobWorkerEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

private val mapper = jacksonObjectMapper()

fun Application.jobConsoleModule(
    dataSource: DataSource,
    demoEnabled: Boolean = false,
    redisUri: String? = null,
) {
    jobConsoleModule(
        dataSource = dataSource,
        demoEnabled = demoEnabled,
        redisUri = redisUri,
        workerEnabled = demoEnabled,
    )
}

internal fun Application.jobConsoleModule(
    dataSource: DataSource,
    demoEnabled: Boolean,
    redisUri: String?,
    workerEnabled: Boolean,
    outboxStartGate: suspend () -> Unit = {},
    workerStartGate: suspend () -> Unit = {},
    runtimeObserver: (JobConsoleKtorRuntime) -> Unit = {},
) {
    JobMigrationRunner(
        dataSource,
        listOf(
            JobMigration.classpath("001", "db/job-console/V001__job_console.sql"),
            JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql"),
        ),
        advisoryLockKey = 520_001L,
    ).migrate()
    val repository = JobRepository(dataSource)
    val redisSignal = redisUri?.takeIf(String::isNotBlank)?.let { runCatching { LettuceCancelSignal(it) }.getOrNull() }
    val service = JobConsoleService(repository, redisSignal ?: NoOpCancelSignal)
    val fanout = BoundedJobEventFanout(Duration.ofSeconds(2))
    val outboxRepository = JobOutboxRepository(dataSource)
    val poller = JobOutboxPoller(outboxRepository, fanout)
    val workerEngine = JobWorkerEngine(repository, DeterministicJobWorkload())

    install(CallLogging)
    install(SSE)
    install(StatusPages) {
        exception<JobRepositoryException> { call, failure ->
            val status =
                when (failure.code) {
                    JobProblemCode.JOB_NOT_FOUND -> HttpStatusCode.NotFound
                    JobProblemCode.SCOPE_DENIED -> HttpStatusCode.Forbidden
                    JobProblemCode.IDEMPOTENCY_KEY_REUSED -> HttpStatusCode.Conflict
                    else -> HttpStatusCode.Conflict
                }
            call.respondJson(status, JobProblem(status.value, failure.code, status.description, Uuid.V7.nextId().toString()))
        }
        exception<IllegalArgumentException> { call, _ ->
            call.respondJson(
                HttpStatusCode.BadRequest,
                JobProblem(400, JobProblemCode.VALIDATION_FAILED, "Bad Request", Uuid.V7.nextId().toString()),
            )
        }
        exception<CancellationException> { _, failure -> throw failure }
        exception<Throwable> { call, _ ->
            call.respondJson(
                HttpStatusCode.InternalServerError,
                JobProblem(500, JobProblemCode.DEPENDENCY_UNAVAILABLE, "Internal Server Error", Uuid.V7.nextId().toString()),
            )
        }
    }

    if (demoEnabled) installJobConsoleRoutes(service, fanout)

    val outboxJob = launch(Dispatchers.IO) {
        outboxStartGate()
        while (isActive) {
            try {
                poller.pollOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Job console background cycle failed", failure)
            }
            delay(100)
        }
    }
    val workerJob =
        if (workerEnabled) {
            launch(Dispatchers.IO) {
                workerStartGate()
                while (isActive) {
                    try {
                        workerEngine.runOnce()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        environment.log.warn("Job console worker cycle failed", failure)
                    }
                    delay(50)
                }
            }
        } else {
            null
        }
    runtimeObserver(
        JobConsoleKtorRuntime(
            repository = repository,
            outboxRepository = outboxRepository,
            workerEngine = workerEngine,
            outboxJob = outboxJob,
            workerJob = workerJob,
            redisSignal = redisSignal,
        ),
    )
    monitor.subscribe(ApplicationStopped) {
        workerJob?.cancel()
        outboxJob.cancel()
        redisSignal?.close()
        (dataSource as? AutoCloseable)?.close()
    }
}

internal class JobConsoleKtorRuntime(
    val repository: JobRepository,
    val outboxRepository: JobOutboxRepository,
    val workerEngine: JobWorkerEngine,
    private val outboxJob: Job,
    private val workerJob: Job?,
    val redisSignal: LettuceCancelSignal?,
) {
    val backgroundJobsStopped: Boolean
        get() = !outboxJob.isActive && workerJob?.isActive != true
}

private fun Application.installJobConsoleRoutes(service: JobConsoleService, fanout: BoundedJobEventFanout) {
    routing {
        get("/") {
            call.respondText(JobConsoleUi.indexHtml, ContentType.Text.Html, HttpStatusCode.OK)
        }
        get("/healthz") {
            call.respondJson(HttpStatusCode.OK, mapOf("status" to "up"))
        }
        get("/readyz") {
            val readiness = withContext(Dispatchers.IO) { service.readiness() }
            call.respondJson(if (readiness.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, readiness)
        }
        route("/v1/jobs") {
            post {
                val scope = call.demoScope()
                val key = requireNotNull(call.request.headers["Idempotency-Key"]) { "Idempotency-Key is required" }
                val request = mapper.readValue(call.receiveText(), SubmitJobRequest::class.java)
                call.respondJson(HttpStatusCode.Accepted, withContext(Dispatchers.IO) { service.submit(scope, key, request) })
            }
            get("/{jobId}") {
                val jobId = UUID.fromString(requireNotNull(call.parameters["jobId"]))
                call.respondJson(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.snapshot(call.demoScope(), jobId) })
            }
            post("/{jobId}/cancel") {
                val jobId = UUID.fromString(requireNotNull(call.parameters["jobId"]))
                val scope = call.demoScope()
                withContext(Dispatchers.IO) { service.cancel(scope, jobId) }
                call.respondJson(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.snapshot(scope, jobId) })
            }
        }
        get("/v1/queues/me") {
            val cursor = call.request.headers["X-Queue-Cursor"]
            val pageSize = call.request.headers["X-Queue-Page-Size"]?.toIntOrNull() ?: 25
            call.respondJson(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.myQueue(call.demoScope(), cursor, pageSize) })
        }
        get("/v1/tenants/{tenantId}/queue") {
            val tenantId = requireNotNull(call.parameters["tenantId"])
            val scope = call.demoScope()
            if (call.request.headers["X-Demo-Operator"] != "true" || scope.tenantId != tenantId) {
                throw JobRepositoryException(JobProblemCode.SCOPE_DENIED)
            }
            val cursor = call.request.headers["X-Queue-Cursor"]
            val pageSize = call.request.headers["X-Queue-Page-Size"]?.toIntOrNull() ?: 25
            call.respondJson(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.tenantQueue(tenantId, cursor, pageSize) })
        }
        sse("/v1/jobs/{jobId}/events") {
            val jobId = UUID.fromString(requireNotNull(call.parameters["jobId"]))
            withContext(Dispatchers.IO) { service.snapshot(call.demoScope(), jobId) }
            val channel = Channel<io.bluetape4k.workshop.operations.jobconsole.api.JobEvent>(capacity = 16)
            val subscription = fanout.subscribe("ktor-${Uuid.V7.nextId().toString()}") { if (it.jobId == jobId) channel.trySend(it) }
            try {
                send(ServerSentEvent(data = "{}", event = "heartbeat"))
                while (true) {
                    val event = withTimeoutOrNull(Duration.ofSeconds(10).toMillis()) { channel.receive() }
                    if (event == null) {
                        send(ServerSentEvent(data = "{}", event = "heartbeat"))
                    } else {
                        send(
                            ServerSentEvent(
                                data = mapper.writeValueAsString(event),
                                event = event.eventType.wireValue,
                                id = event.eventId.toString(),
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                subscription.close()
                channel.close()
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.demoScope(): DemoCallerScope =
    DemoCallerScope(
        tenantId = requireNotNull(request.headers["X-Demo-Tenant"]) { "X-Demo-Tenant is required" },
        submitterHash = requireNotNull(request.headers["X-Demo-Submitter"]) { "X-Demo-Submitter is required" },
    )

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(status: HttpStatusCode, value: Any) {
    respondText(mapper.writeValueAsString(value), ContentType.Application.Json, status)
}

fun main() {
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = environment("POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/postgres")
                username = environment("POSTGRES_USERNAME", "postgres")
                password = environment("POSTGRES_PASSWORD", "postgres")
                schema = environment("POSTGRES_SCHEMA", "public")
            },
        )
    embeddedServer(Netty, port = environment("PORT", "8080").toInt()) {
        jobConsoleModule(
            dataSource,
            demoEnabled = environment("JOB_CONSOLE_DEMO", "false").toBoolean(),
            redisUri = environment("REDIS_URI", "").ifBlank { null },
        )
    }.start(wait = true)
}

private fun environment(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: System.getProperty(name.lowercase().replace('_', '.')) ?: default
