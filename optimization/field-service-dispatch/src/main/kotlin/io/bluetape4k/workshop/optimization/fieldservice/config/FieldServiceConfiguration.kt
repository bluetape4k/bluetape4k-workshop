package io.bluetape4k.workshop.optimization.fieldservice.config

import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.workshop.optimization.fieldservice.adapter.http.FieldServiceHttpService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceOutboxScheduler
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceOutboxWorker
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceReplanService
import io.bluetape4k.workshop.optimization.fieldservice.application.ReplayOutcome
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceApprovalService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceCommandService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceDispatchService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceExecutorLifecycle
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.persistence.OutboxRecord
import io.bluetape4k.workshop.optimization.fieldservice.planner.DeterministicFieldServicePlanner
import io.bluetape4k.workshop.optimization.fieldservice.planner.PlannerInput
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ExecutorService

/** demo application bean이며 CPU planner admission은 별도로 bounded하게 유지합니다. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
@EnableScheduling
internal class FieldServiceConfiguration {
    @Bean
    fun fieldServiceRepository(): FieldServiceRepository = FieldServiceRepository()

    @Bean
    fun fieldServicePlanner(): DeterministicFieldServicePlanner = DeterministicFieldServicePlanner()

    @Bean
    fun fieldServiceCommandService(repository: FieldServiceRepository): FieldServiceCommandService =
        FieldServiceCommandService(repository)

    @Bean
    fun fieldServiceApprovalService(repository: FieldServiceRepository): FieldServiceApprovalService =
        FieldServiceApprovalService(repository)

    @Bean
    fun fieldServiceDispatchService(repository: FieldServiceRepository): FieldServiceDispatchService =
        FieldServiceDispatchService(repository)

    @Bean
    fun fieldServiceHttpService(
        repository: FieldServiceRepository,
        commandService: FieldServiceCommandService,
        planner: DeterministicFieldServicePlanner,
        approvalService: FieldServiceApprovalService,
        dispatchService: FieldServiceDispatchService,
    ): FieldServiceHttpService = FieldServiceHttpService(repository, commandService, planner, approvalService, dispatchService)

    @Bean(destroyMethod = "close")
    fun fieldServiceReplanService(
        repository: FieldServiceRepository,
        planner: DeterministicFieldServicePlanner,
        fieldServiceVirtualThreadExecutor: ExecutorService,
    ): FieldServiceReplanService = FieldServiceReplanService(
        planner = planner,
        blockingExecutor = fieldServiceVirtualThreadExecutor,
        closeBlockingExecutor = false,
        snapshot = {
            transaction {
                val visits = repository.findVisits()
                val workers = repository.findWorkers()
                val planId = PlanId("field-service")
                val history = repository.listPlans(planId)
                val revision = (history.maxOfOrNull { it.planRevision } ?: -1L) + 1L
                PlannerInput(
                    workers = workers,
                    visits = visits,
                    matrix = repository.currentTravelTimeMatrix(
                        (visits.map { it.coordinateId } + workers.mapNotNull { it.homeCoordinateId }).toSet(),
                    ),
                    datasetId = DatasetId("field-service-demo"),
                    planId = planId,
                    planRevision = revision,
                    parentRevision = history.maxOfOrNull { it.planRevision },
                    requestGeneration = revision,
                )
            }
        },
    )

    @Bean
    fun fieldServiceOutboxWorker(
        repository: FieldServiceRepository,
        replanService: FieldServiceReplanService,
    ): FieldServiceOutboxWorker = FieldServiceOutboxWorker(repository = repository, handler = { record: OutboxRecord ->
        val eventType = record.payload.substringAfterLast(':')
        if (eventType in REPLAN_TRIGGER_EVENTS) {
            val plan = replanService.await(replanService.requestReplan(AggregateId("field-service")))
            if (plan == null) {
                ReplayOutcome.RETRYABLE
            } else {
                transaction { repository.savePlan(plan) }
                ReplayOutcome.COMPLETED
            }
        } else {
            ReplayOutcome.COMPLETED
        }
    })

    @Bean
    fun fieldServiceOutboxScheduler(worker: FieldServiceOutboxWorker): FieldServiceOutboxScheduler =
        FieldServiceOutboxScheduler(worker)

    @Bean(destroyMethod = "")
    fun fieldServiceVirtualThreadExecutor(): ExecutorService = VirtualThreads.executorService()

    @Bean
    fun fieldServiceExecutorLifecycle(fieldServiceVirtualThreadExecutor: ExecutorService): FieldServiceExecutorLifecycle =
        FieldServiceExecutorLifecycle(fieldServiceVirtualThreadExecutor)

    private companion object {
        val REPLAN_TRIGGER_EVENTS = setOf(
            "VISIT_CREATED",
            "VISIT_CANCELLED",
            "VISIT_URGENT",
            "VISIT_PINNED",
            "VISIT_UNPINNED",
            "VISIT_NO_SHOW",
            "WORKER_UNAVAILABLE",
            "TRAVEL_TIME_UPDATED",
        )
    }
}
