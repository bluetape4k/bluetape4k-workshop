package io.bluetape4k.workshop.elasticsearch.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.elasticsearch.ElasticsearchApplication.Companion.esServer
import org.springframework.context.annotation.Configuration
import org.springframework.data.elasticsearch.client.ClientConfiguration
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration
import org.springframework.data.elasticsearch.config.EnableReactiveElasticsearchAuditing
import org.springframework.data.elasticsearch.repository.config.EnableReactiveElasticsearchRepositories

@Configuration
@EnableReactiveElasticsearchRepositories
@EnableReactiveElasticsearchAuditing
class ElasticsearchClientConfig: ElasticsearchConfiguration() {

    companion object: KLogging()

    override fun clientConfiguration(): ClientConfiguration {
        // log.info { "Create Elasticsearch client configuration. username=elastic, password=${esServer.password}" }

        return ClientConfiguration.builder()
            .connectedTo(esServer.url)
            // .usingSsl(esServer.createSslContextFromCa())
            .withBasicAuth("elastic", "changeme")
            .build()
    }
}
