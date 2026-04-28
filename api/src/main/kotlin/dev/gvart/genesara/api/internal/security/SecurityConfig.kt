package dev.gvart.genesara.api.internal.security

import dev.gvart.genesara.account.AccountAuthenticator
import dev.gvart.genesara.admin.AdminAuthenticator
import dev.gvart.genesara.admin.AdminTokenStore
import dev.gvart.genesara.player.AgentRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties::class)
internal class SecurityConfig(
    private val properties: SecurityProperties,
) {

    @Bean
    fun accountAuthenticationProvider(authenticator: AccountAuthenticator): AuthenticationProvider =
        AccountAuthenticationProvider(authenticator)

    @Bean
    fun adminAuthenticationProvider(authenticator: AdminAuthenticator): AdminAuthenticationProvider =
        AdminAuthenticationProvider(authenticator)

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val cfg = CorsConfiguration().apply {
            allowedOrigins = properties.cors.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept")
            allowCredentials = false
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", cfg)
        }
    }

    /** HTTP Basic on `POST /admin/login` exchanges credentials for a bearer token. */
    @Bean
    @Order(0)
    fun adminLoginChain(http: HttpSecurity, provider: AdminAuthenticationProvider): SecurityFilterChain {
        http
            .securityMatcher("/admin/login")
            .cors(Customizer.withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.anyRequest().authenticated()
            }
            .httpBasic(Customizer.withDefaults())
            .authenticationProvider(provider)
        return http.build()
    }

    /** Bearer-token-gated editor / admin endpoints. Requires ROLE_ADMIN. */
    @Bean
    @Order(1)
    fun adminBearerChain(http: HttpSecurity, tokens: AdminTokenStore): SecurityFilterChain {
        http
            .securityMatcher("/api/worlds/**", "/admin/**")
            .cors(Customizer.withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.anyRequest().hasRole("ADMIN")
            }
            .addFilterBefore(AdminBearerTokenFilter(tokens), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    @Order(2)
    fun mcpSecurityChain(http: HttpSecurity, registry: AgentRegistry): SecurityFilterChain {
        http
            .securityMatcher("/sse", "/sse/**", "/mcp/**", "/message", "/message/**", "/api/agent/**")
            .cors(Customizer.withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(BearerTokenAgentFilter(registry), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    @Order(3)
    fun restSecurityChain(
        http: HttpSecurity,
        @Qualifier("accountAuthenticationProvider") provider: AuthenticationProvider,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/**")
            .cors(Customizer.withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/players").permitAll()
                it.anyRequest().authenticated()
            }
            .httpBasic(Customizer.withDefaults())
            .authenticationProvider(provider)
        return http.build()
    }
}
