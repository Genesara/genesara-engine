package dev.gvart.genesara.api.internal.security.jwt

import dev.gvart.genesara.account.PlayerId
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Component
internal class JwtIssuer(
    private val properties: JwtProperties,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val key: SecretKey

    init {
        val bytes = properties.secret.toByteArray(Charsets.UTF_8)
        require(bytes.size >= MIN_SECRET_BYTES) {
            "application.security.jwt.secret must be >= $MIN_SECRET_BYTES bytes for HS256, got ${bytes.size}"
        }
        key = SecretKeySpec(bytes, "HmacSHA256")
    }

    fun issue(playerId: PlayerId): String {
        val now = Instant.now(clock)
        return Jwts.builder()
            .subject(playerId.id.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(properties.ttl)))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    fun parseSubject(token: String): PlayerId {
        val claims = Jwts.parser()
            .verifyWith(key)
            .clock { Date.from(Instant.now(clock)) }
            .build()
            .parseSignedClaims(token)
            .payload
        return PlayerId(java.util.UUID.fromString(claims.subject))
    }

    private companion object {
        const val MIN_SECRET_BYTES = 32
    }
}
