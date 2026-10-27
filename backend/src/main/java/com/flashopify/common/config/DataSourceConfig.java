package com.flashopify.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two data sources: MySQL is the primary store (MyBatis, transactions) and PostgreSQL + pgvector
 * belongs to the {@code ai} module only.
 *
 * <p>Inject the AI one explicitly with {@code @Qualifier("aiDataSource")}; everything else gets
 * MySQL by default.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

  public static final String AI_DATA_SOURCE = "aiDataSource";

  @Bean
  @Primary
  @ConfigurationProperties("app.datasource.mysql")
  public DataSourceProperties mysqlDataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean
  @Primary
  @ConfigurationProperties("app.datasource.mysql.hikari")
  public HikariDataSource dataSource(
      @Qualifier("mysqlDataSourceProperties") DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean
  @ConfigurationProperties("app.datasource.ai")
  public DataSourceProperties aiDataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean(AI_DATA_SOURCE)
  @ConfigurationProperties("app.datasource.ai.hikari")
  public HikariDataSource aiDataSource(
      @Qualifier("aiDataSourceProperties") DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }
}
