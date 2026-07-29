# Issue #333 텍스트 편집 파이프라인 설계

## 문맥

Issue #333은 욕설 필터링을 넘어서는 워크샵 예를 요청합니다.
들어오는 텍스트 정규화, 언어 감지, 민감한 범위 찾기, 출력 수정,
감사에 안전한 메타데이터를 반환합니다. 예제는 작고, 결정적이며, 유지되어야 합니다.
학습자 친화적입니다.

현재 소스 증거:

- `kotlin/text-processing`은(는) 이미 `AbuseWordFilter`를 소유하고 있습니다.
  `LanguageDetectionService`, `CoroutineLanguageDetectionService`,
  `TextNormalizer`, `MultilingualSearchIndex` 및
  `CoroutineMultilingualSearchIndex`.
- `kotlin/text-processing/build.gradle.kts`은(는) 이미 다음에 의존하고 있습니다.
  `bluetape4k.text.search`, `bluetape4k.text.lingua`,
  `bluetape4k.text.korean`, `bluetape4k.text.japanese`,
  `kotlinx.coroutines.core.lib`, `bluetape4k.logging` 및 표준
  bluetape4k 테스트 종속성. 새로운 종속성은 필요하지 않습니다.
- Issue #316 `spring-boot/text-moderation-api`은 web/API 조정을 표시합니다.
  일부분. 이 문제는 다른 HTTP이 아니라 진행 중인 파이프라인의 예여야 합니다.
  제어 장치.
- Issue #332 확장 `kotlin/text-processing` sync/coroutine
  다국어 검색. 동일한 모듈은 다음에 대한 가장 가까운 학습자 컨텍스트입니다.
  정규화된 텍스트 및 소스 범위 처리.
- bluetape4k-text 보안 감사에서는 예외 메시지가 유출될 수 있다고 경고했습니다.
  사용자 입력 및 PII. 이 예에서는 로그에서 민감한 원시 값을 피해야 합니다.
  예외, 메타데이터 및 `toString()`.

## 목표

결정적 PII/sensitive-text을 사용하여 `kotlin/text-processing` 확장
다음을 가르치는 수정 파이프라인:

1. Unicode/whitespace 분류 전 정규화.
2. routing/confidence 메타데이터에 대한 언어 감지.
3. 이메일, 전화, 토큰과 유사한 키워드 및 패턴 기반 범위 감지
   값 및 소규모로 구성된 민감한 용어.
4. 결정적 중첩 해결 및 마스크 생성.
5. 규칙 ID, 범위 오프셋, 길이 및 정보를 노출하는 감사에 안전한 메타데이터
   원시 민감한 값이 없는 수정 카테고리입니다.

## 범위

범위 내:

- 새 패키지 `io.bluetape4k.workshop.text.redaction` 내부
  `kotlin/text-processing`.
- `TextNormalizer`, `LanguageDetectionService` 재사용
  `AhoCorasickAutomaton`, 그러나 먼저 재사용되는 원시 텍스트 디버그 로그인을 삭제합니다.
  이 파이프라인을 사용하는 텍스트 처리 공동 작업자입니다.
- 구성된 민감한 용어에 대한 작은 고정 장치 지원 기본 정책 및
  정규식 지원 email/phone/token-like 값.
- 영어 KDoc을 사용하여 직렬화 가능한 공개 result/value 유형.
- 결정적 편집, 다국어 텍스트, 중복 처리,
  잘못된 입력, 감사에 안전한 출력 및 `toString()` 수정.
- `README.md` 및 `README.ko.md` 패리티(정책 제한 사항 및 시기 포함)
  더 강력한 검출기가 필요합니다.
- README architecture/flow 다이어그램은 SVG+PNG로 업데이트되고 다음을 통해 검증되었습니다.
  전체 다이어그램 체크리스트와 렌더링된 PNG 육안 검사.

범위 외:

- 새로운 Gradle 모듈 `kotlin/text-redaction-pipeline`.
- 외부 PII 감지 서비스, ML/NLP 엔터티 인식, DLP 제품 또는
  국가별 법적 준수.
- 지속성, HTTP API, Spring Boot 연결 또는 Testcontainers.
- 완벽한 phone/email 검증. 정규식 규칙은 의도적으로 경험적입니다.
  ReDoS-안전한 작업장 기본값 및 문서화.
