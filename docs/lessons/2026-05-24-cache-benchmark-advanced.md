# cache-benchmark-advanced — Lessons Learned

**Date**: 2026-05-24  
**Branch**: feat/issue-95-near-cache-benchmark  
**Module**: spring-boot/cache-benchmark  
**Issue**: #95

---

## 요약

7가지 캐시 전략을 kotlinx-benchmark(JMH 기반)으로 비교하는 Spring Boot 워크샵 모듈 구현.
각 프로파일은 동일한 `ProductCacheService` 인터페이스를 구현하며 기능 동등성을 보장한다.

> 2026-07-27 / issue #585 기준 정정: 이 문서의 초기 write-through/write-behind
> 판단은 Redisson `MapLoader`/`MapWriter` 소유권 기준으로는 부정확했다.
> Spring `@Async` flusher로 DB 쓰기를 미루는 방식은 canonical write-behind가
> 아니다. 이후 작업에서는 Redisson writer-backed map을 기준으로 판단한다.

---

## Root Cause / Decisions

### 1. Redisson 4.x API 변경 — LocalCachedMapOptions 패키지 이동

- **문제**: `org.redisson.api.LocalCachedMapOptions` (3.x) → Redisson 4.x에서 `org.redisson.api.options.LocalCachedMapOptions`로 이동
- **증상**: `Unresolved reference 'name'` 컴파일 에러 (올바른 import 없을 때)
- **해결**: import를 `org.redisson.api.options.LocalCachedMapOptions`로 수정, Duration 파라미터 형식도 `TimeUnit` → `java.time.Duration`으로 변경
- **교훈**: Redisson 버전 업그레이드 시 `api.options` 패키지 확인 필수

### 2. Write-behind 소유권 정정 — Spring async flusher는 canonical 전략이 아님

- **문제**: 애플리케이션 service가 cache update 후 Spring `@Async`로 repository
  저장을 호출하면 DB 쓰기 소유권이 Redisson cache writer가 아니라 service에
  남는다.
- **결과**: 낮은 enqueue latency를 보일 수는 있지만 Redisson write-behind의
  delayed/batched writer queue 계약을 증명하지 못한다.
- **해결**: issue #585에서 `MapWriter`를 붙인 Redisson map의
  `WRITE_BEHIND` mode로 전환했다.
- **교훈**: write-behind라는 이름을 쓰려면 caller는 map/cache에 쓰고,
  DB persistence는 Redisson `MapWriter`가 소유해야 한다.

### 3. Writer-backed 전략에서 새 엔티티(id=0) 키 오염

- **문제**: 새 `Product(id=0L)`를 캐시에 먼저 저장 → `products:write-behind:0` 키로 캐시됨. DB 비동기 저장 후 실제 id는 discarded. 호출자는 항상 `id=0` 반환.
- **해결**: issue #585 이후 canonical writer-backed benchmark는 stable existing
  ID update만 측정한다. `id == 0L` insert는 write-through/write-behind
  benchmark 연산에서 제외한다.
- **교훈**: cache key로 DB 자동생성 ID를 쓰면 canonical writer-backed map
  쓰기와 신규 insert가 충돌한다. 전략 benchmark는 stable IDs로 update를
  측정해야 한다.

### 4. JMH + kotlinx.benchmark 이중 @Setup — double invocation

- **문제**: 추상 클래스에 JMH `@Setup(Level.Trial)`, 구체 클래스에 kotlinx.benchmark `@Setup` 동시 존재 → JMH가 계층 전체를 스캔하여 `setup()` 2회 실행 → Spring 컨텍스트 2개 기동
- **해결**: 추상 클래스의 JMH 어노테이션 제거. 구체 클래스의 kotlinx.benchmark `@Setup`만 유지.
- **교훈**: kotlinx-benchmark는 내부적으로 JMH를 사용한다. 추상 클래스에 JMH 어노테이션을 직접 쓰면 구체 클래스의 kotlinx.benchmark 어노테이션과 중복된다.

### 5. Write-behind benchmark teardown — queue보다 context를 먼저 닫지 말 것

- **문제**: enqueue throughput 측정 직후 Spring context를 닫으면 아직 남은
  Redisson write-behind task가 `RedissonShutdownException`으로 실패한다.
- **해결**: benchmark가 key별 최신 update를 기록하고, 공통 teardown 전에
  repository가 모두 반영했는지 확인한 뒤 context를 닫는다.
- **교훈**: write-behind enqueue 측정의 종료 조건은 JMH iteration 종료가
  아니다. queued write drain까지 lifecycle에 포함해야 raw 결과가 유효하다.

