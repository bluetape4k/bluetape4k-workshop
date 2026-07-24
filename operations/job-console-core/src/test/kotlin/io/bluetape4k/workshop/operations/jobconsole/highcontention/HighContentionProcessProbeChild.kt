package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireEquals
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CountDownLatch

object HighContentionProcessProbeChild {

    @JvmStatic
    fun main(args: Array<String>) {
        args.size.requireEquals(1, "process probe child argument count")
        writeCreateNewAndForce(Path.of(args.single()), "READY\n")
        CountDownLatch(1).await()
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
}