- 원시 소스 텍스트 또는 원시 일치 민감한 값을 로깅합니다.

## 디자인 옵션

### 옵션 A: 새 `kotlin/text-redaction-pipeline` 모듈 추가

이렇게 하면 예제 경계가 시각적으로 분리되지만
텍스트 처리 종속성 설정, settings/CI/smoke/README 등록 필요,
학습자가 정규화, 언어 모듈 사이를 이동하게 만듭니다.
감지 및 범위 일치.

결정: 거부됨. 이 문제는 "또는 유사한 모듈"과 현재 증거를 허용합니다.
`kotlin/text-processing`은(는) 이미 표준 텍스트 유틸리티 워크샵임을 보여줍니다.

### 옵션 B: `redaction` 패키지로 `kotlin/text-processing` 확장

이렇게 하면 기존 노멀라이저, 감지기, 남용 필터 옆에 예제가 유지됩니다.
및 다국어 검색 색인. 현재 bluetape4k-text 종속성을 재사용합니다.
새로운 모듈 작업 흐름 위험을 방지합니다. README는 파이프라인을 다섯 번째로 표시할 수 있습니다.
저장소 등록이나 CI 토폴로지를 변경하지 않고 구성 요소를 사용할 수 있습니다.

결정: 선택됨. 가장 작고 일관된 디자인이며 가장 잘 어울립니다.
간단한 필터링부터 감사에 안전한 편집까지의 학습자 경로입니다.

### 옵션 C: 확장 `spring-boot/text-moderation-api`

이는 배포 가능한 HTTP 경계를 보여 주지만  #316은 이미
웹 조정 슬라이스. PII 수정에는 먼저 재사용 가능한 도메인 동작이 필요합니다. 편물
전송은 핵심 파이프라인 주변에 validation/error-mapping 노이즈를 추가합니다.

결정: 이 문제로 인해 거부되었습니다. README는 #316을 웹API으로 참조할 수 있습니다.
짝.

## 선택된 아키텍처

`SensitiveTextRedactionPipeline`은 5단계를 조정합니다:

1. bluetape4k 검증 도우미를 사용하여 발신자 텍스트를 검증합니다. 빈 텍스트가 실패함
   예외 메시지에 원시 입력을 포함하지 않고 빠릅니다. 작은
   최대 텍스트 길이는 정규식 규칙을 보호하고 워크샵 예제를 유지합니다.
   경계.
2. 탐지 경로에 대해 유니코드를 NFC으로 정규화한 다음 적용합니다.
   whitespace/case 메타데이터 및 언어에 대한 `TextNormalizer.normalize`
   자신감 맥락. 소스 오프셋은 여전히 ​​원래 입력을 참조합니다.
3. 재사용 가능한 `LanguageDetectionService`을 통해 언어를 감지하여
   감지된 언어와 결과 메타데이터에 대한 최고의 신뢰도를 제공합니다.
4. 다음을 통해 민감한 범위를 감지합니다.
   - 사전 컴파일된 ReDoS-이메일, 전화 및 토큰과 유사한 안전한 정규식 상수
     가치;
   - 파이프라인 건설 시 구축된 하나의 Aho-Corasick 구성된 용어 자동화 장치
     NFC 정규화 및 대소문자를 구분하지 않는 일치를 사용합니다.
5. 겹치는 범위를 결정론적으로 병합하고 다음을 통해 수정된 출력을 렌더링합니다.
   병합된 각 소스 범위를 동일한 소스 길이의 마스크로 대체합니다.

공개 모델 모양:

- `SensitiveTextRange`: 명명된 반 오픈 소스 범위 값 객체
  `startInclusive`, `endExclusive` 및 검증된 길이입니다.
- `SensitiveRedactionRule`: 검증된 키워드 또는 정규식 규칙 메타데이터입니다.
- `SensitiveRedactionPolicy`: 마스크 문자 및 규칙 컬렉션입니다.
- `SensitiveSpan`: 원시 값이 없는 내부 감사 범위, 카테고리, 규칙 ID 및
  길이.
- `SensitiveRedactionResult`: 수정된 텍스트, 감지된 언어, 정규화된 텍스트
  길이, 일치 횟수, 최고 신뢰도 및 범위.
