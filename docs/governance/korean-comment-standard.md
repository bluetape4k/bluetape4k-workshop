# 한국어 KDoc/주석 작성 기준

## 목적

이 문서는 이슈 #600의 산출물이며, 이슈 #591-#598에서 Kotlin KDoc과 코드 주석을
한국어로 정리할 때 적용할 공통 검토 기준이다. 기준의 목적은 단순 번역이 아니라
예제 코드를 읽는 사람이 제약, 의도, 실패 경계, 운영상 주의점을 빠르게 파악하게
하는 것이다.

## 공통 규칙

- 한국어 문장을 기본으로 사용한다.
- 클래스명, 함수명, 속성명, 인자명, package, annotation, Gradle task, command,
  URL, 외부 제품명, 정확한 오류 메시지는 원문을 유지한다.
- 코드가 이미 말하는 obvious syntax를 반복하지 않는다.
- 주석은 예제의 선택 이유, caller 책임, transaction/coroutine/lifecycle 경계,
  실패 시 의미처럼 코드만으로 놓치기 쉬운 정보를 보강한다.
- production behavior를 바꾸지 않는다. 주석 정리 중 동작 결함을 발견하면 별도
  이슈로 분리하고 현재 PR에서는 주석 변경만 유지한다.

## KDoc 기준

KDoc은 public surface뿐 아니라 예제 이해에 필요한 internal contract에도 작성한다.
다음 항목 중 해당되는 내용을 포함한다.

- 이 타입이나 함수가 예제에서 맡는 역할.
- caller가 만족해야 하는 전제 조건.
- 반환값의 의미와 비어 있음/null/실패 표현 방식.
- transaction, coroutine, dispatcher, virtual thread, Testcontainers 같은 실행 경계.
- persistence, serialization, messaging, cache, graph traversal 같은 외부 상태와의 관계.
- 예외 또는 validation 실패가 caller에게 전달되는 방식.

좋은 KDoc은 기능 목록보다 선택 기준을 설명한다. 예를 들어 “이 함수는 조회한다”보다
“PostgreSQL이 authoritative state를 갖고 Redis는 admission gate로만 쓰는 이유”를
설명하는 쪽이 낫다.

## 속성 주석 기준

속성 주석은 다음을 가능한 한 구체적으로 설명한다.

| 항목 | 설명 |
|---|---|
| 목적 | 이 값이 예제의 어떤 의사결정이나 상태 전이에 쓰이는지 |
| 허용 값 | 값의 범위, enum 의미, 빈 문자열/음수/0 허용 여부 |
| 단위 | 시간, 크기, 개수, 비율, 금액, retry 횟수 같은 단위 |
| 기본값 | 생략 시 적용되는 값과 그 이유 |
| nullability | `null`이 미지정, 미존재, 실패, lazy loading 중 무엇을 뜻하는지 |
| 영속화/직렬화 | DB column, Kafka payload, JSON field, cache key와의 매핑 |
| 동시성 | atomicity, visibility, lock ownership, transaction boundary |
| 운영 주의점 | timeout, retry, backpressure, rate limit, resource cleanup |

## 함수 인자 주석 기준

함수 인자 주석은 caller 관점에서 작성한다.

- caller가 준비해야 하는 값과 값의 출처.
- 같은 타입 인자가 여러 개 있을 때 순서 혼동을 막는 설명.
- 인자가 transaction 내부에서만 유효한지, coroutine context에 묶이는지.
- 인자가 외부 I/O, cache invalidation, event publication, retry 동작을 유발하는지.
- validation 실패 시 예외 타입이나 domain error가 어떻게 표현되는지.
- optional/default 인자가 생략될 때 어떤 정책이 적용되는지.

## Inline Comment 기준

Inline comment는 다음 경우에만 유지하거나 추가한다.

- 테스트가 의도적으로 race, timeout, retry, cancellation, fixture ordering을 만든다.
- Exposed DSL, coroutine bridge, virtual thread, graph traversal처럼 receiver나 execution
  context가 오해되기 쉽다.
- 예제 코드가 production 권장 방식이 아니라 학습을 위해 일부러 단순화되어 있다.
- 외부 시스템이 authoritative source이고 local state는 projection/cache/admission gate다.

반대로 “값을 변수에 저장한다”, “목록을 순회한다”처럼 코드와 같은 말을 반복하는 주석은
한국어로 번역하지 말고 삭제하거나 더 의미 있는 설명으로 교체한다.

## 검토 체크리스트

각 comment/KDoc PR은 다음을 DoD에 기록한다.

- [ ] 변경된 `*.kt` 파일 목록과 담당 issue scope.
- [ ] `README*`, `AGENTS.md`, `CLAUDE.md`, `docs/manual/en/**`, `docs/manual/ko/**`,
      `build/**`, `.worktrees/**` 변경 없음.
- [ ] 새로 작성하거나 의미 있게 수정한 KDoc/주석은 한국어이며 identifier를 보존한다.
- [ ] 속성 주석은 목적, 범위/단위/nullability/default 중 해당 항목을 설명한다.
- [ ] 함수 인자 주석은 caller 책임, 허용 값, 부작용, 실패 경계 중 해당 항목을 설명한다.
- [ ] inline comment는 non-obvious intent 또는 constraint를 설명한다.
- [ ] `node scripts/validate-korean-rewrite-scope.mjs changed --issue <issue> --base <parent-branch>` PASS.
- [ ] `git diff --check` PASS.
- [ ] Kotlin source를 건드린 경우 가능한 가장 좁은 `compileKotlin` 또는 module test PASS.

## 샘플링 기준

대량 PR은 모든 주석을 줄 단위로 다시 보고하기보다 다음 샘플을 반드시 포함한다.

- changed Kotlin file 중 최소 5개 또는 전체 변경 파일이 5개 미만이면 전부.
- data access, coroutine, messaging, graph, Spring runtime처럼 성격이 다른 하위 영역이
  섞이면 영역별 최소 1개.
- property 주석과 function argument 주석이 모두 포함된 PR이면 각각 최소 1개.
- 삭제한 obvious comment가 있다면 삭제 이유와 대표 파일 1개.

샘플에서 기준 위반이 발견되면 해당 PR은 P1로 보고하고, 같은 패턴이 있는 파일을
추가 검색한 뒤 수정한다.

