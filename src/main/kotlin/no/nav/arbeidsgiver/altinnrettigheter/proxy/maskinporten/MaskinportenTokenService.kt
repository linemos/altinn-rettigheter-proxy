package no.nav.arbeidsgiver.altinnrettigheter.proxy.maskinporten

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

@Component
class MaskinportenTokenService(
    private val maskinportenClient: MaskinportenClient,
    private val meterRegistry: MeterRegistry,
):
    HealthIndicator,
    InitializingBean
{
    private val logger = LoggerFactory.getLogger(javaClass)
    private val tokenStore = AtomicReference<TokenResponseWrapper?>()

    fun currentAccessToken(): String {
        val storedToken = tokenStore.get()
        val token = if (storedToken != null && storedToken.percentageRemaining() > 20.0) {
            storedToken
        } else {
            logger.error("maskinporten access token almost expired. is refresh loop running? doing emergency fetch.")
            /* this shouldn't happen, as refresh loop above refreshes often */
            maskinportenClient.fetchNewAccessToken().also {
                tokenStore.set(it)
            }
        }

        return token.tokenResponse.accessToken
    }

    override fun afterPropertiesSet() {
        meterRegistry.gauge(
            "maskinporten.token.expiry.seconds", tokenStore
        ) {
            it.get()?.expiresIn()?.seconds?.toDouble() ?: Double.NaN
        }

        Thread {
            while (true) {
                try {
                    logger.info("sjekker om accesstoken er i ferd med å utløpe..")
                    val token = tokenStore.get()
                    if (token == null || token.percentageRemaining() < 50.0) {
                        val newToken = maskinportenClient.fetchNewAccessToken()
                        tokenStore.set(newToken)
                    }
                } catch (e: Exception) {
                    logger.error("refreshing maskinporten token failed with exception {}.", e.message, e)
                }
                Thread.sleep(Duration.ofSeconds(30).toMillis())
            }
        }.start()
    }


    override fun health(): Health =
        when (val token = tokenStore.get()) {
            null -> {
                if (uptime() > Duration.ofMinutes(5))
                    Health.down().withDetail("reason", "no token fetched since start up").build()
                else
                    healthy
            }
            else -> {
                if (token.percentageRemaining() < 20)
                    Health.down().withDetail("reason", "token about to expire").build()
                else
                    healthy
            }
        }

    private fun uptime(): Duration =
        Duration.ofMillis(
            ManagementFactory.getRuntimeMXBean().uptime
        )

    companion object {
        private val healthy = Health.up().build()
    }
}