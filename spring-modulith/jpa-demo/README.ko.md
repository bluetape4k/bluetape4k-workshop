# Spring Modulith JPA Demo

[English](README.md) | 한국어

이 모듈은 Spring Data JPA와 H2를 사용하는 Spring Modulith service sample입니다.
애플리케이션을 `organization`, `department`, `employee`, `gateway` 모듈로 나누고,
Spring Modulith 테스트로 module structure를 검증합니다.

## 아키텍처

![Spring Modulith JPA architecture](../../docs/images/readme-diagrams/spring-modulith-jpa-demo-readme-architecture-01.png)

Gateway는 REST endpoint를 노출하고 각 모듈의 external API에만 의존합니다. Domain
module 간에는 다른 모듈이 소유한 read model이 필요할 때 internal API를 사용합니다.

## Organization Event Flow

![Spring Modulith JPA event flow](../../docs/images/readme-diagrams/spring-modulith-jpa-demo-readme-flow-01.png)

Organization을 추가하면 JPA entity를 저장하고 `OrganizationAddEvent`를 발행합니다.
Department는 `@ApplicationModuleListener`로 이벤트를 받아 기본 부서를 생성합니다.
Organization을 삭제하면 `OrganizationRemoveEvent`가 발행되고, department는 해당
organization의 department를 삭제합니다.

## Modules

| Module | Public contract | Persistence | Responsibility |
|---|---|---|---|
| `organization` | `OrganizationExternalAPI` | `OrganizationRepository` | Organization 추가/삭제와 organization view 조립. |
| `department` | `DepartmentExternalAPI`, `DepartmentInternalAPI` | `DepartmentRepository` | Department 관리와 employee 정보 보강. |
| `employee` | `EmployeeExternalAPI`, `EmployeeInternalAPI` | `EmployeeRepository` | Employee 관리와 organization/department 기준 조회. |
| `gateway` | `/api` 아래 REST controller | 없음 | Module API를 HTTP로 노출. |

## REST API

| Method | Path | 결과 |
|---|---|---|
| `GET` | `/api/organizations/{id}/with-departments` | Department가 포함된 organization. |
| `GET` | `/api/organizations/{id}/with-departments-and-employees` | 중첩 module data가 포함된 organization view. |
| `GET` | `/api/departments/{id}/with-employees` | Employee가 포함된 department. |
| `POST` | `/api/organizations` | Organization 추가와 organization-add event 발행. |
| `POST` | `/api/departments` | Department 추가. |
| `POST` | `/api/employees` | Employee 추가. |

## Runtime

이 데모는 Spring Data JPA와 in-memory H2를 사용합니다. Sample 용도로 actuator endpoint가
노출되어 있고, `application.yml`의 tracing sampling은 `1.0`입니다.

## 빌드와 테스트

```bash
./gradlew :spring-modulith:jpa-demo:test
```
