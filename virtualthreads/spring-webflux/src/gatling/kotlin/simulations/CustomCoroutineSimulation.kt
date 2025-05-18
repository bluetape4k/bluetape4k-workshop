package simulations

import io.bluetape4k.workshop.webflux.virtualthread.model.DispatcherType
import io.gatling.javaapi.core.Simulation

class CustomCoroutineSimulation: Simulation() {

    val dispatcherType = DispatcherType.Custom

    val httpProtocol = ScenarioProvider.getHttpProtocol()
    val scn = ScenarioProvider.getScenario(dispatcherType)
    val injectionStep = ScenarioProvider.getRampConcurrentUsers()

    init {
        setUp(
            scn.injectClosed(injectionStep)
        ).protocols(httpProtocol)
    }

}
