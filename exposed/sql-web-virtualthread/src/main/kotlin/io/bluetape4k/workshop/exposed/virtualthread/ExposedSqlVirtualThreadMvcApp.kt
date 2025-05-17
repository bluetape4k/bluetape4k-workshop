package io.bluetape4k.workshop.exposed.virtualthread

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.exposed.virtualthread.domain.DatabaseInitializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ExposedSqlVirtualThreadMvcApp: ApplicationRunner {

    companion object: KLoggingChannel()

    @Autowired
    private val databaseInitializer: DatabaseInitializer = uninitialized()

    override fun run(args: ApplicationArguments) {
        // 데이터베이스 초기화 및 샘플 데이터 추가
        databaseInitializer.createSchemaAndTestData()
    }
}

fun main(vararg args: String) {
    runApplication<ExposedSqlVirtualThreadMvcApp>(*args) {
        webApplicationType = WebApplicationType.SERVLET
    }
}
