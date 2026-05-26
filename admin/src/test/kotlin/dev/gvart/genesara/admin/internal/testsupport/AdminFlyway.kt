package dev.gvart.genesara.admin.internal.testsupport

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

internal object AdminFlyway {

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
            .locations("classpath:db/migration/admin")
            .load()
            .migrate()
    }
}
