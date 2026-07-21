package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorage
import org.junit.jupiter.api.Test
import java.sql.Connection
import javax.sql.DataSource

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

    @Test
    fun `repository implementation is stateless and does not own a data source`() {
        val dataSourceParameters =
            JdbcVoucherPoolRepository::class.java.declaredConstructors
                .flatMap { constructor -> constructor.parameterTypes.toList() }
                .filter { parameterType -> DataSource::class.java.isAssignableFrom(parameterType) }
                .map { parameterType -> parameterType.name }

        dataSourceParameters.shouldBeEmpty()
    }

    @Test
    fun `crypto storage boundary does not expose JDBC connections`() {
        val connectionMethods =
            VoucherCryptoStorage::class.java.declaredMethods
                .filter { method -> Connection::class.java in method.parameterTypes }
                .map { method -> method.name }
                .sorted()

        connectionMethods.shouldBeEmpty()
    }
}
