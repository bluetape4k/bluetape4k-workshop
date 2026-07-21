package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.assertions.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.sql.Connection

internal class VoucherPoolRepositoryContractTest {
    @Test
    fun `repository boundary does not expose JDBC connections`() {
        val connectionMethods =
            VoucherPoolRepository::class.java.declaredMethods
                .filter { method -> Connection::class.java in method.parameterTypes }
                .map { method -> method.name }
                .sorted()

        connectionMethods.shouldBeEmpty()
    }
}
