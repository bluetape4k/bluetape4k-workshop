package io.bluetape4k.workshop.leader.jobsafety.coordination.redis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

internal class JobFencingScriptsTest {
    @Test
    fun `scripts keep the counter durable and bind renew and release to owner plus fence`() {
        JobFencingScripts.acquire.source.contains("INCR").shouldBeTrue()
        JobFencingScripts.acquire.source.contains("PX").shouldBeTrue()
        JobFencingScripts.renew.source.contains("PEXPIRE").shouldBeTrue()
        JobFencingScripts.release.source.contains("DEL', KEYS[1]").shouldBeTrue()
        JobFencingScripts.release.source.contains("DEL', KEYS[2]").shouldBeFalse()
    }

    @Test
    fun `all scripts expose stable Redis SHA1 identifiers`() {
        listOf(JobFencingScripts.acquire, JobFencingScripts.renew, JobFencingScripts.release)
            .forEach { script ->
                script.sha1.length shouldBeEqualTo 40
                HEX_SHA1.matches(script.sha1).shouldBeTrue()
            }
    }

    companion object {
        private val HEX_SHA1 = Regex("[0-9a-f]{40}")
    }
}
