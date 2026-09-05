# Issue #891 NFKC offset와 normalization 경계

## Context

`bluetape4k-dependencies` 2.0.0으로 올린 두 text 예제는 `NormalizationForm.NFC`를
고정해 canonical Unicode 변형은 찾았지만,
`㈜`처럼 NFKC에서 길이가 달라지는 compatibility 문자는 정책 단어 `(주)`와 일치하지
않았다. Workshop에서 normalization을 직접 수행하면 match offset이 normalized text 기준이
되어 원문 masking 위치와 길이를 잃을 수 있었다.

## Decision or Finding

- Workshop은 자체 offset table을 만들지 않고 `bluetape4k-text` 1.0.0의
  `AhoCorasickAutomaton`과 내부 `OffsetMapping` 경계를 소비한다.
- 기존 caller는 NFC default를 유지한다. NFKC는 `AbuseWordFilter`,
  `SensitiveRedactionPolicy`, Spring `TextModerationProperties`에서 명시적으로 선택한다.
- Match와 public span은 normalized 문자열이 아니라 원본 Kotlin `String` code-unit 범위를
  사용한다. 따라서 `(주)`가 원문 `㈜`와 일치해도 mask는 `*` 하나다.
- Overlap merge와 adjacent 분리는 원문 range 변환 뒤의 기존 규칙을 유지한다.
- Starter와 combining mark가 만드는 normalization segment는 upstream 1,024 code-unit
  상한을 그대로 적용한다. 초과 입력은 raw text를 노출하지 않고 fail-fast한다.

## Outcome

두 예제는 NFC와 NFKC를 선택할 수 있고, compatibility expansion이 있어도 source offset과
same-length masking을 보존한다. Spring property binding도 같은 enum을 singleton automaton에
전달하며 HTTP response schema는 바뀌지 않는다.

## Verification

- `㈜` → `(주)` NFKC match의 원문 start/end와 한 글자 masking 테스트
- NFKC adjacent match와 기존 overlap/adjacent·NFC decomposed Unicode 회귀 테스트
- 1,025 combining mark 입력의 raw-free `IllegalArgumentException` 테스트
- Spring `workshop.text-moderation.normalization=NFKC` binding과 masking 테스트
- Targeted clean suites, detekt, README parity, stale/ecosystem/actionlint를 완료 기준으로 고정

## Future Guidance

Normalization을 적용한 뒤 substring index를 직접 원문에 사용하지 않는다. 항상 matching
library의 원문 range 계약을 소비하고, mask 길이는 normalized keyword 길이가 아니라 원문
span 길이로 계산한다. Locale별 자동 정책이나 streaming 처리가 필요하면 별도 issue에서
정책 registry와 chunk 경계 offset mapping을 먼저 설계한다.
