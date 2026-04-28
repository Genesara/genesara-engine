package dev.gvart.genesara.admin.internal

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration
internal class AdminConfiguration {

    /**
     * Named bean so it doesn't collide with :account's `passwordEncoder` when both modules are
     * loaded into the same Spring context. [JooqAdminStore] injects this one explicitly via
     * @Qualifier so the wiring is deterministic regardless of module load order.
     */
    @Bean(ADMIN_PASSWORD_ENCODER)
    fun adminPasswordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    companion object {
        const val ADMIN_PASSWORD_ENCODER = "adminPasswordEncoder"
    }
}
