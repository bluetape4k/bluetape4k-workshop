package io.bluetape4k.workshop.flow.search.pipeline

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.junit5.coroutines.runSuspendTest
import java.io.Serializable
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchPipelineTest {

    private fun settings(
        tenantId: String = "tenant-a",
        mode: SearchMode = SearchMode.PREFIX,
        resultLimit: Int = 5,
        featureFlags: Set<String> = setOf("prefix-rank"),
    ): SearchSettings = SearchSettings(
        tenantId = tenantId,
        locale = Locale.US,
        mode = mode,
        featureFlags = featureFlags,
        resultLimit = resultLimit,
    )

    @Test
    fun `buffering debounce emits latest query from a typing burst`() = runSuspendTest {
        val adapter = RecordingSearchAdapter()
        val pipeline = SearchPipeline(adapter)
        val queries = flow {
            emit("r")
            emit("re")
            emit("red")
        }

        val results = pipeline.search(queries, flowOf(settings()), flowOf(), 100.milliseconds).toList()

        results shouldHaveSize 1
        results.single().request.query.text shouldBeEqualTo "red"
        adapter.requests.map { it.query.text } shouldBeEqualTo listOf("red")
    }

    @Test
    fun `large burst still starts one search`() = runSuspendTest {
        val adapter = RecordingSearchAdapter()
        val pipeline = SearchPipeline(adapter)
        val queries = flow {
            repeat(1_000) { emit("red-$it") }
        }

        val results = pipeline.search(queries, flowOf(settings()), flowOf(), 100.milliseconds).toList()

        results shouldHaveSize 1
        adapter.requests shouldHaveSize 1
        adapter.requests.single().query.text shouldBeEqualTo "red-999"
    }

    @Test
    fun `latest settings are joined with each debounced query`() = runSuspendTest {
        val adapter = RecordingSearchAdapter()
        val pipeline = SearchPipeline(adapter)
        val settings = MutableStateFlow(settings(tenantId = "tenant-a", mode = SearchMode.PREFIX))

        val first = pipeline.search(flowOf("red"), settings, flowOf(), 10.milliseconds).take(1).toList()
        settings.value = settings(tenantId = "tenant-b", mode = SearchMode.EXACT)
        val second = pipeline.search(flowOf("redis"), settings, flowOf(), 10.milliseconds).take(1).toList()

        first.single().request.settings.tenantId shouldBeEqualTo "tenant-a"
        second.single().request.settings.tenantId shouldBeEqualTo "tenant-b"
        second.single().request.settings.mode shouldBeEqualTo SearchMode.EXACT
    }

    @Test
    fun `query before settings is dropped permanently and next query uses settings snapshot`() = runSuspendTest {
        val adapter = RecordingSearchAdapter()
        val pipeline = SearchPipeline(adapter)
        val queries = MutableSharedFlow<String>()
        val settings = MutableSharedFlow<SearchSettings>()

        val collection = async {
            pipeline.search(queries, settings, flowOf(), 20.milliseconds)
                .take(1)
                .toList()
        }

        queries.emit("lost")
        delay(30)
        adapter.requests.shouldBeEmpty()

        settings.emit(settings(tenantId = "tenant-ready"))
        delay(1)
        adapter.requests.shouldBeEmpty()

        queries.emit("red")
        delay(30)

        val results = collection.await()
        results.single().request.query.text shouldBeEqualTo "red"
        results.single().request.settings.tenantId shouldBeEqualTo "tenant-ready"
        adapter.requests.map { it.query.text } shouldBeEqualTo listOf("red")
    }

    @Test
    fun `settings flow failure propagates downstream`() = runSuspendTest {
        val boom = IllegalStateException("settings unavailable")
        val pipeline = SearchPipeline(RecordingSearchAdapter())
        val settings = flow<SearchSettings> { throw boom }

        val failure = assertFailsWith<IllegalStateException> {
            pipeline.search(flowOf("red"), settings, flowOf(), 10.milliseconds).toList()
        }

        failure.message shouldBeEqualTo boom.message
    }

    @Test
    fun `newer query cancels stale in-flight search`() = runSuspendTest {
        val firstCancelled = AtomicBoolean(false)
        val adapter = RecordingSearchAdapter(
            delayByQuery = mapOf("red" to 1_000, "redis" to 0),
            onCancel = { request ->
                if (request.query.text == "red") {
                    firstCancelled.set(true)
                }
            },
        )
        val pipeline = SearchPipeline(adapter)
        val queries = flow {
            emit("red")
            delay(50)
            emit("redis")
        }

        val results = pipeline.search(queries, flowOf(settings()), flowOf(), 10.milliseconds).toList()

        results.map { it.request.query.text } shouldBeEqualTo listOf("redis")
        firstCancelled.get().shouldBeTrue()
    }

    @Test
    fun `cancelled stale request cannot emit or fail downstream later`() = runSuspendTest {
        val firstCanContinue = CompletableDeferred<Unit>()
        val firstCancelled = AtomicBoolean(false)
        val adapter = RecordingSearchAdapter(
            delayByQuery = mapOf("red" to Long.MAX_VALUE, "redis" to 0),
            failAfterCancelGate = firstCanContinue,
            onCancel = { request ->
                if (request.query.text == "red") {
                    firstCancelled.set(true)
                }
            },
        )
        val pipeline = SearchPipeline(adapter)
        val queries = flow {
            emit("red")
            delay(50)
            emit("redis")
        }

        val results = pipeline.search(queries, flowOf(settings()), flowOf(), 10.milliseconds).toList()
        firstCanContinue.complete(Unit)

        results.map { it.request.query.text } shouldBeEqualTo listOf("redis")
        firstCancelled.get().shouldBeTrue()
    }

    @Test
    fun `session close cancels in-flight search`() = runSuspendTest {
        val cancelled = AtomicBoolean(false)
        val sessionClosed = MutableSharedFlow<Unit>()
        val adapter = RecordingSearchAdapter(
            delayByQuery = mapOf("red" to 1_000),
            onCancel = { cancelled.set(true) },
        )
        val pipeline = SearchPipeline(adapter)

        val collection = async {
            pipeline.search(flowOf("red"), flowOf(settings()), sessionClosed, 10.milliseconds).toList()
        }

        delay(20)
        sessionClosed.emit(Unit)
        delay(1)

        collection.await().shouldBeEmpty()
        cancelled.get().shouldBeTrue()
    }

    @Test
    fun `session close source is collected once and shared across stop observers`() = runSuspendTest {
        val subscriptions = AtomicInteger(0)
        val close = MutableSharedFlow<Unit>()
        val sessionClosed = flow {
            subscriptions.incrementAndGet()
            close.collect { emit(it) }
        }
        val pipeline = SearchPipeline(RecordingSearchAdapter(delayByQuery = mapOf("red" to 1_000)))

        val collection = async {
            pipeline.search(flowOf("red"), flowOf(settings()), sessionClosed, 10.milliseconds).toList()
        }

        delay(20)
        close.emit(Unit)
        delay(1)

        collection.await().shouldBeEmpty()
        subscriptions.get() shouldBeEqualTo 1
    }

    @Test
    fun `collector cancellation cleans up active search and stop observer`() = runSuspendTest {
        val searchCancelled = AtomicBoolean(false)
        val adapter = RecordingSearchAdapter(
            delayByQuery = mapOf("red" to 1_000),
            onCancel = { searchCancelled.set(true) },
        )
        val pipeline = SearchPipeline(adapter)
        val sessionClosed = MutableSharedFlow<Unit>()

        val job = launch {
            pipeline.search(flowOf("red"), flowOf(settings()), sessionClosed, 10.milliseconds).toList()
        }

        delay(20)
        job.cancelAndJoin()

        searchCancelled.get().shouldBeTrue()
    }

    @Test
    fun `upstream failure is propagated after buffered query handling`() = runSuspendTest {
        val boom = IllegalStateException("query source failed")
        val adapter = RecordingSearchAdapter()
        val pipeline = SearchPipeline(adapter)
        val queries = flow {
            emit("red")
            delay(20)
            throw boom
        }

        val failure = assertFailsWith<IllegalStateException> {
            pipeline.search(queries, flowOf(settings()), flowOf(), 10.milliseconds).toList()
        }

        failure.message shouldBeEqualTo boom.message
        adapter.requests.map { it.query.text } shouldBeEqualTo listOf("red")
    }

    @Test
    fun `blank input is ignored before search starts`() = runSuspendTest {
        val adapter = RecordingSearchAdapter()
        val pipeline = SearchPipeline(adapter)

        val results = pipeline.search(flowOf("", "   ", "red"), flowOf(settings()), flowOf(), 10.milliseconds).toList()

        results shouldHaveSize 1
        adapter.requests.map { it.query.text } shouldBeEqualTo listOf("red")
    }

    @Test
    fun `domain values reject invalid query settings and limits`() = runSuspendTest {
        assertFailsWith<IllegalArgumentException> { SearchQuery("   ") }
        assertFailsWith<IllegalArgumentException> { SearchQuery("x".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { settings(tenantId = "   ") }
        assertFailsWith<IllegalArgumentException> { settings(resultLimit = 0) }
        assertFailsWith<IllegalArgumentException> { settings(resultLimit = 21) }
        assertFailsWith<IllegalArgumentException> { settings(featureFlags = setOf("BadFlag")) }
    }

    @Test
    fun `domain construction stores trimmed query and tenant values`() = runSuspendTest {
        SearchQuery("  red  ").text shouldBeEqualTo "red"
        settings(tenantId = "  tenant-a  ").tenantId shouldBeEqualTo "tenant-a"
    }

    @Test
    fun `settings expose unmodifiable feature flags`() = runSuspendTest {
        val source = mutableSetOf("prefix-rank")
        val settings = settings(featureFlags = source)

        source += "extra-rank"

        settings.featureFlags shouldBeEqualTo setOf("prefix-rank")
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (settings.featureFlags as MutableSet<String>).add("late-rank")
        }
    }

    @Test
    fun `debounce duration must be positive`() = runSuspendTest {
        val pipeline = SearchPipeline(RecordingSearchAdapter())

        assertFailsWith<IllegalArgumentException> {
            pipeline.search(flowOf("red"), flowOf(settings()), flowOf(), 0.milliseconds).toList()
        }
    }

    @Test
    fun `regex looking query is treated as literal text`() = runSuspendTest {
        val adapter = RecordingSearchAdapter()
        val pipeline = SearchPipeline(adapter)

        val results = pipeline.search(flowOf(".*"), flowOf(settings(mode = SearchMode.EXACT)), flowOf(), 10.milliseconds).toList()

        results.single().hits.shouldBeEmpty()
        adapter.requests.single().query.text shouldBeEqualTo ".*"
    }

    @Test
    fun `debug string rendering hides query tenant flags and hit titles`() = runSuspendTest {
        val result = SearchResult(
            request = SearchRequest(
                query = SearchQuery("secret-query"),
                settings = settings(tenantId = "secret-tenant", featureFlags = setOf("secret-flag")),
            ),
            hits = listOf(SearchHit("hit-secret", "Secret Title", 100)),
            source = "secret-source",
        )

        val rendered = result.toString() + result.hits.single().toString()

        rendered.shouldContain("<redacted>")
        rendered.contains("secret-query").shouldBeFalse()
        rendered.contains("secret-tenant").shouldBeFalse()
        rendered.contains("secret-flag").shouldBeFalse()
        rendered.contains("Secret Title").shouldBeFalse()
        rendered.contains("secret-source").shouldBeFalse()
    }

    @Test
    fun `result limit caps hit materialization before returning hits`() = runSuspendTest {
        val adapter = RecordingSearchAdapter()
        val pipeline = SearchPipeline(adapter)

        val results = pipeline.search(
            queries = flowOf("re"),
            settings = flowOf(settings(resultLimit = 2)),
            sessionClosed = flowOf(),
            debounce = 10.milliseconds,
        ).toList()

        results.single().hits shouldHaveSize 2
        adapter.materializedHits.get() shouldBeEqualTo 2
    }

    private class RecordingSearchAdapter(
        private val delayByQuery: Map<String, Long> = emptyMap(),
        private val failForQueries: Set<String> = emptySet(),
        private val failAfterCancelGate: CompletableDeferred<Unit>? = null,
        private val onCancel: (SearchRequest) -> Unit = {},
    ): SearchAdapter {

        val requests: MutableList<SearchRequest> = Collections.synchronizedList(mutableListOf())
        val materializedHits = AtomicInteger(0)

        override suspend fun search(request: SearchRequest): SearchResult {
            requests += request

            try {
                delayByQuery[request.query.text]?.let { delay(it) }
                if (request.query.text in failForQueries) {
                    throw IllegalStateException("search failed for ${request.query.text}")
                }

                val hits = catalog.asSequence()
                    .filter { hit -> hit.matches(request) }
                    .take(request.settings.resultLimit)
                    .map { hit ->
                        materializedHits.incrementAndGet()
                        SearchHit(hit.id, hit.title, hit.score(request))
                    }
                    .toList()

                return SearchResult(request, hits, "test-catalog")
            } catch (e: CancellationException) {
                onCancel(request)
                failAfterCancelGate?.await()
                throw e
            }
        }

        private fun CatalogEntry.matches(request: SearchRequest): Boolean =
            when (request.settings.mode) {
                SearchMode.PREFIX -> title.lowercase(request.settings.locale)
                    .startsWith(request.query.text.lowercase(request.settings.locale))
                SearchMode.FUZZY -> title.lowercase(request.settings.locale)
                    .contains(request.query.text.lowercase(request.settings.locale))
                SearchMode.EXACT -> title.equals(request.query.text, ignoreCase = true)
            }

        private fun CatalogEntry.score(request: SearchRequest): Int =
            baseScore + request.settings.featureFlags.size

        private data class CatalogEntry(
            val id: String,
            val title: String,
            val baseScore: Int,
        ): Serializable {
            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        private companion object {
            val catalog = listOf(
                CatalogEntry("redis", "redis", 100),
                CatalogEntry("redisson", "redisson", 90),
                CatalogEntry("reactor", "reactor", 80),
                CatalogEntry("resilience", "resilience", 70),
            )
        }
    }
}
