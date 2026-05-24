# Spring Boot Advanced (#84) — README Improvements

**Date**: 2026-05-24
**Branch**: feat/issue-84-spring-boot-advanced
**Issue**: #84

---

## 요약

Issue #84 범위 중 문서화(README 강화) 부분 완료.
신규 코드 추가 없이 기존 모듈 README의 gap을 메우고 다이어그램을 개선.

---

## Root Cause / Decisions

### 1. grep 케이스 감도 문제 — bluetape4k 표 누락 오진단

- **문제**: `grep -c "Used Bluetape4k"` 로 검색 시 spring-security, spring-modulith 모듈이 표 없음으로 잘못 판단
- **원인**: 해당 모듈들은 `"Used bluetape4k Features"` (소문자 b)로 작성됨
- **교훈**: bluetape4k 표 존재 여부 확인 시 케이스 무관 검색 사용: `grep -ci "bluetape4k features"`

### 2. ASCII art vs SVG+PNG — resilience4j-coroutines

- **문제**: 아키텍처 섹션에 ASCII block art 다이어그램 사용 중
- **해결**: `spring-boot-resilience4j-coroutines-diagram-02.{svg,png}` 생성 후 교체
- **추가**: Circuit Breaker State Machine 섹션의 PNG 아래 중복 ASCII 제거

### 3. `CoDecorators` → `SuspendDecorators` 명칭 오류

- **문제**: README Bluetape4k 표에서 `CoDecorators` (존재하지 않는 이름) 기재
- **실제**: `io.bluetape4k.resilience4j.SuspendDecorators` (실제 클래스명)
- **교훈**: README 작성 시 실제 import 경로 `rg "io.bluetape4k" src/` 로 확인 필수

### 4. gateway/customers, gateway/orders — Bluetape4k 표 누락

- 두 모듈 모두 표 없음 → 실제 import 기반으로 추가

---

## Verification Evidence

- **resilience4j-coroutines**: 78 tests, 6 skipped, 0 failed
- **gateway/orders**: 1 test, 0 failed
- **gateway/customers**: no test source
- 다이어그램 PNG: `rsvg-convert` 변환 성공

---

## Modules Covered

| 모듈 | 상태 | 작업 내용 |
|------|------|----------|
| `spring-boot/resilience4j-coroutines` | ✅ | SVG+PNG 아키텍처 다이어그램, SuspendDecorators 수정 |
| `gateway/customers` | ✅ | Bluetape4k 표 추가 |
| `gateway/orders` | ✅ | Bluetape4k 표 추가 |
| `spring-security/mvc/hello` | ⏭ | 이미 완료 |
| `spring-security/webflux/hello-security` | ⏭ | 이미 완료 |
| `spring-security/webflux/jwt` | ⏭ | 이미 완료 |
| `spring-modulith/events-deep-dive` | ⏭ | 이미 완료 |
| `spring-modulith/jpa-demo` | ⏭ | 이미 완료 |

---

## Future Guidance

1. README Bluetape4k 표 존재 확인: `grep -ci "bluetape4k features" README.md`
2. 실제 사용 모듈 확인: `rg "io\.bluetape4k" src/ -g "*.kt" | grep import | sed 's/.*import //' | sort -u`
3. 다이어그램 생성: SVG 작성 후 `rsvg-convert -w 1378 file.svg -o file.png`
