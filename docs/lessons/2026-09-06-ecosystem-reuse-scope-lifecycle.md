# Ecosystem reuse scope 생명주기 분리

## 배경

Ecosystem reuse manifest의 fixed node는 `state=MERGED`로 종료 상태를 표현했지만,
`follow_up_scopes`에는 생명주기 필드가 없었다. PR checker는 두 종류의 scope를
모두 과거 이력과 현재 후보를 구분하지 않고 경로 매칭에 사용했다. 그 결과 이미
병합된 여러 scope가 같은 테스트 경로를 계속 소유해 Nightly 회귀 수정 PR #960이
`found 9`로 실패했다.

## 결정

- follow-up scope는 `lifecycle=ACTIVE|MERGED`를 반드시 선언한다.
- fixed node는 `state != MERGED`, follow-up scope는 `lifecycle == ACTIVE`일 때만
  PR 후보 선택과 outside-train 판정에 참여한다.
- `MERGED` follow-up scope는 이력과 review evidence로 manifest에 보존하되 새 PR의
  ref/OID/path 후보로 재사용하지 않는다.
- trusted manifest 비교는 fresh coordinator receipt가 있더라도 `MERGED -> ACTIVE`
  전환을 거부한다. 같은 작업을 다시 열어야 하면 기존 scope를 되살리지 않고 새
  `scope_id`와 새 receipt를 등록한다.
- manifest와 이 lifecycle lesson은 ecosystem policy control-plane 경로로 분류한다.
  제품 코드가 섞이면 maintenance 예외를 받지 않는다.

## Migration 근거

2026-09-06 GitHub 실조회에서 현재 follow-up scope 47개의 `expected_head_ref`를
연결한 PR이 모두 `MERGED` 상태임을 확인했다. 따라서 47개 scope를 일괄
`lifecycle=MERGED`로 전환하고, canonical follow-up scope JSON의 SHA-256
`5f1fb07c27c5ce31729bb25fa8aa4d65c987ea68976620c080ba3eab6af51cd3`을
coordinator receipt에 기록했다.

## 회귀 검증 기준

- lifecycle 누락과 알 수 없는 값은 manifest validation에서 실패한다.
- `ACTIVE` scope의 기존 ref/OID/path 검증은 그대로 통과한다.
- `MERGED` fixed/follow-up scope만 남은 경로는 historical candidate로 매칭되지 않는다.
- `ACTIVE` mapped path와 unmapped path가 섞인 diff는 계속 fail closed 한다.
- PR #960의 exact base/head/ref 입력은 outside-train scope로 분류되어 통과해야 한다.

## 향후 지침

후속 PR을 manifest에 추가할 때는 처음부터 `lifecycle=ACTIVE`와 fresh coordinator
receipt를 함께 기록한다. 병합 확인 후에는 scope를 삭제하지 말고 `MERGED`로 닫아
감사 이력을 남긴다. 새 작업이 같은 경로를 수정하더라도 과거 scope를 재활성화하지
말고 새 scope identity를 발급한다.
