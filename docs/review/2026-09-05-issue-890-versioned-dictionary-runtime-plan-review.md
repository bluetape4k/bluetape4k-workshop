# #890 VersionedDictionary 런타임 reload·rollback 계획 review

## 판정

**APPROVED WITH GATES** — 독립 worktree와 TDD 순서, 두 모듈의 호환성, bounded
history, exact-head PR 검증이 명시됐다.

## 필수 gate

- red test가 새 wrapper API 부재로 실패하는 증거를 먼저 남긴다.
- candidate build 이후 publish 순서와 요청당 snapshot 1회 캡처를 코드와 테스트로
  확인한다. slow-loader 중 current reader non-blocking과 늦은 낮은 revision 거부를
  barrier로 고정한다.
- Korean public dictionary snapshot에서 만든 exact noun matcher를 document/query에 함께
  주입하고, shared language detector contention을 직렬화한다.
- loader/stale/input-limit 실패 뒤 current와 rollback journal이 유지돼야 한다.
- 외부 collection은 bounded defensive snapshot으로 복사하고 개별·aggregate character 상한을
  초과하면 candidate publish 전에 거부해야 한다.
- 기존 `TextModerationService(..., moderationAutomaton=...)`, `analyze`,
  `MultilingualSearchIndex.search`, Spring singleton bean 테스트가 계속 통과해야 한다.
- HTTP reload endpoint, raw dictionary log, 개별 library BOM, 2.1.0-SNAPSHOT은
  추가하지 않는다.
- dictionary name을 고정하고 old/new revision과 count만 audit metadata로 남긴다.
- 두 module은 transitive dependency에 기대지 않고 versionless
  `libs.bluetape4k.text.core`를 직접 선언한다.
- README/test에 service-level v1→v2→rollback과 metadata 관찰 경로를 제공하고,
  HTTP response에는 revision을 노출하지 않는 경계를 명시한다.
- PR 생성 전 local six-lens review, 전체 지정 검증과 docs/manifest/stale coverage를
  완료한다.

## 결론

위 gate를 순서대로 검증하면 구현 단계로 진행할 수 있다. merge는 다섯 PR의
최종 exact-head 확인 뒤 사용자 승인 전까지 보류한다.
