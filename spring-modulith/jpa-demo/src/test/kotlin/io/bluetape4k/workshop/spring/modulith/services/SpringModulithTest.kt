package io.bluetape4k.workshop.spring.modulith.services

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

class SpringModulithTest {

    companion object: KLoggingChannel()

    val modules = ApplicationModules.of(SpringModulith::class.java)

    @Test
    fun `should be compliant`() {
        modules.verify()
    }

    @Test
    fun `write documentation snippets`() {
        Documenter(modules)
            .writeModuleCanvases()
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml()
    }
}
