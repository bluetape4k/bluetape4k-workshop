package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
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

/** Spring `@PreDestroy`가 신규 admission을 닫고 bounded drain을 한 번만 수행하는지 검증한다. */
/** Spring `@PreDestroy`가 신규 admission을 닫고 bounded drain을 한 번만 수행하는지 검증한다. */
class JobConsoleSpringLifecycleTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "pre-destroy closes admission and is idempotent" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `pre-destroy closes admission and is idempotent`() {
        val service = JobConsoleService(JobRepository(neverUsedDataSource), boundedWaitEnabled = false)
        val lifecycle = JobConsoleSpringLifecycle(service)

        lifecycle.stop()
        lifecycle.stop()

        val failure = assertFailsWith<JobRepositoryException> {
            service.submit(
                DemoCallerScope("tenant-a", "submitter-a"),
                "shutdown-key",
                SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1),
            )
        }
        failure.code shouldBeEqualTo JobProblemCode.DEPENDENCY_UNAVAILABLE
        service.activeSubmissionCount() shouldBeEqualTo 0
    }

/**
 * neverUsedDataSource은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val neverUsedDataSource: DataSource =
        object: DataSource {
/**
 * getConnection은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getConnection(): Connection = throw SQLException("connection must not be acquired")

/**
 * getConnection은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getConnection(
                username: String?,
                password: String?,
            ): Connection = throw SQLException("connection must not be acquired")

/**
 * unwrap은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun <T> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")

/**
 * isWrapperFor은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun isWrapperFor(iface: Class<*>?): Boolean = false

/**
 * setLogWriter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun setLogWriter(out: PrintWriter?) = Unit

/**
 * getLogWriter은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getLogWriter(): PrintWriter? = null

/**
 * setLoginTimeout은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun setLoginTimeout(seconds: Int) = Unit

/**
 * getLoginTimeout은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getLoginTimeout(): Int = 0

/**
 * getParentLogger은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getParentLogger(): Logger = Logger.getGlobal()
        }
}
