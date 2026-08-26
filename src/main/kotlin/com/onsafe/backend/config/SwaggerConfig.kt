package com.onsafe.backend.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun openAPI(): OpenAPI {
        val jwtSchemeName = "BearerAuth"

        return OpenAPI()
            .info(
                Info()
                    .title("On-Safe API")
                    .description("AI 기반 노인 낙상 감지 솔루션 API 명세서")
                    .version("v1.0.0")
            )
            // 전역 JWT 보안 요구사항 적용
            .addSecurityItem(SecurityRequirement().addList(jwtSchemeName))
            .components(
                Components().addSecuritySchemes(
                    jwtSchemeName,
                    SecurityScheme()
                        .name(jwtSchemeName)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                )
            )
    }
}
