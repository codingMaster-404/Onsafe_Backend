package com.onsafe.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient

@Configuration
class SesConfig(
    @Value("\${aws.ses.region}") private val region: String
) {
    @Bean
    fun sesV2AsyncClient(): SesV2AsyncClient =
        SesV2AsyncClient.builder()
            .region(Region.of(region))
            .build()
}
