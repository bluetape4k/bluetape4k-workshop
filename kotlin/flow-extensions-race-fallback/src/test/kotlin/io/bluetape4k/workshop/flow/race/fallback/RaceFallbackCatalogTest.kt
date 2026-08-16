package io.bluetape4k.workshop.flow.race.fallback

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.coroutines.flow.extensions.FlowEvent
import io.bluetape4k.junit5.coroutines.runSuspendTest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
    fun `bounded eager mapping preserves outer order and caps active inner flows`() = runSuspendTest {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val secondInnerStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val collection = async {
            catalog.boundedEagerFallbackBySource(
                sources = flowOf(
                    CatalogSource.CACHE,
                    CatalogSource.REPLICA,
                    CatalogSource.REMOTE_API,
                    CatalogSource.BACKUP_API,
                ),
                maxConcurrency = 2,
                bufferCapacity = 1,
            ) { source ->
                flow {
                    val current = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, current) }
                    if (current == 2) {
                        secondInnerStarted.complete(Unit)
                    }
                    try {
                        emit(result(source))
                        release.await()
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }.toList()
        }

        withTimeout(1_000) { secondInnerStarted.await() }
        peak.get() shouldBeLessOrEqualTo 2
        release.complete(Unit)

        collection.await().map { it.source } shouldBeEqualTo listOf(
            CatalogSource.CACHE,
            CatalogSource.REPLICA,
            CatalogSource.REMOTE_API,
            CatalogSource.BACKUP_API,
        )
        active.get() shouldBeEqualTo 0
    }

    @Test
    fun `bounded eager mapping suspends an inner producer at its buffer boundary`() = runSuspendTest {
        val firstInnerRelease = CompletableDeferred<Unit>()
        val secondFirstEmission = CompletableDeferred<Unit>()
        val secondSecondEmission = CompletableDeferred<Unit>()

        val collection = async {
            catalog.boundedEagerFallbackBySource(
                sources = flowOf(CatalogSource.CACHE, CatalogSource.REMOTE_API),
                maxConcurrency = 2,
                bufferCapacity = 1,
            ) { source ->
                if (source == CatalogSource.CACHE) {
                    flow {
                        firstInnerRelease.await()
                        emit(result(source))
                    }
                } else {
                    flow {
                        emit(result(source, sku = "sku-200"))
                        secondFirstEmission.complete(Unit)
                        emit(result(source, sku = "sku-201"))
                        secondSecondEmission.complete(Unit)
                    }
                }
            }.toList()
        }

        withTimeout(1_000) { secondFirstEmission.await() }
        secondSecondEmission.isCompleted shouldBeEqualTo false

        firstInnerRelease.complete(Unit)
        collection.await().map { it.item.sku } shouldBeEqualTo listOf("sku-100", "sku-200", "sku-201")
        secondSecondEmission.isCompleted shouldBeEqualTo true
    }

    @Test
    fun `bounded eager cancellation stops all inner flows`() = runSuspendTest {
        val cancelled = AtomicInteger(0)

        catalog.boundedEagerFallbackBySource(
            sources = flowOf(CatalogSource.CACHE, CatalogSource.REPLICA, CatalogSource.REMOTE_API),
            maxConcurrency = 2,
            bufferCapacity = 1,
        ) { source ->
            flow {
                try {
                    emit(result(source))
                    awaitCancellation()
                } finally {
                    cancelled.incrementAndGet()
                }
            }
        }.take(1).toList()

        cancelled.get() shouldBeGreaterThan 0
    }

    @Test
    fun `bounded eager arguments and inner failures keep their contracts`() = runSuspendTest {
        assertFailsWith<IllegalArgumentException> {
            catalog.boundedEagerFallbackBySource(flowOf(CatalogSource.CACHE), maxConcurrency = 0) { flowOf(result(it)) }
                .toList()
        }
        assertFailsWith<IllegalArgumentException> {
            catalog.boundedEagerFallbackBySource(
                flowOf(CatalogSource.CACHE),
                maxConcurrency = 1,
                bufferCapacity = -1,
            ) { flowOf(result(it)) }.toList()
        }

        val boom = IllegalStateException("bounded inner failed")
        val failure = assertFailsWith<IllegalStateException> {
            catalog.boundedEagerFallbackBySource(flowOf(CatalogSource.REMOTE_API), maxConcurrency = 2) {
                flow { throw boom }
            }.toList()
        }
        failure.message shouldBeEqualTo boom.message
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
