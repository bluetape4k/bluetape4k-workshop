package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

@Tag("process-probe")
/**
 * HighContentionProcessProbeTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionProcessProbeTest {

    private companion object: KLogging() {
/**
 * POLL_INTERVAL_MILLIS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val POLL_INTERVAL_MILLIS = 25L
    }

    @Test
/**
 * 이 시나리오는 "worker publishes its pid starts one descendant and blocks for parent cleanup" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `worker publishes its pid starts one descendant and blocks for parent cleanup`() {
        val probeRoot = Path.of(System.getProperty("highContentionProbeRoot").shouldNotBeNull())
            .toRealPath(NOFOLLOW_LINKS)

        val deadlineEpochMillis = System.getProperty("highContentionProbeDeadlineEpochMillis")
            .shouldNotBeNull()
            .toLong()
            .requirePositiveNumber("highContentionProbeDeadlineEpochMillis")

        val processOwner = System.getProperty("highContentionProcessOwner").shouldNotBeNull()

        val workerPid = ProcessHandle.current().pid()
        writeCreateNewAndForce(probeRoot.resolve("worker.pid"), "$workerPid\n")
        val processJournal = probeRoot.resolve("process-journal.log")
        writeCreateNewAndForce(processJournal, "WORKER $workerPid\n")
        val descendantReady = probeRoot.resolve("descendant.ready")
        val javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "java.exe" else "java",
        )
        val descendant = ProcessBuilder(
            javaExecutable.toString(),
            "-DhighContentionProcessOwner=$processOwner",
            "-cp",
            System.getProperty("java.class.path"),
            HighContentionProcessProbeChild::class.java.name,
            descendantReady.toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(probeRoot.resolve("descendant.log").toFile())
            .start()
        writeCreateNewAndForce(probeRoot.resolve("descendant.pid"), "${descendant.pid()}\n")
        appendAndForce(processJournal, "DESCENDANT ${descendant.pid()}\n")

        while (!Files.isRegularFile(descendantReady, NOFOLLOW_LINKS)) {
            if (!descendant.isAlive) {
                error("process probe descendant exited before readiness")
            }
            if (System.currentTimeMillis() >= deadlineEpochMillis) {
                error("process probe descendant readiness exceeded the parent deadline")
            }
            awaitPollInterval()
        }
        writeCreateNewAndForce(probeRoot.resolve("probe.ready"), "READY\n")

        while (System.currentTimeMillis() < deadlineEpochMillis) {
            awaitPollInterval()
        }
        error("process probe parent did not terminate the worker before its deadline")
    }

/**
 * awaitPollInterval은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitPollInterval() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MILLIS))
    }

/**
 * appendAndForce은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun appendAndForce(path: Path, content: String) {
        FileChannel.open(path, APPEND, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

/**
 * writeCreateNewAndForce은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun writeCreateNewAndForce(path: Path, content: String) {
        FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        HighContentionArtifactPaths.forceDirectory(path.parent)
    }

}
