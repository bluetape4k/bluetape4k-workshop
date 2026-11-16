package io.bluetape4k.workshop.shared.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.http.BluetapeHttpServer
import io.bluetape4k.workshop.shared.AbstractSharedTest

abstract class AbstractSpringTest: AbstractSharedTest() {

    companion object: KLogging() {
        @JvmStatic
        protected val bluetapeHttpServer by lazy { BluetapeHttpServer.Launcher.bluetapeHttpServer }

        @JvmStatic
        protected val baseUrl by lazy { bluetapeHttpServer.httpbinUrl }
    }
}
