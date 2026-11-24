# Cross-repository `SNAPSHOT` 소비와 `Digesters` 이전 교훈

## 배경과 결과

Projects #1917은 `bluetape4k-io`의 공통 SHA-256 API와 Workshop commerce 소비자 9개 모듈의 이전을 다룬다. Workshop은 Projects PR 병합 후 `2.2.0-SNAPSHOT` 게시가 끝난 뒤 중앙 Dependencies BOM을 통해 새 API를 해석해야 했다. 소비자 이전은 29개 production 파일, 35개 호출에 적용했고 입력 framing과 digest 표현을 유지했다.

## 결정과 반복 방지 규칙

- **저장소 간 공개 순서를 계획에 고정한다.** upstream Projects PR 병합과 정확한 source SHA의 `2.2.0-SNAPSHOT` 게시를 확인하고, Dependencies BOM 병합·게시를 확인한 다음에만 Workshop의 BOM pin과 consumer migration을 진행한다. 게시 artifact는 Central metadata와 실제 resolve 결과로 다시 확인한다.
- **사용자가 설계 이름을 바로잡으면 설계 원본도 갱신한다.** 이 작업에서 `DigestUtils`는 단순 구현 별칭 요청이 아니었다. 사용자는 기존 설계 문서가 `Digesters`로 바뀐 상태에서 작업하라고 정정했다. 따라서 설계·계획·API·예제·리뷰에서 `Digesters`를 같은 이름으로 사용하고 `DigestUtils` compatibility alias를 만들지 않는다.
- **digest 교체는 호출 표현이 아니라 입력 계약을 기준으로 한다.** raw bytes와 UTF-8 문자열을 구분하고, 증분 입력 순서, 구분자, field framing, byte order, truncation, lowercase hex와 Base64 padding을 보존한다. `MessageDigest.isEqual`, HMAC·MAC, credential, 테스트 oracle과 fixture는 키 없는 해시 호출과 별도 계약이므로 일괄 교체하지 않는다.
- **멈춘 세션에서는 새 run을 만들기 전에 영수증을 읽는다.** 기존 Workshop Type A receipt가 있었는데 이를 확인하기 전 중복 run을 만들었고 바로 취소했다. 이후 `resume-check`로 기존 run을 확인해 이어갔다. 다음에는 현재 repository `.bluetape`의 run id·상태·lane을 확인하고, 유효한 기존 run이면 먼저 resume 경로를 사용한다.
- **재개된 lane의 만료된 command deadline을 heartbeat로 우회하지 않는다.** `liveness-check`가 이전 deadline 만료를 보고했고 heartbeat는 `observed_at < lease <= command_deadline` 계약으로 거부됐다. 재개만으로 deadline이 연장됐다고 가정하지 않는다. 원래 lane의 실제 완료 증거를 기록하거나 workflow가 정의한 replacement 경로를 따른다.
- **workflow helper 인자를 스키마대로 전달한다.** `--expected-head`는 Git commit SHA가 아니라 `resume-check`가 반환하는 receipt SHA-256 checksum이다. `--evidence`는 evidence JSON 파일 경로이며, 텍스트 요약은 해당 command가 지원하는 `--evidence-summary`에만 전달한다. 변경 전에 helper `--help`와 해당 input schema를 확인한다.
- **계획 승인과 나중의 범위 승인은 현재 지시 전체로 재평가한다.** 최초 계획이 PR·게시를 제외했더라도 사용자가 이후 Projects PR, `2.2.0-SNAPSHOT` 게시, Dependencies PR·게시, Workshop 순서를 명시적으로 승인했다면 그 지시의 각 경계를 적용한다. 오래된 제외 문구만 근거로 다음 승인 단계를 막지 말고, 반대로 exact-head 병합 승인은 새로 받는다.
- **1인 개발자 리뷰 면제의 적용 범위를 기록한다.** 사용자는 현재와 향후 PR의 독립 code/architecture review를 면제한다고 확인했다. 이를 독립 검토가 수행된 증거로 표현하지 않는다. main-session 코드 검토, exact-head CI, review/thread read-back, P0/P1 처리 및 별도 exact-head 병합 승인은 유지한다.
- **저장소 전체 정적 검사 실패와 변경 파일 결과를 나눠 기록한다.** root Detekt가 실패하면 SARIF에서 지적을 고유화하고 변경 경로와 대조한다. 이번 실행은 변경되지 않은 세 모듈에 23건(10/11/2)이 남았고 변경 파일 지적은 0건이었다. 최초 실행에서 이전 후 사용되지 않는 private `toHex()`가 잡혀 제거했으며, 재실행에서 해당 지적이 사라졌다. 저장소 전체 Detekt 성공으로 표현하지 않는다.
- **integration test는 실제 Gradle task로 선택한다.** `CorrectionReconciliationIntegrationTest`를 `test` task에서 선택했을 때 찾지 못했다. 테스트 종류에 맞게 `integrationTest` task를 사용하고, 실행 XML에서 test/failure/error/skipped 수를 읽는다.

