package io.bluetape4k.okio

import okio.BufferedSource

fun BufferedSource.readUtf8Lines(): Sequence<String> = sequence {
    while (true) {
        val line = readUtf8Line() ?: break
        yield(line)
    }
}
