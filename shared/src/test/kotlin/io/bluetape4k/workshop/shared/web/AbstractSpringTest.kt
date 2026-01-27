package io.bluetape4k.workshop.shared.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.http.HttpbinServer
import io.bluetape4k.workshop.shared.AbstractSharedTest

abstract class AbstractSpringTest: AbstractSharedTest() {

    companion object: KLogging() {
        @JvmStatic
        protected val httpbin by lazy { HttpbinServer.Launcher.httpbin }

        @JvmStatic
        protected val baseUrl by lazy { httpbin.url }
    }
}
