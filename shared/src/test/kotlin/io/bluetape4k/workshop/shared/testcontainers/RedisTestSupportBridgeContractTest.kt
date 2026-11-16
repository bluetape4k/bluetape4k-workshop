package io.bluetape4k.workshop.shared.testcontainers

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Redis helper가 수동 endpoint 등록을 반복하지 않고 upstream Spring bridge에
 * 위임하는지 source 수준에서 고정합니다.
 */
class RedisTestSupportBridgeContractTest {

    @Test
    fun `RedisTestSupport 는 upstream bridge 에 위임한다`() {
        val projectDir = System.getProperty("bluetape4k.workshop.shared.projectDir")
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.dir")).toAbsolutePath().let { userDir ->
                if (userDir.fileName?.toString() == "shared") userDir else userDir.resolve("shared")
            }
        val sourcePath = projectDir.resolve(
            "src/main/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupport.kt",
        ).normalize()
        Files.isRegularFile(sourcePath).shouldBeTrue()

        val source = Files.readString(sourcePath)
        source shouldContain "redis.registerDynamicProperties(registry)"
        source shouldNotContain "registry.add("
    }
}
