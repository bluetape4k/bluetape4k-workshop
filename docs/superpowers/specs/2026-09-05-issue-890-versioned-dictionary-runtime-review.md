# #890 VersionedDictionary 런타임 reload·rollback 설계 review

## 판정

**KEEP WITH REVISION** — application-owned bounded store와 whole-snapshot swap은
Issue #890의 원자성·rollback 요구를 충족한다. 구현은 아래 경계를 지켜야 한다.

## 관점별 검토

- 기능/API: 기존 동기 API와 constructor를 유지하고 version을 포함한 opt-in 결과와
  reload/rollback API만 추가한다. `VersionedSearchResult`와
  `VersionedModerationResult`가 exact version/result 경계를 정의한다.
- Kotlin/동시성: 요청당 `snapshot()`은 한 번만 호출하고 shared Lingua detector 접근은
  동기 lock으로 보호한다. candidate build는 lock 밖에서 끝내고 immutable value만 publish한다.
  `runBlocking`은 추가하지 않는다.
- 성능/안정성: search는 부분 tokenizer 교체 대신 완성된 index generation을
  교체한다. 동시 낮은 revision과 loader 실패는 current/history를 보존한다.
- 보안: reload/rollback HTTP endpoint를 만들지 않는다. 입력 크기를 제한하고
  raw word/text/source/exception payload를 로그에서 제외한다.
- 운영: dictionary name은 고정하고 old/new revision, operation, count만 구조화된
  metadata와 로그로 확인한다.
- Spring DI: `@Autowired` primary constructor는 versioned bean을 받고, 기존
  automaton secondary constructor는 direct caller 호환 adapter로 남긴다. HTTP JSON
  schema는 변경하지 않는다.
- 문서/소비자: `bluetape4k-dependencies 2.0.0` BOM과 versionless
  `bluetape4k-text-core` alias만 사용하고 README 양국, matrix, stale guard,
  manifest, lesson을 함께 갱신한다.

## 사전 검토 finding 처리

| Finding | 처리 |
|---|---|
| upstream provider `historyCapacity=0` | workshop-owned `VersionedDictionary`에 명시적 capacity 적용 |
| 요청 중 mixed revision | 요청 시작 시 snapshot 1회 캡처 |
| search index/query generation 불일치 | public Korean noun snapshot으로 exact matcher를 만들고 document/query에 같은 matcher를 주입 |
| shared detector 동시 접근 | 두 모듈의 detector lock과 contention test 적용 |
| loader가 mutation lock 점유 | candidate build 후 `reload(DictionarySnapshot)` publish |
| public endpoint 정책 변조 | endpoint 미추가, 내부 service API로 제한 |
| mutable collection·민감 로그 | bounded defensive snapshot, aggregate limit, metadata-only 로그 |
| versioned API·Spring DI 모호성 | exact signature, `@Autowired` primary와 compatibility secondary constructor 명시 |
| 실행 가능한 caller 경계 부재 | README와 service test에 v1→v2→rollback 및 metadata 관찰 예제 추가 |
| transitive `tokenizer-core` 의존 | 두 module에 versionless direct alias 추가 |
| coroutine 적용 범위 | sync wrapper로 한정하고 caller-owned dispatcher 경계를 문서화 |

## 결론

P0 blocker는 없다. P1은 위 설계로 닫았고, P2는 failure/history/concurrency/log
테스트와 기존 API 회귀 테스트를 구현 단계에서 닫는다.
