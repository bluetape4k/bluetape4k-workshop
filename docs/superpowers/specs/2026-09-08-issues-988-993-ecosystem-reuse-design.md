# Issues #988~#993 ecosystem 재사용 PR train 설계

## 문서 상태

- 기준 브랜치: `develop`
- 기준 커밋: `c7087f48cd563fdd1282fdfb7547e9780cc88d33`
- 대상 이슈: [#988](https://github.com/bluetape4k/bluetape4k-workshop/issues/988), [#989](https://github.com/bluetape4k/bluetape4k-workshop/issues/989), [#990](https://github.com/bluetape4k/bluetape4k-workshop/issues/990), [#991](https://github.com/bluetape4k/bluetape4k-workshop/issues/991), [#992](https://github.com/bluetape4k/bluetape4k-workshop/issues/992), [#993](https://github.com/bluetape4k/bluetape4k-workshop/issues/993)
- 상태: 구현 승인됨

## 목표

Workshop에서 직접 만든 일반 SHA-256, Jackson3 mapper, application-owned coroutine scope를 이미 배포된 Bluetape4k API로 교체한다. 저장 형식, Kafka wire payload, strict parser, Spring HTTP 경계, 종료 순서는 바꾸지 않는다. 각 이슈는 별도 PR로 검증하고, 모든 PR이 준비된 뒤 한 번에 머지한다.

## PR 위상

`#989`가 `bluetape4k-tink` versionless catalog alias를 단일 소유한다. Ecosystem Reuse Gate의 active manifest scope를 충돌 없이 유지하도록 `#989 → #990 → #991 → #992 → #988 → #993` 순서의 선형 stacked PR로 만든다. 각 PR은 직전 branch를 base로 해 자기 이슈 diff만 보여 준다. 일괄 머지 단계에서는 앞 PR부터 순서대로 머지하고 다음 PR을 `develop`으로 retarget한 뒤 exact-head 검증을 다시 수행한다.

## 계약

### Tink digest

- 문자열은 UTF-8로 인코딩하고 SHA-256 결과를 lowercase 64자 hex로 유지한다.
- production 생성 경로는 `TinkDigesters.SHA256.digestHex`, 검증 경로는 기존 malformed·uppercase·wrong-length rejection을 유지하는 `matchesHex`로 이전한다.
- 테스트의 JDK `MessageDigest` helper와 composition fixture는 독립 oracle 또는 외부 producer 역할을 유지한다.
- digest는 비인증 checksum, 계약 일치 확인, 우발 손상 탐지, 상관 식별·중복 처리용 pseudonym이다. hostile tampering 방지는 MAC 또는 signature가 필요하며 이 값을 인증, credential 검증으로 확장하지 않는다.
- `bluetape4k-tink`는 Google Tink를 전이 노출한다. ecosystem gate용 `testRuntimeClasspath`와 production 비용 판정용 `runtimeClasspath` `dependencyInsight`, 전이 jar 크기, `bootJar` 크기 차이로 실제 비용을 기록한다. `bootJar` 증가가 module당 5 MiB를 넘거나 crypto dependency가 중복되면 owner의 별도 수용 결정 전까지 PENDING으로 남기고 raw JDK 구현을 유지한다.
- 이번 변경은 알고리즘이나 호출 횟수를 바꾸지 않고 provider도 호출마다 JDK `MessageDigest`를 생성한다. 따라서 성능 향상을 주장하지 않으며 별도 microbenchmark는 N/A로 기록한다. 대표 payload에 대한 회귀 테스트와 build artifact 크기 비교만 수행한다.

### Reservation 위협 모델

`IdempotencyFingerprint.request()`와 test/fixture용 `key()`는 요청 동등성 판정용 unkeyed fingerprint다. 이 값은 비밀번호 저장소가 아니며 저장소가 노출됐을 때 low-entropy 입력의 offline enumeration을 막지 못한다. `IdempotentReservationCommandService`가 만드는 production `keyDigest`는 `ReservationCredentialService.idempotencyDigest()`의 HMAC이고, `requestFingerprint`만 unkeyed SHA-256이다. `HttpIdempotencyRepository`는 두 값을 별도 필드로 저장한다. 이 PR은 저장 호환성을 위해 `request()`와 test/fixture `key()`의 `domain + NUL + ordered fields` framing만 유지해 Tink SHA-256으로 이전한다. `ReservationCredentialService.idempotencyDigest`, `ownerDigest`, `operatorDigest`, `ReservationCommandExecutionGate`, credential configuration은 변경하지 않는다. 원문 idempotency key, owner, `customer-visible-secret`가 저장 필드나 log에 남지 않는지 검증한다.

### Jackson3 baseline

- Ktor outbound NDJSON writer는 변경하지 않는 `Jackson.defaultJsonMapper`를 재사용한다.
- Spring `ObjectMapper` bean은 application customizer가 singleton을 변경하지 않도록 `Jackson.createDefaultJsonMapper()`로 격리한다.
- NDJSON의 줄별 JSON과 마지막 `\n`, outbox payload 구조, 시간·Unicode·null·collection 직렬화 결과를 고정한다.
- Spring 입력 경계의 unknown property, trailing JSON value, trailing comma, duplicate key, malformed JSON, null/coercion 결과를 교체 전후 동일하게 유지하고 unsafe default typing을 활성화하지 않는다. provider factory 기본값이 기존 결과를 바꾸면 Spring bean 교체를 중단한다.
- Ktor writer는 공용 singleton을 mutate하지 않으며 escaping과 출력 전용 계약만 검증한다.
- strict request parsing과 canonical JSON은 별도 책임 경계다.

교체 전 baseline에는 unknown property, trailing JSON value, trailing comma, duplicate key, malformed JSON, null/coercion의 현재 성공/실패와 HTTP status를 표로 기록한다. 모든 행이 교체 후 동일할 때만 Spring bean 교체를 허용한다.

### Coroutine lifecycle

- `LeaderEventListenerService`와 `JobSafetyAuditScope`는 각각 독립 `DefaultCoroutineScope`를 소유한다.
- listener 제거와 scope close를 모두 시도하고 기존 순서를 지킨다. listener 제거 실패는 기존 `closeQuietly` 계약대로 기록하고 다음 정리를 계속한다. coroutine cancellation은 삼키지 않으며 provider scope의 idempotent close 상태를 보존한다.
- `JobSafetyAuditScope`의 public type, 주입 계약, `isActive`, `destroyMethod=""`, coordinator의 scope-last close를 보존한다.
- `LeaderEventListenerService`는 `DefaultCoroutineScope`를 직접 소유하고 `scope.cancel()` 대신 `scope.close()`로 종료한다. `JobSafetyAuditScope`는 `DefaultCoroutineScope`를 상속하며 `isActive`는 coroutine context의 `Job` 상태에서 계산한다.
- test-local scope와 dispatcher-specific scope는 변경하지 않는다.

## 실패 처리와 롤백

- golden vector가 달라지면 공용 API 채택을 중단하고 byte framing 또는 mapper feature 차이를 먼저 분리한다.
- Spring request parsing 동작이 달라지면 production bean 교체를 롤백하고 provider factory 차이를 후속 이슈로 기록한다.
- coroutine close 순서나 child cancellation이 달라지면 raw scope 구현을 유지하고 provider lifecycle gap을 후속 이슈로 분리한다.
- stacked PR은 선행 PR이 변경되면 child branch를 최신 parent head에 rebase하고 PR base/head, merge-base, changed-file 목록, exact 40-hex head CI를 다시 확인한다. `#989` 머지 후에는 `origin/develop` 기준으로 같은 검증을 반복한다.
- 각 PR은 `docs/ecosystem-reuse-train.json` active scope, `docs/ecosystem-reuse-inventory.md`, 한국어 review/lesson, exact-head receipt를 함께 갱신한다. 변경 경로가 scope 하나에만 매핑되지 않으면 PR 생성을 중단한다.
- dependency graph와 Spring/Ktor startup smoke가 실패하거나 Google Tink 전이 비용이 수용되지 않으면 해당 provider 사용을 되돌리고 기존 raw 구현의 테스트 결과를 복원한다.
- digest 변경에는 schema migration과 backfill이 없다. 기존 DB의 lowercase 64자 값, old producer→new consumer, new producer→old consumer가 byte-for-byte 호환되어야 하며 provider rollback 뒤에도 같은 값이 생성되어야 한다.
- #989 비용 gate가 provider 채택을 거절하면 alias에 의존하는 #990~#992의 구현과 PR 생성을 중단하고 이슈를 재평가한다.

## 수용 기준

- 여섯 이슈의 완료 조건이 각 PR의 테스트와 dependency evidence에 일대일로 연결된다.
- 중앙 `bluetape4k-dependencies` BOM만 사용하고 Bluetape4k module version을 직접 고정하지 않는다.
- 변경 production 경로에서 대상 raw 구현이 사라지고 비범위 경계는 그대로 남는다.
- module test, `detekt`, dependency graph, `git diff --check`가 통과한다.
- #988의 세 module은 PR CI required check에서 실제 test task가 실행되어야 한다. `Examples.yml` 또는 동등한 required check에 누락된 module을 등록하고 local receipt만으로 대체하지 않는다. smoke/nightly/validation matrix는 실제 실행 경계를 함께 맞춘다.
- #989의 affected service와 composition 한·영 README는 `digestHex` 생성, `matchesHex` 검증, 비인증 checksum 경계, migration 없음, 독립 JDK oracle의 이유를 같은 의미로 설명한다.
- 각 PR의 review artifact 또는 PR 본문에는 exact 40-hex head, resolved coordinate, configuration, targeted test와 CI receipt, rollback 판정을 기록한다.
- PR 본문은 한국어이며 issue metadata를 반영하고 `## DoD Status`를 마지막에 둔다.
- 머지는 모든 PR 생성과 live CI 확인 뒤 별도 승인까지 보류한다.

## 검토 결과

6개 관점의 독립 spec 검토 결과와 수정 반영은 `docs/review/2026-09-08-issues-988-993-spec-review.md`에 기록한다.
