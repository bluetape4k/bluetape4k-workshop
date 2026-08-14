# Structured OCR API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use `- [ ]` syntax for tracking.

**Goal:** image-processing/ocr-api가 PLAIN_TEXT, LINE, WORD 요청을 받아 구조화 OCR metadata를 노출하면서 기존 plain-text와 native fallback 계약을 유지하도록 구현한다.

**Architecture:** OcrEngineProvider와 OcrEngine은 유지한다. 서비스가 요청의 OcrStructuredDetail과 런타임 capability를 검사해 recognize 또는 StructuredOcrEngine.recognizeStructured를 호출하고, plain engine이면 legacy line block fallback을 반환한다. 응답에는 additive DTO와 effectiveStructuredDetail을 추가해 source metadata와 fallback 상태를 구분한다.

**Tech Stack:** Kotlin 2.4.0, Java 25, Spring Boot 4.0.6 MVC, kotlinx-coroutines, JUnit 5, MockK, bluetape4k assertions, bluetape4k-image 0.4.0 StructuredOcrEngine, Gradle root bluetape4k-dependencies BOM.

---

## 승인된 입력과 실행 경계

- 설계 기준: docs/superpowers/specs/2026-08-14-structured-ocr-design.md, commit 284784d5.
- 공식 source: https://github.com/bluetape4k/bluetape4k-image/blob/0.4.0/images-ocr/src/main/kotlin/io/bluetape4k/images/ocr/OcrEngine.kt 및 https://github.com/bluetape4k/bluetape4k-image/blob/0.4.0/images-ocr/src/main/kotlin/io/bluetape4k/images/ocr/OcrOptions.kt.
- 사용자 승인: 2026-08-14, 접근 1(capability detection + plain-text fallback).
- 구현 전제: 이 계획의 사용자 승인을 별도로 받은 뒤에만 Task 1의 테스트 파일을 변경한다.
- 범위: image-processing/ocr-api의 model/service/web/test/README만 변경한다. OcrEngineProvider, native config, dependency version, CI module grouping은 변경하지 않는다.
- 커밋: 각 논리적 task 완료 때 한국어 Lore commit message를 사용한다. 모든 커밋에는 Constraint, Rejected, Confidence, Scope-risk, Directive, Tested, Not-tested를 포함한다.
- PR·merge·remote branch 삭제·worktree cleanup은 로컬 검증 이후 별도의 사용자 승인 게이트다.

## 파일 책임 지도

| 파일 | 책임 | 변경 이유 |
|---|---|---|
| image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/model/ImageOcrModels.kt | HTTP/service DTO와 nullable structured metadata | request/effective detail, page/line/word/box를 additive API로 정의 |
| image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImpl.kt | 이미지 검증, capability 분기, timeout/cancellation, DTO mapping | OcrOptions.structuredDetail 전달과 structured/plain fallback 구현 |
| image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrController.kt | multipart HTTP adapter | 선택적 structuredDetail parameter 전달 |
| image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImplTest.kt | service contract 회귀 테스트 | structured mapping, fallback, nullable metadata, failure/lifecycle 검증 |
| image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrControllerTest.kt | HTTP binding/JSON 회귀 테스트 | parameter 생략·LINE·WORD 전달과 기존 response 확인 |
| image-processing/ocr-api/README.md | English public module guide | detail parameter, response fields, fallback, examples 동기화 |
| image-processing/ocr-api/README.ko.md | Korean public module guide | English와 같은 API 의미를 한국어로 설명 |

## Task 1: RED 테스트로 API 계약 고정

**Files:**

- Modify: image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImplTest.kt
- Modify: image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrControllerTest.kt

- [ ] **Step 1: 구조화 fake와 service RED 테스트를 추가한다.**

ImageOcrServiceImplTest에 source imports와 fixture를 추가한다. StructuredOcrEngine은 OcrEngine을 확장하므로 plain 호출과 structured 호출을 분리해 capability branch를 증명한다.

