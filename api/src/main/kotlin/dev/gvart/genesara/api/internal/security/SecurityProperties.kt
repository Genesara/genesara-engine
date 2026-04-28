package dev.gvart.genesara.api.internal.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "application.security")
data class SecurityProperties(
    val cors: CorsConfig
) {
    data class CorsConfig(
        val allowedOrigins: List<String>,
    )
}