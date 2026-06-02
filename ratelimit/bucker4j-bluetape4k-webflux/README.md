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

## 아키텍처 다이어그램

![bucker4j bluetape4k webflux Architecture diagram](../../docs/images/readme-diagrams/ratelimit-bucker4j-bluetape4k-webflux-diagram-01.png)

Spring Webflux 환경에서 IpAddress 가 아닌 User Token으로 Rate Limit을 적용하는 예제입니다.

참고: `UserRateLimitWebFilter` 는 Spring Webflux 환경에서 요청 정보 (`ServerHttpRequest`) 의 Header에서 `X-BLUETAPE4K-UID` 값을 추출해서
이 값을 기준의 Bucket4j의 Rate Limit을 적용합니다.

기존 `bucket4j-spring-boot-starter` 는 User 기반으로 사용하려면 Spring SpEL을 동기 방식으로 사용해야 하는데,
헤더에서 User Token 값을 추출하는데, 동기 방식만 지원해서 성능이 느려질 수 있습니다.
