package io.bluetape4k.workshop.cassandra

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

abstract class AbstractCassandraCoroutineTest(
    private val coroutineName: String = "cassandra4",
): AbstractCassandraTest(),
   CoroutineScope by CoroutineScope(CoroutineName(coroutineName) + Dispatchers.IO) {

    companion object: KLogging()
}
