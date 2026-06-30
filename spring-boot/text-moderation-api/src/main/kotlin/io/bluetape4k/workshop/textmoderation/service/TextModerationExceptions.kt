package io.bluetape4k.workshop.textmoderation.service

/**
 * Raised when a moderation request text exceeds the configured size limit.
 */
class PayloadTooLargeException(message: String) : IllegalArgumentException(message)
