package dev.gvart.genesara.world.internal.testsupport

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Helpers for spinning up a Hikari-pooled DataSource against a Postgres Testcontainer
 * and running the world module's Flyway migrations against it. Each integration test
 * class manages its own [PostgreSQLContainer] (matching the pattern in
 * `RedisAgentEventLogIntegrationTest`); this file exists so the boilerplate doesn't
 * have to be duplicated.
 */
internal object WorldFlyway {

    /**
     * Build a pooled DataSource against the test container.
     *
     * `initializationFailTimeout = 30_000` lets Hikari retry connection establishment for up
     * to 30s. This papers over a known timing race on macOS + colima where the host-side
     * port forwarder isn't quite ready when Testcontainers reports the container as started.
     */
    fun pooledDataSource(container: PostgreSQLContainer<*>): HikariDataSource {
        val cfg = HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 4
            initializationFailTimeout = 30_000
            connectionTimeout = 30_000
        }
        return HikariDataSource(cfg)
    }

    fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/world")
            .load()
            .migrate()
    }
}
