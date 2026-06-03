# AWS Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **AWS Demo** as a runnable AWS integration workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![AWS Demo Graphviz architecture diagram](../docs/images/readme-diagrams/aws-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.aws` as the source of truth when comparing this README with the code.

![AWS Demo architecture diagram](../docs/images/readme-diagrams/aws-storage-abstraction-readme-architecture-01.png)

## Flow Diagram

1. Prepare the local runtime required by `AWS Demo`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

![AWS Demo flow diagram](../docs/images/readme-diagrams/aws-diagram-01.png)

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

![AWS Demo sequence diagram](../docs/images/readme-diagrams/aws-s3-spring-cloud-sequence-01.png)

Provides an example using AWS Java SDK V2 and [Spring Cloud AWS](https://github.com/awspring/spring-cloud-aws)).

![AWS Demo diagram](../docs/images/readme-diagrams/aws-diagram-01.png)

## Module structure

| module | directory | explanation |
|------|----------|------|
| S3 Spring Cloud | `s3-spring-cloud/` | Spring Cloud AWS + AWS SDK v2-based S3 bucket creation/file upload/download example |

## Prerequisites

| item | explanation |
|------|------|
| Docker | The Docker daemon must be running because Testcontainers automatically starts LocalStack containers. |
| AWS Credentials | Local testing uses the LocalStack emulator, so no actual AWS credentials are required |
| Java 25 | Use `--enable-preview` flag, requires Java 25 or higher |
| Kotlin 2.x | Includes multi-platform compatible Kotlin coroutine-based code |

## Core library

| library | Version/Role |
|-----------|----------|
| `software.amazon.awssdk:s3` | AWS SDK v2 — S3 Low-Level API |
| `io.awspring.cloud:spring-cloud-aws-starter-s3` | Spring Cloud AWS — `S3Template` High-level abstraction |
| `io.bluetape4k:bluetape4k-testcontainers` | `LocalStackServer` — LocalStack wrapper based on Testcontainers |
| `io.bluetape4k:bluetape4k-aws` | bluetape4k AWS extension functions such as `staticCredentialsProviderOf`, `createBucket`, etc. |

## Build and Test

```bash
# AWS
./gradlew :aws:s3-spring-cloud:build

# AWS
./gradlew :aws:s3-spring-cloud:test
```
