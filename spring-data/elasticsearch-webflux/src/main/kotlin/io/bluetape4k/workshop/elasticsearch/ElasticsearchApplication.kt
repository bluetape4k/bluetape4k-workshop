package io.bluetape4k.workshop.elasticsearch

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.ElasticsearchOssServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class ElasticsearchApplication {

    companion object : KLogging() {
        // 필요하면 기본 Elasticsearch 서버 런처로 교체할 수 있습니다.
        // val esServer = ElasticsearchServer.Launcher.elasticsearch
        val esServer = ElasticsearchOssServer.Launcher.elasticsearchOssServer
    }
}

fun main(vararg args: String) {
    runApplication<ElasticsearchApplication>(*args)
}
