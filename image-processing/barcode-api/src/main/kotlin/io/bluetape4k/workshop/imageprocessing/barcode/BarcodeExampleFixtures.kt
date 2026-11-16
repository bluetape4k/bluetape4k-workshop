package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull

internal enum class BarcodeExampleFixture(val resource: String) {
    SAMPLE("barcodes/qr.png"),
    NO_RESULT("barcodes/no-result.png"),
    MALFORMED("barcodes/malformed.bin"),
}

internal class BarcodeExampleFixtures internal constructor(
    resourceLoader: (String) -> ByteArray? = ::loadClasspathResource,
) {

    private companion object: KLogging()

    private val resources: Map<BarcodeExampleFixture, ByteArray> =
        BarcodeExampleFixture.entries.associateWith { fixture ->
            resourceLoader(fixture.resource).requireNotNull {
                "Required barcode example fixture is missing: ${fixture.resource}"
            }.copyOf()
        }

    fun bytes(fixture: BarcodeExampleFixture): ByteArray = resources.getValue(fixture).copyOf()
}

private fun loadClasspathResource(resource: String): ByteArray? =
    BarcodeExampleFixtures::class.java.classLoader
        .getResourceAsStream(resource)
        ?.use { it.readBytes() }
