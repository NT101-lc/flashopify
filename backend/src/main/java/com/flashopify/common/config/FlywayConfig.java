package com.flashopify.common.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One Flyway instance per data source. Spring Boot's single-instance auto-configuration is turned
 * off ({@code spring.flyway.enabled=false}); both instances migrate on startup.
 */
@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

  public static final String MYSQL_LOCATION = "classpath:db/migration/mysql";
  public static final String POSTGRES_LOCATION = "classpath:db/migration/postgres";

  @Bean(initMethod = "migrate")
  public Flyway mysqlFlyway(DataSource dataSource) {
    return Flyway.configure().dataSource(dataSource).locations(MYSQL_LOCATION).load();
  }

  @Bean(initMethod = "migrate")
  public Flyway aiFlyway(@Qualifier(DataSourceConfig.AI_DATA_SOURCE) DataSource aiDataSource) {
    return Flyway.configure().dataSource(aiDataSource).locations(POSTGRES_LOCATION).load();
  }
}
