package io.bluetape4k.workshop.elasticsearch.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.elasticsearch.ElasticsearchApplication
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchTemplate

@SpringBootTest(
    classes = [ElasticsearchApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ElasticsearchClientConfigTest {

    companion object: KLogging()

    @Autowired
    protected val operations: ElasticsearchTemplate = uninitialized()

    @Autowired
    private val reactiveOperations: ReactiveElasticsearchTemplate = uninitialized()

    @Test
    fun `context loading`() {
        operations.shouldNotBeNull()
        reactiveOperations.shouldNotBeNull()

        log.debug { "runtime version=${operations.runtimeLibraryVersion}" }
    }
}
