package io.bluetape4k.workshop.operations.jobconsole.application

object JobConsoleUi {
    val indexHtml: String by lazy {
        requireNotNull(javaClass.classLoader.getResourceAsStream("static/job-console/index.html")) {
            "job console UI resource is missing"
        }.bufferedReader().use { it.readText() }
    }
}
