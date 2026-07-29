# Issue #78 — 저가치 모듈 아카이브

## 배경

Epic #76의 issue #77 감사는 모든 활성 workshop 모듈을 Bluetape4k 가치
기준으로 점수화했다. 다섯 개 모듈은 LOW 점수를 받았다. 기준은
bt-ref ≤ 2이거나, 도메인 학습 성과 없이 infrastructure 수준의 BT 사용만
있는 경우였다. workshop을 first-party Bluetape4k 예제에 집중시키기 위해
이 모듈들을 제거했다.

## 결정

모듈 디렉터리를 삭제하고 `settings.gradle.kts`를 갱신한다.

| Module | Reason |
|--------|--------|
| `spring-boot/async-logging` | bt-ref=2; only logging infra; no domain BT value |
| `kotlin/workshop` | bt-ref=3; 4 test files; no clear learning outcome |
| `reactive/mutiny` | bt-ref=2; Quarkus-adjacent; `quarkus/` domain already disabled |
| `gatling/gradle-plugin-demo` | bt-ref=0; zero source; Gradle config demo only |
| `mapping/mapstruct` | bt-ref=1; MapStruct is not a Bluetape4k feature |

제거 후 `reactive/`와 `mapping/`이 비었으므로, 해당 `includeModules(...)`
라인은 `#78` 참조 주석과 함께 주석 처리했다.

## 결과

- 모듈 디렉터리 5개 삭제
- `settings.gradle.kts`: 도메인 라인 2개 주석 처리(`reactive`, `mapping`)
- 모듈 수: 활성 모듈 57개 → 52개
- `./gradlew build -x test`가 정상 통과(44초)

## 검증

```bash
./gradlew build -x test --no-daemon
# BUILD SUCCESSFUL in 44s
```

## 향후 지침

- 신규 모듈을 추가하기 전
  `docs/superpowers/specs/2026-05-22-issue-77-module-audit-criteria.md`의
  점수화 기준을 사용해 저가치 예제가 다시 누적되지 않게 한다.
- 제거 후 도메인 디렉터리가 비면 Gradle configuration 오류를 막기 위해
  해당 `includeModules(...)` 라인을 즉시 주석 처리한다.
- `includeModules`는 빈 디렉터리를 조용히 건너뛰지만, 주석은 명시적 감사
  기록으로 남는다.
