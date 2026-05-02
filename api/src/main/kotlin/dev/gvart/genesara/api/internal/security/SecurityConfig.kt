package dev.gvart.genesara.api.internal.security

import dev.gvart.genesara.account.PlayerLookup
import dev.gvart.genesara.admin.AdminAuthenticator
import dev.gvart.genesara.admin.AdminTokenStore
import dev.gvart.genesara.api.internal.security.jwt.JwtDecoderFilter
import dev.gvart.genesara.api.internal.security.jwt.JwtIssuer
import dev.gvart.genesara.api.internal.security.jwt.JwtProperties
import dev.gvart.genesara.player.AgentRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
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
@EnableConfigurationProperties(SecurityProperties::class, JwtProperties::class)
internal class SecurityConfig(
    private val properties: SecurityProperties,
) {

    @Bean
    fun adminAuthenticationProvider(authenticator: AdminAuthenticator): AdminAuthenticationProvider =
        AdminAuthenticationProvider(authenticator)

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val cfg = CorsConfiguration().apply {
            allowedOrigins = properties.cors.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "X-Agent-Id")
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
    fun mcpSecurityChain(
        http: HttpSecurity,
        players: PlayerLookup,
        agents: AgentRegistry,
    ): SecurityFilterChain {
        http
            .securityMatcher("/sse", "/sse/**", "/mcp/**", "/message", "/message/**", "/api/agent/**")
            .cors(Customizer.withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(
                PlayerApiTokenAgentFilter(players, agents),
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }

    @Bean
    @Order(3)
    fun playerJwtChain(
        http: HttpSecurity,
        jwtIssuer: JwtIssuer,
        players: PlayerLookup,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/agents/**", "/api/agents", "/api/me/**")
            .cors(Customizer.withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.anyRequest().hasRole("PLAYER")
            }
            .addFilterBefore(
                JwtDecoderFilter(jwtIssuer, players),
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }

    @Bean
    @Order(4)
    fun publicRestChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/players", "/api/players/login")
            .cors(Customizer.withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/players").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/players/login").permitAll()
                it.anyRequest().denyAll()
            }
        return http.build()
    }
}
