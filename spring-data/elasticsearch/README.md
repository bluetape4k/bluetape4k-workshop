# Spring Data Elasticsearch - Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Data Elasticsearch - Demo** as a runnable Spring Data persistence workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Spring Data Elasticsearch - Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-elasticsearch-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springdata` as the source of truth when comparing this README with the code.

![Spring Data Elasticsearch - Demo architecture diagram](../../docs/images/readme-diagrams/spring-data-elasticsearch-diagram-01.png)

## Flow Diagram

1. Prepare the local runtime required by `spring-data-elasticsearch`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![Spring Data Elasticsearch - Demo sequence diagram](../../docs/images/readme-diagrams/spring-data-elasticsearch-sequence-01.png)

## Architecture Diagram

![elasticsearch Class Structure diagram](../../docs/images/readme-diagrams/spring-data-elasticsearch-diagram-01.png)

![elasticsearch Sequence Flow 2 diagram](../../docs/images/readme-diagrams/spring-data-elasticsearch-sequence-01.png)

This is a synchronous (MVC) example that uses Spring Data Elasticsearch.
Testcontainers automatically starts an Elasticsearch container for integration tests.

## Key Topics

- Elasticsearch document mapping with the `@Document` annotation
- `ElasticsearchRepository`-based CRUD and search methods
- Custom queries with `@Query` and Native Query usage
- REST API exposure through Spring MVC controllers

## References

- [Spring Data Elasticsearch Reference Documentation](https://docs.spring.io/spring-data/elasticsearch/reference/)
- For the WebFlux (reactive) version, see the [`spring-data/elasticsearch-webflux`](../elasticsearch-webflux) module