- `SensitiveTextRedactionPipeline`: 불변에 대한 재사용 가능, 상태 비저장 파사드
  정책 및 재사용 가능한 탐지기.

모든 공개 값 유형은 `Serializable`을 구현하고 `serialVersionUID`을 정의합니다.
생성자의 유효성 검사가 필요한 모든 유형은 개인 생성자와 플러스를 사용합니다.
호출자가 유효성 검사를 우회할 수 없도록 하는 팩토리 메서드입니다. 검증된 데이터 클래스는 다음과 같아야 합니다.
`@ConsistentCopyVisibility`을 사용하거나 `SearchDocument`과 같은 일반 클래스여야 합니다.
따라서 생성된 `copy()`은(는) 유효성 검사를 우회할 수 없습니다. `toString()` 절대 원시 인쇄하지 않음
소스 텍스트, 수정된 원시 텍스트, 정규화된 원시 텍스트 또는 일치하는 원시 값.

정책 구성은 불변 상태를 한 번만 컴파일합니다.

- 정규식 규칙은 사전 컴파일된 상수 또는 다음 위치에 구축된 검증된 컴파일 패턴입니다.
  정책 구축. 기본값은 중첩된 무제한 수량자를 피해야 합니다.
  치명적인 alternation/backtracking 및 역참조.
- 구성된 키워드 규칙은 결정적 불변 스냅샷에 복사됩니다.
  파이프라인 구성 시 하나의 Aho-Corasick 자동 장치로 컴파일됩니다.
- 공용 접근자는 변경할 수 없는 컬렉션만 노출합니다.

기본 규칙 고정 장치:

| 규칙 ID | 카테고리 | 우선순위 | 검출기 | 설비 경계 |
|---|---|---:|---|---|
| `email` | `contact` | 10 | 정규식 | 예약된 예시 도메인만 |
| `phone` | `contact` | 20 | 정규식 | 예약된 555 스타일 예제만 |
| `token` | `secret` | 1 | 정규식 | 합성 bearer/JWT/API-key-like 문자열만 |
| `support-keyword` | `keyword` | 30 | Aho-Corasick | `account number`와 같이 소규모로 구성된 비밀이 아닌 용어 |

기본 토큰형 규칙 계약:

- 실제가 아닌 예시의 합성 bearer/JWT/API-key-like 문자열과 일치
  공급자 비밀;
- `Bearer `, `token=`, `api_key=`와 같은 명확한 접두사 또는 경계가 필요합니다.
  또는 JWT과 같은 3분할 모양;
- 일반 주문 ID 또는 짧은 해시가 마스크되지 않도록 최소 길이가 필요합니다.
- 테스트, README 및 다이어그램에서 공급자에게 현실적인 프로덕션 샘플을 피하십시오.

규칙 ID와 카테고리는 메타데이터이므로 안전해야 합니다.

- 안정적이고 민감하지 않은 슬러그;
- 소문자, 숫자, 점, 밑줄 및 하이픈으로 제한됩니다.
- 제한된 길이;
- 구성된 키워드 샘플, 정규식 샘플, 소스와 같거나 이를 포함해서는 안 됩니다.
  텍스트, 이메일 주소, 전화번호, 토큰과 유사한 값 또는 customer/ticket
  식별자.

발신자 연결 API 계약:

```kotlin
val pipeline = SensitiveTextRedactionPipeline.default()
val result = pipeline.redact("Support note: user at user@example.test sent token=demo_token_value_123456")

result.redactedText   // contains masks and no raw email or token value
result.spans          // raw-value-free internal metadata
```

README 조각은 최종 API 이름에 대해 컴파일되거나 업데이트되어야 합니다.
PR 생성 전. 예에서는 예약된 도메인, 555 스타일 번호 및
합성 토큰만 해당됩니다.

## 범위 및 중복 계약

원시 규칙 일치 항목이 먼저 수집된 후 다음 기준으로 정렬됩니다.

1. `start` 오름차순,
2. `endExclusive` 내림차순,
3. 규칙 우선순위 오름차순,
4. 규칙 ID 오름차순.

