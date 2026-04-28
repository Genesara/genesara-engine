plugins {
    id("genesara.spring-module")
    id("genesara.jooq-module")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":account"))
    implementation("org.springframework.boot:spring-boot-starter-jooq")
}

jooqModule {
    migrationsSubdir.set("player")
    tableIncludes.set("agents|agent_profiles")
}
