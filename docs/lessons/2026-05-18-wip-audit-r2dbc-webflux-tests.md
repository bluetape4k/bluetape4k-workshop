# WIP audit R2DBC WebFlux 테스트

## Context

2026-05-18 GNO 기반 workshop audit는 `WIP.md`를 갱신하기 전에 이전
compile-drift lesson, live GitHub issue, 현재 소스 marker를 확인했다.

## Decision or Finding

`spring-data/r2dbc-webflux`는 schema initialization이 해결되지 않은 상태로
남아 있어 service, annotated-controller, functional handler integration
test가 class level에서 비활성화되어 있다. 이 모듈은 이미 `data/schema.sql`,
`data/data.sql`, 주석 처리된 `ConnectionFactoryInitializer` block을 포함하고
있으므로, 이 gap은 새 예제 기능이 아니라 집중적인 테스트 복원 bug다.

## Outcome

GitHub issue #120을 등록하고 repo-local WIP queue에서 example epic보다
앞으로 옮겼다.

## Verification

- `gno query ... --no-rerank -c bluetape4k-docs`가 이전 workshop
  compile-drift lesson을 찾아냈다.
- `gh issue list --assignee debop`로 기존 open assigned queue를 확인했다.
- `gh issue list --search "r2dbc schema disabled tests"`에서 중복 issue가
  없음을 확인했다.
- `./gradlew :spring-data-r2dbc-webflux:test --tests ...`는
  `BUILD SUCCESSFUL`, `0 passing`, `44 pending`으로 완료됐다.

## Future Guidance

workshop 모듈의 Gradle build가 green으로 보고되더라도, 예제가 보호된다고
보기 전에 의미 있는 테스트가 pending이거나 disabled 상태인지 확인한다.