다음과 같은 경우 겹치는 일치 항목이 하나의 방출된 `SensitiveSpan`으로 병합됩니다.
`next.range.startInclusive < current.range.endExclusive`. 인접한 범위는
병합하지 마세요. 방출된 범위는 가장 빠른 시작, 가장 늦은 끝, 결합 정렬을 사용합니다.
규칙 ID 및 우선순위가 가장 높은 일치 항목의 카테고리입니다. 동일 우선순위
카테고리 연결은 규칙 ID 오름차순으로 끊어진 다음 카테고리 오름차순으로 끊어집니다.
수정은 병합된 범위에 대해 하나의 마스크 범위를 렌더링합니다. 이렇게 하면 부분적인 것을 방지할 수 있습니다.
누출을 방지하고 nested/double 마스킹을 방지합니다.

오프셋은 원래 입력에 대해 `startInclusive` 및 `endExclusive`을 사용합니다.
이는 기존의 Aho-Corasick `AhoCorasickMatch.end`과 다른 점은
포함한; 파이프라인은 이를 경계에서 변환합니다.

## 보안 및 실패 모드

| 위험 | 완화 |
|---|---|
| 원시 PII가 결과 메타데이터에 나타남 | `SensitiveSpan`은 오프셋, 길이, 카테고리 및 규칙 ID만 저장합니다. 일치하는 텍스트를 저장하지 않습니다. |
| 원시 PII가 로그에 나타나거나 `toString()` | 파이프라인 로그는 lengths/counts/language만 기록하고 수정된 `toString()` 구현에서는 source/redacted 텍스트를 생략합니다. |
| 규칙 메타데이터로 인해 민감한 값 유출 | 규칙 ids/categories은 제한적이고 민감하지 않은 슬러그를 사용하고 테스트는 민감해 보이는 ids/categories을 거부합니다. |
| 정규식은 실제 PII를 놓칩니다 | README에서는 규칙이 경험적이라고 명시하고 규제 대상 데이터에 대한 전용 DLP/PII 감지기를 권장합니다. |
| 정규식 역추적은 신뢰할 수 없는 입력 처리에 해를 끼칩니다. | 기본 정규식 규칙은 bounded/precompiled이며 역참조 또는 중첩된 무한 구성을 피합니다. 입력 길이가 제한됩니다. |
| 일치하는 항목이 일치하여 부분 값 누출 | 출력을 렌더링하기 전에 겹치는 소스 범위를 병합합니다. |
| 예외 메시지가 사용자 입력을 반영함 | 유효성 검사 오류는 필드와 정책의 이름만 지정합니다. 원시 텍스트는 포함되지 않습니다. |
| 유니코드 정규화는 오프셋을 변경합니다 | 감지 및 수정 오프셋은 원래 입력을 기반으로 합니다. 정규화된 텍스트는 메타데이터일 뿐입니다. |
| 재사용된 공동작업자는 원시 텍스트를 기록합니다 | 파이프라인을 연결하기 전에 접촉된 공동 작업자 디버그 로그를 삭제하거나 안전한 래퍼를 사용하십시오. |

이 디자인에서 `audit-safe`은 값이 없는 내부 감사 메타데이터를 의미합니다.
익명 데이터이며 공개 클라이언트에게는 자동으로 안전하지 않습니다. README 안내
오프셋, 길이, 카테고리 및 규칙 ID가 여전히 공개될 수 있다고 말해야 합니다.
구조이며 최종 사용자나 광범위한 운영 로그에 노출되어서는 안 됩니다.
명시적인 제품 결정 없이.

## 관찰 가능성, CI 및 롤백

로깅 계약:

- 원시 소스 텍스트가 없습니다.
- 정규화된 텍스트가 없습니다.
- 수정된 텍스트가 없습니다.
- 일치하는 값이 없습니다.
- 쿼리 문자열이나 추출된 키워드 목록이 없습니다.
- 길이, 언어, 개수, 규칙 id/category 슬러그 및 경과된 단계 이름은 다음과 같습니다.
  허용된.

구현 시 원시 디버그 로그인을 삭제해야 합니다.
`LanguageDetectionService`, `TextNormalizer` 및 수정 경로를 터치했습니다. 테스트
DEBUG 로그를 캡처하고 합성 이메일, 전화, 토큰 및 구성된 용어를 증명합니다.
나타나지 않습니다.

모듈 등록 영향:

