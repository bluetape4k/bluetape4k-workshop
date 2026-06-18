# AWS Workshop

[English](README.md) | 한국어

AWS 워크샵은 로컬에서 검증할 수 있는 S3 예제 두 가지를 제공합니다. Spring Cloud AWS와
`S3Template` 흐름을 작게 실행해 보고 싶다면 `s3-spring-cloud/`를 사용합니다. 로컬 파일,
S3, pre-signed S3 URL 사이를 Spring profile로 전환하는 서비스 경계를 보고 싶다면
`storage-abstraction/`을 사용합니다.

## 아키텍처

![AWS Workshop architecture diagram](../docs/images/readme-diagrams/aws-readme-architecture-01.png)

두 모듈 모두 테스트와 샘플에서 로컬 AWS 호환 인프라를 사용하므로 실제 AWS 자격 증명 없이
S3 동작을 확인할 수 있습니다.

## 모듈 가이드

| module | Gradle task path | use it for |
| --- | --- | --- |
| `s3-spring-cloud/` | `:aws-s3-spring-cloud` | Spring Cloud AWS `S3Template`, AWS SDK v2 `S3Client`, 버킷 생성, 객체 업로드, 객체 목록 조회, `ResourceLoader` 접근을 확인합니다. |
| `storage-abstraction/` | `:aws-storage-abstraction` | `local`, `s3`, `s3-presigned` profile을 가진 `StorageService`, coroutine 친화적인 blocking I/O 경계, pre-signed URL 동작을 확인합니다. |

## 런타임 모델

| concern | implementation |
| --- | --- |
| AWS endpoint | 샘플 또는 테스트가 `bluetape4k-testcontainers`의 로컬 AWS 호환 에뮬레이터를 시작합니다. |
| Credentials | 에뮬레이터가 제공하는 정적 로컬 자격 증명을 사용하며, 로컬 검증에는 실제 AWS 자격 증명이 필요하지 않습니다. |
| S3 client | AWS SDK v2 `S3Client`를 사용합니다. storage abstraction 모듈은 blocking 호출을 `Dispatchers.IO`로 감쌉니다. |
| Spring integration | `s3-spring-cloud/`는 Spring Cloud AWS `S3Template`과 `ResourceLoader`를, `storage-abstraction/`은 Spring profile을 사용합니다. |

## 실행

```bash
./gradlew :aws-s3-spring-cloud:test
./gradlew :aws-storage-abstraction:test
```

backend별 동작을 비교하려면 profile 기반 storage 샘플을 직접 실행합니다.

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=local'
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3'
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3-presigned'
```

## 전제 조건

| item | requirement |
| --- | --- |
| JDK | Java 21 이상. |
| Docker | 에뮬레이터 기반 테스트와 S3 샘플 실행에 필요합니다. |
| AWS account | 로컬 워크샵 경로에는 필요하지 않습니다. |
