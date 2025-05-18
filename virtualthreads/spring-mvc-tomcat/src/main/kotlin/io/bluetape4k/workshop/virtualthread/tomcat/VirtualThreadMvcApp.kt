package io.bluetape4k.workshop.virtualthread.tomcat

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.virtualthread.tomcat.domain.DatabaseInitializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.repository.config.BootstrapMode
import org.springframework.transaction.annotation.EnableTransactionManagement

@SpringBootApplication
@EnableJpaRepositories(
    basePackageClasses = [DatabaseInitializer::class],
    bootstrapMode = BootstrapMode.DEFERRED
)
@EntityScan(basePackageClasses = [DatabaseInitializer::class])
@EnableTransactionManagement
class VirtualThreadMvcApp: ApplicationRunner {

    companion object: KLogging()

    @Autowired
    private val databaseInitializer: DatabaseInitializer = uninitialized()

    override fun run(args: ApplicationArguments) {
        databaseInitializer.insertSampleData()
    }
}

fun main(vararg args: String) {
    runApplication<VirtualThreadMvcApp>(*args) {
        webApplicationType = WebApplicationType.SERVLET
    }
}
