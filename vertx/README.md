# Vert.x Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Vert.x Demo** as a runnable Vert.x reactive service workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Vert.x Demo architecture diagram](../docs/images/readme-diagrams/vertx-coroutines-diagram-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.vertx` as the source of truth when comparing this README with the code.

![Vert.x Demo Graphviz architecture diagram](../docs/images/readme-diagrams/vertx-readme-architecture-01.png)

## Flow Diagram

1. Prepare the local runtime required by `Vert.x Demo`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![Vert.x Demo sequence diagram](../docs/images/readme-diagrams/vertx-coroutines-sequence-01.png)

## Module Structure

![vertx Architecture diagram](../docs/images/readme-diagrams/vertx-diagram-01.png)

## References

* [Vertx Documents](https://vertx.io/docs/)
* [Vertx Lang Kotlin Coroutines](https://vertx.io/docs/vertx-lang-kotlin-coroutines/kotlin/)