- 새로운 Gradle 모듈이 없습니다.
- `settings.gradle.kts` 프로젝트 수 변경 없음;
- 새로운 루트 README 모듈 행이 없습니다.
- 예제 워크플로 경로 필터에 `kotlin/text-processing/**`를 추가합니다.
- Examples/all-smoke 적용 범위에 `:kotlin-text-processing:test`을 추가하고 테스트하세요.
  새 조각이 보안에 민감하기 때문에 아티팩트 컬렉션이 필요합니다.

롤백은 일반적인 코드 되돌리기입니다. 수정 패키지를 제거하고 테스트하고 README
섹션, 다이어그램 업데이트 및 CI/smoke 추가. 데이터베이스 마이그레이션이 없습니다.
외부 서비스, 컨테이너 또는 런타임 구성 정리가 필요합니다.

## 테스트 전략

테스트는 먼저 `bluetape4k-assertions`로만 작성됩니다.

- 결정론적 email/phone/token/keyword 수정;
- 언어 메타데이터가 포함된 다국어 Korean/English 입력;
- 겹치는 키워드와 email/token 범위는 하나의 방출된 범위로 축소됩니다.
- 인접한 범위는 병합되지 않습니다. equal-start/nested 범위는 결정적으로 병합됩니다.
  동일한 우선순위 범주 연결은 결정적이며 Aho-Corasick 포괄적 종료
  오프셋은 반개방 범위로 올바르게 변환됩니다.
- NFC/decomposed 유니코드 소스 오프셋 보존;
- email/phone/token-like 규칙에 대한 긴 일치하지 않는 입력은 없이 종료됩니다.
  치명적인 정규식 동작;
- 하나의 파이프라인 인스턴스는 동일한 출력으로 반복적인 수정을 실행할 수 있으며
  컴파일된 정책 상태가 재사용됨을 증명합니다.
- `MultithreadingTester`은 공유 파이프라인 호출이 스레드로부터 안전한지 확인합니다. 그만큼
  구현은 감지기 자체가 안전하지 않은 경우 감지기 액세스를 보호해야 합니다.
  동시 통화의 경우;
- 공백 입력은 bluetape4k 검증을 통해 `IllegalArgumentException`을 발생시킵니다.
- 수정된 출력 및 결과 `toString()`에는 민감한 원시 값이 포함되어 있지 않습니다.
- 메타데이터, 예외, README 예제, 테스트 이름 및 로그에는 다음이 포함되지 않습니다.
  원시 이메일, 전화, 토큰 또는 구성된 민감한 값 비품에는 다음이 포함될 수 있습니다.
  reserved/synthetic 원시 입력이며 이스케이프되지 않음을 증명해야 합니다.
- 정책 검증은 빈 규칙 ID, 민감해 보이는 ids/categories을 거부합니다.
  빈 범주, 잘못된 마스크 문자, 잘못된 범위, 안전하지 않은 정규식
  기본값 및 빈 규칙 컬렉션입니다.

선택한 파이프라인은 공유 애플리케이션 사용을 위해 스레드로부터 안전합니다. 그것은 없다
코루틴 API이므로 `SuspendedJobTester`은 사용되지 않습니다. 스레드 안전성 검증
`io.bluetape4k.junit5.concurrency.MultithreadingTester`을 사용합니다. 임시 아님
thread/coroutine 스트레스 루프가 허용됩니다.

## 문서 및 다이어그램

`kotlin/text-processing/README.md` 및 `README.ko.md`은 다음으로 업데이트됩니다.

- 수정 파이프라인 구성요소 테이블 항목;
- 지원 티켓 스타일 입력에 대한 사용 스니펫
- 정책 제한 및 더 강력한 탐지기 지침;
- 감사에 안전한 메타데이터 설명
- `SensitiveTextRange` 반 개방 범위를 사용한 오프셋 설명
  하나의 overlap/merge 예제를 포함한 원래 입력;
- 안전한 구조화된 로그 지침: 기본적으로 메타데이터만 로그하고 store/display
  제품 정책인 경우에만 user-facing/support-ticket 필드의 `redactedText`
  그것을 허용합니다;
- construct-once/reuse 컴파일된 정규식에 대한 지침, Aho-Corasick 자동 장치,
  검출기 재사용;
- 새로운 종속성을 표시하지 않는 종속성 문입니다.

