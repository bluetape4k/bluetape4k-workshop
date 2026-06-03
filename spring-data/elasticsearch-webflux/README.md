# Spring Data Elasticsearch Example with Spring Boot 4 and Elasticsearch 8

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Data Elasticsearch Example with Spring Boot 4 and Elasticsearch 8** as a runnable Spring Data persistence workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Spring Data Elasticsearch Example with Spring Boot 4 and Elasticsearch 8 Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-elasticsearch-webflux-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springdata` as the source of truth when comparing this README with the code.

## Sequence Diagram

![Spring Data Elasticsearch Example with Spring Boot 4 and Elasticsearch 8 sequence diagram](../../docs/images/readme-diagrams/spring-data-elasticsearch-webflux-sequence-01.png)

## Introduction

This example demonstrates how to use Spring Data Elasticsearch to do simple CRUD operations.

You can find the tutorial about this example at this
link: [Getting started with Spring Data Elasticsearch](https://www.geekyhacker.com/getting-started-with-spring-data-elasticsearch/)

For this example, we created a Book controller that allows doing the following operations with Elasticsearch:

- Get the list of all books
- Create a book
- Update a book by Id
- Delete a book by Id
- Search for a book by ISBN
- Fuzzy search for books by author and title

## References

- [Spring Data Elasticsearch - Reference Documentation](https://docs.spring.io/spring-data/elasticsearch/docs/current/reference/html/)
- [How to use Spring Data Elasticsearch NativeSearchQuery](https://juntcom.tistory.com/149)
