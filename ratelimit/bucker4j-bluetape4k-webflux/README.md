# Rate Limit per user with Bucket4j in Spring Webflux

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Rate Limit per user with Bucket4j in Spring Webflux** as a runnable rate limiting workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Rate Limit per user with Bucket4j in Spring Webflux Graphviz architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.ratelimit` as the source of truth when comparing this README with the code.
