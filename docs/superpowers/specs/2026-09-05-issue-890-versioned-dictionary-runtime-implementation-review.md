# Issue #890 VersionedDictionary 런타임 reload·rollback 구현 리뷰

## 리뷰 범위

- `bluetape4k-dependencies` 2.0.0의 `VersionedDictionary` 소비 경계
- `kotlin/text-processing`의 versioned whole-index generation
- `spring-boot/text-moderation-api`의 versioned blockword automaton과 service wiring
- 테스트, EN/KO README, coverage/manifest, stale guard, lesson

## Six-lens 결과

| 관점 | 결과 | 근거 |
|---|---|---|
| 기능 | PASS | Korean noun snapshot/index 결합, reload·rollback·history·stale·loader 실패와 요청별 revision 일관성을 테스트함 |
| API/호환성 | PASS | 기존 `MultilingualSearchIndex`, moderation HTTP response, automaton secondary constructor를 보존함 |
| 성능/안정성 | PASS | bounded defensive snapshot과 aggregate limit을 적용하고, 전체 candidate build는 mutation lock 밖에서 끝나며 slow loader 중 reader가 현재 snapshot으로 진행함 |
| 보안/운영 | PASS | 고정 dictionary name, bounded input, old/new revision audit metadata를 적용하고 공개 reload endpoint를 만들지 않음 |
| Kotlin/동시성 | PASS | 두 모듈의 shared detector lock, immutable candidate snapshot, no unmanaged scope, completed generation publish를 사용함 |
| 사용자/문서 | PASS | EN/KO README가 전체 index 교체, service 관리 경계, 실패·stale·rollback 의미를 동일하게 설명함 |

## 발견 사항과 해소

- P1: Search document와 query tokenizer가 서로 다른 generation을 볼 수 있음 — query tokenizer만
  바꾸지 않고 public Korean noun snapshot에서 만든 exact matcher를 document와 query 양쪽에
  주입한 완성된 `MultilingualSearchIndex` 전체를 교체했다. 공개 후 global provider를 읽지 않는다.
- P1: Shared Lingua detector의 동시 접근 안전성이 불명확함 — 두 모듈의 detector 접근에
  동기 lock을 적용하고 concurrent caller/request 회귀 테스트를 추가했다.
- P1: 인증 없는 runtime mutation endpoint가 생길 수 있음 — management API는 service layer에만
  두고 기존 controller contract를 유지했다.
- P1: Loader가 upstream mutation lock 안에서 실행되면 reader가 정지할 수 있음 — loader와
  candidate build 후 `reload(DictionarySnapshot)`만 호출하며 slow-loader barrier test로 고정했다.
- P2: 외부 collection mutation과 oversized dictionary가 candidate build를 흔들 수 있음 — 입력을
  build 전에 bounded snapshot으로 복사하고 document/noun/blockword count, 개별 길이, aggregate
  character 수를 제한했다.
- P2: dictionary name 기반 log injection과 전환 추적 누락 — 고정 name을 검증하고 raw 값 없이
  `previousRevision`/`revision`과 count만 기록한다.
- P2: 기존 Spring/direct-construction caller가 깨질 수 있음 — `@Autowired` primary constructor와
  기존 automaton secondary constructor를 함께 유지했다.
- P2: Spring property 초기화가 runtime limits를 우회할 수 있음 — 기존 automaton bean을 유지하되
  같은 canonical words를 bounded 검증하는 `fromAutomaton(words=...)` 경로로 초기 snapshot을 만든다.

## 검증 증거

- Clean module tests: `kotlin-text-processing` 67, `spring-boot-text-moderation-api` 19, 합계 86 통과
- Root detekt, README language/parity, stale-check, ecosystem checker unit 113/113, actionlint 통과
- 두 module에서 `io.github.bluetape4k.text:tokenizer-core:1.0.0` resolution 확인
- Exact-head hosted CI와 GitHub metadata는 PR 생성 후 확인한다.

## 판정

P0 0건, P1 0건. 남은 범위는 인증·감사·durable revision store를 갖춘 운영 control plane이며
Issue #890의 workshop 범위 밖이다.
