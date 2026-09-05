# 외부 dependency maintenance와 ecosystem train scope 분리

## 배경

workshop은 `bluetape4k-dependencies`를 Bluetape 버전 원본으로 사용하지만,
외부 라이브러리의 호환성 버전은 예제 검증을 위해 독립적으로 올릴 수 있다.
그런데 `gradle/libs.versions.toml`과 `build.gradle.kts`를 ecosystem train의
`allowed_paths`에만 연결하면 Dependabot maintenance PR도 고정된 feature
branch/OID를 요구받는다.

## 원인

PR checker가 변경 경로 전체를 train scope에 대입했지만, dependency catalog
변경의 의미를 diff 내용으로 구분하지 않았다. 그 결과 외부 버전 한 줄 변경이
여러 historical scope와 일치하고, Dependabot branch가 어느 expected head에도
해당하지 않아 `found N` 오류가 발생했다. 외부 버전 자체는 checker의 금지
규칙이 아니었다.

## 결정

변경된 모든 경로가 Gradle dependency declaration이고, 추가·삭제된 diff 줄에
`bluetape4k` marker가 없으며, git diff를 해석할 수 있을 때만
`dependency-maintenance`로 분류한다. 이 경우 inventory, BOM pin, assertion,
Action pin, 일반 Build/CI 검사는 유지하고 ecosystem train scope/ref/OID 검사만
적용하지 않는다. 경로가 섞이거나 diff를 해석할 수 없으면 기존 train 검사를
그대로 적용해 fail closed 한다.

checker 자체와 회귀 테스트, Action pin 원장, 이 정책 lesson만 바꾸는 control-plane
유지보수도 별도 명시 경로로 분류해 train scope를 요구하지 않는다. 제품 소스나
다른 문서가 섞이면 이 예외를 받지 않는다. 현재 workflow가 사용하는
`actions/checkout`·`actions/upload-artifact` v7.0.1 SHA도 원장과 동기화한다.

workflow의 PR 경로 필터는 여러 workshop 예제의 `src/test` 변경을 넓게
포착할 수 있다. 따라서 명시된 ecosystem policy control-plane 경로를
제외한 product 경로가 manifest의 어떤 `allowed_paths`에도 속하지 않는
PR은 `outside-train-scope`로 분류해 train scope를 요구하지 않는다. 단
하나라도 manifest track 경로가 섞이면 이 면제를 적용하지 않고 기존의
exactly-one-track 검사를 유지한다. 이렇게 해야 train에 아직 등록되지
않은 예제 수정은 통과시키면서, 등록된 track과 비대상 경로를 우연히 섞은
변경은 fail closed로 차단할 수 있다.

## 검증

- 외부 catalog와 module build dependency 변경은 maintenance로 분류된다.
- Bluetape BOM/version 변경, source/test 혼합, 해석 불가능한 diff는 maintenance
  예외를 받지 않는다.
- 실제 Hibernate PR diff를 대상으로 checker가 `INFO dependency-maintenance`
  후 `PASS ecosystem-reuse inventory and train contract`를 출력한다.
- manifest track 밖의 virtualthreads 예제 PR diff는 `INFO outside-train-scope`
  후 동일한 `PASS`를 출력한다.
- manifest track 경로와 track 밖 경로를 섞은 diff는 train scope 검사를
  계속 호출하고 실패한다.

## 향후 지침

새로운 dependency declaration 경로를 workflow trigger에 추가할 때는 외부
maintenance와 ecosystem train의 의미를 함께 정의하고, 외부-only 통과와
Bluetape 변경 차단 회귀 테스트를 유지한다. 새 예제 경로를 추가할 때도
manifest에 등록하기 전의 단독 변경과 등록 경로가 섞인 변경을 각각 검증해
`outside-train-scope` 면제가 train 경계 우회 수단이 되지 않도록 한다.
