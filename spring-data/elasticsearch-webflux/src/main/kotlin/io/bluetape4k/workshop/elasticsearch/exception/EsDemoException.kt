package io.bluetape4k.workshop.elasticsearch.exception

/**
 * Elasticsearch Demo Application 의 기본 Exception 클래스
 */
open class EsDemoException: RuntimeException {
    constructor(): super()
    constructor(message: String): super(message)
    constructor(message: String, cause: Throwable?): super(message, cause)
    constructor(cause: Throwable?): super(cause)
}
