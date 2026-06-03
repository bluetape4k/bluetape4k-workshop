# Spring Data Elasticsearch - Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Data Elasticsearch - Demo** as a runnable Spring Data persistence workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Spring Data Elasticsearch - Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-elasticsearch-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springdata` as the source of truth when comparing this README with the code.

## Sequence Diagram

![Spring Data Elasticsearch - Demo sequence diagram](../../docs/images/readme-diagrams/spring-data-elasticsearch-sequence-01.png)

## Key Topics

- Elasticsearch document mapping with the `@Document` annotation
- `ElasticsearchRepository`-based CRUD and search methods
- Custom queries with `@Query` and Native Query usage
- REST API exposure through Spring MVC controllers

## References

- [Spring Data Elasticsearch Reference Documentation](https://docs.spring.io/spring-data/elasticsearch/reference/)
- For the WebFlux (reactive) version, see the [`spring-data/elasticsearch-webflux`](../elasticsearch-webflux) module
