package io.bluetape4k.workshop.virtualthread.tomcat

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.virtualthread.tomcat.domain.DatabaseInitializer
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.repository.config.BootstrapMode
import org.springframework.transaction.annotation.EnableTransactionManagement

@SpringBootApplication(proxyBeanMethods = false)
@EnableJpaRepositories(
    basePackageClasses = [DatabaseInitializer::class],
    bootstrapMode = BootstrapMode.DEFERRED,
)
@EntityScan(basePackageClasses = [DatabaseInitializer::class])
@EnableTransactionManagement
class VirtualThreadMvcApp(
    private val databaseInitializer: DatabaseInitializer,
) : ApplicationRunner {

    companion object : KLogging()

    override fun run(args: ApplicationArguments) {
        databaseInitializer.insertSampleData()
    }
}

fun main(vararg args: String) {
    runApplication<VirtualThreadMvcApp>(*args)
}
