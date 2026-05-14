package com.gridstore.huevista.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Bean
    public OpenAPI hueVistaOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("HueVista API")
                        .description("""
                                **AI-Powered Paint Shade Visualizer — B2B Platform for the Indian Paint Retail Market**

                                ## Authentication
                                1. Register at `POST /api/auth/register` or login at `POST /api/auth/login`
                                2. Copy the `accessToken` from the response
                                3. Click **Authorize** above and enter: `<your-token>`

                                ## Key flows
                                - **Upload image** → `POST /api/images/upload`
                                - **Create project** → `POST /api/projects`
                                - **Run segmentation** → `POST /api/projects/{id}/segment`
                                - **Poll status** → `GET /api/projects/{id}/status`
                                - **Auto-save colors** → `PUT /api/projects/{id}/regions`
                                - **Browse paint catalog** → `GET /api/shades?brand=asian-paints`
                                - **Seed catalog** → `POST /api/admin/paint/seed/asian-paints`
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("HueVista")
                                .url("https://huevista.com")))
                .servers(List.of(new Server().url(baseUrl).description("Current server")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer JWT"))
                .components(new Components()
                        .addSecuritySchemes("Bearer JWT", new SecurityScheme()
                                .name("Bearer JWT")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the accessToken returned by /api/auth/login or /api/auth/register")));
    }
}
