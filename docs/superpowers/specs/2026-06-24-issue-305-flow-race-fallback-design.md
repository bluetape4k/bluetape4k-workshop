# 305호 Flow Race/Fallback 디자인

## 목표

수동 async/select 배관 없이 다중 소스 Flow 선택을 설명하는 워크숍 모듈을 추가합니다.

## 결정

- `RaceFallbackCatalog`을 얇은 연산자 선택 외관으로 사용하세요.
- 도메인을 인메모리에서 결정적으로 유지합니다: `CatalogItem`, `CatalogSource`, `SourceResult`, `SourceQuality`.
- `race`을 `amb`로 처리: 처음으로 내보낸 값이 승리하고 손실된 소스 작업이 취소됩니다.
- 엄격한 우선순위 폴백을 위해서는 `concat`을 사용하세요.
- `concatArrayEager` 및 `concatMapEager`을 사용하면 순서대로 출력된 소스 시작을 표시할 수 있습니다.
- 모든 소스가 부분 데이터를 제공하는 경우 `merge`을 사용하세요.
- `materialize` / `dematerialize`을 사용하여 터미널 오류와 값으로서의 오류 설명을 구별하세요.

## 다이어그램

README는 시나리오, 아키텍처, ERD, 클래스 및 시퀀스 다이어그램을 SVG 소스와 함께 생성된 PNG 자산으로 포함합니다.

## 검증 대상

- 모듈 테스트는 경합 취소, 순서화된 폴백, 열성적 폴백, 동적 열성적 매핑, 병합, 구체화 및 비물질화를 다룹니다.
- 다이어그램 XML, 기하학, 엔드포인트, 렌더링된 PNG 시각적 검사가 통과되었습니다.
