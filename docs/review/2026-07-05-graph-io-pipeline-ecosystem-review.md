# graph-io-pipeline 생태계 리뷰

## 범위

- 모듈: `:graph-io-pipeline`
- 경로: `graph/io-pipeline`
- 리뷰 유형: 7-Tier bluetape4k 생태계/코드 패턴 리뷰

## 해결한 발견 사항

- 경로 검증은 이제 bluetape4k `require*` 헬퍼 패턴을 사용한다.
- 누락된 원본 파일, 디렉터리인 원본, 디렉터리인 내보내기 대상에는 각각 회귀 테스트가 있다.
- 불리언 검증에는 bluetape4k 불리언 매처 스타일을 사용한다.
- 양방향 README는 조기 실패 방식의 경로 검증을 문서화한다.

## 검증

| 점검 | 결과 |
|---|---|
| `:graph-io-pipeline:compileKotlin :graph-io-pipeline:compileTestKotlin :graph-io-pipeline:cleanTest :graph-io-pipeline:test --no-build-cache --rerun-tasks` | PASS, 테스트 10개 |
| `git diff --check` | PASS |
| 정적 패턴 스캔 | PASS, 새로 추가된 JUnit/kotlin.test 직접 검증문, `!!`, `Thread.sleep`, 직접 생성한 컨테이너, UUID 생성 없음 |

## DoD 상태

P0/P1 이슈는 닫혔다. 남은 알려진 위험은 이 PR 범위 밖에 있는 더 넓은 graph 모듈의 일관성으로 제한된다.
