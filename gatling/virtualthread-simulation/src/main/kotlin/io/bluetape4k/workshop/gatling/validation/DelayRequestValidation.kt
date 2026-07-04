package io.bluetape4k.workshop.gatling.validation

import io.bluetape4k.support.requireInRange

internal const val MIN_DELAY_SECONDS = 1
internal const val MAX_DELAY_SECONDS = 10

internal fun Int.requireValidDelaySeconds(): Int =
    requireInRange(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS, "seconds")
