# Rate Limit per user with Bucket4j in Spring Webflux

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Rate Limit per user with Bucket4j in Spring Webflux** as a runnable rate limiting workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Rate Limit per user with Bucket4j in Spring Webflux Graphviz architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.ratelimit` as the source of truth when comparing this README with the code.

![Rate Limit per user with Bucket4j in Spring Webflux architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-diagram-01.png)

## Flow Diagram

1. Prepare the local runtime required by `ratelimit-bucker4j-bluetape4k-webflux`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

## Architecture Diagram

![bucker4j bluetape4k webflux Architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-diagram-01.png)

This example applies rate limiting by user token, rather than by IP address, in a Spring WebFlux environment.

Note: In a Spring WebFlux environment, `UserRateLimitWebFilter` extracts the `X-BLUETAPE4K-UID` value from the headers of the request information (`ServerHttpRequest`),
then applies Bucket4j rate limiting based on that value.

To use the existing `bucket4j-spring-boot-starter` with a user-based key, Spring SpEL must run synchronously.
Because extracting the user token value from headers only supports the synchronous path there, performance can be slower.
