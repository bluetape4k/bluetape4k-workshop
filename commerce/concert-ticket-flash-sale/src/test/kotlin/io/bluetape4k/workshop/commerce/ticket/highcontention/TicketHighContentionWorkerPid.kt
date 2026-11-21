package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8Bytes
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/**
 * TicketHighContentionWorkerPid 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal object TicketHighContentionWorkerPid {

/**
 * publishIfConfigured은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun publishIfConfigured() {
        val configured = System.getProperty(WORKER_PID_FILE_PROPERTY) ?: return
        System.getProperty(PROCESS_OWNER_PROPERTY).requireNotBlank(PROCESS_OWNER_PROPERTY)

        val path = Path.of(configured).toAbsolutePath().normalize()
        val parent = path.parent

        require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
            "high-contention worker PID parent is not trusted"
        }

        val bytes = "${ProcessHandle.current().pid()}\n".toUtf8Bytes()
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
            // directory fsync는 모든 file provider가 지원하지 않습니다.
        }
    }

/**
 * WORKER_PID_FILE_PROPERTY은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val WORKER_PID_FILE_PROPERTY = "highContentionWorkerPidFile"
/**
 * PROCESS_OWNER_PROPERTY은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val PROCESS_OWNER_PROPERTY = "highContentionProcessOwner"
}