### 6. bluetape4k 로깅 extension 명시적 import 필요

- **문제**: `log.info { "..." }`, `log.warn(e) { "..." }` 사용 시 `Cannot convert () -> String` 에러
- **원인**: `io.bluetape4k.logging.Slf4jExtensions.kt`의 extension 함수는 자동 import가 아님. `import io.bluetape4k.logging.info`, `import io.bluetape4k.logging.warn` 명시 필요
- **교훈**: bluetape4k 로깅 extension은 반드시 파일별로 명시적 import. IDE auto-import 설정 확인.

### 7. Gradle version catalog — 동일 prefix 공유 alias 문제

- **문제**: `libs.redisson`이 `LibraryAccessors` 그룹을 반환해 dependency로 변환 불가
- **원인**: catalog에 `redisson-lib`, `redisson-spring-boot-starter` 등 공통 prefix 공유 시 `libs.redisson`은 leaf가 아닌 group accessor가 됨
- **해결**: `libs.redisson.lib` 사용
- **교훈**: version catalog에서 라이브러리 alias가 다른 alias의 prefix인 경우, `-lib` suffix를 붙인 leaf alias를 사용해야 한다.

---

## Verification Evidence

- **컴파일**: `compileKotlin`, `compileTestKotlin`, `compileBenchmarkKotlin` 모두 오류 없이 성공
- **테스트**: issue #585 수정 후 module test 27개, 0 실패, 0 에러
  - NoCacheServiceTest: 3/3
  - CaffeineServiceTest: 4/4
  - RedisCacheServiceTest: 3/3
  - NearCacheServiceTest: 4/4
  - ReadThroughServiceTest: 5/5
  - WriteThroughServiceTest: 3/3
  - WriteBehindServiceTest: 3/3
  - StrategyOwnershipContractTest: 2/2
- **전체 benchmark 재실행**:
  `:spring-boot-cache-benchmark:allProfilesBenchmark --rerun-tasks
  --no-build-cache` 성공(5분 10초)
  - JMH measurement 23/23 완료
  - Spring context 23/23 정상 종료
  - shutdown timeout과 `RedissonShutdownException` 0건
  - raw JSON, summary, README 표와 SVG/PNG chart를 2026-07-27 결과로 갱신

---

## Review Findings Resolved

| Severity | Finding | Resolution |
|----------|---------|------------|
| CRITICAL | Spring async flusher was treated as write-behind | issue #585에서 Redisson `MapWriter` 기반 `WRITE_BEHIND`로 정정 |
| CRITICAL | `save()` caches at key=0 for new entities | writer-backed benchmark를 stable existing ID update로 제한 |
| HIGH | Mixed JMH + kotlinx.benchmark `@Setup` → double invocation | 추상 클래스의 중복 `@Setup` 제거 |
| HIGH | Benchmark fork가 Spring/Redisson context를 닫지 않아 30초 후 강제 종료 | override되지 않는 공통 `@TearDown`으로 context close |
| HIGH | Write-behind queue drain 전에 context close | key별 최신 DB state 확인 후 Redisson shutdown |
| Missing | `WriteBehindServiceTest` trivial assertion | Awaitility async DB flush 검증 추가 |
| Missing | `AbstractCacheBenchmarkTest` `@TestInstance` | `@TestInstance(PER_CLASS)` 추가 |

---

## Future Guidance

1. **Write-behind 명명 기준**: Spring async flusher나 service-managed dual-write를 write-behind로 부르지 말 것. Redisson `MapWriter`가 DB persistence를 소유해야 한다.
2. **Writer-backed 캐시 키**: DB 자동생성 ID를 쓰는 경우 benchmark write path는 stable existing ID update로 제한한다.
3. **kotlinx-benchmark + JMH lifecycle**: `@Setup`을 추상 클래스와 구체
   override에 함께 붙이지 말 것. 공통 `@TearDown`은 구체 클래스가 override하지
   않을 때 추상 클래스에 한 번만 선언해 Spring/Redisson context를 닫는다.
4. **Write-behind benchmark 종료**: enqueue iteration이 끝나도 queue가 남을 수
   있다. key별 최신 update의 DB drain을 확인한 뒤 context를 닫는다.
5. **Redisson 4.x**: `api.options.*` 패키지로 이동된 클래스 확인. `Duration` 파라미터 형식 변경.
6. **bluetape4k logging**: `import io.bluetape4k.logging.info/warn/debug/error` 명시적 import.
