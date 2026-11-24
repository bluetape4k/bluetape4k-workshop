# Issue #1917 Workshop `Digesters` 소비자 이전 7-Tier 검토

## 검토 근거와 범위

- 대상 저장소: `bluetape4k-workshop`, 기준 `origin/develop` `3c86c23eaf95637ee7813560858543928e1a6a6f`, 코드 검토 시점 head `5d6e30e96b5a1093b11dbe78aef99e4dd76ff592`.
- 범위: 9개 commerce 모듈, production 29개 파일의 SHA-256 호출 35개, 두 README locale, 의존성 선언과 digest 계약 테스트.
- 검토 방법: main session이 승인된 설계·계획 및 현재 변경 diff를 대조한 자체 7-Tier 검토다. 사용자는 1인 개발자 lane의 현재 및 향후 PR 독립 code/architecture review를 면제한다고 확인했다. 따라서 이 PR의 독립 리뷰는 `N/A`이며 독립 검토가 수행됐다고 주장하지 않는다. 사람 리뷰도 단독 개발자 lane 근거로 `N/A`다. main-session 검토, exact-head CI, 리뷰 스레드 확인은 그대로 필요하다.
- 선행 artifact: Projects PR #1919와 Dependencies PR #291이 차례로 병합되고 `2.2.0-SNAPSHOT` 배포를 확인한 뒤 소비자 branch를 검증했다. Workshop 중앙 version catalog의 `2.2.0-SNAPSHOT` pin은 재개 전에 이미 존재하던 unstaged 변경이며, 승인된 소비 순서에 필요해 보존했다.
- 설계 원본은 `bluetape4k-projects/docs/superpowers/specs/2026-11-23-shared-digester-design.md`와 `docs/superpowers/plans/2026-11-24-issue-1917-shared-digester-plan.md`다. 두 문서의 기본 API 이름은 사용자의 수정에 따라 `Digesters`다.

## 7-Tier 검토

| 관점 | P0 | P1 | 판정 | 근거 |
| --- | ---: | ---: | --- | --- |
| architecture | 0 | 0 | PASS | 9개 모듈이 중앙 BOM의 `libs.bluetape4k.io` alias로 `bluetape4k-io`를 사용한다. 새 로컬 digest 추상화나 의존성은 추가하지 않았다. |
| performance | 0 | 0 | PASS | one-shot과 증분 session 경로를 입력 형태에 맞춰 사용한다. Workshop 성능 개선은 주장하지 않으며 별도 benchmark 결과도 추가하지 않았다. |
| stability | 0 | 0 | PASS | production 29개 파일에서 `Digesters.SHA256` 호출 35개를 확인했고, 바뀐 production 파일에는 raw `MessageDigest.getInstance("SHA-256")` 호출이 남지 않았다. framing·필드 순서·문자 인코딩·저장 표현을 parity 및 golden tests로 고정했다. |
| security | 0 | 0 | PASS | HMAC credential 경로와 `MessageDigest.isEqual` 비교를 보존했다. 키 없는 SHA-256 이전을 인증·서명 용도로 확장하지 않았다. |
| operations | 0 | 0 | PASS | Projects의 `2.2.0-SNAPSHOT`과 중앙 Dependencies BOM을 먼저 공개한 뒤 Workshop에서 해석했다. DB schema나 운영 설정 변경은 없다. |
| developer/API | 0 | 0 | PASS | byte 입력은 `digestHex(bytes)`, UTF-8 문자열은 `digestHexUtf8`, 증분 계약은 `Digesters.SHA256.newSession()`에 맞췄다. 두 README locale을 함께 갱신했다. |
| caller/user | 0 | 0 | PASS | `PayloadDigest`의 공개 모양과 기존 digest 값·framing·wire 표현을 유지했다. 호출자 데이터나 저장값의 재작성은 요구되지 않는다. |

## 소스 검토

