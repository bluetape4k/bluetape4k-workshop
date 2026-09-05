# #890 VersionedDictionary 런타임 reload·rollback 설계

## 목적

`kotlin/text-processing`과 `spring-boot/text-moderation-api`에
`bluetape4k-text 1.0.0`의 `VersionedDictionary`를 소비하는 예제를 추가한다.
완성된 immutable candidate만 원자적으로 공개하고, 요청은 시작 시점의 snapshot을
한 번만 읽는다. 기존 `MultilingualSearchIndex.search`, `TextModerationService.analyze`
및 직접 생성 constructor는 유지한다.

## 근거와 upstream 계약

- Workshop Issue #890, `bluetape4k-text` Issue #102, merged PR #220과 #257을
  기준으로 한다.
- `DictionaryVersion`은 비어 있지 않은 이름과 음이 아닌 revision을 요구한다.
- `VersionedDictionary.reload`는 같은 이름의 더 높은 revision만 허용하며,
  loader 실패나 revision 검증 실패 시 현재 snapshot과 history를 보존한다.
- `historyCapacity`는 현재 snapshot을 제외한 rollback journal 크기다. `0`이면
  rollback이 비활성화되고, `N`이면 최근 `N`개 이전 snapshot만 보존한다.
- upstream Korean/Japanese provider는 내부 저장소를 `historyCapacity=0`으로
  생성하므로 workshop rollback 예제의 저장소로 직접 재사용하지 않는다.
- `VersionedDictionary`는 value 내부를 deep copy하지 않는다. workshop은
  collection을 복사하고 완성된 search index 또는 Aho-Corasick automaton만 value로
  넣는다.

## Kotlin text-processing 설계

`VersionedMultilingualSearchIndex`는
`VersionedDictionary<MultilingualSearchIndex>`를 application-owned store로 가진다.

- `indexOf(version, documents, historyCapacity, detectionService)`가 최초 완성된
  index generation을 만든다.
- `search(query, limit)`는 store의 snapshot을 정확히 한 번 읽고, 그 snapshot의
  index로 검색한 결과와 `DictionaryVersion`을 함께 반환한다.
- `reload(version, loader)`는 loader가 반환한 document를 독립 collection으로
  복사하고 새 `MultilingualSearchIndex`를 lock 밖에서 완성한 뒤
  `reload(DictionarySnapshot(...))`으로 publish한다.
- `rollback()`은 bounded journal의 최근 generation으로 되돌아간다.
- 기존 `MultilingualSearchIndex`는 변경하지 않는다. 외부에서 upstream global
  Korean/Japanese provider를 독립 변경하는 행위는 이 store의 generation 계약에
  포함하지 않는다. query tokenizer만 따로 reload하는 부분 갱신은 지원하지 않는다.
- `CoroutineMultilingualSearchIndex`는 이번 sync wrapper의 적용 대상이 아니다.
  coroutine caller는 blocking loader를 넘기지 말고 자신의 `Dispatchers.IO` 또는
  `Dispatchers.Default` 경계에서 document를 준비한 뒤 sync publish API를 호출한다.
  workshop wrapper 내부에는 `runBlocking`을 두지 않는다.

공개 signature는 다음과 같이 기존 API와 분리한다.

```kotlin
data class VersionedSearchResult(
    val version: DictionaryVersion,
    val hits: List<SearchHighlightHit>,
)

class VersionedMultilingualSearchIndex {
    fun currentVersion(): DictionaryVersion
    fun search(query: String, limit: Int = 10): VersionedSearchResult
    fun reload(version: DictionaryVersion, documents: Collection<SearchDocument>): DictionaryVersion
    fun reload(version: DictionaryVersion, loader: () -> Collection<SearchDocument>): DictionaryVersion
    fun rollback(): DictionaryVersion

    companion object {
        fun indexOf(
            version: DictionaryVersion,
            documents: Collection<SearchDocument>,
            historyCapacity: Int = 1,
            detectionService: LanguageDetectionService = LanguageDetectionService(),
        ): VersionedMultilingualSearchIndex
    }
}
```

## Spring text moderation 설계

`VersionedModerationDictionary`는
`VersionedDictionary<ModerationDictionaryValue>`를 감싸고, value에는 완성된
`AhoCorasickAutomaton<String>`과 안전한 count metadata만 둔다.

- 초기 blockword와 reload 입력은 blank 제거·중복 제거 후 새 collection으로
  materialize한다.
- 단어 수, 개별 단어 길이, 전체 문자 수에 명시적 상한을 적용한다.
- `reload(version, loader)`는 loader 실행, 입력 검증, automaton build를 mutation
  lock 밖에서 끝내고 완성된 snapshot만 publish한다.
