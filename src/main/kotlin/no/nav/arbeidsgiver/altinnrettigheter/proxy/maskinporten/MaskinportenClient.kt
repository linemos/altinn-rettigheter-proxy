package no.nav.arbeidsgiver.altinnrettigheter.proxy.maskinporten

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.micrometer.core.instrument.MeterRegistry
import no.nav.arbeidsgiver.altinnrettigheter.proxy.basedOnEnv
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicReference

interface MaskinportenClient {
    fun fetchAccessToken(): String
}
@Component
@Profile("dev", "prod")
class MaskinportenClientImpl(
    val config: MaskinportenConfig,
    val meterRegistry: MeterRegistry,
    restTemplateBuilder: RestTemplateBuilder,
): MaskinportenClient, InitializingBean, HealthIndicator {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val restTemplate = restTemplateBuilder.build()
    private lateinit var wellKnownResponse: WellKnownResponse
    private val tokenStore = AtomicReference<TokenResponseWrapper?>()

    override fun afterPropertiesSet() {
        wellKnownResponse = restTemplate.getForObject(config.wellKnownUrl, WellKnownResponse::class.java)!!
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
                    if (token == null || token.expiresIn() < Duration.ofMinutes(10)) {
                        val newToken = fetchNewAccessToken()
                        tokenStore.set(newToken)
                    }
                } catch (e: Exception) {
                    logger.error("refreshing maskinporten token failed with exception {}.", e.message, e)
                }
                Thread.sleep(Duration.ofSeconds(30).toMillis())
            }
        }.start()
    }


    override fun health(): Health {
        val token = tokenStore.get()
        if (token == null && uptime() > Duration.ofMillis(5)) {
            return Health.down().withDetail("reason", "no token fetched since start up").build()
        }

        if (token != null && token.expiresIn() < Duration.ofMinutes(5)) {
            return Health.down().withDetail("reason", "token about to expire").build()
        }

        return healthy
    }

    private fun createClientAssertion(): String {
        val now = Instant.now()
        val expire = now + Duration.ofSeconds(120)

        val claimsSet: JWTClaimsSet = JWTClaimsSet.Builder()
            .audience(wellKnownResponse.issuer)
            .issuer(config.clientId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expire))
            .notBeforeTime(Date.from(now))
            .claim("scope", "altinn:serviceowner/reportees")
            .claim("resource", basedOnEnv(prod = {"https://www.altinn.no/"}, other = {"https://tt02.altinn.no/"}))
            .jwtID(UUID.randomUUID().toString())
            .build()

        val signedJWT = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(config.privateJwkRsa.keyID)
                .build(),
            claimsSet
        )
        signedJWT.sign(config.jwsSigner)
        return signedJWT.serialize()
    }

    private fun fetchNewAccessToken(): TokenResponseWrapper {
        logger.info("henter ny accesstoken")
        val requestedAt = Instant.now()

        val tokenResponse = restTemplate.exchange(RequestEntity
            .method(HttpMethod.POST, wellKnownResponse.tokenEndpoint)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(LinkedMultiValueMap(mapOf(
                "grant_type" to listOf("urn:ietf:params:oauth:grant-type:jwt-bearer"),
                "assertion" to listOf(createClientAssertion())
            ))),
            TokenResponse::class.java
        ).body!!

        logger.info("Fetched new access token. Expires in {} seconds.", tokenResponse.expiresInSeconds)

        return TokenResponseWrapper(
            requestedAt = requestedAt,
            tokenResponse = tokenResponse,
        )
    }


    private fun fetchAccessTokenCached(): TokenResponse {
        val value = tokenStore.get()
        return if (value != null && value.expiresIn() >= Duration.ofSeconds(40)) {
            value.tokenResponse
        } else {
            logger.error("maskinporten access token almost expired. is refresh loop running? doing emergency fetch.")
            /* this shouldn't happen, as refresh loop above refreshes often */
            fetchNewAccessToken().also {
                tokenStore.set(it)
            }.tokenResponse
        }
    }

    override fun fetchAccessToken(): String {
        return fetchAccessTokenCached().accessToken
    }

    private fun uptime(): Duration =
        Duration.ofMillis(
            ManagementFactory.getRuntimeMXBean().uptime
        )

    companion object {
        private val healthy = Health.up().build()
    }
}


@Component
@Profile("local", "test")
class MaskinportenClientStub: MaskinportenClient {
    override fun fetchAccessToken(): String {
        return "stub-access-token"
    }
}
