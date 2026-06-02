# STOMP WebSocket 예제

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **STOMP WebSocket 예제** 모듈을 실행 가능한 Spring Boot 애플리케이션 기능 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 아키텍처 다이어그램

모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지 아래의 구현을 기준으로 삼습니다.

## 흐름 다이어그램

1. `spring-boot-stomp-websocket` 예제에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 처리는 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![STOMP WebSocket 예제 시퀀스 다이어그램](../../docs/images/readme-diagrams/spring-boot-stomp-websocket-sequence-01.png)

## 원문 상세 항목

영어 README에는 다음 상세 항목이 포함되어 있습니다. 한국어 요약은 위의 시나리오/아키텍처/흐름을 기준으로 읽고, 코드 예제와 설정 세부사항은 영어 README의 같은 모듈 설명을 함께 참고하세요.

- 구성
- STOMP 메시지 흐름
- 핵심 설정
- 실행
- STOMP 프로토콜 개념
- 메시지 데이터 모델
- GreetingController 동작
- Virtual Thread 적용

## 실행

```bash
./gradlew :spring-boot-stomp-websocket:test
```