- `commerce/shared/src/main/kotlin/io/bluetape4k/workshop/commerce/shared/integration/CommerceIntegrationContracts.kt:15,19`는 UTF-8 digest 생성과 검증을 `digestHexUtf8`/`matchesHexUtf8`로 연결한다.
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/idempotency/VoucherPoolFingerprint.kt:92`는 여러 필드의 증분 입력을 `newSession()`으로 유지한다.
- `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/BillingCommandServices.kt:319`는 source-system과 event-id 사이의 `\u0000` 구분자를 보존한다.
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/security/VoucherCodeService.kt:446`와 `commerce/reservation-control-plane/src/main/kotlin/io/bluetape4k/workshop/commerce/reservation/application/ReservationCredentialService.kt:152`는 기존 `HmacSHA256` 경로를 계속 사용한다.
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolRepository.kt:1017`과 관련 비교 지점은 `MessageDigest.isEqual`을 유지한다.
- `commerce/shared/src/test/kotlin/io/bluetape4k/workshop/commerce/shared/integration/DigestMigrationParityTest.kt`는 기존 출력과 신규 API 출력의 parity를 확인한다.

## 테스트 실패·재시도·건너뜀

| 테스트 | 결과 |
| --- | --- |
| `DigestMigrationParityTest` | 1 통과, 실패 0, 오류 0, 건너뜀 0 |
| `VoucherPoolRedisSignalsTest` | 1 통과, 실패 0, 오류 0, 건너뜀 0 |
| `EventContractTest` | 4 통과, 실패 0, 오류 0, 건너뜀 0 |
| `CorrectionReconciliationIntegrationTest` | 2 통과, 실패 0, 오류 0, 건너뜀 0 |
| `CanonicalEventHashTest` | 2 통과, 실패 0, 오류 0, 건너뜀 0 |
| `ProjectionWorkerDigestTest` | 1 통과, 실패 0, 오류 0, 건너뜀 0 |

위 여섯 XML 결과는 총 11개 테스트 통과를 보이며 실패·오류·건너뜀은 모두 0이다. 최초 `CorrectionReconciliationIntegrationTest` 선택은 일반 `test` task에서 찾지 못했다. 해당 테스트가 integration task에 속함을 확인해 `integrationTest`로 다시 실행했고 2개가 통과했다. Testcontainers 재시도 중 첫 context-mode 실행은 Colima 환경 변수를 전달하지 못했다. 셸 기반 재실행에서는 기존 Colima 환경을 유지했고 대상 통합 테스트가 통과했다. 두 초기 시도는 실패한 테스트 결과로 집계하지 않고, 수정 실행의 결과와 구분했다.

## CI와 toolchain

- 9개 변경 모듈의 `compileKotlin`, `compileTestKotlin`이 통과했고 중앙 `2.2.0-SNAPSHOT` BOM을 통한 의존성 선택을 확인했다.
- Projects `2.2.0-SNAPSHOT` 게시 run `45367606532`는 Projects PR #1919의 승인 head에서 성공했다. Dependencies PR #291은 `78a2f525c1d12c576f80f2c8f84dbb2fbb942ede`에 병합됐고 post-merge CI run `45404899275`, dependency submission run `45404896534`, BOM `2.2.0-SNAPSHOT` 게시 run `45407764459`가 성공했다.
- Workshop PR은 아직 생성되지 않아 exact-head GitHub CI는 `PENDING`이다. PR 생성 후 exact head의 필수 check, review, thread와 mergeability를 다시 읽는다.
- root `./gradlew detekt --no-configuration-cache --console=plain`은 실패했다. 최신 SARIF에서 고유 지적 23건을 확인했으며, 변경되지 않은 세 모듈에만 있다: `event-sourced-promotion-voucher-campaign` 10건, `pre-generated-voucher-pool` 11건, `usage-metering-billing-event-sourcing` 2건. 변경 파일의 지적은 0건이다. 최초 실행에서 변경 파일의 미사용 private `toHex()`가 보고되어 제거했고, 재실행 SARIF에서 해당 지적이 사라진 것을 확인했다. 따라서 저장소 Detekt 전체 통과는 아니며, 변경 파일 기준 추가 지적은 없다.

## API/ABI

- 변경 범위는 Workshop의 consumer modules와 examples이며, 공개·배포 artifact의 함수 descriptor 변경은 발견되지 않았다. `PayloadDigest`의 공개 필드와 호출 모양은 유지했다.
- `:commerce-shared`와 `:commerce-reservation-control-plane`에서는 키 없는 SHA-256만 `bluetape4k-io`로 이동했다. 공개 ABI baseline 갱신 대상은 없다.
- `gradle/libs.versions.toml`은 Dependencies BOM `2.2.0-SNAPSHOT`을 pin하고 module alias는 versionless로 유지한다.

## 생명주기와 보안 경계

- byte 배열과 UTF-8 문자열을 구별하고, 증분 입력의 순서·구분자·필드 framing을 그대로 유지했다.
- idempotency 및 event fingerprint 결과의 hex·Base64 표현을 바꾸지 않았다. Test oracle과 fixture는 이전하지 않았다.
- keyed credential, voucher HMAC, `Mac.getInstance("HmacSHA256")`, constant-time digest 비교는 새 `Digester` 호출로 대체하지 않았다.

## 역사적 근거와 미해결 항목

- Projects 이슈 #1917이 9개 모듈·29개 production 파일·35개 호출 인벤토리의 원본이다. 앞선 개별 이슈 #989 범위와 중복되지 않는 잔여 호출을 이전했다.
- 사용자가 `DigestUtils`라는 초안 이름을 기존 설계 문서의 `Digesters`로 바로잡도록 지시했다. 구현뿐 아니라 설계·계획에도 이름을 반영했다.
- P0=0, P1=0이며 main-session 자체 검토에서 미해결 P2/P3는 없다.
- 남은 gate: PR exact-head CI, live review/thread/mergeability read-back, 사용자 exact-head 병합 승인. 저장소 Detekt 전체 실행은 23건의 변경 외 파일 지적으로 실패했으며, 변경 파일에는 지적이 없다. 독립 PR code/architecture review는 사용자의 현재·향후 면제에 따라 `N/A`다.

## Writer DoD

| 항목 | 상태 | 증거 |
| --- | --- | --- |
| SPW-01 — 독자·목적·근거 고정 | PASS | 이 문서, 9개 모듈 diff, 계획·설계와 #1917, 실행 XML 결과 |
| SPW-02 — review artifact 요건 충족 | PASS | 범위·심각도·소스 근거·판정·검증 공백·결론 절 구성 |
| SPW-03 — 한국어 기술 문체 | PASS | KO-01…KO-07 문장·근거·용어·표·링크 점검 및 용어 감사 |
| SPW-04 — 사실과 추적성 확인 | PASS | 변경 경로·35개 호출·테스트 XML·upstream run과 대조 |
| SPW-05 — 최종 read-back | PASS | 저장 후 Markdown 전체를 다시 읽고 링크·표·코드 토큰 확인 |

KO-07 용어 감사: `audit-korean-terms.mjs` findings 0건. Detekt 저장소 전체는 실패했으며 PR exact-head CI는 아직 실행 전이다.
