package io.bluetape4k.workshop.operations.jobconsole.performance

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleAuthorityQueries
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxTable
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** PostgreSQL fixture에서 high-contention authority query의 비용을 관찰합니다. */
/** PostgreSQL fixture에서 high-contention authority query의 비용을 관찰합니다. */
@Tag("performance")
class JobConsoleAuthorityQueriesPerformanceTest {

    @Test
/**
 * 이 시나리오는 "authority queries preserve zero violations while recording bounded size observations"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `authority queries preserve zero violations while recording bounded size observations`() {
        Files.deleteIfExists(REPORT_PATH)
        val runId = UUID.randomUUID().toString()
        val observations = DATASET_SIZES.flatMap(::observe)
        writeReport(observations, runId)

        observations.forEach { observation ->
            observation.violations shouldBeEqualTo 0L
        }
    }

/**
 * observe은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun observe(size: Int): List<QueryObservation> =
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            seed(fixture.dataSource, size)

            listOf(
                measure(
                    size = size,
                    query = "duplicateOutboxLogicalEventCount",
                    expected = 0L,
                ) { JobConsoleAuthorityQueries.duplicateOutboxLogicalEventCount(fixture.dataSource) },
                measure(
                    size = size,
                    query = "burstQueueViolationCount",
                    expected = 0L,
                ) { JobConsoleAuthorityQueries.burstQueueViolationCount(fixture.dataSource) },
            )
        }

/**
 * measure은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun measure(
        size: Int,
        query: String,
        expected: Long,
        block: () -> Long,
    ): QueryObservation {
        repeat(WARMUP_ITERATIONS) { block() shouldBeEqualTo expected }
/**
 * observedViolations은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var observedViolations = Long.MIN_VALUE
/**
 * samples은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val samples = buildList {
            repeat(MEASUREMENT_ITERATIONS) {
/**
 * result은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                var result = Long.MIN_VALUE
/**
 * elapsedNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val elapsedNanos = measureNanoTime { result = block() }
                result shouldBeEqualTo expected
                observedViolations = result
                add(elapsedNanos)
            }
        }.sorted()

        return QueryObservation(
            rows = size,
            query = query,
            violations = observedViolations,
            medianNanos = samples[samples.lastIndex / 2],
            p95Nanos = samples[p95Index(samples.size)],
            warmupIterations = WARMUP_ITERATIONS,
            measurementIterations = MEASUREMENT_ITERATIONS,
        )
    }

/**
 * seed은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun seed(dataSource: javax.sql.DataSource, size: Int) {
        val database = Database.connect(dataSource)
        try {
            transaction(database) {
                val now = Instant.parse("2026-11-19T00:00:00Z")
                repeat(size) { ordinal ->
                    val tenantId = "tenant-${ordinal % TENANT_COUNT}"
                    val enqueueSequence = (ordinal / TENANT_COUNT) + 1L
                    val jobId = UUID.randomUUID()
                    JobTable.insert { row ->
                        row[JobTable.jobId] = jobId
                        row[JobTable.tenantId] = tenantId
                        row[JobTable.submitterHash] = ordinal.toString().padStart(64, '0')
                        row[JobTable.jobType] = "document_export"
                        row[JobTable.workUnits] = 1
                        row[JobTable.failureMode] = "none"
                        row[JobTable.enqueueSequence] = enqueueSequence
                        row[JobTable.state] = "queued"
                        row[JobTable.queueVersion] = 1L
                        row[JobTable.progress] = 0
                        row[JobTable.retryBudget] = 2
                        row[JobTable.attempt] = 0
                        row[JobTable.version] = 1L
                        row[JobTable.createdAt] = now
                        row[JobTable.updatedAt] = now
                    }
                    JobOutboxTable.insert { row ->
                        row[JobOutboxTable.eventId] = UUID.randomUUID()
                        row[JobOutboxTable.jobId] = jobId
                        row[JobOutboxTable.eventType] = "JOB_UPDATED"
                        row[JobOutboxTable.resourceVersion] = 1L
                        row[JobOutboxTable.queueVersion] = 1L
                        row[JobOutboxTable.occurredAt] = now
                    }
                }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

/**
 * writeReport은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun writeReport(observations: List<QueryObservation>, runId: String) {
        val output = REPORT_PATH
        Files.createDirectories(output.parent ?: Path.of("."))
        val temporary = output.resolveSibling("${output.fileName}.tmp-$runId")
        val report = buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            appendLine("  \"productionCapacityClaim\": false,")
            appendLine("  \"runId\": ${quoteJson(runId)},")
            appendLine("  \"generatedAt\": ${quoteJson(Instant.now().toString())},")
            appendLine("  \"runtime\": {")
            appendLine("    \"javaVersion\": ${quoteJson(System.getProperty("java.version").orEmpty())},")
            appendLine("    \"osName\": ${quoteJson(System.getProperty("os.name").orEmpty())},")
            appendLine("    \"osArch\": ${quoteJson(System.getProperty("os.arch").orEmpty())}")
            appendLine("  },")
            appendLine("  \"datasetSizes\": [${DATASET_SIZES.joinToString()}],")
            appendLine("  \"observations\": [")
            observations.forEachIndexed { index, observation ->
                append("    ")
                append(observation.toJson())
                if (index != observations.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
        try {
            Files.writeString(temporary, report)
            try {
                Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, output, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

/**
 * p95Index은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun p95Index(sampleCount: Int): Int =
        (ceil(sampleCount * 0.95).toInt() - 1).coerceIn(0, sampleCount - 1)

/**
 * QueryObservation 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class QueryObservation(
/**
 * rows은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val rows: Int,
/**
 * query은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val query: String,
/**
 * violations은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val violations: Long,
/**
 * medianNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val medianNanos: Long,
/**
 * p95Nanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val p95Nanos: Long,
/**
 * warmupIterations은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val warmupIterations: Int,
/**
 * measurementIterations은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val measurementIterations: Int,
    ) {
/**
 * toJson은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun toJson(): String =
            "{\"rows\":$rows," +
                "\"query\":${quoteJson(query)},\"violations\":$violations," +
                "\"medianNanos\":$medianNanos,\"p95Nanos\":$p95Nanos," +
                "\"warmupIterations\":$warmupIterations," +
                "\"measurementIterations\":$measurementIterations}"
    }

    private companion object {
/**
 * REPORT_PATH은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val REPORT_PATH = Path.of("build/reports/job-console-authority-queries/p3-observation.json")
/**
 * DATASET_SIZES은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val DATASET_SIZES = listOf(512, 2_048, 5_000)
/**
 * TENANT_COUNT은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TENANT_COUNT = 8
/**
 * WARMUP_ITERATIONS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val WARMUP_ITERATIONS = 3
/**
 * MEASUREMENT_ITERATIONS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val MEASUREMENT_ITERATIONS = 20
    }
}

/**
 * quoteJson은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun quoteJson(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
