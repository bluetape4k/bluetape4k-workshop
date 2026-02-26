package io.bluetape4k.workshop.observation.support

import io.micrometer.observation.Observation
import java.util.function.Supplier

fun <T: Any> Observation.observe(supplier: () -> T): T {
    return observe(Supplier { supplier() })
}

fun <T: Any> Observation.observeOrNull(action: () -> T): T? {
    return observe(Supplier { action() })
}

fun <T: Any> Observation.scopedOrNull(action: () -> T): T? {
    return scoped(Supplier { action() })
}
