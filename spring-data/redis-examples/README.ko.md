# Redis Examples

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Redis Examples**를 실행 가능한 Spring Data 영속성 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 아키텍처 다이어그램

![Redis Examples Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/spring-data-redis-examples-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springdata` 패키지를 기준으로 삼습니다.

## 예제 범주

- **Redis Stream** (`stream/`) — Consumer Group 기반 message stream 발행과 소비
- **Redis Hash / String / List / Set / ZSet** — 기본 data-structure CRUD
- **Pub/Sub** — Channel 기반 message 발행과 subscription
- **Transaction** — `MULTI`/`EXEC` transaction 처리
- **Lua Script** — `RedisScript`를 사용한 atomic script 실행

## 참고 자료

- [Spring Data Redis Reference Documentation](https://docs.spring.io/spring-data/redis/reference/)
- Redisson 기반 예제는 [`redis/redisson-examples`](../../redis/redisson-examples) 모듈을 참고하세요