- `snapshot()`은 module 내부에서만 사용하며 mutable collection이나 word list를
  공개하지 않는다. 공개 metadata는 name, revision, word count, total characters뿐이다.
- `TextModerationService.analyzeWithVersion`은 요청당 snapshot을 한 번 캡처하고
  parse와 mask에 같은 automaton을 사용한다. 기존 `analyze`는 versioned 결과의
  response만 반환한다.
- Spring configuration은 기존 singleton automaton bean을 유지하면서 revision 1,
  history capacity 2인 versioned bean을 추가한다. 기존 automaton constructor도
  source compatibility를 위해 유지한다.
- reload·rollback은 내부 service API로만 제공하며 HTTP endpoint를 추가하지 않는다.

공개 모델과 service signature는 다음과 같다.

```kotlin
data class ModerationDictionaryLimits(
    val maxWords: Int = 10_000,
    val maxWordCharacters: Int = 200,
    val maxTotalCharacters: Int = 100_000,
)

data class ModerationDictionaryMetadata(
    val version: DictionaryVersion,
    val wordCount: Int,
    val totalCharacters: Int,
)

data class VersionedModerationResult(
    val dictionary: ModerationDictionaryMetadata,
    val response: ModerationResponse,
)

class VersionedModerationDictionary {
    fun currentMetadata(): ModerationDictionaryMetadata
    fun reload(version: DictionaryVersion, words: Collection<String>): ModerationDictionaryMetadata
    fun reload(version: DictionaryVersion, loader: () -> Collection<String>): ModerationDictionaryMetadata
    fun rollback(): ModerationDictionaryMetadata
}

class TextModerationService {
    fun analyze(text: String): ModerationResponse
    fun analyzeWithVersion(text: String): VersionedModerationResult
    fun reloadDictionary(version: DictionaryVersion, loader: () -> Collection<String>): ModerationDictionaryMetadata
    fun rollbackDictionary(): ModerationDictionaryMetadata
}
```

Spring은 `@Autowired`가 붙은 versioned-store primary constructor를 사용한다. 기존
`moderationAutomaton` secondary constructor는 test와 직접 생성 caller를 위해
유지하며 revision 0 compatibility snapshot으로 감싼다. configuration은 기존
singleton automaton을 versioned bean의 initial value로 재사용해 이중 build를 피한다.
`/api/moderation/analyze`의 기존 `ModerationResponse` JSON에는 revision을 추가하지
않는다. caller는 module test 또는 README의 service-level v1→v2→rollback 예제로
`analyzeWithVersion`과 metadata를 관찰한다.

## 실패와 관찰성

- name mismatch, equal/lower revision, 입력 제한 초과, loader/build 실패는 예외를
  호출자에게 반환하고 current/history를 바꾸지 않는다.
- 동시 candidate build에서는 먼저 publish된 높은 revision이 권위가 된다. 늦게
  도착한 낮은 revision은 upstream monotonic 검증으로 거부한다.
- 성공 로그는 operation, dictionary name, old/new revision, word count,
  total characters만 기록한다. raw text, matched terms, word list, loader source,
  credential, 예외 payload는 기록하지 않는다.
- rollback할 snapshot이 없으면 upstream `IllegalStateException`을 그대로 전달한다.

## 테스트 전략

1. 두 store의 v1→v2 reload와 bounded rollback을 검증한다.
2. name mismatch, equal/lower revision, loader failure, 입력 제한 실패 뒤 current
   snapshot과 rollback 순서가 유지되는지 검증한다.
3. search reader가 reload 중 old/new 전체 index 결과 중 하나만 반환하고 version과
   결과가 일치하는지 검증한다.
4. moderation reader가 `matchedTerms`와 `maskedText`에 같은 revision의 automaton을
   사용하며 혼합 결과를 만들지 않는지 동시성 테스트로 검증한다.
5. 느린 loader가 candidate를 준비하는 동안 current snapshot 검색/분석이 막히지
   않고, 더 높은 revision이 먼저 publish되면 늦은 낮은 revision이 거부되는지
   barrier 테스트로 검증한다.
6. 기존 `MultilingualSearchIndex`와 `TextModerationService` 생성자/동기 API 및 Spring
   singleton bean 회귀 테스트를 유지한다.
7. 로그 캡처에서는 version/count metadata만 허용하고 dictionary contents를 금지한다.

## 범위 밖

분산 revision consensus, 영구 저장소 migration, upstream provider history 변경,
public reload HTTP endpoint, 언어 모델 재학습, JMH 성능 주장은 다루지 않는다.

## 롤백

새 wrapper/store, tests, bean과 문서만 되돌리면 된다. 기존 동기 API와 HTTP JSON
schema는 바뀌지 않으며 저장 데이터 migration도 없다.
