# Rate Limit per user with Bucket4j in Spring Webflux

## 아키텍처 다이어그램

```mermaid
flowchart LR
    subgraph 클라이언트
        C[HTTP 클라이언트\nX-BLUETAPE4K-UID 헤더]
    end

    subgraph WebFlux 필터 체인
        UF[UserRateLimitWebFilter\n동기]
        AUF[AsyncUserRateLimitWebFilter\n비동기 코루틴]
        KR[UserKeyResolver\nUID 키 추출]
    end

    subgraph 컨트롤러
        CC[CoroutineController]
        RC[ReactiveController]
    end

    subgraph Bucket4j 인프라
        BPP[BucketProxyProvider\n동기]
        ABPP[AsyncBucketProxyProvider\n비동기]
        PM[LettuceBasedProxyManager]
        BC[BucketConfiguration\n10토큰/10초\n100토큰/1분]
    end

    subgraph Redis
        R[(Redis\n사용자별 버킷 상태)]
    end

    C -->|요청| UF
    C -->|요청| AUF
    UF --> KR
    AUF --> KR
    KR -->|UID| BPP
    KR -->|UID| ABPP
    BPP --> PM
    ABPP --> PM
    PM <-->|CAS 연산| R
    BC --> PM
    BPP -->|토큰 있음| CC
    ABPP -->|토큰 있음| RC
    BPP -->|토큰 없음 429| C
    ABPP -->|토큰 없음 429| C
```

Spring Webflux 환경에서 IpAddress 가 아닌 User Token으로 Rate Limit을 적용하는 예제입니다.

참고: `UserRateLimitWebFilter` 는 Spring Webflux 환경에서 요청 정보 (`ServerHttpRequest`) 의 Header에서 `X-BLUETAPE4K-UID` 값을 추출해서
이 값을 기준의 Bucket4j의 Rate Limit을 적용합니다.

기존 `bucket4j-spring-boot-starter` 는 User 기반으로 사용하려면 Spring SpEL을 동기 방식으로 사용해야 하는데,
헤더에서 User Token 값을 추출하는데, 동기 방식만 지원해서 성능이 느려질 수 있습니다.
