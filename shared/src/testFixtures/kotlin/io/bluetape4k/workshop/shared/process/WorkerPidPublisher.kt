package io.bluetape4k.workshop.shared.process

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** 고경합 자식 프로세스가 owner 검증 후 PID를 한 번만 handoff하는 공통 test fixture입니다. */
object WorkerPidPublisher {

    /** 설정된 handoff 경로에 현재 프로세스 PID를 원자적으로 기록합니다. */
    fun publishIfConfigured() {
        val configured = System.getProperty(WORKER_PID_FILE_PROPERTY) ?: return
        val processOwner = System.getProperty(PROCESS_OWNER_PROPERTY)
        require(!processOwner.isNullOrBlank()) {
            "$PROCESS_OWNER_PROPERTY must not be blank"
        }

        val path = Path.of(configured).toAbsolutePath().normalize()
        val parent = path.parent
        require(
            parent != null &&
                Files.isDirectory(parent, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(parent),
        ) {
            "high-contention worker PID parent is not trusted"
        }

        val bytes = "${ProcessHandle.current().pid()}\n".toByteArray(Charsets.UTF_8)
        FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        try {
            FileChannel.open(parent, READ).use { it.force(true) }
        } catch (_: UnsupportedOperationException) {
            // Directory fsync is not supported by every file provider.
        }
    }

    private const val WORKER_PID_FILE_PROPERTY = "highContentionWorkerPidFile"
    private const val PROCESS_OWNER_PROPERTY = "highContentionProcessOwner"
}
