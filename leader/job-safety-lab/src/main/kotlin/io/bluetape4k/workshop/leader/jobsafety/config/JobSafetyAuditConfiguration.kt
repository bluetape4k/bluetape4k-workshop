package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.audit.ExportingLeaderHistorySink
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.leader.audit.LeaderAuditExporter
import io.bluetape4k.leader.audit.LeaderAuditValueSanitizer
import io.bluetape4k.leader.audit.LeaderElectionEventExportSubscription
import io.bluetape4k.leader.audit.http.HttpLeaderAuditExporter
import io.bluetape4k.leader.audit.http.LeaderAuditHttpOptions
import io.bluetape4k.leader.audit.http.LeaderAuditTrustedHttpsEndpoint
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.micrometer.audit.MicrometerLeaderAuditExporter
import io.bluetape4k.workshop.leader.jobsafety.audit.AdmissionOnlyLeaderHistorySink
import io.bluetape4k.workshop.leader.jobsafety.audit.BoundedAuditPayloadStore
import io.bluetape4k.workshop.leader.jobsafety.audit.InMemoryAuditHttpClient
import io.bluetape4k.workshop.leader.jobsafety.audit.JobSafetyAuditPayloadEncoder
import io.bluetape4k.workshop.leader.jobsafety.audit.JobSafetyAuditReportPort
import io.bluetape4k.workshop.leader.jobsafety.audit.JobSafetyAuditReportService
import io.bluetape4k.workshop.leader.jobsafety.audit.RecordingLeaderAuditPayloadEncoder
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor

