package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.support.requireNotNull
import io.bluetape4k.utils.Resourcex

object JobConsoleUi {
    val indexHtml: String by lazy {
        Resourcex.getInputStream("static/job-console/index.html")
            .requireNotNull { "job console UI resource is missing" }
            .bufferedReader()
            .use { it.readText() }
    }
}
