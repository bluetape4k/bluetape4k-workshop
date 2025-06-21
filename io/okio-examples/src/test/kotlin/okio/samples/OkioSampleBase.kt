package okio.samples

import io.bluetape4k.logging.KLogging
import net.datafaker.Faker
import java.util.*

abstract class OkioSampleBase {

    companion object: KLogging() {
        @JvmStatic
        private val faker = Faker(Locale.getDefault())
    }
}
