package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireEquals
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CountDownLatch

/**
 * HighContentionProcessProbeChild 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
object HighContentionProcessProbeChild {

    @JvmStatic
/**
 * main은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun main(args: Array<String>) {
        args.size.requireEquals(1, "process probe child argument count")
        writeCreateNewAndForce(Path.of(args.single()), "READY\n")
        CountDownLatch(1).await()
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
