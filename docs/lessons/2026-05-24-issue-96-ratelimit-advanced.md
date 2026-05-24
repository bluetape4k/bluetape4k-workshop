# Issue #96 — ratelimit/bucket4j-advanced 구현 회고

날짜: 2026-05-24
이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/96

## 개요

`ratelimit/bucket4j-advanced` 모듈을 신규 생성하여 세 가지 Rate Limit identity 전략을 구현했다.

- **IP-based**: `X-Forwarded-For` / `X-Real-IP` → TCP remote address 순 폴백
- **userId-based**: `X-User-ID` 헤더 기반 per-user 버킷
- **Combined**: `combined:<ip>:<userId>` 복합 키로 단일 버킷 (per IP+user pair)

## 아키텍처 결정

### Combined 전략의 해석

명세에 "두 조건 모두 통과해야 허용"이라는 문구와 "`ip:userId` 복합 키" 두 가지 표현이 혼재했다.
두 개의 별도 버킷을 AND-gate 방식으로 소비하는 방법도 고려했지만, 복합 키 방식을 채택했다.
이유:
- AND-gate 방식은 부분 소비 롤백이 필요해 원자성 보장이 어렵다.
- 복합 키(`combined:ip:userId`)는 각 (IP, user) 쌍이 독립 쿼터를 가지므로, 한 사용자가 다른 사용자의 쿼터를 소비할 수 없다.
- README에 두 방식의 차이를 명시했다.

### 필터 격리

기존 `bucker4j-bluetape4k-webflux` 모듈은 `UserRateLimitWebFilter`(sync)와 `AsyncUserRateLimitWebFilter`(async) 두 필터가 동일 경로에 중복 적용되어 토큰이 2배 소비되는 버그가 있었다. 이번 모듈은 각 필터가 자신의 경로 prefix에만 개입하도록 격리했다.

- `IpRateLimitWebFilter` → `/api/anonymous/**`만
- `UserRateLimitWebFilter` → `/api/authenticated/**`만
- `CombinedRateLimitWebFilter` → `/api/sensitive/**`만

### 응답 헤더 표준화

기존 모듈의 `X-BLUETAPE4K-REMAINING-TOKEN` 커스텀 헤더 대신 HTTP 표준 헤더를 채택했다.

| 헤더 | 용도 |
|---|---|
| `X-RateLimit-Remaining` | 남은 토큰 수 |
| `Retry-After` | 429 시 대기 시간(초) |

### IP fallback "unknown"

`WebTestClient.bindToApplicationContext()`는 mock client여서 TCP remote address가 null이다.
처음에는 null IP를 400으로 거부했지만, 이는 테스트 뿐 아니라 IPv6 환경이나 특수 프록시 설정에서도
문제가 될 수 있다. `"unknown"` fallback 키로 공유 버킷을 적용하는 방식으로 변경했다.

실제 TCP 연결이 필요한 IP 테스트는 `WebTestClient.bindToServer()`(RANDOM_PORT)로 전환했다.

## 테스트 격리 전략

| 전략 | 격리 방법 |
|---|---|
| IP-based | `@TestMethodOrder`로 exhaustion 테스트를 마지막에 배치 |
| User-based | 매 테스트마다 `Base58.randomString()` unique userId 사용 |
| Combined | 매 테스트마다 unique userId 사용 (IP는 공유돼도 userId가 다르면 별도 버킷) |

## 배운 점

1. **KDoc 내 `*/` 주의**: KDoc 내부에서 경로 패턴 `/**`을 쓰면 `*/`가 주석 종결자로 인식돼 컴파일 오류 발생.
   백틱으로 감싸도 해결되지 않으며, `/**` 대신 `/` 또는 산문 표현으로 교체해야 한다.

2. **`mono {}` 블록의 모든 분기는 같은 타입 반환**: `mono<Void?>` 블록에서 `return@mono`(Unit 반환)와
   `awaitSingleOrNull()`(Void? 반환)이 혼재하면 컴파일 에러. 모든 분기를 `awaitSingleOrNull()` 종결로 통일.

3. **Gradle 프로젝트 이름은 디렉토리 이름**: `includeModules("ratelimit", false, false)`에서
   `withProjectName=false, withBaseDir=false`이면 프로젝트 이름 = 디렉토리 이름 = `bucket4j-advanced`.
   태스크 명령어: `./gradlew :bucket4j-advanced:test`.

4. **@Qualifier + 동일 타입 빈 3개**: 같은 `DistributedSuspendRateLimiter` 타입 빈이 3개일 때
   `@Qualifier`를 빈 선언부와 주입부 모두에 명시해야 한다.

5. **`@TestMethodOrder`로 공유 리소스 테스트 순서 제어**: Redis 버킷 상태는 테스트 간 공유되므로
   exhaustion 테스트는 반드시 단순 200 확인 테스트보다 나중에 실행해야 한다.
