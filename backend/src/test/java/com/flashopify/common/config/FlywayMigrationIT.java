package com.flashopify.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Boots the application against real MySQL and PostgreSQL + pgvector and checks both Flyways. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class FlywayMigrationIT {

  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("flashopify")
          .withUsername("flashopify")
          .withPassword("flashopify");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("flashopify_ai");

  @DynamicPropertySource
  static void dataSources(DynamicPropertyRegistry registry) {
    registry.add("app.datasource.mysql.url", MYSQL::getJdbcUrl);
    registry.add("app.datasource.mysql.username", MYSQL::getUsername);
    registry.add("app.datasource.mysql.password", MYSQL::getPassword);
    registry.add("app.datasource.ai.url", POSTGRES::getJdbcUrl);
    registry.add("app.datasource.ai.username", POSTGRES::getUsername);
    registry.add("app.datasource.ai.password", POSTGRES::getPassword);
  }

  @Autowired DataSource mysqlDataSource;

  @Autowired
  @Qualifier(DataSourceConfig.AI_DATA_SOURCE)
  DataSource aiDataSource;

  @Test
  void mysqlMigrationAppliedAndCreatesCoreTables() {
    JdbcTemplate jdbc = new JdbcTemplate(mysqlDataSource);

    assertThat(successfulVersions(jdbc)).containsExactly("1", "202610271200");
    List<String> tables =
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
            String.class);
    assertThat(tables)
        .contains(
            "users",
            "products",
            "inventory",
            "flash_sale_items",
            "orders",
            "payments",
            "outbox_events",
            "processed_events");
  }

  @Test
  void postgresMigrationAppliedWithPgvector() {
    JdbcTemplate jdbc = new JdbcTemplate(aiDataSource);

    assertThat(successfulVersions(jdbc)).containsExactly("1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_product_embeddings_hnsw'",
                Integer.class))
        .isEqualTo(1);
  }

  private static List<String> successfulVersions(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
        String.class);
  }
}
