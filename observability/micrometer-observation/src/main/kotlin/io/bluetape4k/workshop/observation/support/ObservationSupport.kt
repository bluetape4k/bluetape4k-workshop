package io.bluetape4k.workshop.observation.support

import io.micrometer.observation.Observation
import java.util.function.Supplier

inline fun <T: Any> Observation.observe(crossinline supplier: () -> T): T {
    return observe(Supplier { supplier() })
}

inline fun <T: Any> Observation.observeOrNull(crossinline action: () -> T): T? {
    return observe(Supplier { action() })
}

inline fun <T: Any> Observation.scopedOrNull(crossinline action: () -> T): T? {
    return scoped(Supplier { action() })
}
