package io.bluetape4k.workshop.operations.jobconsole.highcontention

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
class HighContentionProcessProbeTest {

    @Test
    fun `worker publishes its pid starts one descendant and blocks for parent cleanup`() {
        val probeRoot = Path.of(
            System.getProperty("highContentionProbeRoot").requireNotNull("highContentionProbeRoot"),
        ).toRealPath(NOFOLLOW_LINKS)
        val deadlineEpochMillis = System.getProperty("highContentionProbeDeadlineEpochMillis")
            .requireNotNull("highContentionProbeDeadlineEpochMillis")
            .toLong()
            .requirePositiveNumber("highContentionProbeDeadlineEpochMillis")
        val processOwner = System.getProperty("highContentionProcessOwner")
            .requireNotNull("highContentionProcessOwner")

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

    private fun awaitPollInterval() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MILLIS))
    }

    private fun appendAndForce(path: Path, content: String) {
        FileChannel.open(path, APPEND, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

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

    private companion object {
        const val POLL_INTERVAL_MILLIS = 25L
    }
}