~~~kotlin
import com.sksamuel.scrimage.ImmutableImage
import io.bluetape4k.images.ocr.OcrBoundingBox
import io.bluetape4k.images.ocr.OcrOptions
import io.bluetape4k.images.ocr.OcrPage
import io.bluetape4k.images.ocr.OcrResult
import io.bluetape4k.images.ocr.OcrStructuredDetail
import io.bluetape4k.images.ocr.OcrStructuredResult
import io.bluetape4k.images.ocr.OcrTextBlock as SourceOcrTextBlock
import io.bluetape4k.images.ocr.OcrTextLine
import io.bluetape4k.images.ocr.OcrWord
import io.bluetape4k.images.ocr.StructuredOcrEngine
~~~

~~~kotlin
private class FakeStructuredOcrEngine(
    private val structuredResult: OcrStructuredResult,
) : StructuredOcrEngine {
    var recognizeCalls = 0
    var structuredCalls = 0
    lateinit var lastOptions: OcrOptions

    override fun recognize(image: ImmutableImage, options: OcrOptions): OcrResult {
        recognizeCalls++
        lastOptions = options
        return OcrResult(structuredResult.text, options)
    }

    override fun recognizeStructured(image: ImmutableImage, options: OcrOptions): OcrStructuredResult {
        structuredCalls++
        lastOptions = options
        return structuredResult.copy(options = options)
    }
}
~~~

Fixture source values must cover populated and nullable metadata:

~~~kotlin
private fun structuredResult(): OcrStructuredResult =
    OcrStructuredResult(
        text = "Bluetape OCR",
        options = OcrOptions(),
        pages = listOf(OcrPage(pageIndex = 0, text = "Bluetape OCR")),
        blocks = listOf(
            SourceOcrTextBlock(
                pageIndex = 0,
                text = "Bluetape OCR",
                boundingBox = OcrBoundingBox(1, 2, 30, 12),
                confidence = 91.5,
            ),
        ),
        lines = listOf(
            OcrTextLine(pageIndex = 0, text = "Bluetape OCR", boundingBox = null, confidence = null),
        ),
        words = listOf(
            OcrWord(
                pageIndex = 0,
                text = "Bluetape",
                boundingBox = OcrBoundingBox(1, 2, 16, 12),
                confidence = 88.0,
            ),
        ),
    )
~~~

Add tests that assert structured LINE/WORD mapping, nullable metadata, options propagation, plain engine fallback, effectiveStructuredDetail=PLAIN_TEXT, legacy blocks, and empty pages/lines/words. Update the request helper with trailing structuredDetail default:

~~~kotlin
private fun request(
    bytes: ByteArray = tinyPng(),
    contentType: String = "image/png",
    languages: List<String> = listOf("eng"),
    structuredDetail: OcrStructuredDetail = OcrStructuredDetail.PLAIN_TEXT,
): ImageOcrRequest =
    ImageOcrRequest(bytes, contentType, languages, structuredDetail)
~~~

The LINE test must assert structuredCalls=1 and recognizeCalls=0; the WORD test must assert source confidence and bounding box values; the plain fallback test must assert the warning contains structured.

- [ ] **Step 2: controller binding RED 테스트를 추가한다.**

Add controller tests for multipart parameter WORD and parameter omission. Use coVerify match blocks to assert the service receives OcrStructuredDetail.WORD and OcrStructuredDetail.PLAIN_TEXT respectively. Keep existing response fixture construction unchanged to prove additive compatibility.

- [ ] **Step 3: RED 상태를 확인한다.**

~~~bash
./gradlew :image-processing-ocr-api:test --tests "io.bluetape4k.workshop.imageprocessing.ocr.service.ImageOcrServiceImplTest" --tests "io.bluetape4k.workshop.imageprocessing.ocr.web.ImageOcrControllerTest"
~~~

Expected: missing request/response model fields, structured DTO, or controller parameter produce compile/test failure. Do not add production code in this step.

- [ ] **Step 4: RED 테스트를 한국어 Lore 커밋으로 저장한다.**

~~~text
구조화 OCR 경로의 실패하는 계약 테스트를 먼저 고정한다

StructuredOcrEngine capability, metadata null 보존, plain fallback, HTTP detail 전달을 구현 전 RED 상태로 고정한다.

