# AWS Demo

AWS Java SDK V2 와 [Spring Cloud AWS](https://github.com/awspring/spring-cloud-aws) 를 사용한 예제를 제공합니다.

```mermaid
flowchart TD
    subgraph 서브모듈["AWS 서브모듈"]
        S3["s3-spring-cloud\nSpring Cloud AWS S3 연동"]
    end

    subgraph 인프라["인프라 (LocalStack)"]
        LocalStack["LocalStack\n(Docker 컨테이너)"]
        S3Svc["S3 서비스"]
    end

    subgraph 라이브러리["핵심 라이브러리"]
        SDKV2["AWS SDK v2"]
        SpringCloudAWS["Spring Cloud AWS"]
        Testcontainers["Testcontainers\n(LocalStackServer)"]
    end

    S3 --> SpringCloudAWS
    S3 --> SDKV2
    S3 --> Testcontainers
    Testcontainers --> LocalStack
    LocalStack --> S3Svc
    S3 -->|"버킷 생성 / 파일 업로드 / 다운로드"| S3Svc
```

## 모듈 구조

| 모듈 | 디렉토리 | 설명 |
|------|----------|------|
| S3 Spring Cloud | `s3-spring-cloud/` | Spring Cloud AWS + AWS SDK v2 기반 S3 버킷 생성·파일 업로드·다운로드 예제 |

## 전제 조건

| 항목 | 설명 |
|------|------|
| Docker | Testcontainers가 LocalStack 컨테이너를 자동으로 기동하므로 Docker 데몬이 실행 중이어야 함 |
| AWS 자격증명 | 로컬 테스트는 LocalStack 에뮬레이터를 사용하므로 실제 AWS 자격증명 불필요 |
| Java 25 | `--enable-preview` 플래그 사용, Java 25 이상 필요 |
| Kotlin 2.x | 멀티플랫폼 호환 Kotlin 코루틴 기반 코드 포함 |

## 핵심 라이브러리

| 라이브러리 | 버전/역할 |
|-----------|----------|
| `software.amazon.awssdk:s3` | AWS SDK v2 — S3 저수준 API |
| `io.awspring.cloud:spring-cloud-aws-starter-s3` | Spring Cloud AWS — `S3Template` 고수준 추상화 |
| `io.bluetape4k:bluetape4k-testcontainers` | `LocalStackServer` — Testcontainers 기반 LocalStack 래퍼 |
| `io.bluetape4k:bluetape4k-aws` | `staticCredentialsProviderOf`, `createBucket` 등 bluetape4k AWS 확장 함수 |

## 빌드 및 테스트

```bash
# 전체 AWS 모듈 빌드
./gradlew :aws:s3-spring-cloud:build

# 테스트 실행 (Docker 필요)
./gradlew :aws:s3-spring-cloud:test
```
