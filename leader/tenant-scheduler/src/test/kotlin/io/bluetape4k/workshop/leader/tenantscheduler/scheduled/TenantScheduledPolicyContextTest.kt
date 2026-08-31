package io.bluetape4k.workshop.leader.tenantscheduler.scheduled

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.micrometer.LeaderMetricTagMode
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.bluetape4k.leader.spring.metrics.LeaderMicrometerAutoConfiguration
import io.bluetape4k.leader.spring.metrics.LeaderObservationAutoConfiguration
import io.bluetape4k.leader.spring.scheduling.LeaderScheduled
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyAutoConfiguration
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyBeanPostProcessor
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyProperties
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyRegistry
import io.bluetape4k.workshop.leader.tenantscheduler.TenantSchedulerLabApp
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertTimeout
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.getBeansOfType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration

@SpringBootTest(
    classes = [TenantSchedulerLabApp::class],
    properties = [
        "bluetape4k.leader.observability.tracing.include-lock-name=true",
    ],
)
@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class])
@ActiveProfiles("scheduled-policy")
@Import(TenantScheduledPolicyContextTest.ObservationConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScheduledPolicyContextTest @Autowired constructor(
    private val environment: ConfigurableEnvironment,
    private val policyProperties: LeaderScheduledPolicyProperties,
    private val leaderProperties: LeaderProperties,
    private val registry: LeaderScheduledPolicyRegistry,
    private val policyBpp: LeaderScheduledPolicyBeanPostProcessor,
    private val fixture: TenantScheduledPolicyFixture,
    private val applicationContext: ApplicationContext,
    private val observationRegistry: ObservationRegistry,
    private val observationRecorder: MicrometerObservationLeaderAopMetricsRecorder,
    private val observationHandler: RecordingObservationHandler,
) {

    @Test
    fun `scheduled policy profile binds packaged YAML and registers one Spring task`() {
        environment.getProperty("bluetape4k.leader.scheduling.enabled", Boolean::class.java) shouldBeEqualTo true
        policyProperties.enabled.shouldBeTrue()
        policyProperties.policies.single().let { policy ->
            policy.selector shouldBeEqualTo "tenantScheduledPolicyFixture#reconcile"
            policy.name shouldBeEqualTo "tenant-scheduler:reconcile"
            policy.waitTime shouldBeEqualTo Duration.ZERO
            policy.leaseTime shouldBeEqualTo Duration.ofSeconds(30)
            policy.minLeaseTime shouldBeEqualTo Duration.ofSeconds(5)
            policy.bean shouldBeEqualTo "localLeaderElectionFactory"
            policy.failureMode.name shouldBeEqualTo "SKIP"
        }

        val sourceNames = environment.propertySources.map { it.name }
        sourceNames.any { it.contains("application-scheduled-policy") }.shouldBeTrue()
        ClassPathResource("application-scheduled-policy.yml").exists().shouldBeTrue()

        registry::class.java shouldBeEqualTo LeaderScheduledPolicyRegistry::class.java
        policyBpp::class.java shouldBeEqualTo LeaderScheduledPolicyBeanPostProcessor::class.java
        AopUtils.getTargetClass(fixture) shouldBeEqualTo TenantScheduledPolicyFixture::class.java
        observationRegistry.isNoop.shouldBeFalse()
        observationRecorder.options.includeLockName.shouldBeTrue()
        observationRecorder.options.includeLeaderId.shouldBeFalse()
        observationRecorder.options.includeExceptionDetails.shouldBeFalse()
        observationRecorder.options.tagOptions.lockName.mode shouldBeEqualTo LeaderMetricTagMode.REDACT
        observationRecorder.options.tagOptions.lockName.redactedValue shouldBeEqualTo "redacted-lock"
        leaderProperties.observability.tracing.includeLockName shouldBeEqualTo true
        leaderProperties.observability.tracing.includeLeaderId shouldBeEqualTo false
        leaderProperties.observability.tracing.includeExceptionDetails shouldBeEqualTo false
        ClassPathResource("application-scheduled-policy.yml").inputStream.bufferedReader().readText()
            .contains("include-lock-name: false").shouldBeTrue()

        environment.getProperty("bluetape4k.leader.aop.strict", Boolean::class.java) shouldBeEqualTo true
        environment.getProperty("bluetape4k.leader.aop.spel.allow-method-invocation", Boolean::class.java) shouldBeEqualTo false
        environment.getProperty("bluetape4k.leader.aop.metrics.tags.lock-name.mode") shouldBeEqualTo "REDACT"
        contextBeanNames().contains("localLeaderElectionFactory").shouldBeTrue()
        contextBeanNames().contains("tenantScheduledPolicyFixture").shouldBeTrue()
        contextBeanNames().any { it.endsWith("internalAutoProxyCreator") }.shouldBeTrue()
        scheduledTaskCount() shouldBeEqualTo 1
    }

    @Test
    fun `main source fixture is proxied and records sanitized leader observations on direct calls`() {
        observationHandler.clear()

        assertTimeout(Duration.ofSeconds(15)) {
            fixture.reconcile()
            fixture.reconcile()
        }

        fixture.invocationCount() shouldBeEqualTo 2
        scheduledTaskCount() shouldBeEqualTo 1
        val observations = observationHandler.stopped.filter { it.name.startsWith("leader.aop.") }
        observations.count { it.name == "leader.aop.acquire" } shouldBeEqualTo 2
        observations.count { it.name == "leader.aop.execution" } shouldBeEqualTo 2
        observations.filter { it.name == "leader.aop.acquire" }
            .forEach { it.low["leader.operation"] shouldBeEqualTo "acquire"; it.low["outcome"] shouldBeEqualTo "acquired" }
        observations.filter { it.name == "leader.aop.execution" }
            .forEach { it.low["leader.operation"] shouldBeEqualTo "execute"; it.low["outcome"] shouldBeEqualTo "success" }
        observations.forEach { snapshot ->
            snapshot.high["lock.name"] shouldBeEqualTo "redacted-lock"
            snapshot.high.values.none { value -> value.contains("tenant-scheduler:reconcile") }.shouldBeTrue()
        }
    }

    @Test
    fun `Spring scheduler callback uses policy proxy and records leader observations`() {
        runner
            .withUserConfiguration(
                LeaderScheduledTriggerConfiguration::class.java,
                ObservationConfiguration::class.java,
            )
            .withPropertyValues(
                "spring.aop.auto=false",
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=leaderScheduledTriggerFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=tenant-scheduler:trigger",
                "bluetape4k.leader.scheduling.policies[0].wait-time=0s",
                "bluetape4k.leader.scheduling.policies[0].lease-time=30s",
                "bluetape4k.leader.scheduling.policies[0].min-lease-time=0s",
                "bluetape4k.leader.scheduling.policies[0].bean=localLeaderElectionFactory",
                "bluetape4k.leader.scheduling.policies[0].auto-extend=false",
                "bluetape4k.leader.scheduling.policies[0].stream-bounded=false",
                "bluetape4k.leader.scheduling.policies[0].failure-mode=SKIP",
                "bluetape4k.leader.observability.tracing.include-lock-name=true",
                "bluetape4k.leader.aop.metrics.tags.lock-name.mode=REDACT",
                "bluetape4k.leader.aop.metrics.tags.lock-name.redacted-value=redacted-lock",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                val fixture = context.getBean(
                    "leaderScheduledTriggerFixture",
                    LeaderScheduledTriggerFixture::class.java,
                )
                val handler = context.getBean(RecordingObservationHandler::class.java)
                handler.clear()

                assertTimeout(Duration.ofSeconds(5)) {
                    fixture.awaitStarted(Duration.ofSeconds(5)).shouldBeTrue()
                }

                (fixture.invocationCount() >= 1).shouldBeTrue()
                context.getBeansOfType<ScheduledTaskHolder>().values
                    .flatMap { it.scheduledTasks }
                    .toSet()
                    .size shouldBeEqualTo 1
                (handler.stopped.count { it.name == "leader.aop.acquire" } >= 1).shouldBeTrue()
                (handler.stopped.count { it.name == "leader.aop.execution" } >= 1).shouldBeTrue()
                handler.stopped
                    .filter { it.name.startsWith("leader.aop.") }
                    .forEach { snapshot ->
                        snapshot.high["lock.name"] shouldBeEqualTo "redacted-lock"
                        snapshot.high.values.none { value -> value.contains("tenant-scheduler:trigger") }
                            .shouldBeTrue()
                    }
            }
    }

    @Test
    fun `failed policy call omits exception details while retaining bounded outcome tags`() {
        runner
            .withUserConfiguration(
                LeaderScheduledFailureConfiguration::class.java,
                ObservationConfiguration::class.java,
            )
            .withPropertyValues(
                "spring.aop.auto=false",
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=leaderScheduledFailureFixture#fail",
                "bluetape4k.leader.scheduling.policies[0].name=tenant-scheduler:failure",
                "bluetape4k.leader.scheduling.policies[0].wait-time=0s",
                "bluetape4k.leader.scheduling.policies[0].lease-time=30s",
                "bluetape4k.leader.scheduling.policies[0].min-lease-time=0s",
                "bluetape4k.leader.scheduling.policies[0].bean=localLeaderElectionFactory",
                "bluetape4k.leader.scheduling.policies[0].failure-mode=RETHROW",
                "bluetape4k.leader.observability.tracing.include-lock-name=true",
                "bluetape4k.leader.observability.tracing.include-exception-details=false",
                "bluetape4k.leader.aop.metrics.tags.lock-name.mode=REDACT",
                "bluetape4k.leader.aop.metrics.tags.lock-name.redacted-value=redacted-lock",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                val fixture = context.getBean(
                    "leaderScheduledFailureFixture",
                    LeaderScheduledFailureFixture::class.java,
                )
                val handler = context.getBean(RecordingObservationHandler::class.java)
                handler.clear()

                val error = assertFailsWith<Throwable> { fixture.fail() }
                generateSequence(error) { it.cause }
                    .last()
                    .message shouldBeEqualTo "sensitive-customer-id"

                val failureObservation = handler.stopped.single {
                    it.name == "leader.aop.execution" && it.low["outcome"] == "error"
                }
                failureObservation.low["exception"] shouldBeEqualTo "IllegalStateException"
                failureObservation.error.shouldBeNull()
                failureObservation.high["lock.name"] shouldBeEqualTo "redacted-lock"
                failureObservation.high.values.none { value -> value.contains("sensitive-customer-id") }
                    .shouldBeTrue()
            }
    }

    @Test
    fun `default runner keeps policy infrastructure disabled`() {
        runner
            .withUserConfiguration(ScheduledFixtureConfiguration::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.containsBean("leaderScheduledPolicyRegistry").shouldBeFalse()
                context.containsBean("leaderScheduledPolicyBeanPostProcessor").shouldBeFalse()
            }
    }

    @Test
    fun `enabled policy without entries fails closed`() {
        runner
            .withUserConfiguration(ScheduledFixtureConfiguration::class.java)
            .withPropertyValues("bluetape4k.leader.scheduling.enabled=true")
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain "property 'policies'"
            }
    }

    @Test
    fun `external scheduling enable override also fails closed without policy`() {
        runner
            .withUserConfiguration(ScheduledFixtureConfiguration::class.java)
            .withPropertyValues("bluetape4k.leader.scheduling.enabled=true")
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain "bluetape4k.leader.scheduling"
            }
    }

    @Test
    fun `malformed selectors and durations fail with actionable property`() {
        listOf(
            "scheduledFixture.reconcile",
            "scheduledFixture#reconcile#again",
            "#reconcile",
            "scheduledFixture#",
        ).forEach { selector ->
            runner
                .withUserConfiguration(ScheduledFixtureConfiguration::class.java)
                .withPropertyValues(
                    "bluetape4k.leader.scheduling.enabled=true",
                    "bluetape4k.leader.scheduling.policies[0].selector=$selector",
                    "bluetape4k.leader.scheduling.policies[0].name=scheduled",
                )
                .run { context ->
                    context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain selector.trim()
                }
        }

        runner
            .withUserConfiguration(ScheduledFixtureConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=scheduled",
                "bluetape4k.leader.scheduling.policies[0].wait-time=not-a-duration",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
            }
    }

    @Test
    fun `registry rejects selectors with surrounding whitespace before Spring binding`() {
        val error = assertFailsWith<IllegalArgumentException> {
            LeaderScheduledPolicyRegistry(
                listOf(
                    LeaderScheduledPolicyProperties.Policy(
                        selector = " scheduledFixture#reconcile",
                        name = "scheduled",
                    ),
                ),
            )
        }
        error.message.orEmpty() shouldContain "scheduledFixture#reconcile"
    }

    @Test
    fun `duplicate and unmatched selectors fail during startup`() {
        runner
            .withUserConfiguration(ScheduledFixtureConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=one",
                "bluetape4k.leader.scheduling.policies[1].selector=scheduledFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[1].name=two",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain "scheduledFixture#reconcile"
            }

        runner
            .withUserConfiguration(ScheduledFixtureConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#missing",
                "bluetape4k.leader.scheduling.policies[0].name=missing",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain "scheduledFixture#missing"
            }
    }

    @Test
    fun `explicit annotation takes precedence over a policy with invalid policy values`() {
        runner
            .withUserConfiguration(AnnotatedScheduledFixtureConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=annotatedScheduledFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=",
                "bluetape4k.leader.scheduling.policies[0].wait-time=-1s",
                "bluetape4k.leader.scheduling.policies[0].lease-time=0s",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.containsBean("leaderScheduledPolicyRegistry").shouldBeTrue()
            }
    }

    @Test
    fun `single and group annotations take precedence over conflicting property policies`() {
        runner
            .withUserConfiguration(AnnotationPrecedenceFixtureConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=leaderAnnotationFixture#single",
                "bluetape4k.leader.scheduling.policies[0].name=",
                "bluetape4k.leader.scheduling.policies[0].wait-time=-1s",
                "bluetape4k.leader.scheduling.policies[0].lease-time=0s",
                "bluetape4k.leader.scheduling.policies[1].selector=leaderAnnotationFixture#group",
                "bluetape4k.leader.scheduling.policies[1].name=",
                "bluetape4k.leader.scheduling.policies[1].wait-time=-1s",
                "bluetape4k.leader.scheduling.policies[1].lease-time=0s",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                val registry = context.getBean(LeaderScheduledPolicyRegistry::class.java)
                val single = context.getBean("leaderAnnotationFixture", AnnotationPrecedenceFixture::class.java)
                val singleMethod = AnnotationPrecedenceFixture::class.java.getDeclaredMethod("single")
                val groupMethod = AnnotationPrecedenceFixture::class.java.getDeclaredMethod("group")

                registry.lookup(singleMethod, single).shouldBeNull()
                registry.lookup(groupMethod, single).shouldBeNull()
                context.containsBean("localLeaderElectionFactory").shouldBeTrue()
                context.containsBean("localLeaderGroupElectionFactory").shouldBeTrue()
            }
    }

    @Test
    fun `overloaded scheduled selector is rejected by the registry`() {
        val fixture = OverloadedScheduledFixture()
        val methods = OverloadedScheduledFixture::class.java.declaredMethods
            .filter { it.name == "reconcile" }
            .sortedBy { it.parameterCount }
        methods.size shouldBeEqualTo 2

        val policy = LeaderScheduledPolicyProperties.Policy(
            selector = "overloadedScheduledFixture#reconcile",
            name = "overloaded-scheduled",
            leaseTime = Duration.ofSeconds(30),
        )
        val registry = LeaderScheduledPolicyRegistry(listOf(policy))
        registry.register("overloadedScheduledFixture", fixture, methods[0], policy)

        val error = assertFailsWith<IllegalStateException> {
            registry.register("overloadedScheduledFixture", fixture, methods[1], policy)
        }
        error.message.orEmpty() shouldContain "overloaded methods are not supported"
    }

    @Test
    fun `plain policy semantic errors fail fast with actionable property names`() {
        listOf(
            listOf(
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=scheduled",
                "bluetape4k.leader.scheduling.policies[0].wait-time=-1s",
            ) to "wait-time",
            listOf(
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=scheduled",
                "bluetape4k.leader.scheduling.policies[0].lease-time=0s",
            ) to "lease-time",
            listOf(
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=scheduled",
                "bluetape4k.leader.scheduling.policies[0].lease-time=5s",
                "bluetape4k.leader.scheduling.policies[0].min-lease-time=6s",
            ) to "min-lease-time",
            listOf(
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=",
            ) to "property 'name'",
        ).forEach { (properties, expectedProperty) ->
            runner
                .withUserConfiguration(ScheduledFixtureConfiguration::class.java)
                .withPropertyValues(
                    "bluetape4k.leader.scheduling.enabled=true",
                    *properties.toTypedArray(),
                )
                .run { context ->
                    context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain expectedProperty
                }
        }
    }

    @Test
    fun `leader auto configuration imports retain factory metrics observation policy and AOP order`() {
        val imports = javaClass.classLoader
            .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .shouldNotBeNull()
            .readText()
            .lines()

        val factory = imports.indexOf(LeaderAopFactoryAutoConfiguration::class.qualifiedName)
        val policy = imports.indexOf(LeaderScheduledPolicyAutoConfiguration::class.qualifiedName)
        val micrometer = imports.indexOf(LeaderMicrometerAutoConfiguration::class.qualifiedName)
        val observation = imports.indexOf(LeaderObservationAutoConfiguration::class.qualifiedName)
        val aop = imports.indexOf(LeaderAopAutoConfiguration::class.qualifiedName)

        (factory >= 0).shouldBeTrue()
        (policy > factory).shouldBeTrue()
        (micrometer > policy).shouldBeTrue()
        (observation > micrometer).shouldBeTrue()
        (aop > observation).shouldBeTrue()
    }

    @Test
    fun `AOP opt out leaves plain scheduler task but removes leader infrastructure`() {
        runner
            .withUserConfiguration(ScheduledFixtureConfiguration::class.java, ObservationConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.leader.aop.enabled=false",
                "spring.aop.auto=false",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.containsBean("scheduledFixture").shouldBeTrue()
                context.containsBean("localLeaderElectionFactory").shouldBeFalse()
                context.containsBean("leaderScheduledPolicyRegistry").shouldBeFalse()
                context.containsBean("leaderScheduledPolicyBeanPostProcessor").shouldBeFalse()
                context.getBeansOfType<ScheduledTaskHolder>().values
                    .flatMap { it.scheduledTasks }
                    .toSet()
                    .size shouldBeEqualTo 1
                val fixture = context.getBean("scheduledFixture", ScheduledFixture::class.java)
                fixture.reconcile()
                fixture.invocationCount() shouldBeEqualTo 1
                context.getBean(RecordingObservationHandler::class.java).stopped
                    .filter { it.name.startsWith("leader.aop.") }
                    .isEmpty()
                    .shouldBeTrue()
            }
    }

    private fun scheduledTaskCount(): Int = applicationContext.getBeansOfType<ScheduledTaskHolder>()
        .values
        .flatMap { it.scheduledTasks }
        .toSet()
        .size

    private fun contextBeanNames(): Set<String> = applicationContext.getBeanDefinitionNames().toSet()

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ObservationAutoConfiguration::class.java,
                LeaderAopFactoryAutoConfiguration::class.java,
                LeaderScheduledPolicyAutoConfiguration::class.java,
                LeaderMicrometerAutoConfiguration::class.java,
                LeaderObservationAutoConfiguration::class.java,
                LeaderAopAutoConfiguration::class.java,
            ),
        )

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    class ScheduledFixtureConfiguration {
        @Bean("scheduledFixture")
        fun scheduledFixture(): ScheduledFixture = ScheduledFixture()
    }

    open class ScheduledFixture {
        private val invocations = AtomicInteger()

        @Scheduled(fixedDelay = 50, initialDelay = 60_000)
        open fun reconcile() {
            invocations.incrementAndGet()
        }

        open fun invocationCount(): Int = invocations.get()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    class AnnotationPrecedenceFixtureConfiguration {
        @Bean("leaderAnnotationFixture")
        fun leaderAnnotationFixture(): AnnotationPrecedenceFixture = AnnotationPrecedenceFixture()
    }

    open class AnnotationPrecedenceFixture {
        @LeaderElection(name = "annotation-single", leaseTime = "30s")
        @Scheduled(fixedDelay = Long.MAX_VALUE, initialDelay = Long.MAX_VALUE)
        open fun single() = Unit

        @LeaderGroupElection(name = "annotation-group", maxLeaders = 2, leaseTime = "30s")
        @Scheduled(fixedDelay = Long.MAX_VALUE, initialDelay = Long.MAX_VALUE)
        open fun group() = Unit
    }

    open class OverloadedScheduledFixture {
        @Scheduled(fixedDelay = Long.MAX_VALUE, initialDelay = Long.MAX_VALUE)
        open fun reconcile() = Unit

        @Scheduled(fixedDelay = Long.MAX_VALUE, initialDelay = Long.MAX_VALUE)
        open fun reconcile(value: String) = value
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    class LeaderScheduledTriggerConfiguration {
        @Bean("leaderScheduledTriggerFixture")
        fun leaderScheduledTriggerFixture(): LeaderScheduledTriggerFixture = LeaderScheduledTriggerFixture()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    class LeaderScheduledFailureConfiguration {
        @Bean("leaderScheduledFailureFixture")
        fun leaderScheduledFailureFixture(): LeaderScheduledFailureFixture = LeaderScheduledFailureFixture()
    }

    open class LeaderScheduledTriggerFixture {
        private val started = CountDownLatch(1)
        private val invocations = AtomicInteger()

        @Scheduled(fixedDelay = 100, initialDelay = 0)
        open fun reconcile() {
            invocations.incrementAndGet()
            started.countDown()
        }

        open fun awaitStarted(timeout: Duration): Boolean =
            started.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

        open fun invocationCount(): Int = invocations.get()
    }

    open class LeaderScheduledFailureFixture {
        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
        open fun fail(): Unit = error("sensitive-customer-id")
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    class AnnotatedScheduledFixtureConfiguration {
        @Bean("annotatedScheduledFixture")
        fun annotatedScheduledFixture(): AnnotatedScheduledFixture = AnnotatedScheduledFixture()
    }

    open class AnnotatedScheduledFixture {
        @LeaderScheduled(
            name = "annotation-precedence",
            fixedDelay = 50,
            initialDelay = 60_000,
            leaseTime = "30s",
        )
        open fun reconcile() = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class ObservationConfiguration {
        @Bean
        @Primary
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.create()

        @Bean
        fun recordingObservationHandler(): RecordingObservationHandler = RecordingObservationHandler()
    }

    class RecordingObservationHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<ObservationSnapshot>()

        override fun onStop(context: Observation.Context) {
            stopped += ObservationSnapshot(
                name = context.name.orEmpty(),
                low = context.lowCardinalityKeyValues.associate { it.key to it.value },
                high = context.highCardinalityKeyValues.associate { it.key to it.value },
                error = context.error,
            )
        }

        override fun supportsContext(context: Observation.Context): Boolean = true

        fun clear() = stopped.clear()
    }

    data class ObservationSnapshot(
        val name: String,
        val low: Map<String, String>,
        val high: Map<String, String>,
        val error: Throwable?,
    )
}