README 패리티 요구 사항:

- `English | 한국어` 언어 스위치를 유지합니다.
- 두 로케일 모두에서 해당 섹션을 업데이트합니다.
- 동일한 SVG/PNG 다이어그램 자산을 포함합니다.
- 동등한 제한, 감사 메타데이터, 오프셋 및 종속성을 포함합니다.
  진술;
- 직역보다는 현지에 적합한 한국어 산문을 사용하세요.

기존 두 개의 README 다이어그램이 업데이트됩니다.

- 아키텍처: 정규화, 언어 옆에 `Redaction Pipeline` 경로를 추가합니다.
  감지, 필터링, 검색이 가능합니다. 계층화된 레이아웃과 일관된 카드 유지
  조정. 규칙에 휴리스틱 레이블을 지정하고 내부 감사 메타데이터를 표시합니다.
  수정된 출력과 별도로.
- Flow: 입력 표시 -> 정규화 -> 언어 감지 -> 범위 감지 -> 병합
  중복 -> 수정 -> 감사에 안전한 결과. 둥근 직교 커넥터를 사용하고,
  명확한 레이블과 범례가 없는 모호한 커넥터 스타일이 없습니다. 암시하지 마세요
  HTTP 운송, ML, 외부 DLP 또는 규정 준수 범위.

다이어그램 출력은 `bluetape4k-diagram` 체크리스트, 저장소 유효성 검사기,
SVG parse/render 검사 및 전체 크기 PNG 시력 검사.

## 수락 기준 매핑

| 이슈기준 | 디자인 반응 |
|---|---|
| 루트 `bluetape4k-dependencies` BOM만 사용 | 기존 `kotlin/text-processing` 확장; 새로운 종속성이나 모듈 카탈로그 항목이 없습니다. |
| 테스트를 통해 결정적 수정 확인 | 정확한 출력 및 범위 메타데이터 테스트에는 이메일, 전화, 토큰, 키워드 및 중복 순서가 포함됩니다. |
| 민감한 원시 값은 수정된 출력에서 ​​방출되지 않습니다. | 테스트에서는 원시 email/phone/token/keyword 값이 출력 및 `toString()`에 없다고 검증문합니다. |
| README.md 및 README.ko.md에 대한 제한 사항 설명 | 두 README 파일 모두 경험적 정책 제한, 내부 감사 메타데이터 제한 및 더 강력한 탐지기 지침을 포함합니다. |
| policy/rule 데이터를 작고 고정 장치 기반으로 유지 | 기본값은 작은 코드 내 픽스처 목록을 사용하고 테스트에서는 로컬 픽스처 문자열을 사용합니다. |

## DoD

- `./gradlew :kotlin-text-processing:compileKotlin :kotlin-text-processing:compileTestKotlin --warning-mode all --console=plain` 통과.
- `./gradlew :kotlin-text-processing:cleanTest :kotlin-text-processing:test --no-build-cache --warning-mode all --console=plain` 통과.
- 새로운 테스트에서는 `bluetape4k-assertions`을 사용합니다. AssertJ 없음, Kluent, JUnit 어설션
  API 또는 `kotlin.test` 검증문이 도입되었습니다.
- 공개 API KDoc은 영어이고 공개 값 유형은 `Serializable`입니다.
- `MultithreadingTester`은 공유 파이프라인 스레드 안전성을 확인합니다.
- DEBUG 로그 캡처는 원시 민감한 값이 다음의 로그에 나타나지 않는지 확인합니다.
  텍스트 처리 공동 작업자를 만졌습니다.
- README English/Korean 패리티가 업데이트되고 소스 이름 grep-match code가 업데이트됩니다.
- 업데이트된 다이어그램은 저장소 유효성 검사기, SVG 렌더링, PNG 생성 및
  전체 크기 육안 검사.
- `actionlint .github/workflows/Examples.yml`은 예시 워크플로 경로인 경우 통과합니다.
  필터나 연기 작업이 변경됩니다.
- `./scripts/smoke-validate.sh stale-check` 통과.
- `./scripts/smoke-validate.sh all-smoke` 포함 및 통과
  `:kotlin-text-processing:test`.
- `git diff --check` 통과.
- 6-R단계 검토에서는 `P0=0`, `P1=0`을 기록합니다.
