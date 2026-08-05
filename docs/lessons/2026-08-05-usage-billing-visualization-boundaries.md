# 사용량 과금 Visualization의 원본과 게시 경계

## Context

사용량 과금 예제는 원장형 기준선, Event Sourcing, 마이크로서비스 구성으로 발전한다. 세 구현을 각각 설명하면 독자가 공통 불변식보다 기술 차이만 기억하기 쉽다. 한국어와 영어 문서를 따로 작성하면 시나리오 수, 상태 결과와 데이터 권위도 쉽게 어긋난다.

Workshop의 Visual Companion Manifest는 공개 문서를 `simulation` 화면 하나로 게시한다. 반면 이번 자료는 한 문서 안에서 `ledger`, `event-sourcing`, `microservices` 세 화면을 전환해야 한다. Site는 Workshop의 가변 Branch가 아니라 병합 SHA를 고정해 Snapshot을 만든다.

## Decision or Finding

### 하나의 구조화 모델이 두 언어와 세 화면을 생성한다

안정적인 View·Scenario·Invariant ID는 `usage-billing-evolution-model.mjs` 한곳에서 관리한다. Generator는 이 모델로 한국어·영어 독립 HTML을 만든다. 전용 Validator는 세 화면, 16개 시나리오, 결과 상태, 최종 권위, 허용된 후속 조치와 한국어 용어 번역 누락을 함께 검사한다.

Manifest Schema는 확장하지 않았다. 게시 시스템에는 `simulation` 문서 하나로 등록하고, 문서 내부의 안정 Fragment인 `#ledger`, `#event-sourcing`, `#microservices`가 화면 선택을 담당한다. 따라서 기존 Publisher를 바꾸지 않으면서도 특정 글에서 필요한 화면으로 직접 연결할 수 있다.

### PNG는 화면 크기와 생성 입력을 함께 고정한다

초기 1440×1800 Capture는 하단 절반 이상이 비어 있었다. Full-size 검수 뒤 콘텐츠 밀도에 맞는 1440×900으로 조정했다. 세 화면 × 두 언어 × Light/Dark의 12개 조합을 서로 다른 Chrome Profile에서 두 번 생성하고, PNG Header 크기와 SHA-256이 모두 같을 때만 원본 위치로 복사한다.

HTML은 외부 Font, Script, Stylesheet, Media와 Network 요청을 사용하지 않는다. 테마 우선순위는 Capture Query, 저장된 Starlight Theme, 시스템 설정 순이다. Capture Mode에서는 애니메이션을 제거한다.

### 한국어 다이어그램은 제목만 번역해서는 안 된다

첫 Full-size Capture에서 카드 제목은 한국어였지만 상태, 이벤트와 후속 조치는 영문 안정 ID를 그대로 표시했다. 안정 ID는 모델 내부에 유지하되, 한국어 문서는 모든 시나리오 용어를 별도 사전으로 표시한다. Validator가 새 Scenario Term의 한국어 문구 누락을 거부하도록 해 같은 문제가 다시 생기지 않게 했다.

### Workshop 병합이 Site 게시보다 먼저다

Workshop HTML과 PNG가 원본이다. Site는 Workshop PR이 병합된 뒤 실제 Merge SHA의 Detached Worktree에서 HTML Snapshot과 Blog Asset을 가져와야 한다. 로컬 Branch나 `develop` URL을 직접 참조하면 글과 시각 자료가 나중에 서로 다른 구현을 설명할 수 있다.

## Outcome

- 세 아키텍처 선택을 같은 불변식과 시나리오 구조로 비교할 수 있다.
- 한국어·영어 HTML은 같은 모델에서 결정적으로 생성된다.
- 12개 PNG는 Blog, JavaScript 제한 환경과 크게 보기 UI에서 사용할 수 있다.
- 기존 Manifest Schema와 Site Publisher를 확장하지 않았다.
- 한국어 상태·이벤트가 영문 안정 ID로 노출되는 현상을 자동 검증으로 차단했다.

## Verification

- 공통 Visual Companion Validator: 문서 5개, Locale 파일 10개 통과
- 전용 Validator: View 3개, Scenario 16개, Locale 2개 통과
- Chrome 151.0.7922.72 Capture: 12개, 1440×900, 독립 Profile 간 SHA-256 12/12 일치
- Full-size·Contact Sheet: 글자 잘림, 연결선 겹침, 화살촉 방향 오류와 불필요한 대형 여백 없음
- Browser: 한국어·영어 Fragment, Scenario 수, History Back, Theme 전환, 360px 문서 폭과 내부 Flow Scroll 검증
- Browser Console Error: 0건

## Future Guidance

1. 같은 개념의 여러 구현을 비교할 때는 화면별 파일보다 공통 Scenario ID와 불변식 모델을 먼저 정의한다.
2. Manifest에 없는 세부 화면은 문서 내부 Fragment로 제공하고 Publisher Schema를 성급히 확장하지 않는다.
3. 한국어 다이어그램은 제목뿐 아니라 상태, 이벤트, 권위와 후속 조치까지 번역한다.
4. 결정적 PNG는 프로필을 분리한 두 번의 Capture와 Hash 비교로 검증한다.
5. Fallback 화면 크기는 미리 고정하지 말고 첫 Full-size 결과의 콘텐츠 밀도를 확인해 확정한다.
6. 외부 사이트는 원본 저장소의 병합 SHA를 고정한 뒤에만 Snapshot과 파생 Asset을 만든다.

