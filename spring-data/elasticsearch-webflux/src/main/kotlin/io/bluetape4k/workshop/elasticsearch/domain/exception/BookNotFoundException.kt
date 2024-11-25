package io.bluetape4k.workshop.elasticsearch.domain.exception

import io.bluetape4k.workshop.elasticsearch.exception.EsDemoException

open class BookNotFoundException: EsDemoException {
    constructor(): super()
    constructor(message: String): super(message)
    constructor(message: String, cause: Throwable?): super(message, cause)
    constructor(cause: Throwable?): super(cause)
}
