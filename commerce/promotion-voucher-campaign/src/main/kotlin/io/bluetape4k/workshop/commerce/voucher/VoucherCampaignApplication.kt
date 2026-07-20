package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
internal class VoucherCampaignApplication {
    companion object : KLogging()
}

fun main(args: Array<String>) {
    if (VoucherCompatibilityCli.supports(args)) {
        VoucherCompatibilityCli.run(args)
        return
    }
    runApplication<VoucherCampaignApplication>(*args)
    VoucherCampaignApplication.log.info { "voucher_campaign_application_started" }
}
