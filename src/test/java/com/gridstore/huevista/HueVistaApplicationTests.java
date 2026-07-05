package com.gridstore.huevista;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    // Migrations are PostgreSQL SQL; on H2 let Hibernate create the schema.
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.security.oauth2.client.registration.google.client-id=test-client-id",
    "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
    "app.jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLW9ubHk=",
    "app.claude.api-key=test-claude-api-key",
    "app.upload.storage-path=/tmp/huevista/test-uploads",
    "app.cors.allowed-origins=http://localhost:3000",
})
class HueVistaApplicationTests {

    @Test
    void contextLoads() {
    }

}
