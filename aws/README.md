# AWS Workshop

[한국어](README.ko.md) | English

The AWS workshop contains two local-first S3 examples. Use `s3-spring-cloud/` when you want to see
Spring Cloud AWS and `S3Template` in a small runnable sample. Use `storage-abstraction/` when you
want a service boundary that can switch between local files, S3, and pre-signed S3 URLs through
Spring profiles.

## Architecture

![AWS Workshop architecture diagram](../docs/images/readme-diagrams/aws-readme-architecture-01.png)

Both modules run against local AWS-compatible infrastructure in tests and samples, so you can inspect
the S3 behavior without real AWS credentials.

## Module Guide

| module | Gradle task path | use it for |
| --- | --- | --- |
| `s3-spring-cloud/` | `:aws-s3-spring-cloud` | Spring Cloud AWS `S3Template`, AWS SDK v2 `S3Client`, bucket creation, object upload, object listing, and `ResourceLoader` access. |
| `storage-abstraction/` | `:aws-storage-abstraction` | `StorageService` with `local`, `s3`, and `s3-presigned` profiles, coroutine-friendly blocking I/O boundaries, and pre-signed URL behavior. |

## Runtime Model

| concern | implementation |
| --- | --- |
| AWS endpoint | Local AWS-compatible emulator from `bluetape4k-testcontainers`, started by the sample or tests. |
| Credentials | Static local credentials from the emulator; real AWS credentials are not required for local verification. |
| S3 client | AWS SDK v2 `S3Client`; blocking calls are wrapped in `Dispatchers.IO` in the storage abstraction module. |
| Spring integration | Spring Cloud AWS `S3Template` and `ResourceLoader` in `s3-spring-cloud/`; Spring profiles in `storage-abstraction/`. |

## Run

```bash
./gradlew :aws-s3-spring-cloud:test
./gradlew :aws-storage-abstraction:test
```

Run the profile-driven storage sample directly when you want to compare backends:

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=local'
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3'
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3-presigned'
```

## Prerequisites

| item | requirement |
| --- | --- |
| JDK | Java 21 or newer. |
| Docker | Required for emulator-backed tests and S3 samples. |
| AWS account | Not required for the local workshop path. |
