plugins {
    id("genesara.spring-module")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":world"))
    implementation(project(":player"))
    implementation(project(":account"))
    implementation(project(":admin"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
}

tasks.register<JavaExec>("exportMcpSchema") {
    group = "documentation"
    description = "Export the MCP tool catalog to schema/schema.json. Attached as a release " +
        "asset by .github/workflows/release-schema.yml; consumed by docs.genesara.com."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.gvart.genesara.api.internal.mcp.schema.McpSchemaExporterKt")
    val outputFile = rootProject.layout.projectDirectory.file("schema/schema.json")
    val projectVersion = project.version.toString()
    args(outputFile.asFile.absolutePath, projectVersion)
    outputs.file(outputFile)
}
