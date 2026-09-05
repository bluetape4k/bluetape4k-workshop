# Issue #940 cache-redis VirtualThreads 계획 리뷰

## 범위

- stable 2.0.0 `VirtualThreads` API와 JDK25 provider
- Spring executor bean ownership와 Lettuce dependency order
- MDC success/error/null-context cleanup
- 테스트, 문서, smoke, manifest와 stacked PR gate

## 판정

- P0: 0건
- P1: 0건
- 결론: PASS

## 발견 사항과 반영

- 기존 `async-vt-exec-` thread name 보존 여부가 빠져 있었음 — provider-defined prefix를 채택하고
  bean name과 `runtimeName()`을 관측 계약으로 결정했다.
- in-flight 종료 정책이 불명확했음 — `shutdown()`은 신규 admission만 닫고 기존 작업을 interrupt하지 않으며
  context close는 기다리지 않는다고 명시했다.
- Spring destroy 순서가 정적 graph에 머물렀음 — 실제 bean과 destruction recorder를 함께 검증한다.
- MDC와 stable 2.0.0/manifest 검증이 추상적이었음 — success/error/null/pre-existing context와 exact command,
  artifact 기대값을 계획에 고정했다.
