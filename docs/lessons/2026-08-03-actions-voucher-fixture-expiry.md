# 공유 voucher contract fixture는 실행일에 만료되면 안 된다

## 맥락

2026-08-02 `Examples` run 30771298111과 이전 `Nightly` run 30764691938에서
`commerce-event-sourced-promotion-voucher-campaign:integrationTest`가 실패했다.
실패한 HTTP 응답은 `409 CAMPAIGN_ENDED`였고, `CI`의 현재 `develop` compile-only
run은 성공했으므로 workflow YAML 자체보다 integration fixture의 시간 의존성이
문제였다.

## 원인

공유 `VoucherCampaignBlackBoxContract`가 `2026-07-22`부터 `2026-07-31`까지의
고정 campaign window를 사용했다. 계약을 추가한 뒤 실행일이 7월 31일을 지나면서
두 voucher adapter가 정상 allocation을 보내도 만료 campaign으로 거부했다.
`Nightly`의 같은 실행은 이 문제와 별개로 Java 25에서 Detekt parser가 실패한
오래된 `develop` SHA도 포함했다. 현재 `develop`에는 이미 `-x detekt` 완화가
적용되어 있으므로 이번 수정에서 그 workflow 변경은 반복하지 않는다.

## 결정

공유 contract scenario의 시작 시각을 로드 시점 1분 전으로 두고, 종료 시각을
그로부터 2일 뒤로 계산한다. 이렇게 하면 fixture의 의도(현재 시각에 활성인
campaign)를 유지하면서 달력 날짜가 바뀌어도 재발하지 않는다. production clock나
별도 dependency는 추가하지 않는다.

## 검증

- 실패 artifact에서 `CAMPAIGN_ENDED` 응답과 409 assertion을 확인했다.
- `:shared:test --tests ...VoucherCampaignBlackBoxContractTest` 3개 테스트 통과.
- `:commerce-promotion-voucher-campaign:compileTestKotlin` 통과.
- `:commerce-event-sourced-promotion-voucher-campaign:compileTestKotlin` 통과.
- `actionlint .github/workflows/CI.yml .github/workflows/Nightly.yml .github/workflows/Examples.yml` 통과.
- 로컬 event-sourced integrationTest는 Docker 미가용으로 Spring context 초기화에서
  중단되었으며, 이는 테스트 assertion 실패가 아니다. GitHub artifact가 남긴 원래
  RED 증거는 별도로 보존했다.

## 다음 실행 지침

공유 voucher compatibility scenario에 고정된 과거 날짜를 넣지 않는다. 시간 경계
동작 자체를 검증해야 하는 테스트는 해당 adapter의 test clock을 고정하고, 공용
black-box contract는 실행 시점에 유효한 window를 사용한다.
