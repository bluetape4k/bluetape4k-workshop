package io.bluetape4k.workshop.flow.race.fallback

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.coroutines.flow.extensions.FlowEvent
import io.bluetape4k.junit5.coroutines.runSuspendTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class RaceFallbackCatalogTest {

    private val catalog = RaceFallbackCatalog()

    private fun result(
        source: CatalogSource,
        sku: String = "sku-100",
        name: String = "blue tape",
        latencyMs: Long = 0,
        quality: SourceQuality = SourceQuality.FRESH,
        attributes: Map<String, String> = emptyMap(),
    ): SourceResult = SourceResult(
        source = source,
        item = CatalogItem(sku, name, 1299, attributes),
        latencyMs = latencyMs,
        quality = quality,
    )

    @Test
    fun `race selects fastest emitting source and cancels losers`() = runSuspendTest {
        val slowCancelled = AtomicBoolean(false)
        val cache = catalog.source(result(CatalogSource.CACHE, latencyMs = 200), onCancel = { slowCancelled.set(true) })
        val replica = catalog.source(result(CatalogSource.REPLICA, latencyMs = 20))
        val remote = catalog.source(result(CatalogSource.REMOTE_API, latencyMs = 120), onCancel = { slowCancelled.set(true) })

        val winner = catalog.fastestHealthy(listOf(cache, replica, remote)).take(1).toList()

        winner.map { it.source } shouldBeEqualTo listOf(CatalogSource.REPLICA)
        slowCancelled.get() shouldBeEqualTo true
    }

    @Test
    fun `source delay must be zero or positive`() = runSuspendTest {
        assertFailsWith<IllegalArgumentException> {
            catalog.source(result(CatalogSource.CACHE), delayMs = -1)
        }
    }

    @Test
    fun `source composition requires at least one source`() = runSuspendTest {
        assertFailsWith<IllegalArgumentException> { catalog.fastestHealthy(emptyList()) }
        assertFailsWith<IllegalArgumentException> { catalog.orderedFallback(emptyList()) }
        assertFailsWith<IllegalArgumentException> { catalog.eagerFallback() }
        assertFailsWith<IllegalArgumentException> { catalog.mergeContributions() }
    }

    @Test
    fun `ordered fallback preserves source order even when later source is faster`() = runSuspendTest {
        val cache = catalog.source(result(CatalogSource.CACHE, latencyMs = 40, quality = SourceQuality.STALE))
        val backup = catalog.source(result(CatalogSource.BACKUP_API, latencyMs = 1))

        val ordered = catalog.orderedFallback(listOf(cache, backup)).toList()

        ordered.map { it.source } shouldBeEqualTo listOf(CatalogSource.CACHE, CatalogSource.BACKUP_API)
    }

    @Test
    fun `eager fallback starts later sources early but emits in source order`() = runSuspendTest {
        val backupStarted = CompletableDeferred<Unit>()
        val cache = catalog.source(result(CatalogSource.CACHE, latencyMs = 80, quality = SourceQuality.STALE))
        val backup = catalog.source(
            result = result(CatalogSource.BACKUP_API, latencyMs = 1),
            onStart = { backupStarted.complete(Unit) },
        )

        val collection = async { catalog.eagerFallback(cache, backup).toList() }
        withTimeout(1_000) { backupStarted.await() }

        backupStarted.isCompleted shouldBeEqualTo true
        collection.await().map { it.source } shouldBeEqualTo listOf(CatalogSource.CACHE, CatalogSource.BACKUP_API)
    }

    @Test
    fun `concatMapEager starts mapped sources eagerly and preserves outer order`() = runSuspendTest {
        val remoteStarted = CompletableDeferred<Unit>()
        val sources = flowOf(CatalogSource.CACHE, CatalogSource.REMOTE_API)

        val collection = async {
            catalog.eagerFallbackBySource(sources) { source ->
                when (source) {
                    CatalogSource.CACHE -> catalog.source(result(source, latencyMs = 80, quality = SourceQuality.STALE))
                    CatalogSource.REMOTE_API -> catalog.source(
                        result = result(source, latencyMs = 1),
                        onStart = { remoteStarted.complete(Unit) },
                    )
                    else -> catalog.source(result(source))
                }
            }.toList()
        }
        withTimeout(1_000) { remoteStarted.await() }

        remoteStarted.isCompleted shouldBeEqualTo true
        collection.await().map { it.source } shouldBeEqualTo listOf(CatalogSource.CACHE, CatalogSource.REMOTE_API)
    }

    @Test
    fun `merge collects partial contributions from every source`() = runSuspendTest {
        val cache = catalog.source(result(CatalogSource.CACHE, attributes = mapOf("price" to "cached"), latencyMs = 40, quality = SourceQuality.PARTIAL))
        val replica = catalog.source(result(CatalogSource.REPLICA, attributes = mapOf("stock" to "replica"), latencyMs = 10, quality = SourceQuality.PARTIAL))
        val remote = catalog.source(result(CatalogSource.REMOTE_API, attributes = mapOf("description" to "remote"), latencyMs = 20, quality = SourceQuality.PARTIAL))

        val merged = catalog.mergeContributions(cache, replica, remote).toList()

        merged.map { it.source }.toSet() shouldBeEqualTo setOf(CatalogSource.CACHE, CatalogSource.REPLICA, CatalogSource.REMOTE_API)
        merged.flatMap { it.item.attributes.keys }.toSet() shouldBeEqualTo setOf("price", "stock", "description")
    }

    @Test
    fun `materialize turns terminal errors into values`() = runSuspendTest {
        val boom = IllegalStateException("remote catalog failed")
        val remote = flow<SourceResult> {
            emit(result(CatalogSource.REMOTE_API))
            throw boom
        }

        val events = catalog.materialized(remote).toList()

        events[0].shouldBeInstanceOf<FlowEvent.Value<SourceResult>>()
        events[1].shouldBeInstanceOf<FlowEvent.Error>()
        (events[1] as FlowEvent.Error).error shouldBeEqualTo boom
    }

    @Test
    fun `dematerialize restores error as terminal failure`() = runSuspendTest {
        val boom = IllegalArgumentException("bad fallback payload")
        val events = flowOf<FlowEvent<SourceResult>>(
            FlowEvent.Value(result(CatalogSource.CACHE)),
            FlowEvent.Error(boom),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            catalog.dematerialized(events).toList()
        }

        failure shouldBeEqualTo boom
    }
}
