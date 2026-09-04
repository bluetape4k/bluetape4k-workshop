# #884 설계 review

## 관점별 결과

- **기능/API**: TIFF만 structured capability를 요구하고 단일 포맷 fallback은 유지한다. PASS.
- **Kotlin/코루틴**: blocking ImageIO는 upstream suspend API와 `Dispatchers.IO` 경계에 두고
  cancellation을 재전파한다. PASS.
- **보안**: magic/content-type 검증과 preflight budget, sanitized ProblemDetail로 payload·경로
  노출을 막는다. PASS.
- **테스트**: 정상 3-page, page/result limit, disabled fallback, cancellation, HTTP 오류를
  결정적 fixture로 커버한다. PASS.
- **문서/운영**: YAML budget, 양국 README, matrix/stale guard가 실제 명령과 일치한다. PASS.
- **호환성/의존성**: root `bluetape4k-dependencies:2.0.0` BOM과 versionless aliases를
  유지하고 response 필드를 추가하지 않는다. PASS.

통합 판정: P0=0, P1=0. partial result, unsupported plain engine, metadata budget과
timeout은 구현 및 테스트 단계에서 재확인한다.
