# Lessons Learned — Exposed compileTestKotlin failures after lib→core migration (2026-05-14)

**관련 PR**: #68
**영향 모듈**: `:exposed-spring-transaction`, `:exposed-sql-webflux-coroutines`

## L1: 집계 artifact를 세분화 좌표로 교체할 때 모든 하위 아티팩트를 명시적으로 선언해야 한다

### 문제
`7c4feb86` 커밋에서 `libs.bluetape4k.exposed.lib` (core + dao + jdbc 통합 artifact)를
`libs.bluetape4k.exposed.core` 하나로 교체했다.
이 변경 후 `dao`와 `jdbc` 하위 모듈을 참조하는 테스트 코드가 unresolved reference로 컴파일 실패했다.

### 교훈
집계 artifact를 세분화 좌표로 교체할 때는 반드시 모든 하위 artifact를 개별적으로 선언해야 한다.

```kotlin
// BAD — 집계 artifact만 교체
implementation(libs.bluetape4k.exposed.core)  // 구 lib의 일부만 대체

// GOOD — 사용 중인 하위 모듈을 모두 선언
implementation(libs.bluetape4k.exposed.core)
implementation(libs.bluetape4k.exposed.dao)   // dao 패키지 사용 시
implementation(libs.bluetape4k.exposed.jdbc)  // jdbc 패키지 사용 시
```

**검증 방법**: 교체 후 `./gradlew clean :module:compileTestKotlin --no-build-cache`로
캐시 없이 컴파일을 확인한다.

---

## L2: Gradle 빌드 캐시가 누락된 의존성 오류를 장기간 숨긴다

### 문제
의존성 누락 후에도 Gradle incremental build cache가 이전 성공 결과를 재사용해
로컬 및 일반 CI 빌드에서 오류가 표면화되지 않았다.
Nightly 실행이 clean 환경(`--no-build-cache` 또는 fresh runner)에서 실행되어
처음으로 검출됐다.

### 교훈
의존성 변경(추가·제거·교체)이 포함된 PR은 반드시 `./gradlew clean :module:compileTestKotlin`을
실행해 캐시 없이 컴파일 성공 여부를 확인한다.

---

## L3: 부분 수정(partial fix)은 반드시 전체 범위를 검증해야 한다

### 문제
`a7c2b10d`에서 `:exposed-sql-webflux-coroutines`에 `bluetape4k.exposed.dao`는 복원했지만
같은 커밋(`7c4feb86`)에서 손실된 `bluetape4k.exposed.jdbc`는 복원하지 않았다.

### 교훈
부분 수정 커밋을 만들 때, 원래 누락된 커밋 diff 전체를 검토해 누락 범위를 완전히 파악한다.
`git show <commit> -- <file>` 으로 실제 변경 내용을 확인하고,
영향 모듈 전체에 동일 패턴이 없는지 `rg`로 교차 검증한다.
