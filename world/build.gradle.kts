plugins {
    id("genesara.spring-module")
    id("genesara.jooq-module")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":player"))
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("tools.jackson.module:jackson-module-kotlin")

}

jooqModule {
    migrationsSubdir.set("world")
    tableIncludes.set("worlds|regions|region_neighbors|nodes|node_adjacency|agent_positions|agent_bodies")
}
