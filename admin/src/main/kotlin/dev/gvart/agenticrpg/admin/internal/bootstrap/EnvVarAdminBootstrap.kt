package dev.gvart.agenticrpg.admin.internal.bootstrap

import dev.gvart.agenticrpg.admin.AdminLookup
import dev.gvart.agenticrpg.admin.AdminRegistrar
import dev.gvart.agenticrpg.admin.AdminUsernameAlreadyExists
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
internal class EnvVarAdminBootstrap(
    private val lookup: AdminLookup,
    private val registrar: AdminRegistrar,
    @Value("\${admin.bootstrap.username:}") private val bootstrapUsername: String,
    @Value("\${admin.bootstrap.password:}") private val bootstrapPassword: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun seedIfMissing() {
        if (lookup.count() > 0) return
        if (bootstrapUsername.isBlank() || bootstrapPassword.isBlank()) {
            log.warn(
                "no admins exist and ADMIN_BOOTSTRAP_USERNAME / ADMIN_BOOTSTRAP_PASSWORD are not set — " +
                    "the editor and admin endpoints will be unreachable",
            )
            return
        }
        try {
            registrar.register(bootstrapUsername, bootstrapPassword)
            log.info("seeded bootstrap admin '{}'", bootstrapUsername)
        } catch (_: AdminUsernameAlreadyExists) {
            // race with another instance; safe to ignore
        }
    }
}
