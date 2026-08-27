package io.bluetape4k.workshop.r2dbc

import io.bluetape4k.assertions.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.nio.file.Path

class R2dbcTestActivationContractTest {

    @Test
    fun `PostgreSQL repository and controller tests are not disabled`() {
        val disabledTests = TEST_FILES
            .filter { path -> repositoryRoot().resolve(path).toFile().readText().contains("@Disabled") }

        disabledTests.shouldBeEmpty()
    }

    private fun repositoryRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().parent.parent

    private companion object {
        val TEST_FILES = listOf(
            "spring-data/r2dbc-coroutines/src/test/kotlin/io/bluetape4k/workshop/r2dbc/AbstractR2dbcApplicationTest.kt",
            "spring-data/r2dbc-coroutines/src/test/kotlin/io/bluetape4k/workshop/r2dbc/domain/MemberRepositoryTest.kt",
            "spring-data/r2dbc-coroutines/src/test/kotlin/io/bluetape4k/workshop/r2dbc/domain/PostRepositoryTest.kt",
            "spring-data/r2dbc-coroutines/src/test/kotlin/io/bluetape4k/workshop/r2dbc/domain/CommentRepositoryTest.kt",
            "spring-data/r2dbc-coroutines/src/test/kotlin/io/bluetape4k/workshop/r2dbc/controllers/PostControllerTest.kt",
        )
    }
}
