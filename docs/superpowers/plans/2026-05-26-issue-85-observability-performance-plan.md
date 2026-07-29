# 구현 계획 - Issue #85 Observability/Performance 고급 완료

**날짜**: 2026-05-26
**지점**: `feat/issue-85-observability-performance`
**사양**: `docs/superpowers/specs/2026-05-26-issue-85-observability-performance-design.md`

## 작업 목록

| ID | 작업 | 파일 | 검증 |
|---|---|---|---|
| T1 | `observed()`을 코루틴 인식 범위 관찰 래퍼로 교체 | `observability/observability-advanced/src/main/kotlin/.../ObservationSupport.kt` | 컴파일 + 부모-자식 테스트 |
| T2 | 캐시 누락 상위-하위 범위 회귀 테스트 추가 | `observability/observability-advanced/src/test/kotlin/.../UserServiceTest.kt` | `:observability-advanced:test` |
| T3 | 이슈 #85 Bluetape4k-first AC에 대한 영어 README 업데이트 | `observability/observability-advanced/README.md` | 소스 이름 grep + 마크다운 검토 |
| T4 | 영어 README 사용자가 직면하는 변경 사항에 맞게 한국어 README 업데이트 | `observability/observability-advanced/README.ko.md` | 소스 이름 grep + 마크다운 검토 |
| T5 | 타겟 검증 실행 | 소스 편집 없음 | `./gradlew :observability-advanced:test` |
| T6 | 6-R단계 이중 코드 검토 실행 | `.omx/artifacts/*` | 코덱스 검토 + Claude 코드 CLI 아티팩트 P0/P1 = 0 |
| T7 | 강의 캡처, 커밋, 푸시, 생성 PR | `docs/lessons/2026-05-26-issue-85-observability-performance.md` | `git status`, PR URL |

## 구현 제약

- 종속성 버전을 변경하거나 새 종속성을 추가하지 마세요.
- CI과 같은 검증에서는 Gatling 로드 테스트를 실행하지 마세요. 문서 smoke/load
  대신 명령과 중지 조건을 사용하세요.
- 이 패스에서 생성된 README 다이어그램 자산을 건드리지 마세요.
- README 공개 API/code 참조를 실제 class/function 이름에 맞춰 정렬하세요.
- Kotlin KDoc은 영어로 유지하고 conversation/internal 텍스트는 한국어 친화적으로 계획하세요.

## 검증 명령

```bash
./gradlew :observability-advanced:test
rg -n "observed\\(|Used Bluetape4k|structuredTaskScopeAll|Dispatchers.VT|gatlingRun" \
  observability/observability-advanced/README.md \
  observability/observability-advanced/README.ko.md \
  observability/observability-advanced/src/main/kotlin \
  observability/observability-advanced/src/test/kotlin
git diff --check
```

## 게이츠 검토

1. 2-R 단계 / 3-R: 로컬 spec/plan 검토와 Claude 코드 CLI 어드바이저.
2. 6-R단계: 현재 Codex 차이점 검토와 Claude 코드 CLI 코드 검토.
3. P0/P1 발견 항목이 수정되고 다시 확인될 때까지 진행이 차단됩니다.

## 정지 조건

PR 생성 및 CI 상태 보고 후 중지합니다. 자동으로 병합하지 마세요.

## 3-R단계 검토 노트

Claude 코드 CLI 검토 스타일 프롬프트는 이 항목에서 빈 아티팩트를 반복적으로 반환했습니다.
Codex 앱 세션. 사용 가능한 집중된 Advisor 아티팩트는 다음과 같습니다.

- `.omx/artifacts/claude-issue-85-spec-plan-blockers-20260526055825.md`

계획 통합:

| 우선순위 | 찾기 | 대응계획 |
|---|---|---|
| P1 | 코루틴 범위 전파로 인해 현재 관찰이 누출되거나 손실될 수 있습니다. | T1는 `ThreadContextElement`을 구현합니다. T2은 상위-하위 범위를 증명합니다. |
| P1 | 취소는 체계적으로 유지되어야 합니다. | T1는 명시적인 `CancellationException` 재투척을 유지합니다. |
| P2 | 런타임 증명은 구체적이어야 합니다. | T5 실행 `:observability-advanced:test`; T6이(가) 차이점을 검토합니다. |

최신 단계 3-R 상태: 실행 가능한 계획의 경우 P0 = 0, P1 = 0입니다.