Constraint: TDD 순서와 issue #736의 PLAIN_TEXT/LINE/WORD acceptance 기준을 먼저 증명해야 한다.
Rejected: production code를 먼저 추가 | 테스트가 새 계약을 실제로 검증하는지 확인할 수 없다.
Confidence: high
Scope-risk: narrow
Directive: 다음 task는 이 실패를 최소 DTO 계약으로만 해소하고 service branch는 뒤에서 추가한다.
Tested: targeted Gradle test 실행에서 새 계약의 compile/test failure 확인
Not-tested: production implementation pending
~~~

## Task 2: JSON 경계 DTO와 trailing request/response 필드 구현

**Files:**

- Modify: image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/model/ImageOcrModels.kt

- [ ] **Step 1: 기존 필드를 보존하면서 model을 확장한다.**

ImageOcrModels.kt에 OcrStructuredDetail import를 추가한다. ImageOcrRequest의 마지막 인자로 structuredDetail: OcrStructuredDetail = OcrStructuredDetail.PLAIN_TEXT를 추가하고, ImageOcrResponse의 warnings 뒤에 effectiveStructuredDetail, pages, lines, words를 모두 trailing default로 추가한다.

기존 OcrTextBlock(index, text, confidence) 인자는 유지하고 pageIndex: Int? = null, boundingBox: OcrBoundingBox? = null을 추가한다. OcrBoundingBox(x, y, width, height), OcrPage(pageIndex, text, confidence, boundingBox), OcrTextLine(pageIndex, text, confidence, boundingBox), OcrWord(pageIndex, text, confidence, boundingBox)는 Serializable DTO로 만든다. source metadata가 없을 때 default box나 confidence를 계산하지 않는다. 새 KDoc은 한국어로 작성하고 serialVersionUID 패턴을 유지한다.

- [ ] **Step 2: model compile과 기존 consumer 생성자를 확인한다.**

~~~bash
./gradlew :image-processing-ocr-api:compileKotlin :image-processing-ocr-api:compileTestKotlin
~~~

Expected: model과 테스트가 compile되고 service 동작 assertion만 아직 실패한다. OcrEngineProvider와 NativeOcrEngineConfig diff가 없어야 한다.

- [ ] **Step 3: additive model을 Lore 커밋한다.**

커밋 intent는 “구조화 OCR의 nullable JSON 경계를 추가한다”로 하고, Constraint에 기존 three-argument source 호출, Rejected에 library DTO 직접 노출, Tested에 두 compile task, Not-tested에 service branch pending을 기록한다.

## Task 3: service capability 분기와 source-to-DTO mapping 구현

**Files:**

- Modify: image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImpl.kt
- Test: image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImplTest.kt

- [ ] **Step 1: OcrOptions에 request detail을 전달한다.**

현재 OcrOptions 생성에 structuredDetail = request.structuredDetail을 추가한다. languages, tessdataPath, trimText와 이미지 검증 순서는 변경하지 않는다.

- [ ] **Step 2: native lane 내부에서 capability branch를 구현한다.**

기존 engine.recognize 호출을 다음 의미의 단일 when branch로 교체한다.

~~~kotlin
val completed = when {
    request.structuredDetail == OcrStructuredDetail.PLAIN_TEXT ->
        completed(requestId, languages, engine.recognize(image, options).text.trim())

    engine is StructuredOcrEngine ->
        completed(requestId, languages, engine.recognizeStructured(image, options))

    else ->
        plainFallback(
            requestId = requestId,
            languages = languages,
            requestedDetail = request.structuredDetail,
            text = engine.recognize(image, options).text.trim(),
        )
}
~~~

이 branch는 기존 nativeSemaphore.withPermit, withTimeout, runInterruptible(Dispatchers.IO) 안에 둔다. CancellationException은 catch하지 않고 재전파한다. TimeoutCancellationException, OcrConfigurationException, OcrException의 기존 status와 sanitized warning은 유지한다.

- [ ] **Step 3: mapping과 상태 response를 분리한다.**

plain text는 기존처럼 trim된 non-empty line을 legacy OcrTextBlock으로 만들고 pageIndex, boundingBox, top-level confidence, 새 lists를 null/empty로 둔다. structured result는 source pages, blocks, lines, words를 API DTO로 직접 복사하고 block index만 mapIndexed로 부여한다. plain fallback은 plain mapping에 effectiveStructuredDetail=PLAIN_TEXT와 요청 detail/capability를 설명하는 warning을 추가한다.

