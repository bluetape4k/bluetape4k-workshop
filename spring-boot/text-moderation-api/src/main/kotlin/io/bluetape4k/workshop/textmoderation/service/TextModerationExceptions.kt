package io.bluetape4k.workshop.textmoderation.service

/**
 * moderation request text 가 설정된 size limit 을 넘을 때 발생합니다.
 */
class PayloadTooLargeException(message: String) : IllegalArgumentException(message)
