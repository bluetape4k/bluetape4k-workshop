# Event-sourced promotion voucher campaign

이 예제는 PostgreSQL을 event authority로 사용하고, Spring Boot의 HikariCP
`DataSource`와 bluetape4k Exposed Spring transaction manager를 연결한다.

## Identity erasure와 HMAC

immutable event에는 원본 사용자 식별자를 저장하지 않는다. command boundary는
사용자별 random UUIDv7 surrogate를 발급하고, 삭제 가능한
`voucher_subject_identity_mapping`에만 identity HMAC과 surrogate의 연결을
저장한다. erasure는 이 mapping을 삭제하며 `voucher_event_log`를 수정하지 않는다.
같은 사용자가 다시 등록되면 새 surrogate가 발급된다.

HMAC은 erasure가 아니다. HMAC은 저엔트로 identity와 idempotency key를 plain
hash rainbow-table 공격에서 보호하는 stable correlation 수단일 뿐이다. 실제
erasure 경계는 mapping 삭제다. HMAC 입력은 version, purpose, tenant, domain으로
분리되며 event와 terminal receipt에는 사용한 key version을 기록한다.

로컬 실행은 `application.yml`의 workshop 전용 fallback key를 사용한다. 운영
환경에서는 반드시 안정적인 32-byte 이상 key를 Base64로 주입한다.

```bash
export VOUCHER_HMAC_ACTIVE_VERSION=2
export VOUCHER_HMAC_ACTIVE_KEY_BASE64='<base64-secret>'
```

## Key rotation과 rollback

1. 새 active key를 배포하기 전에 기존 key로 생성된 event, snapshot, terminal
   receipt의 최대 replay retention을 확인한다.
2. 기존 key를 `voucher.security.hmac.retired`에 `version`, `key-base64`,
   `retain-until`과 함께 등록하고 새 active key를 배포한다.
3. 이전 key version으로 저장된 terminal receipt와 snapshot replay test를
   실행한다. 이 검증이 통과하기 전에는 기존 key를 제거하지 않는다.
4. `retain-until` 이후 key를 제거하면 해당 version replay는 의도적으로 HTTP
   503 `REPLAY_KEY_UNAVAILABLE`로 fail closed한다.
5. rotation 문제가 생기면 새 write를 중지하고 직전 active key를 다시 active로
   배포한다. immutable event나 terminal receipt를 수정해서 복구하지 않는다.

예시:

```yaml
voucher:
  security:
    hmac:
      active-version: 2
      active-key-base64: ${VOUCHER_HMAC_ACTIVE_KEY_BASE64}
      retired:
        - version: 1
          key-base64: ${VOUCHER_HMAC_RETIRED_KEY_1_BASE64}
          retain-until: 2027-01-01T00:00:00Z
```

## Mapping backup과 restore

mapping backup은 event-store backup과 별도 보안 등급으로 암호화하고 접근을
제한한다. 원본 identity는 어느 backup에도 포함하지 않는다.

```bash
pg_dump \
  --table=voucher_subject_identity_mapping \
  --data-only \
  --format=custom \
  --file=voucher-subject-mapping.dump \
  "$BACKUP_DATABASE_URL"

pg_restore \
  --data-only \
  --table=voucher_subject_identity_mapping \
  --dbname="$RESTORE_DATABASE_URL" \
  voucher-subject-mapping.dump
```

restore 후에는 tenant별 row count, surrogate unique constraint, HMAC key version
가용성을 확인하고 representative reverse lookup test를 실행한다. erasure가
완료된 mapping을 오래된 backup에서 되살리지 않도록 erasure tombstone 또는
backup 생성 시점 이후의 deletion journal을 복원 절차에 반드시 반영한다.