구조화 완료 response의 effectiveStructuredDetail은 request detail과 같게 하고 top-level confidence는 항상 null로 둔다. disabled/unavailable/failed/timeout response는 effectiveStructuredDetail=PLAIN_TEXT와 pages/lines/words=emptyList()를 사용한다.

- [ ] **Step 4: service targeted test를 통과시킨다.**

~~~bash
./gradlew :image-processing-ocr-api:test --tests "io.bluetape4k.workshop.imageprocessing.ocr.service.ImageOcrServiceImplTest"
~~~

Expected: structured LINE/WORD, plain fallback, disabled/failure/timeout/cancellation이 PASS한다.

- [ ] **Step 5: service 구현과 관련 테스트를 Lore 커밋한다.**

커밋 intent는 “구조화 OCR capability 분기와 안전한 plain fallback을 연결한다”로 한다. Constraint에 plain provider와 bounded native lane, Rejected에 빈 metadata 합성, Tested에 service targeted suite, Not-tested에 HTTP/full build pending을 기록한다.

## Task 4: HTTP structuredDetail binding 구현

**Files:**

- Modify: image-processing/ocr-api/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrController.kt
- Test: image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrControllerTest.kt

- [ ] **Step 1: 선택적 enum request parameter를 추가한다.**

Controller method에 다음 parameter를 추가한다.

~~~kotlin
@RequestParam("structuredDetail", required = false) structuredDetail: OcrStructuredDetail?,
~~~

ImageOcrRequest 생성 시 structuredDetail = structuredDetail ?: OcrStructuredDetail.PLAIN_TEXT를 전달한다. language, content type, file size validation과 async contract는 변경하지 않는다.

- [ ] **Step 2: binding과 기존 JSON response test를 통과시킨다.**

~~~bash
./gradlew :image-processing-ocr-api:test --tests "io.bluetape4k.workshop.imageprocessing.ocr.web.ImageOcrControllerTest"
~~~

Expected: 생략은 PLAIN_TEXT, structuredDetail=WORD는 WORD로 service에 전달되고 기존 multipart response/language ordering/validation failure가 PASS한다. 잘못된 enum은 Spring bad-request로 남긴다.

- [ ] **Step 3: controller 변경과 테스트를 Lore 커밋한다.**

커밋 intent는 “HTTP OCR 요청에 구조화 detail 선택을 연결한다”로 한다. Constraint에 multipart validation 보존, Rejected에 별도 endpoint, Tested에 controller targeted suite, Not-tested에 full module/docs pending을 기록한다.

## Task 5: 실패·취소·호환성 회귀를 보강한다

**Files:**

- Modify: image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/service/ImageOcrServiceImplTest.kt
- Modify: image-processing/ocr-api/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/ocr/web/ImageOcrControllerTest.kt

- [ ] **Step 1: 상태별 새 필드 기본값을 고정한다.**

native disabled, configuration unavailable, generic OCR failure, timeout 기존 테스트에 다음 assertion을 추가한다.

~~~kotlin
response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.PLAIN_TEXT
response.pages.size shouldBeEqualTo 0
response.lines.size shouldBeEqualTo 0
response.words.size shouldBeEqualTo 0
~~~

기존 warning sanitization assertion은 유지한다.

- [ ] **Step 2: structured timeout과 cancellation을 별도로 검증한다.**

structured fake의 recognizeStructured에서 CountDownLatch.await 또는 CancellationException을 발생시켜 timeout은 FAILED response, cancellation은 caller 재전파인지 확인한다. timeout 이후 두 번째 요청이 native semaphore를 다시 획득하는 기존 lane-release assertion도 유지한다.

- [ ] **Step 3: API consumer compatibility를 확인한다.**

기존 ImageOcrResponse fixture가 새 인자 없이 compile되는지 확인한다. controller JSON에서 requestId, status, engine, languages, confidence, text, blocks, warnings의 이름과 값을 기존대로 확인하고, structured response의 effectiveStructuredDetail, pages, lines, words도 jsonPath로 확인한다.

- [ ] **Step 4: regression suite를 실행한다.**

~~~bash
./gradlew :image-processing-ocr-api:test --tests "io.bluetape4k.workshop.imageprocessing.ocr.service.ImageOcrServiceImplTest" --tests "io.bluetape4k.workshop.imageprocessing.ocr.web.ImageOcrControllerTest"
~~~

