# AWS Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **AWS Demo**를 실행 가능한 AWS 통합 워크샵 조각으로 다룹니다. 개발자가 가장 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리/프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![AWS Demo Graphviz architecture diagram](../docs/images/readme-diagrams/aws-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README를 코드와 비교할 때는 `io.bluetape4k.workshop.aws` 패키지를 기준으로 삼습니다.

## 시퀀스 다이어그램

![AWS Demo sequence diagram](../docs/images/readme-diagrams/aws-s3-spring-cloud-sequence-01.png)

AWS Java SDK V2와 [Spring Cloud AWS](https://github.com/awspring/spring-cloud-aws))를 사용하는 예제를 제공합니다.

## 모듈 구조

| module | directory | explanation |
|------|----------|------|
| S3 Spring Cloud | `s3-spring-cloud/` | Spring Cloud AWS + AWS SDK v2 기반 S3 버킷 생성/파일 업로드/다운로드 예제 |

## 전제 조건

| item | explanation |
|------|------|
| Docker | Testcontainers가 LocalStack 컨테이너를 자동으로 시작하므로 Docker 데몬이 실행 중이어야 합니다. |
| AWS Credentials | 로컬 테스트는 LocalStack 에뮬레이터를 사용하므로 실제 AWS 자격 증명이 필요하지 않습니다. |
| Java 25 | `--enable-preview` 플래그를 사용하며 Java 25 이상이 필요합니다. |
| Kotlin 2.x | 멀티플랫폼 호환 Kotlin 코루틴 기반 코드를 포함합니다. |

## 핵심 라이브러리

| library | Version/Role |
|-----------|----------|
| `software.amazon.awssdk:s3` | AWS SDK v2 — S3 Low-Level API |
| `io.awspring.cloud:spring-cloud-aws-starter-s3` | Spring Cloud AWS — `S3Template` 고수준 추상화 |
| `io.bluetape4k:bluetape4k-testcontainers` | `LocalStackServer` — Testcontainers 기반 LocalStack 래퍼 |
| `io.bluetape4k:bluetape4k-aws` | `staticCredentialsProviderOf`, `createBucket` 등 bluetape4k AWS 확장 함수 |

## 빌드 및 테스트

```bash
# AWS
./gradlew :aws:s3-spring-cloud:build

# AWS
./gradlew :aws:s3-spring-cloud:test
```
