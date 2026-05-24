# cache-benchmark-advanced — Lessons Learned

**Date**: 2026-05-24  
**Branch**: feat/issue-95-near-cache-benchmark  
**Module**: spring-boot/cache-benchmark  
**Issue**: #95

---

## 요약

7가지 캐시 전략을 kotlinx-benchmark(JMH 기반)으로 비교하는 Spring Boot 워크샵 모듈 구현.
각 프로파일은 동일한 `ProductCacheService` 인터페이스를 구현하며 기능 동등성을 보장한다.

---

## Root Cause / Decisions

### 1. Redisson 4.x API 변경 — LocalCachedMapOptions 패키지 이동

- **문제**: `org.redisson.api.LocalCachedMapOptions` (3.x) → Redisson 4.x에서 `org.redisson.api.options.LocalCachedMapOptions`로 이동
- **증상**: `Unresolved reference 'name'` 컴파일 에러 (올바른 import 없을 때)
- **해결**: import를 `org.redisson.api.options.LocalCachedMapOptions`로 수정, Duration 파라미터 형식도 `TimeUnit` → `java.time.Duration`으로 변경
- **교훈**: Redisson 버전 업그레이드 시 `api.options` 패키지 확인 필수

### 2. @Async 자기 호출(self-invocation) — 프록시 우회 버그

- **문제**: `WriteBehindService.save()` 내에서 `this.asyncPersist(product)`를 직접 호출하면 Spring 프록시를 우회해 동기 실행됨. `@EnableAsync`가 있어도 해결 안 됨.
- **결과**: write-behind 패턴의 핵심 이점(낮은 쓰기 지연)이 사라짐
- **해결**: `WriteBehindFlusher` 별도 `@Component` 추출 → 외부 빈 호출로 프록시 통과
- **교훈**: Spring `@Async`, `@Transactional`, `@Cacheable`은 같은 클래스 내 자기 호출에서 동작하지 않는다. 항상 별도 빈으로 분리할 것.

### 3. Write-Behind에서 새 엔티티(id=0) 키 오염

- **문제**: 새 `Product(id=0L)`를 캐시에 먼저 저장 → `products:write-behind:0` 키로 캐시됨. DB 비동기 저장 후 실제 id는 discarded. 호출자는 항상 `id=0` 반환.
- **해결**: `id == 0L` 경우 DB 먼저 저장해 실제 id 획득 후 캐시 저장. `id > 0L` (업데이트)일 때만 write-behind 패턴 적용.
- **교훈**: 캐시 키로 DB 자동생성 ID를 사용하는 경우, 새 엔티티에는 반드시 DB 저장을 먼저 수행해야 한다.

### 4. JMH + kotlinx.benchmark 이중 @Setup — double invocation

- **문제**: 추상 클래스에 JMH `@Setup(Level.Trial)`, 구체 클래스에 kotlinx.benchmark `@Setup` 동시 존재 → JMH가 계층 전체를 스캔하여 `setup()` 2회 실행 → Spring 컨텍스트 2개 기동
- **해결**: 추상 클래스의 JMH 어노테이션 제거. 구체 클래스의 kotlinx.benchmark `@Setup`만 유지.
- **교훈**: kotlinx-benchmark는 내부적으로 JMH를 사용한다. 추상 클래스에 JMH 어노테이션을 직접 쓰면 구체 클래스의 kotlinx.benchmark 어노테이션과 중복된다.

### 5. bluetape4k 로깅 extension 명시적 import 필요

- **문제**: `log.info { "..." }`, `log.warn(e) { "..." }` 사용 시 `Cannot convert () -> String` 에러
- **원인**: `io.bluetape4k.logging.Slf4jExtensions.kt`의 extension 함수는 자동 import가 아님. `import io.bluetape4k.logging.info`, `import io.bluetape4k.logging.warn` 명시 필요
- **교훈**: bluetape4k 로깅 extension은 반드시 파일별로 명시적 import. IDE auto-import 설정 확인.

### 6. Gradle version catalog — 동일 prefix 공유 alias 문제

- **문제**: `libs.redisson`이 `LibraryAccessors` 그룹을 반환해 dependency로 변환 불가
- **원인**: catalog에 `redisson-lib`, `redisson-spring-boot-starter` 등 공통 prefix 공유 시 `libs.redisson`은 leaf가 아닌 group accessor가 됨
- **해결**: `libs.redisson.lib` 사용
- **교훈**: version catalog에서 라이브러리 alias가 다른 alias의 prefix인 경우, `-lib` suffix를 붙인 leaf alias를 사용해야 한다.

---

## Verification Evidence

- **컴파일**: `compileKotlin`, `compileTestKotlin`, `compileBenchmarkKotlin` 모두 오류 없이 성공
- **테스트**: 23개 테스트, 0 실패, 0 에러
  - NoCacheServiceTest: 3/3
  - CaffeineServiceTest: 4/4
  - RedisCacheServiceTest: 3/3
  - NearCacheServiceTest: 4/4
  - ReadThroughServiceTest: 4/4
  - WriteThroughServiceTest: 2/2
  - WriteBehindServiceTest: 3/3 (Awaitility async flush 검증 포함)

---

## Review Findings Resolved

| Severity | Finding | Resolution |
|----------|---------|------------|
| CRITICAL | `@Async` self-invocation bypasses proxy | `WriteBehindFlusher` 별도 컴포넌트 추출 |
| CRITICAL | `save()` caches at key=0 for new entities | `id==0L` 분기로 DB 먼저 저장 |
| HIGH | Mixed JMH + kotlinx.benchmark `@Setup` → double invocation | 추상 클래스 JMH 어노테이션 제거 |
| Missing | `WriteBehindServiceTest` trivial assertion | Awaitility async DB flush 검증 추가 |
| Missing | `AbstractCacheBenchmarkTest` `@TestInstance` | `@TestInstance(PER_CLASS)` 추가 |

---

## Future Guidance

1. **@Async 사용 시**: 항상 별도 `@Component`에 선언. 같은 클래스에서 호출 금지.
2. **Write-Behind 캐시 키**: DB 자동생성 ID를 쓰는 경우 신규 엔티티는 DB 먼저 저장 필수.
3. **kotlinx-benchmark + JMH**: 추상 클래스에 `@Setup`/`@TearDown` 어노테이션 금지. 구체 클래스에만.
4. **Redisson 4.x**: `api.options.*` 패키지로 이동된 클래스 확인. `Duration` 파라미터 형식 변경.
5. **bluetape4k logging**: `import io.bluetape4k.logging.info/warn/debug/error` 명시적 import.
