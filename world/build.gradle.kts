plugins {
    id("genesara.spring-module")
    id("genesara.jooq-module")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":player"))
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("com.zaxxer:HikariCP")
}

jooqModule {
    migrationsSubdir.set("world")
    tableIncludes.set("worlds|regions|region_neighbors|nodes|node_adjacency|agent_positions|agent_bodies|starter_nodes|agent_inventory|non_renewable_resources|agent_node_memory|agent_equipment_instances")
}
