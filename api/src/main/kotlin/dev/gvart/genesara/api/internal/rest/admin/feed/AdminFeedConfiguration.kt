package dev.gvart.genesara.api.internal.rest.admin.feed

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(AdminFeedProperties::class)
internal class AdminFeedConfiguration
