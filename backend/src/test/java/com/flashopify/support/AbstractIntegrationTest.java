package com.flashopify.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests. MySQL, PostgreSQL + pgvector and Redis start once per JVM and
 * are shared by every subclass (singleton container pattern), so the Spring context is cached.
 * Tests must therefore use unique data (e.g. random emails) instead of assuming empty tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("flashopify")
          .withUsername("flashopify")
          .withPassword("flashopify");

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("flashopify_ai");

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    MYSQL.start();
    POSTGRES.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    registry.add("app.datasource.mysql.url", () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC");
    registry.add("app.datasource.mysql.username", MYSQL::getUsername);
    registry.add("app.datasource.mysql.password", MYSQL::getPassword);
    registry.add("app.datasource.ai.url", POSTGRES::getJdbcUrl);
    registry.add("app.datasource.ai.username", POSTGRES::getUsername);
    registry.add("app.datasource.ai.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }
}
