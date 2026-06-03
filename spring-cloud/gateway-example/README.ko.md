# Spring Cloud Gateway Sample

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Cloud Gateway Sample**을 실행 가능한 Spring Cloud 통합 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Spring Cloud Gateway Sample Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/spring-cloud-gateway-example-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제에서 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README와 코드를 비교할 때는 `io.bluetape4k.workshop.springcloud` 패키지를 기준으로 삼으세요.

## 참고 사항

이 예제는 Bucket4j를 사용하지 않습니다. Spring Cloud의 내장 Redis-backed Rate Limiter와 Resilience4j Circuit Breaker를 보여 줍니다.

## 리소스

-[Spring cloud gateway with Resilience4j circuit breaker](https://medium.com/@mahmoud.romeh/spring-cloud-gateway-with-resilience4j-circuit-breaker-4f46d86822f0)
-[spring-cloud-gateway](https://github.com/m-thirumal/spring-cloud-gateway)

몇 가지 route 방식과 filter를 보여 주는 sample입니다.

`DemogatewayApplication`을 실행하세요.

## 샘플

```
$ http :8080/get
HTTP/1.1 200 OK
Access-Control-Allow-Credentials: true
Access-Control-Allow-Origin: *
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Connection: keep-alive
Content-Length: 257
Content-Type: application/json
Date: Fri, 13 Oct 2017 15:36:12 GMT
Expires: 0
Pragma: no-cache
Server: meinheld/0.6.1
Via: 1.1 vegur
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-Powered-By: Flask
X-Processed-Time: 0.00123405456543
X-XSS-Protection: 1 ; mode=block

{
    "args": {},
    "headers": {
        "Accept": "*/*",
        "Accept-Encoding": "gzip, deflate",
        "Connection": "close",
        "Host": "httpbin.org",
        "User-Agent": "HTTPie/0.9.8"
    },
    "origin": "207.107.158.66",
    "url": "http://httpbin.org/get"
}

$ http :8080/headers Host:www.myhost.org
HTTP/1.1 200 OK
Access-Control-Allow-Credentials: true
Access-Control-Allow-Origin: *
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Connection: keep-alive
Content-Length: 175
Content-Type: application/json
Date: Fri, 13 Oct 2017 15:36:35 GMT
Expires: 0
Pragma: no-cache
Server: meinheld/0.6.1
Via: 1.1 vegur
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-Powered-By: Flask
X-Processed-Time: 0.0012538433075
X-XSS-Protection: 1 ; mode=block

{
    "headers": {
        "Accept": "*/*",
        "Accept-Encoding": "gzip, deflate",
        "Connection": "close",
        "Host": "httpbin.org",
        "User-Agent": "HTTPie/0.9.8"
    }
}

$ http :8080/foo/get Host:www.rewrite.org
HTTP/1.1 200 OK
Access-Control-Allow-Credentials: true
Access-Control-Allow-Origin: *
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Connection: keep-alive
Content-Length: 257
Content-Type: application/json
Date: Fri, 13 Oct 2017 15:36:51 GMT
Expires: 0
Pragma: no-cache
Server: meinheld/0.6.1
Via: 1.1 vegur
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-Powered-By: Flask
X-Processed-Time: 0.000664949417114
X-XSS-Protection: 1 ; mode=block

{
    "args": {},
    "headers": {
        "Accept": "*/*",
        "Accept-Encoding": "gzip, deflate",
        "Connection": "close",
        "Host": "httpbin.org",
        "User-Agent": "HTTPie/0.9.8"
    },
    "origin": "207.107.158.66",
    "url": "http://httpbin.org/get"
}

$ http :8080/delay/2 Host:www.circuitbreaker.org
HTTP/1.1 504 Gateway Timeout
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Expires: 0
Pragma: no-cache
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 1 ; mode=block
content-length: 0

```

## Websocket 샘플

[install wscat](https://www.npmjs.com/package/wscat)

한 terminal에서 websocket server를 실행합니다.

```
wscat --listen 9000
```

다른 terminal에서 gateway를 통해 연결하는 client를 실행합니다.

```
wscat --connect ws://localhost:8080/echo
```

server와 client 어느 쪽에서든 입력하면 message가 적절히 전달됩니다.

## Redis Rate Limiter 테스트 실행

redis가 localhost:6379에서 실행 중인지 확인하세요(brew, apt 또는 docker 사용).

그런 다음 `DemogatewayApplicationTests`를 실행합니다. 테스트가 통과하면 호출 중 하나가 429 TO_MANY_REQUESTS HTTP status를 받았다는 뜻입니다.
