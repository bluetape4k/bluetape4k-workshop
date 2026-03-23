# Spring Security Workshop

Spring Security를 활용한 MVC·WebFlux 보안 예제 모음입니다.

## 서브모듈 구성

```mermaid
flowchart LR
    subgraph mvc["MVC 보안"]
        MVCApp["mvc/hello\nSpring MVC + Form 로그인"]
        MVCSecurity["SecurityConfig\nFormLogin + 인메모리 사용자\nBCryptPasswordEncoder"]
        MVCController["MainController\n/ (퍼블릭)\n/user/** (ROLE_USER)"]
        MVCApp --> MVCSecurity
        MVCApp --> MVCController
    end

    subgraph webflux["WebFlux 보안"]
        WFHello["webflux/hello-security\nWebFlux + 기본 인증"]
        WFJwt["webflux/jwt\nWebFlux + JWT 토큰"]
    end

    subgraph jwt["JWT 모듈 상세"]
        JwtConfig["JwtConfig\n토큰 생성·검증"]
        TokenController["TokenController\n토큰 발급"]
        HelloController["HelloController\n보호된 리소스"]
        JwtConfig --> TokenController
        JwtConfig --> HelloController
    end

    WFJwt --> jwt
```

## Security Filter Chain 흐름

```mermaid
flowchart LR
    요청([HTTP 요청]) --> FilterChain

    subgraph FilterChain["Security Filter Chain"]
        AuthFilter["인증 필터\n(Form / JWT / Basic)"]
        AuthzFilter["인가 필터\nauthorizeHttpRequests"]
        UserDetails["UserDetailsService\n(인메모리 / DB)"]
        PasswordEncoder["BCryptPasswordEncoder"]
    end

    AuthFilter --> UserDetails
    AuthFilter --> PasswordEncoder
    AuthFilter --> AuthzFilter

    AuthzFilter -->|허용| 컨트롤러([Controller])
    AuthzFilter -->|거부| 에러([401 / 403])
```

## 참고

### Documents

* [Spring Security Reference](https://docs.spring.io/spring-security/reference/)

### Examples

* [spring-security-samples](https://github.com/spring-projects/spring-security-samples)
* [Spring Security OAuth Resource Server demo](https://github.com/arthuroz/spring-security-multi-tenancy)
* [Java Spring Security Example](https://github.com/Yoh0xFF/java-spring-security-example)
