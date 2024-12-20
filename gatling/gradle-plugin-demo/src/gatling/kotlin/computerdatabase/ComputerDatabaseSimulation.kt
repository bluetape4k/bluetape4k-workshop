package computerdatabase

import io.gatling.javaapi.core.CoreDsl.css
import io.gatling.javaapi.core.CoreDsl.csv
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.feed
import io.gatling.javaapi.core.CoreDsl.pause
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.repeat
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.CoreDsl.tryMax
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.util.concurrent.ThreadLocalRandom

/**
 * This sample is based on our official tutorials:
 *
 * - [Gatling quickstart tutorial](https://gatling.io/docs/gatling/tutorials/quickstart)
 * - [Gatling advanced tutorial](https://gatling.io/docs/gatling/tutorials/advanced)
 */
class ComputerDatabaseSimulation: Simulation() {

    val feeder = csv("search.csv").random()

    val search = exec(
        http("Home").get("/"),
        pause(1),
        feed(feeder),
        http("Search")
            .get("/computers?f=#{searchCriterion}")
            .check(
                css("a:contains('#{searchComputerName}')", "href").saveAs("computerUrl")
            ),
        pause(1),
        http("Select")
            .get("#{computerUrl}")
            .check(status().shouldBe(200)),
        pause(1)
    )

    // repeat is a loop resolved at RUNTIME
    // Note how we force the counter name, so we can reuse it
    val browse = repeat(4, "i")
        .on(
            http("Page #{i}").get("/computers?p=#{i}"),
            pause(1)
        )

    // Note we should be using a feeder here
    // let's demonstrate how we can retry: let's make the request fail randomly and retry a given
    // number of times

    val edit =
        // let's try at max 2 times
        tryMax(2).on(
            http("Form").get("/computers/new"),
            pause(1),
            http("Post")
                .post("/computers")
                .formParam("name", "Beautiful Computer")
                .formParam("introduced", "2012-05-30")
                .formParam("discontinued", "")
                .formParam("company", "37")
                .check(
                    status().shouldBe { session ->
                        // we do a check on a condition that's been customized with
                        // a lambda. It will be evaluated every time a user executes
                        // the request
                        200 + ThreadLocalRandom.current().nextInt(2)
                    }
                )
        )
            // if the chain didn't finally succeed, have the user exit the whole scenario
            .exitHereIfFailed()

    val httpProtocol =
        http.baseUrl("https://computer-database.gatling.io")
            .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .acceptLanguageHeader("en-US,en;q=0.5")
            .acceptEncodingHeader("gzip, deflate")
            .userAgentHeader(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0"
            )

    val users = scenario("Users").exec(search, browse)
    val admins = scenario("Admins").exec(search, browse, edit)

    init {
        setUp(
            users.injectOpen(rampUsers(10).during(10)),
            admins.injectOpen(rampUsers(2).during(10))
        ).protocols(httpProtocol)
    }
}
