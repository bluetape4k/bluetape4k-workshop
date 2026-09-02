# Issue #860 graph-io Micrometer 진행 지표 구현 검토

- 검토일: 2026-09-02
- 저장소: `bluetape4k-workshop`
- 이슈: [#860](https://github.com/bluetape4k/bluetape4k-workshop/issues/860)
- 브랜치: `feat/issue-860-graph-io-micrometer`
- 대상 모듈: `graph/io-pipeline`
- 기준: Issue #860 live 본문, `bluetape4k-graph`의 graph-io progress listener와
  Micrometer bridge API, 현재 consumer diff와 targeted test 실행 결과

## 범위와 판정

기존 graph-io CSV/NDJSON/GraphML pipeline에 선택적인
`bluetape4k-graph-io-micrometer` listener 연결 지점을 추가하고, 사용자
listener와 Micrometer listener가 같은 lifecycle을 관찰하는지 검토했다. root
`bluetape4k-dependencies` BOM을 유일한 version authority로 유지했으며,
Spring Boot auto-configuration이나 graph-io core API 재설계는 범위에 넣지 않았다.

현재 구현의 미해결 blocker는 `P0=0`, `P1=0`, `P2=0`이다. 선택 의존성은
기존 호출자의 기본 동작을 바꾸지 않고, 실패 실행도 terminal event 뒤 active gauge를
0으로 되돌리며, metric tag는 upstream bridge가 정의한 bounded enum 값으로
제한된다.

## 독립 검토 결과

| 관점 | P0 | P1 | P2 | 근거와 처분 |
| --- | ---: | ---: | ---: | --- |
| 코드/API 경계 | 0 | 0 | 0 | `GraphIoPipeline`의 마지막 constructor 인자에 nullable listener를 추가하고 기존 null 경로는 기존 overload를 그대로 사용한다. CSV checkpoint/scratch, NDJSON, GraphML 경로 모두 upstream listener overload로만 연결한다. |
| 의존성·릴리스 | 0 | 0 | 0 | catalog alias는 versionless이고 module은 `bluetape4k-dependencies` BOM만 import한다. runtime dependency 해석에서 BOM `2.0.0`, graph-io child `1.0.0`, Micrometer `1.17.1`이 확인되며 개별 graph BOM은 없다. |
| lifecycle·정확성 | 0 | 0 | 0 | 완료 CSV import에서 `STARTED`, `PHASE_COMPLETED`, `COMPLETED`, runs/records/bytes/duration/phase/active를 확인했다. 실패 CSV는 `status=failed`, failure record, active `0`을 확인했다. NDJSON export도 동일 bridge를 통해 완료 지표를 남긴다. |
| 테스트·회귀 | 0 | 0 | 0 | 신규 Micrometer/composite listener 테스트 3개가 GREEN이다. 성공·실패 import와 export를 포함하고, 모든 tag key가 `operation`, `format`, `status`, `kind`, `phase` allow-list 안에 있는지 검사한다. 기존 pipeline 테스트는 변경하지 않았다. |
| 보안·운영 | 0 | 0 | 0 | metric tag에 path, record id, run id, exception message를 넣지 않는 규칙을 README 두 언어에 명시했다. `SimpleMeterRegistry`만 테스트에 사용하며 외부 backend나 실제 credential은 필요하지 않다. |
| 문서·사용성 | 0 | 0 | 0 | `README.md`와 `README.ko.md`에 composite listener 예제, six meters, bounded tag와 실패/active gauge 운영 주의를 같은 순서로 추가했다. 기존 CSV/NDJSON/GraphML round-trip 설명과 관련 이슈 링크를 보존했다. |

## 요구사항 추적

- `bluetape4k-graph-io-micrometer` alias와 implementation dependency를
  `graph/io-pipeline`에 추가했다.
- `GraphIoPipeline(..., progressListener = listener)`로 사용자 callback과
  `GraphIoMicrometerProgressListener`를 `GraphIoCompositeProgressListener`에
  함께 전달하는 경로를 추가했다.
- `graph.io.runs`, `graph.io.records`, `graph.io.bytes`, `graph.io.duration`,
  `graph.io.phase.duration`, `graph.io.active`의 정상·실패 상태를
  `SimpleMeterRegistry` assertion으로 고정했다.
- metric cardinality를 낮게 유지하기 위해 dataset path, element id, run id,
  exception text를 tag에 사용하지 않는 운영 계약을 양국어 README에 기록했다.
- core library에 Micrometer를 직접 추가하거나 Spring Boot 자동 구성을 만들지
  않았다. listener lifecycle과 meter 이름·tag 정의는 upstream bridge가 소유한다.

## Fresh verification evidence

| 검증 | 결과 |
| --- | --- |
| 신규 CSV success/failure 및 NDJSON export targeted tests | 3 tests, `BUILD SUCCESSFUL` |
| `:graph-io-pipeline:dependencies --configuration runtimeClasspath --no-build-cache` | `bluetape4k-dependencies:2.0.0`, graph-io child `1.0.0`, `bluetape4k-graph-io-micrometer`, `micrometer-core:1.17.1` 해석; `BUILD SUCCESSFUL` |
| `git diff --check` | PASS |

## 남은 범위

- Prometheus/OTLP 같은 외부 registry 연동과 dashboard/alert rule은 이 consumer
  예제의 범위가 아니다. 애플리케이션은 실제 registry를 주입하되 동일한 listener
  contract를 사용해야 한다.
- upstream bridge의 meter lifecycle 구현과 tag 집합은 upstream 테스트가 소유한다.
  이 consumer는 adapter wiring과 사용자 관찰 예제를 검증한다.
- 전체 repository container-backed matrix와 hosted CI는 PR exact head에서
  별도로 재검증한다.

## 최종 판정

**READY FOR LOCAL FULL VERIFICATION — P0=0, P1=0, P2=0.** 현재 diff는 Issue
#860의 graph/io-pipeline consumer 범위에 한정되어 있으며, full module test와
정적/문서 검증 후 PR을 생성한다.
