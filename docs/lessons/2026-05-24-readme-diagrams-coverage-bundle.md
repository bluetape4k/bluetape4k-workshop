# Lessons — README, Diagram, Coverage Matrix Bundle

**Date**: 2026-05-24  
**Issues**: #90 (README rewrite), #89 (diagram asset convention), #92 (coverage matrix)  
**Type**: Type-E Maintenance  
**Branch**: `docs/issue-76-readme-diagrams-coverage`

---

## 근본 원인 / 동기

이전 root `README.md`는 대부분 한국어로 된 93줄 문서였고, module-level detail
(difficulty, infra requirement, bluetape4k library mapping, run command)이 없었다.
신규 contributor와 bluetape4k 사용자는 적절한 시작 예제를 빠르게 고르거나 coverage
gap을 이해할 방법이 없었다.

추가 문제는 다음과 같았다.

- diagram naming과 placement convention이 없었다(`docs/assets/CONVENTIONS.md` 부재).
- bluetape4k library → existing example → gap을 매핑하는 문서가 없어 신규 workshop
  module 우선순위를 정하기 어려웠다.

---

## 결정 사항

### README structure

**6-domain catalog**(Data Access, Spring Boot Ops, Serialization/Messaging,
Async/Reactive, Observability/Performance, Architecture Extensions)을 선택했다. 이유는
다음과 같다.

- open issue #79, #82, #83(Basic) 및 향후 Advanced module에 직접 매핑된다.
- bluetape4k library group 구성을 반영한다.
- 각 domain 안에서 Basic → Advanced로 이어지는 점진적 학습이 가능하다.

catalog table의 각 row는 module name(linked), 사용하는 bluetape4k libs, infra
(TC = Testcontainers), learning outcome을 보여준다. 이는 학습 가치를 전달하지 못하던
일반 directory listing을 대체한다.

### README.ko.md

translation wrapper가 아니라 완전한 한국어 counterpart로 만들었다. workspace
CLAUDE.md 기준은 다음과 같다.

- `README.md`는 English를 유지한다(contributor-facing).
- `README.ko.md`는 Korean-speaking user를 위한 문서다.
- 둘은 동기 유지되며, 향후 한쪽 변경은 다른 쪽에도 반영해야 한다.

### docs/assets/CONVENTIONS.md

ad-hoc name 대신 `<scope>-<type>-<seq>.<ext>` naming pattern을 정의했다. 핵심
결정은 "diagram은 있지만 편집할 수 없는" technical debt를 막기 위해 source file
(`.drawio`, `.puml`, `.mmd`)을 export와 함께 commit하는 것이다.

### Coverage matrix

30개 bluetape4k lib를 추적했다. 결과는 57% good coverage, 40% partial, 3% missing
(idgenerators)이었다. 이는 issue #62, #79, #82, #83의 구체 작업을 드러내고 Tier 2
우선순위 판단에 도움을 준다.

---

## 결과

| Artifact | Lines | Status |
|----------|-------|--------|
| `README.md` | 207 | ✅ Rewritten |
| `README.ko.md` | 209 | ✅ Created |
| `docs/assets/CONVENTIONS.md` | 101 | ✅ Created |
| `docs/images/readme-diagrams/root-readme-architecture.md` | 92 | ✅ Created |
| `docs/coverage-matrix.md` | 145 | ✅ Created |

code 변경은 없다. Type-E이므로 tests/compile은 필요하지 않았다.

---

## 향후 지침

1. **모듈 추가/제거마다 README 동기화**: 새 workshop module이 들어오면 같은 PR에서
   `README.md`와 `README.ko.md` 양쪽에 추가한다. table format은 다른 부분을 건드리지
   않고 row 하나를 쉽게 추가할 수 있게 한다.

2. **Coverage matrix는 living document다**: proposed scenario가 실제 module이 되면
   `docs/coverage-matrix.md`를 갱신한다. row를 "Proposed"에서 "Existing example"로
   옮기고 coverage level을 갱신한다.

3. **Diagram source file**: PNG/SVG만 commit하지 않는다. diagram이 계속 편집 가능하도록
   source(`.drawio`, `.puml`, `.mmd`)를 항상 함께 commit한다. CONVENTIONS.md checklist가
   이를 강제한다.

4. **README.ko.md locale discipline**: CLAUDE.md 문서 언어 정책은 user-facing Korean
   README file을 허용한다. content 변경 시 drift를 막기 위해 같은 PR에서 두 locale을
   모두 갱신한다.