## 검증 근거

- Projects PR #1919가 `28ed92559d7f42bf27f20902dd545833c4c1d58e`에서 병합됐고 `2.2.0-SNAPSHOT` 게시 run `45367606532`가 성공했다.
- Dependencies PR #291이 `78a2f525c1d12c576f80f2c8f84dbb2fbb942ede`에서 병합됐고 post-merge CI·dependency submission·BOM `2.2.0-SNAPSHOT` 게시가 성공했다.
- Workshop 코드 검토에서 변경 production 파일 29개와 `Digesters.SHA256` 호출 35개를 확인했다. raw `MessageDigest.getInstance("SHA-256")` 잔여 호출은 0개였고, HMAC/MAC 및 15개 constant-time 비교 호출은 보존됐다.
- 대상 여섯 테스트 XML은 총 11개 통과, 실패 0, 오류 0, 건너뜀 0이다. 9개 변경 모듈의 Kotlin main/test compile 및 중앙 BOM dependency selection도 통과했다.
- root `./gradlew detekt --no-configuration-cache --console=plain`은 실패했다. 최신 SARIF의 고유 지적 23건은 변경되지 않은 `event-sourced-promotion-voucher-campaign` 10건, `pre-generated-voucher-pool` 11건, `usage-metering-billing-event-sourcing` 2건에 한정됐고 변경 경로에서는 0건이었다. 최초 실행의 변경 경로 미사용 `toHex()` 지적을 제거한 뒤 재실행에서 사라졌다.
- 이 문서가 작성될 때 Workshop PR은 아직 없으므로 exact-head CI와 merge read-back은 남은 단계다.

## 다음 작업 전 확인

1. GNO와 live GitHub에서 issue·PR·milestone·`2.2.0-SNAPSHOT` 상태를 다시 읽는다.
2. 사용자가 승인한 저장소별 순서를 지키고, consumer PR 직전에는 현재 guidance와 linked issue metadata를 다시 확인한다.
3. 변경한 task 경로만 대상으로 compile·테스트·정적 검사를 실행하고 XML 또는 Gradle 결과를 남긴다.
4. 단독 개발자 리뷰 면제와 별도 병합 승인을 혼동하지 않는다.

## Writer DoD

| 항목 | 상태 | 증거 |
| --- | --- | --- |
| SPW-01 — 독자·목적·근거 고정 | PASS | cross-repository lesson, Projects #1917, plan, receipt 및 테스트 XML |
| SPW-02 — lesson artifact 요건 충족 | PASS | 배경·결정·결과·검증·실수/수정·향후 guard 포함 |
| SPW-03 — 한국어 기술 문체 | PASS | KO-01…KO-07 문장·근거·용어 검토 및 용어 감사 |
| SPW-04 — 사실과 추적성 확인 | PASS | issue, PR, SHA, test XML 및 현재 branch 상태와 대조 |
| SPW-05 — 최종 read-back | PASS | 저장 후 Markdown 전체를 다시 읽고 표·링크·명령 확인 |

KO-07 용어 감사: `audit-korean-terms.mjs` findings 0건.
