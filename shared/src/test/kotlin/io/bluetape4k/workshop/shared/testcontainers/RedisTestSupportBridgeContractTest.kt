package io.bluetape4k.workshop.shared.testcontainers

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Redis helper가 수동 endpoint 등록을 반복하지 않고 upstream Spring bridge에
 * 위임하는지 source 수준에서 고정합니다.
 */
class RedisTestSupportBridgeContractTest {

    @org.junit.jupiter.api.Test
    fun `RedisTestSupport 는 upstream bridge 에 위임한다`() {
        val relativePath = Path.of(
            "shared/src/main/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupport.kt",
        )
        val sourcePathFromModule = Path.of(
            "src/main/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupport.kt",
        )
        val userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val candidates = buildList {
            add(relativePath)
            add(userDir.resolve(sourcePathFromModule))
            generateSequence(userDir) { it.parent }
                .map { it.resolve(relativePath) }
                .forEach(::add)
        }.distinct()
        val sourcePath = candidates.firstOrNull(Files::isRegularFile)
            ?: error("RedisTestSupport source not found; checked: $candidates")

        val source = Files.readString(sourcePath)
        source.contains("redis.registerDynamicProperties(registry)").shouldBeTrue()
        source.contains("registry.add(").shouldBeFalse()
    }
}