/**
 * leader audit export 예제의 transport, recorder, publisher와 종료 소유권을 연결합니다.
 *
 * 이 configuration은 `MEMORY` fake를 기본으로 선택하여 startup 시 외부 DNS/socket을
 * 열지 않습니다. `HTTPS`는 properties가 검증한 trusted endpoint와 allow-listed header만
 * 사용합니다. resource bean의 implicit destroy는 꺼 두고 coordinator 하나만 close를
 * 소유하여 context 종료 순서를 재현 가능하게 유지합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobSafetyAuditProperties::class)
class JobSafetyAuditConfiguration {

    @Bean
    fun jobSafetyAuditPayloadStore(properties: JobSafetyAuditProperties): BoundedAuditPayloadStore =
        BoundedAuditPayloadStore(
            maxEntries = properties.recentHistoryLimit,
            maxBytes = properties.recentHistoryByteBudget,
        )

    @Bean
    fun jobSafetyAuditPayloadEncoder(
        properties: JobSafetyAuditProperties,
        store: BoundedAuditPayloadStore,
    ): RecordingLeaderAuditPayloadEncoder =
        RecordingLeaderAuditPayloadEncoder(
            delegate = JobSafetyAuditPayloadEncoder(properties.maxPayloadBytes),
            store = store,
        )

    @Bean(destroyMethod = "")
    fun jobSafetyAuditExecutor(): ExecutorService = VirtualThreads.executorService()

    @Bean(destroyMethod = "")
    fun jobSafetyAuditScheduler(): ScheduledThreadPoolExecutor =
        ScheduledThreadPoolExecutor(1).apply {
            removeOnCancelPolicy = true
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        }

    @Bean(destroyMethod = "")
    fun jobSafetyAuditHttpClient(
        properties: JobSafetyAuditProperties,
        @Qualifier("jobSafetyAuditExecutor") executor: ExecutorService,
    ): HttpClient = when (properties.transport) {
        AuditTransport.MEMORY -> InMemoryAuditHttpClient()
        AuditTransport.HTTPS -> HttpClient.newBuilder()
            .executor(executor)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    @Bean
    fun jobSafetyAuditHttpClientLifecycle(
        @Qualifier("jobSafetyAuditHttpClient") client: HttpClient,
    ): JobSafetyAuditHttpClientLifecycle = JobSafetyAuditHttpClientLifecycle(client)

    @Bean
    fun jobSafetyAuditExportOptions(
        properties: JobSafetyAuditProperties,
        @Qualifier("jobSafetyAuditExecutor") executor: ExecutorService,
        @Qualifier("jobSafetyAuditScheduler") scheduler: ScheduledThreadPoolExecutor,
    ): LeaderAuditExportOptions = LeaderAuditExportOptions(
        queueCapacity = properties.queueCapacity,
        maxInFlight = properties.maxInFlight,
        maxAttempts = properties.maxAttempts,
        attemptTimeout = properties.attemptTimeout,
        initialBackoff = properties.initialBackoff,
        maxBackoff = properties.maxBackoff,
        executor = executor,
        scheduler = scheduler,
    )

    @Bean(destroyMethod = "")
    fun jobSafetyAuditExporter(
        properties: JobSafetyAuditProperties,
        @Qualifier("jobSafetyAuditHttpClient") client: HttpClient,
        @Qualifier("jobSafetyAuditPayloadEncoder") encoder: RecordingLeaderAuditPayloadEncoder,
        @Qualifier("jobSafetyAuditExportOptions") exportOptions: LeaderAuditExportOptions,
        meterRegistry: MeterRegistry,
    ): MicrometerLeaderAuditExporter {
        val delegate = HttpLeaderAuditExporter(
            client = client,
            endpoint = auditEndpoint(properties),
            headers = if (properties.transport == AuditTransport.HTTPS) properties.headers.asMap() else emptyMap(),
            encoder = encoder,
            exportOptions = exportOptions,
            httpOptions = LeaderAuditHttpOptions(maxPayloadBytes = properties.maxPayloadBytes),
        )
        return MicrometerLeaderAuditExporter(delegate, meterRegistry)
    }

    @Bean
    fun jobSafetyAuditHistoryRecorder(
        @Qualifier("jobSafetyAuditExporter") exporter: LeaderAuditExporter,
    ): SafeLeaderHistoryRecorder = SafeLeaderHistoryRecorder(
        ExportingLeaderHistorySink(
            delegate = AdmissionOnlyLeaderHistorySink(),
            exporter = exporter,
            sanitizer = LeaderAuditValueSanitizer.Default,
        ),
    )

    @Bean(destroyMethod = "")
    fun jobSafetyAuditScope(): JobSafetyAuditScope = JobSafetyAuditScope()

    @Bean(destroyMethod = "")
    fun jobSafetyAuditSubscription(
        @Qualifier("jobSafetyLeaderElector") publisher: LeaderElectionEventPublisher,
        @Qualifier("jobSafetyAuditScope") scope: JobSafetyAuditScope,
        @Qualifier("jobSafetyAuditExporter") exporter: LeaderAuditExporter,
    ): LeaderElectionEventExportSubscription =
        LeaderElectionEventExportSubscription(publisher, scope, exporter)

    @Bean
    fun jobSafetyAuditReportPort(
        properties: JobSafetyAuditProperties,
        store: BoundedAuditPayloadStore,
        @Qualifier("jobSafetyAuditExporter") exporter: LeaderAuditExporter,
    ): JobSafetyAuditReportPort = JobSafetyAuditReportService(
        transport = properties.transport.name,
        enabled = true,
        payloadStore = store,
        exporter = exporter,
    )

    @Bean(destroyMethod = "close")
    fun jobSafetyAuditShutdownCoordinator(
        properties: JobSafetyAuditProperties,
        @Qualifier("jobSafetyAuditSubscription") subscription: LeaderElectionEventExportSubscription,
        @Qualifier("jobSafetyAuditExporter") exporter: LeaderAuditExporter,
        @Qualifier("jobSafetyAuditHttpClientLifecycle") clientLifecycle: JobSafetyAuditHttpClientLifecycle,
        @Qualifier("jobSafetyAuditScheduler") scheduler: ScheduledThreadPoolExecutor,
        @Qualifier("jobSafetyAuditExecutor") executor: ExecutorService,
        @Qualifier("jobSafetyAuditScope") scope: JobSafetyAuditScope,
    ): JobSafetyAuditShutdownCoordinator = JobSafetyAuditShutdownCoordinator(
        shutdownTimeout = properties.shutdownTimeout,
        subscription = subscription,
        exporter = exporter,
        clientLifecycle = clientLifecycle,
        scheduler = scheduler,
        executor = executor,
        scope = scope,
    )

    private fun auditEndpoint(properties: JobSafetyAuditProperties): LeaderAuditTrustedHttpsEndpoint =
        LeaderAuditTrustedHttpsEndpoint.trusted(
            URI(properties.endpoint ?: "https://audit.invalid/in-memory"),
        )
}
