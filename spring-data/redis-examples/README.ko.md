# Redis Examples

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Redis Examples**를 실행 가능한 Spring Data 영속성 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 아키텍처 다이어그램

![Redis Examples architecture diagram](../../docs/images/readme-diagrams/spring-data-redis-examples-diagram-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springdata` 패키지를 기준으로 삼습니다.

![Redis Examples Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-redis-examples-readme-architecture-01.png)

## 흐름 다이어그램

1. `spring-data-redis-examples`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 자산이 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 그렇지 않은 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

## 아키텍처 다이어그램

![redis examples Class Structure diagram](../../docs/images/readme-diagrams/spring-data-redis-examples-diagram-01.png)

![redis examples Architecture 2 diagram](../../docs/images/readme-diagrams/spring-data-redis-examples-diagram-02.png)

Spring Data Redis를 사용한 여러 data structure 예제 모음입니다.
Integration test에서는 Testcontainers가 Redis container를 자동으로 시작합니다.

## 예제 범주

- **Redis Stream** (`stream/`) — Consumer Group 기반 message stream 발행과 소비
- **Redis Hash / String / List / Set / ZSet** — 기본 data-structure CRUD
- **Pub/Sub** — Channel 기반 message 발행과 subscription
- **Transaction** — `MULTI`/`EXEC` transaction 처리
- **Lua Script** — `RedisScript`를 사용한 atomic script 실행

## 참고 자료

- [Spring Data Redis Reference Documentation](https://docs.spring.io/spring-data/redis/reference/)
- Redisson 기반 예제는 [`redis/redisson-examples`](../../redis/redisson-examples) 모듈을 참고하세요
