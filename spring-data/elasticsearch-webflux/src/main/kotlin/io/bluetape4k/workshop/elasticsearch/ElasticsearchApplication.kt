package io.bluetape4k.workshop.elasticsearch

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.testcontainers.storage.ElasticsearchOssServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.elasticsearch.core.ElasticsearchOperations

@SpringBootApplication

class ElasticsearchApplication {

    companion object: KLogging() {
        // val esServer = ElasticsearchServer.Launcher.elasticsearch
        val esServer = ElasticsearchOssServer.Launcher.elasticsearchOssServer
    }

    @Autowired
    private val operations: ElasticsearchOperations = uninitialized()
}

fun main(vararg args: String) {
    runApplication<ElasticsearchApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
