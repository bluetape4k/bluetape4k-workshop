# Issue #890 VersionedDictionary 런타임 reload와 rollback

## Context

`bluetape4k-dependencies` 2.0.0의 `tokenizer-core`는 `VersionedDictionary`와
`DictionarySnapshot`을 제공하지만, workshop의 검색 index와 moderation blockword는 시작 시
한 번 만든 객체만 사용했다. 운영 중 reload를 설명하려면 새 revision을 공개하는 것뿐 아니라
요청이 부분 generation을 보지 않는 경계, 실패·stale 보존, 제한된 rollback을 함께 보여줘야 했다.

## Decision or Finding

- `VersionedMultilingualSearchIndex`는 public Korean dictionary snapshot의 noun set으로
  exact-match automaton을 만들고 document와 query에 같은 matcher를 주입한 뒤 전체
  `MultilingualSearchIndex` generation을 공개한다. 공개 후 global provider를 다시 읽지 않는다.
- `VersionedModerationDictionary`도 validation과 Aho-Corasick build를 끝낸 뒤 완성된
  automaton만 공개한다. 단어 수·길이·전체 문자 수는 bounded input으로 제한한다.
- 검색과 moderation 요청은 시작 시 snapshot을 한 번만 읽는다. Version과 결과, parsing과
  masking은 각각 같은 generation을 사용한다.
- Shared Lingua detector 접근은 동기 lock으로 직렬화하고 dictionary name은 고정 allowlist로
  제한해 concurrency drift와 log injection을 막는다.
- Reload 관리 API는 service layer에만 둔다. 공개 HTTP endpoint를 만들지 않아 인증 없는
  dictionary mutation surface를 피한다.
- 기존 direct-construction caller와 Spring bean contract는 보존하고, raw blockword나 입력
  text 대신 revision과 크기 metadata만 로그에 남긴다.

## Outcome

두 모듈은 application-owned `VersionedDictionary`를 사용해 Korean noun snapshot/index와
moderation automaton의 완성된 generation만 원자적으로 교체한다. Loader/build 실패나 stale
revision은 현재 값과 history를 바꾸지 않으며,
`historyCapacity` 안에서 rollback할 수 있다. 검색 결과는 사용한 revision을 명시하고 기존
moderation HTTP JSON 응답은 그대로 유지한다.

## Verification

- 두 모듈 clean suite: 86 tests passed (`kotlin-text-processing` 67, `spring-boot-text-moderation-api` 19)
- Slow loader 중 reader 비차단, Korean noun v1/v2 index-query 결합, shared detector contention,
  stale/실패 보존, bounded history, 동시 old/new generation 관찰 테스트 통과
- Root detekt, README language/parity, stale-check, ecosystem scope/unit, dependency resolution,
  actionlint, diff-check를 PR 전 검증 대상으로 고정

## Future Guidance

운영 control plane을 추가할 때는 인증·감사·rate limit과 durable revision store를 별도 경계로
설계한다. Candidate 준비는 caller-owned dispatcher에서 수행하고, wrapper 내부에 unmanaged
coroutine scope를 만들지 않는다. 전역 provider나 부분 tokenizer 교체로 application snapshot
원자성을 우회하지 않는다. 외부 collection은 순회 중 bounded defensive snapshot으로 만들고,
개별 크기뿐 아니라 aggregate character 상한도 적용한다.
