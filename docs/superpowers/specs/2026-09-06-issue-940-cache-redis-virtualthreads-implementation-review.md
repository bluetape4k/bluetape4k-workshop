# Issue #940 cache-redis VirtualThreads 구현 리뷰

## 범위

- stable 2.0.0 VirtualThreads executor와 Spring bean lifecycle
- Lettuce → applicationTaskExecutor → managed delegate dependency와 destruction order
- in-flight non-interrupt completion, 신규 admission 거부, MDC cleanup
- module tests, documentation, smoke, stale guard와 manifest

## 독립 리뷰

- architecture/API: Docker-free all-smoke 경계와 assertion 계약 P1을 수정한 뒤 P0 0건, P1 0건, PASS
- lifecycle/operations: bounded close와 destruction callback 존재 검증 P1 2건을 수정한 뒤 P0 0건, P1 0건, PASS
- test/verification: AsyncConfigTest XML 3 tests, 0 failures/errors/skips 및 fresh targeted PASS

## 검증

- TDD RED: managed bean name과 lifecycle contract 부재로 compile 실패
- targeted lifecycle/MDC/Lettuce order 3 tests: PASS
- clean module: 8 passing, 1 existing pending, BUILD SUCCESSFUL
- stable dependency: `bluetape4k-virtualthread-api:2.0.0`, `bluetape4k-virtualthread-jdk25:2.0.0`
- root detekt, stale-check, README language/parity, ecosystem checker 113 tests,
  assertion governance 1,200 files, `git diff --check`: PASS
- hosted CI: PENDING

## 판정

- P0: 0건
- P1: 0건
- 결론: PASS

## 리뷰에서 수정한 사항

- bounded context close를 별도 closer Future에서 2초 timeout으로 검증해 shutdown 회귀가 테스트 자체를
  교착시키지 않게 했다.
- destruction callback 세 개의 존재를 먼저 확인한 뒤 Lettuce → adapter → delegate 순서를 비교했다.
- 신규 테스트의 `check`/`runCatching`을 `bluetape4k-assertions` intent matcher와 `assertFailsWith`로 교체했다.
- Testcontainers 의존 cache-redis를 Docker-free `all-smoke`에 추가하지 않고 기존 Examples container lane을 유지했다.
