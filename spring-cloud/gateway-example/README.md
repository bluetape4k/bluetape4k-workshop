# Spring Cloud Gateway Sample

## 아키텍처 다이어그램

```mermaid
flowchart LR
    subgraph 클라이언트
        C[HTTP 클라이언트]
    end

    subgraph Spring Cloud Gateway :8080
        RL[RedisRateLimiter\nRequestRateLimiter 필터]
        CB[CircuitBreaker\nResilience4j slowcmd]
        UK[UserKeyResolver\n사용자 키 추출]
        FB[FallbackController\n/circuitbreaker/fallback]

        subgraph 라우트 규칙
            PR[path_route\nGET /get]
            HR[host_route\n*.myhost.org]
            RR[rewrite_route\n*.rewrite.org\n/foo/* → /*]
            CCR[circuitbreaker_route\n*.circuitbreaker.org]
            CCFR[circuitbreaker_fallback_route\n*.circuitbreakerfallback.org]
            LR[limit_route\n*.limited.org]
            WR[websocket_route\n/echo]
        end
    end

    subgraph 백엔드
        BE[nghttp2.org\nhttpbin]
        WS[WebSocket 서버\n:9000]
        RD[(Redis\n레이트 리밋 상태)]
    end

    C --> PR & HR & RR & CCR & CCFR & LR & WR
    PR & HR & RR --> BE
    CCR --> CB --> BE
    CCFR --> CB
    CB -->|폴백| FB
    LR --> RL
    RL <--> RD
    UK --> RL
    RL --> BE
    WR --> WS
```

## 참고

이 예제는 Bucket4j 를 사용하지 않고, Spring Cloud에서 자체 제공하는
Redis 를 이용한 Rate Limiter 와 Resilience4j 의 Circuit Breaker를 사용하는 예를 보여준다.

## Resources

-[Spring cloud gateway with Resilience4j circuit breaker](https://medium.com/@mahmoud.romeh/spring-cloud-gateway-with-resilience4j-circuit-breaker-4f46d86822f0)
-[spring-cloud-gateway](https://github.com/m-thirumal/spring-cloud-gateway)

Sample that shows a few different ways to route and showcases some filters.

Run `DemogatewayApplication`

## Samples

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

## Websocket Sample

[install wscat](https://www.npmjs.com/package/wscat)

In one terminal, run websocket server:

```
wscat --listen 9000
``` 

In another, run a client, connecting through gateway:

```
wscat --connect ws://localhost:8080/echo
```

type away in either server and client, messages will be passed appropriately.

## Running Redis Rate Limiter Test

Make sure redis is running on localhost:6379 (using brew or apt or docker).

Then run `DemogatewayApplicationTests`. It should pass which means one of the calls received a 429 TO_MANY_REQUESTS HTTP
status.
