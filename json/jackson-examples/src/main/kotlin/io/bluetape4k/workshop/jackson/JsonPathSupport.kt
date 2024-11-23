package io.bluetape4k.workshop.jackson

import com.jayway.jsonpath.DocumentContext

inline fun <reified T: Any> DocumentContext.readAs(json: String): T? =
    read(json, T::class.java)
