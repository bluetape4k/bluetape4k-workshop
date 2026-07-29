# 305호 Flow Race/Fallback 계획

## 작업

- [x] `kotlin/flow-extensions-race-fallback` 모듈을 생성합니다.
- [x] 도메인 DTO 및 `RaceFallbackCatalog` 연산자 외관을 추가합니다.
- [x] `race`, `concat`, `concatArrayEager`, `concatMapEager`, `merge`, `materialize` 및 `dematerialize`에 대한 테스트를 추가합니다.
- [x] 영어, 한국어 README 파일을 추가합니다.
- [x] 시나리오, 아키텍처, ERD, 클래스 및 시퀀스 다이어그램을 추가합니다.
- [x] 루트 README 파일에 모듈을 등록합니다.
- [ ] 모듈 테스트를 실행합니다.
- [ ] 모듈 등록 및 차이점 확인을 실행합니다.
- [ ] 다이어그램 감사 및 전체 크기 PNG 시각적 QA을 실행합니다.
- [ ] PR을 커밋하고 푸시하고 엽니다.

## 위험

- 열정적인 운영자는 소스 작업을 즉시 시작하지만 여전히 소스 순서대로 내보냅니다. 테스트는 두 가지 사실을 모두 입증해야 합니다.
- `race` 승자는 시작할 첫 번째 소스가 아니라 처음으로 방출된 값입니다.
- 병합 출력 순서는 도착 기반이므로 테스트는 정확한 순서 대신 집합을 검증해야 합니다.
