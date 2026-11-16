package io.bluetape4k.workshop.gateway.routes

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.gateway.filter.UserKeyResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.cloud.gateway.route.builder.filters
import org.springframework.cloud.gateway.route.builder.routes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

@Configuration(proxyBeanMethods = false)
class DemoRoutes {

    companion object: KLoggingChannel()

    val testUri = "https://nghttp2.org/"

    @Autowired
    private val redisRateLimiter: RedisRateLimiter = uninitialized()

    @Autowired
    private val userKeyResolver: UserKeyResolver = uninitialized()

    @Bean
    fun routeLocator(builder: RouteLocatorBuilder): RouteLocator = builder.routes {
        route("path_route") {
            path("/get")
            filters {
                prefixPath("/httpbin")
            }
            uri(testUri)
        }

        route("host_route") {
            host("*.myhost.org")
            filters {
                prefixPath("/httpbin")
            }
            uri(testUri)
        }

        route("rewrite_route") {
            host("*.rewrite.org")
            filters {
                prefixPath("/httpbin")
                // 요청 경로인 /foo/get 을 /get 으로 변경 (rewrite)
                rewritePath("/foo/(?<segment>.*)", "/\${segment}")
            }
            uri(testUri)
        }

        route("circuitbreaker_route") {
            host("*.circuitbreaker.org")
            filters {
                prefixPath("/httpbin")
                circuitBreaker {
                    it.setName("slowcmd")
                }
            }
            uri(testUri)
        }
        route("circuitbreaker_fallback_route") {
            host("*.circuitbreakerfallback.org")
            filters {
                prefixPath("/httpbin")
                circuitBreaker {
                    it.setName("slowcmd")
                    it.setFallbackUri(URI.create("forward:/circuitbreaker/fallback"))
                }
            }
            uri(testUri)
        }

        route("limit_route") {
            host("*.limited.org") and path("/anything/**")
            filters {
                prefixPath("/httpbin")

                requestRateLimiter {
                    it.setRateLimiter(redisRateLimiter)
                    it.setKeyResolver(userKeyResolver)
                }
                // 이렇게 중복한 Filter 를 적용할 수도 있지만, 설정 방식이 복잡하다.
                requestRateLimiter {
                    it.setRateLimiter(redisRateLimiter)
                    it.setKeyResolver(userKeyResolver)
                }
            }
            uri(testUri)
        }

        route("websocket_route") {
            path("/echo")
            uri("ws://localhost:9000")
        }
    }
}
