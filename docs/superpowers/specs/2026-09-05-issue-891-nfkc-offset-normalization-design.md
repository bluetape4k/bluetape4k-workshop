# #891 NFKC offset·normalization 경계 설계

## 목적

`kotlin/text-processing`과 `spring-boot/text-moderation-api`가 `bluetape4k-text`
1.0.0의 NFC/NFKC 원문 offset 복원과 normalization segment 상한을 실제 소비하도록 한다.
기존 기본값은 NFC로 유지하고, 명시적으로 NFKC를 선택한 caller만 compatibility
문자 탐지를 활성화한다.

## 근거와 upstream 계약

- Workshop Issue #891, `bluetape4k-text` Issue #244와 merged PR #279를 기준으로 한다.
- `AhoCorasickAutomaton`은 keyword와 입력을 같은 `NormalizationForm`으로 정규화하고,
  내부 `OffsetMapping`으로 match의 inclusive start/end를 원본 Kotlin `String` code-unit
  범위에 되돌린다.
- NFKC에서는 `㈜` 한 글자가 `(주)` 세 글자로 확장되지만 반환 match는 원문의
  `㈜` 한 code-unit을 가리킨다.
- 한 starter 뒤 normalization 상호작용 segment가 1,024 code-unit을 초과하면
  upstream이 raw 입력을 포함하지 않는 `IllegalArgumentException`으로 fail-fast한다.

## API와 호환성

- `AbuseWordFilter` constructor에 `normalization: NormalizationForm = NFC`를 추가한다.
- `SensitiveRedactionPolicy.of`에 `keywordNormalization: NormalizationForm = NFC`를
  추가하고 immutable policy 값으로 보관한다. Regex rule은 정규화하지 않는다.
- `TextModerationProperties`에 `normalization: NormalizationForm = NFC`를 추가하고
  Spring configuration이 singleton automaton 생성에 그대로 전달한다.
- 기존 constructor와 property caller는 default argument로 NFC 동작을 유지한다.
  `NormalizationForm.NONE`도 upstream 공개 enum이므로 별도 제한하지 않는다.

## offset·masking 경계

- workshop은 normalized index를 직접 계산하지 않고 upstream automaton의 원문 match
  range를 사용한다.
- public span은 기존 계약대로 원본 Kotlin `String`의 half-open code-unit range다.
  `AhoCorasickMatch.end`의 inclusive 원문 offset에 1을 더해 변환한다.
- NFKC expansion match를 mask할 때 normalized keyword 길이가 아니라 원문 span 길이만큼
  mask한다. 따라서 `㈜`는 `*` 하나가 되고 전체 문자열 길이가 보존된다.
- overlap merge와 adjacent 분리는 원문 range 변환 뒤의 기존 로직을 그대로 사용한다.

## 실패·보안·운영 경계

- 1,025개 combining mark를 붙인 입력은 upstream segment 상한에서 즉시 거부된다.
- exception message에는 segment 길이와 상한만 허용하고 원문, keyword, 탐지 값은
  로그나 response에 기록하지 않는다.
- Spring HTTP 예제는 기존 exception mapping과 response schema를 유지하며 새로운
  정책 변경 endpoint를 추가하지 않는다.

## 테스트 전략

1. NFC 기본값이 기존 decomposed Unicode와 ASCII fixture를 계속 처리하는지 검증한다.
2. NFKC filter/pipeline에서 `(주)` keyword가 원문 `㈜`를 찾아 정확한 원문 range와
   same-length mask를 반환하는지 검증한다.
3. overlap/adjacent 회귀 테스트를 NFKC 정책에서도 실행한다.
4. 1,025 combining mark 입력이 raw-free `IllegalArgumentException`을 발생시키는지
   filter와 pipeline에서 검증한다.
5. Spring property binding으로 `NFKC`를 선택해 compatibility 입력을 탐지하고 기본
   NFC context가 유지되는지 검증한다.

## 범위 밖

새 PII classifier, locale 자동 추론, normalized offset 공개 API, streaming scanner,
runtime normalization 변경 endpoint, 독자적인 offset mapping 구현은 포함하지 않는다.

## 롤백

추가된 defaulted option과 문서·테스트만 되돌리면 된다. 데이터 migration과 HTTP
schema 변경은 없다.
