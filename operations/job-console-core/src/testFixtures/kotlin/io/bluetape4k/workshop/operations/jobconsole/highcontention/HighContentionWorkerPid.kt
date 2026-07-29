package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireNotBlank
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

object HighContentionWorkerPid {
    fun publishIfConfigured() {
        val configured = System.getProperty(WORKER_PID_FILE_PROPERTY) ?: return
        System.getProperty(PROCESS_OWNER_PROPERTY)
            .requireNotBlank(PROCESS_OWNER_PROPERTY)
        val path = Path.of(configured).toAbsolutePath().normalize()
        val parent = path.parent
        require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
            "high-contention worker PID parent is not trusted"
        }
        val bytes = "${ProcessHandle.current().pid()}\n".toByteArray()
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
