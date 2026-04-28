plugins {
    id("agenticrpg.spring-module")
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
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    // Removed: org.springaicommunity:mcp-server-security-spring-boot — its OAuth2-style enforcement
    // collided with our simple BearerTokenAgentFilter (held SSE connections open without responding).
    // Our SecurityConfig already gates /sse, /mcp/**, /message** via BearerTokenAgentFilter.

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
}
