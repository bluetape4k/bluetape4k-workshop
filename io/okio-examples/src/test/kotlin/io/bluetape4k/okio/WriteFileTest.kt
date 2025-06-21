package okio.samples

import io.bluetape4k.junit5.tempfolder.TempFolder
import io.bluetape4k.junit5.tempfolder.TempFolderTest
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.okio.readUtf8Lines
import okio.buffer
import okio.sink
import okio.source
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test

@TempFolderTest
class WriteFileTest {

    companion object: KLogging()

    private lateinit var tempFolder: TempFolder

    @BeforeAll
    fun beforeAll(tempFolder: TempFolder) {
        this.tempFolder = tempFolder
    }

    @Test
    fun `환경변수를 파일에 씁니다`() {
        val file = tempFolder.createFile()
        file.sink().buffer().use { sink ->
            System.getenv().forEach { (key, value) ->
                sink.writeUtf8("$key=$value\n")
            }
        }

        file.source().buffer().use { source ->
            source.readUtf8Lines()
                .forEach { log.debug { it } }
        }
    }

    @Test
    fun `환경변수를 파일에 씁니다 2`() {
        val file = tempFolder.createFile()
        file.sink().buffer().use { sink ->
            System.getenv().forEach { (key, value) ->
                sink.writeUtf8("$key=$value\n")
            }
        }

        file.source().buffer().use { source ->
            source.readUtf8Lines()
                .forEach { log.debug { it } }
        }
    }
}