Expected: 두 test class가 모두 PASS한다. 실패를 native 설치나 dependency version 변경으로 우회하지 않는다.

- [ ] **Step 5: regression test 보강을 Lore 커밋한다.**

커밋 intent는 “구조화 OCR 실패와 기존 소비자 계약을 회귀 검증한다”로 한다. Constraint에 native 설치 독립성, Rejected에 환경 의존 Tesseract smoke만 사용, Tested에 두 regression suite, Not-tested에 README/static analysis pending을 기록한다.

## Task 6: English/Korean README API parity 갱신

**Files:**

- Modify: image-processing/ocr-api/README.md
- Modify: image-processing/ocr-api/README.ko.md

- [ ] **Step 1: endpoint/request section을 같은 순서로 갱신한다.**

두 파일에 optional structuredDetail과 PLAIN_TEXT, LINE, WORD를 추가한다. 기존 plain curl은 유지하고 -F "structuredDetail=WORD" 예제를 추가한다.

- [ ] **Step 2: response examples와 fallback semantics를 갱신한다.**

fallback example에는 effectiveStructuredDetail, pages, lines, words empty list를 추가한다. completed structured example에는 page/block/line/word, nullable confidence/bounding box, top-level confidence가 집계되지 않는다는 설명을 추가한다. plain engine fallback은 effectiveStructuredDetail=PLAIN_TEXT와 warning으로 확인한다고 동일하게 설명한다.

- [ ] **Step 3: native prerequisites와 test 범위를 보존한다.**

native install/language pack 배포를 새로 요구하지 않는다. 기존 deterministic fake test와 ./gradlew :image-processing-ocr-api:test 명령을 유지하고 structured test가 native 설치 없이 실행됨을 명시한다. enum, JSON field, URL, command token은 두 locale에서 동일하게 유지한다.

- [ ] **Step 4: README parity read-back을 수행한다.**

~~~bash
git diff --check
rg -n "structuredDetail|effectiveStructuredDetail|PLAIN_TEXT|LINE|WORD|pages|lines|words|bounding" image-processing/ocr-api/README.md image-processing/ocr-api/README.ko.md
~~~

Expected: 두 locale 모두 같은 API field/detail/fallback/test command를 포함하고 Korean prose는 자연스러운 기술 문체를 유지한다.

- [ ] **Step 5: 문서를 Lore 커밋한다.**

커밋 intent는 “구조화 OCR 요청과 fallback 계약을 양국어 문서에 반영한다”로 한다. Constraint에 public locale parity와 한국어 정책, Rejected에 native 설치 확대, Tested에 diff check와 keyword read-back, Not-tested에 full Gradle pending을 기록한다.

## Task 7: 전체 검증, 정적 분석, workflow evidence

- [ ] **Step 1: 변경 파일과 dependency 경계를 확인한다.**

~~~bash
git status --short
git diff --check
git diff --name-only origin/develop...HEAD
git diff -- gradle/libs.versions.toml image-processing/ocr-api/build.gradle.kts
~~~

Expected: 변경 목록이 model/service/controller/tests/README와 설계·계획 문서로 제한되고 version catalog/build dependency는 바뀌지 않는다.

- [ ] **Step 2: targeted test와 module build를 순서대로 실행한다.**

~~~bash
./gradlew :image-processing-ocr-api:test
./gradlew :image-processing-ocr-api:build
~~~

Expected: 두 명령 모두 BUILD SUCCESSFUL. 첫 명령이 실패하면 build를 실행하지 않고 원인을 수정한 뒤 test를 재실행한다.

- [ ] **Step 3: static analysis와 문서 검사를 실행한다.**

~~~bash
./gradlew :image-processing-ocr-api:detekt
git diff --check
~~~

Expected: detekt와 diff check가 PASS한다. suppression으로 경고를 숨기지 않는다.

- [ ] **Step 4: full-feature Step 5 검증을 기록한다.**

bluetape-full-feature/references/step-5-verifier-checklist.md를 다시 읽고 spec-to-plan-to-code traceability, DoD, test output, README parity, rollback risk를 확인한다. bluetape-flow.py check-result로 implementation, tests, documentation, integration 결과를 기록하고 lane 완료 후 component-evidence를 helper로 첨부한다. receipt JSONL은 수동 편집하지 않는다.

