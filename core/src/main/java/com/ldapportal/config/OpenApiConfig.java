// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes Swagger UI at {@code /swagger-ui.html} and the raw OpenAPI spec at
 * {@code /v3/api-docs}.
 *
 * <p>Two authentication schemes are documented:
 * <ul>
 *   <li><b>cookieAuth</b> — httpOnly JWT cookie ({@code jwt-token}); used by the
 *       browser-based frontend.</li>
 *   <li><b>bearerAuth</b> — {@code Authorization: Bearer <token>}; used by API
 *       clients and automated tests.</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LDAP Admin Portal API")
                        .version("v1")
                        .description("REST API for LDAP/Active Directory administration"))
                .addSecurityItem(new SecurityRequirement()
                        .addList("cookieAuth")
                        .addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("cookieAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name("jwt-token")
                                        .description("httpOnly JWT cookie set on login"))
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Authorization: Bearer <token>")));
    }
}
