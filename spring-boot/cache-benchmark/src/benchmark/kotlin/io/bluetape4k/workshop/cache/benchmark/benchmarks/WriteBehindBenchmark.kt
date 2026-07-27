package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import io.bluetape4k.workshop.cache.benchmark.service.WriteBehindService
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import java.util.concurrent.TimeUnit

/**
 * Profile 7 — Write-Behind Cache.
 *
 * Measures Redisson WRITE_BEHIND in two separate operations:
 * - [enqueueExistingUpdate] measures cache update and queue acceptance latency.
 * - [enqueueAndWaitForDrain] measures completed persistence by waiting until
 *   the repository reflects the queued update.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
class WriteBehindBenchmark : AbstractCacheBenchmark() {
    private lateinit var service: WriteBehindService
    private lateinit var productRepository: ProductRepository
    private lateinit var targetProducts: List<Product>
    private val pendingUpdates = mutableMapOf<Long, Product>()
    private var counter = 0

    @Setup
    override fun setup() {
        super.setup()
        service = context.getBean(WriteBehindService::class.java)
        productRepository = context.getBean(ProductRepository::class.java)
        targetProducts = (101..200).map { id ->
            requireNotNull(service.findById(id.toLong())) { "Missing benchmark product id=$id" }
        }
        pendingUpdates.clear()
    }

    @Benchmark
    fun findByIdHit() = service.findById(101L)

    @Benchmark
    fun enqueueExistingUpdate(): Product =
        service.save(nextUpdate("wb-enqueue"))

    @Benchmark
    fun enqueueAndWaitForDrain(): Product {
        val updated = service.save(nextUpdate("wb-drain"))
        waitForDrain(updated)
        return updated
    }

    private fun nextUpdate(prefix: String): Product {
        val revision = counter++
        val product = targetProducts[revision % targetProducts.size]
        return product.copy(
            name = "${product.name}-$prefix-$revision",
            stock = product.stock + revision,
        ).also { pendingUpdates[it.id] = it }
    }

    private fun waitForDrain(product: Product) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val drained = productRepository.findById(product.id).orElse(null)
            if (drained?.name == product.name) return
            Thread.sleep(10)
        }
        error("Timed out waiting for write-behind drain. id=${product.id}")
    }

    override fun beforeTeardown() {
        if (pendingUpdates.isEmpty()) return

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val drained = pendingUpdates.values.all { product ->
                productRepository.findById(product.id).orElse(null)?.name == product.name
            }
            if (drained) return
            Thread.sleep(50)
        }
        error("Timed out draining ${pendingUpdates.size} write-behind benchmark keys")
    }
}
