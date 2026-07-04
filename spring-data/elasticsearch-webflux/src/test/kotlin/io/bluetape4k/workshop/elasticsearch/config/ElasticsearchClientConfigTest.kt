package io.bluetape4k.workshop.elasticsearch.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.elasticsearch.ElasticsearchApplication
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchTemplate

@Disabled("Elasticsearch Client 가 Jackson2 를 사용합니다. Spring Boot 4 는 Jackson 3를 사용해서 충돌이 발생합니다.")
@SpringBootTest(
    classes = [ElasticsearchApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ElasticsearchClientConfigTest {

    companion object : KLogging()

    @Autowired
    private var elasticsearchOperations: ElasticsearchTemplate? = null

    private val operations: ElasticsearchTemplate
        get() = checkNotNull(elasticsearchOperations) { "elasticsearchOperations is not injected." }

    @Autowired
    private var reactiveElasticsearchOperations: ReactiveElasticsearchTemplate? = null

    private val reactiveOperations: ReactiveElasticsearchTemplate
        get() = checkNotNull(reactiveElasticsearchOperations) { "reactiveElasticsearchOperations is not injected." }

    @Test
    fun `context loading`() {
        operations.shouldNotBeNull()
        reactiveOperations.shouldNotBeNull()

        log.debug { "runtime version=${operations.runtimeLibraryVersion}" }
    }
}
