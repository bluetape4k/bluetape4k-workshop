# Issue #77 — 모듈 감사와 Basic/Advanced 분류

## 배경

Epic #76의 목표는 `bluetape4k-workshop`을 선별된 first-party Bluetape4k
학습 경로로 재구성하는 것이다. 모듈 삭제, 변환, 신규 예제 작업을 시작하기
전에 모든 활성 모듈을 Bluetape4k 가치 기준으로 점수화하고 Basic/Advanced
수준을 부여하는 기준 감사가 필요했다.

## 결정

57개 활성 모듈을 네 가지 기준으로 점수화했다. 기준은 `build.gradle.kts`의
BT-ref 개수, 고가치 BT 라이브러리 구체성, `src/main` production 파일 수,
`src/test` coverage 수다.

분류 기준은 다음과 같다.

- **Basic**: 주 BT 라이브러리 1개, 단일 실행 명령, 제거된 boilerplate를
  보여주는 예제.
- **Advanced**: BT 라이브러리 2개 이상, production concern
  (transactions/concurrency/observability/failure/performance/distributed),
  실행 가능한 Spring entrypoint + API + tests.

## 결과

| Verdict | Count |
|---------|------:|
| KEEP    | 40    |
| CONVERT | 6     |
| ARCHIVE | 6     |
| REWRITE (→ #97) | 5 |

Archive 후보는 `spring-boot/async-logging`, `kotlin/workshop`,
`reactive/mutiny`, `gatling/gradle-plugin-demo`, `mapping/mapstruct`다.
여기에 `quarkus/`는 이미 `settings.gradle.kts`에서 비활성화되어 있었다.

Rewrite 후보는 다섯 개 `exposed/` 모듈 전체이며, #97에서 추적하는 세 개의
production-shaped 앱으로 재구성한다.

## 검증

- 모듈 목록은 `settings.gradle.kts`의 `includeModules()` 호출에서 도출했다.
- BT-ref 수는 모듈별 `rg 'bluetape4k' build.gradle.kts` 결과로 산정했다.
- 구체 BT 라이브러리 목록은 모듈별
  `rg 'libs\.bluetape4k\.[a-z.]+'` 결과로 산정했다.
- source/test 수는 `find src/main -name '*.kt'` /
  `find src/test -name '*.kt'` 결과로 산정했다.

## 향후 지침

- Epic #76의 각 wave 이후 이 감사를 다시 실행해 verdict를 갱신한다.
- ARCHIVE verdict에는 모듈을 `settings.gradle.kts`에서 제거하고 디렉터리를
  삭제하거나 이동하는 PR이 필요하다. 이 작업은 #78에서 추적한다.
- CONVERT verdict는 도메인 epic 이슈(#79–#88)에서 개별 추적한다.
- Basic/Advanced 수준과 README의 `Used Bluetape4k features` 표를 지정하지
  않은 신규 모듈은 추가하지 않는다.
