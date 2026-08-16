package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the admission side of Spring shutdown. Spring stops scheduled beans and
 * container-managed executors around this callback; the service gate closes
 * first so a late request cannot create a new owner while resources drain.
 */
class JobConsoleSpringLifecycle(
    private val service: JobConsoleService,
) {
    private val stopped = AtomicBoolean()

    @PreDestroy
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        service.closeAdmission()
        service.awaitSubmissionQuiescence(Duration.ofSeconds(5))
    }
}
