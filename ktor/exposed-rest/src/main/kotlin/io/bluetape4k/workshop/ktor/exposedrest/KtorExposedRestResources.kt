package io.bluetape4k.workshop.ktor.exposedrest

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.Executors

internal class KtorExposedRestResources private constructor(
    private val dataSource: HikariDataSource,
    val jdbcDatabase: Database,
    val jdbcDispatcher: ExecutorCoroutineDispatcher,
): AutoCloseable {

    override fun close() {
        runCatching { dataSource.close() }
        runCatching { jdbcDispatcher.close() }
    }

    companion object {
        fun create(
            jdbcUrl: String,
            username: String,
            password: String,
            driverClassName: String = "org.postgresql.Driver",
            poolName: String = "ktor-exposed-rest",
        ): KtorExposedRestResources {
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    this.username = username
                    this.password = password
                    this.driverClassName = driverClassName
                    maximumPoolSize = 3
                    minimumIdle = 1
                    this.poolName = poolName
                }
            )
            val resources = KtorExposedRestResources(
                dataSource = dataSource,
                jdbcDatabase = Database.connect(dataSource),
                jdbcDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher(),
            )
            initializeBookSchema(resources)
            return resources
        }
    }
}