- [ ] **Step 5: final local exact-head 증적을 확인한다.**

~~~bash
git log --oneline --decorate -8
git status --short --branch
git rev-parse HEAD
~~~

Expected: worktree clean, branch feat/issue-736-structured-ocr, 모든 구현 커밋이 현재 HEAD에 포함된다. PR 생성 전 exact head와 fresh local verification을 별도 보고한다.

## Task 8: rollback과 재실행 지점

- [ ] resolved bluetape4k-image API가 0.4.0 계약과 다르면 소스 수정을 중단하고 dependencies/version catalog를 read-only로 확인한다. 개별 artifact pin이나 별도 BOM은 추가하지 않는다.
- [ ] model/service 변경을 되돌리면 structured branch와 additive DTO 커밋을 역순으로 revert하고 기존 plain OcrEngine.recognize 테스트를 먼저 통과시킨다. data migration/native installation rollback은 없다.
- [ ] targeted test가 flaky하면 TestMutexService와 serialized test 규칙을 확인하고 timeout latch를 정리하는 방식으로 수정한다. retry로 실패를 숨기지 않는다.
- [ ] README parity가 어긋나면 두 locale을 함께 수정하고 SPW-01~05를 다시 수행한다.
- [ ] PR/CI가 실패하면 gh live check로 exact head와 failed job을 읽은 뒤 별도 CI fix 절차로 분기한다. 이 계획은 merge나 remote branch 삭제를 자동 승인하지 않는다.

## Step 3-R 계획 검토

| 우선순위 | 영역 | 근거 | 계획 반영 |
|---|---|---|---|
| N/A | Performance | structured 호출은 기존 단일 native lane에서 한 번 실행하며 추가 round trip이 없다. | Task 3 mapping과 semaphore/timeout 경계, Task 7 module build |
| N/A | Stability | timeout, cancellation, lane release, provider failure가 Task 3·5에 명시되어 있다. | 기존 coroutine 경계를 유지하고 전용 회귀 실행 |
| N/A | Security | enum binding과 기존 image validation만 사용하며 native path/OCR text를 warning에 넣지 않는다. | Task 4 binding과 Task 5 sanitized failure assertion |
| N/A | Operator/Ops | effectiveStructuredDetail과 fallback warning을 response에 남기고 로그/native prerequisites는 유지한다. | Task 3·6 상태 설명과 Task 8 rollback |
| N/A | Developer/API | 파일 책임, DTO names, provider compatibility, source mapping, commands가 순서대로 정의되어 있다. | Task 1~4 RED→model→service→controller |
| N/A | User/caller | HTTP parameter, JSON examples, nullable metadata, unsupported fallback이 두 README에 매핑된다. | Task 6 locale parity read-back |

### 필수 항목 결과

- 모든 spec 요구사항과 DoD: Task 1~7에 매핑됨.
- 구현 순서: RED test → DTO → service → controller → regression → docs → verification.
- coroutine/cancellation/backend capability: Task 3·5.
- README locale parity: Task 6.
- BOM/dependency/new module/CI scope: 새 module과 dependency가 없음을 Task 7에서 확인.
- rollback/compatibility: 승인된 설계와 Task 8.
- P0/P1: 없음. P2/P3: 없음.

## Plan Writer DoD

- SPW-01: 완료 — 승인된 spec, source paths, official URLs, 타입과 독자/언어를 고정했다.
- SPW-02: 완료 — 파일 지도, TDD order, commands, expected output, tests, docs, rollback, rerun, approval gates를 포함했다.
- SPW-03: 완료 — structuredDetail, effectiveStructuredDetail, fallback, nullable metadata 용어를 일관되게 사용했다.
- SPW-04: 완료 — 설계 명세의 목표·비목표·수용 기준을 Task 1~8과 대조했다.
- SPW-05: 완료 — 계획 전체를 read-back했고 placeholder scan, type/signature consistency, trailing whitespace 검사와 git diff --check equivalent 검사를 통과했다.

이 계획은 사용자 승인 후 executing-plans 또는 subagent-driven-development 중 현재 세션에서 선택한 실행 방식으로 Task 1부터 진행한다.
