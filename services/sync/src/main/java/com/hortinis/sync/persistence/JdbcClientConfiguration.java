package com.hortinis.sync.persistence;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
@ConditionalOnProperty(
    prefix = "hortinis.sync.persistence",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class JdbcClientConfiguration {

  @Bean
  @Lazy
  JdbcClient jdbcClient(DataSource dataSource) {
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setExceptionTranslator(new PostgreSqlExceptionTranslator());
    return JdbcClient.create(template);
  }
}
