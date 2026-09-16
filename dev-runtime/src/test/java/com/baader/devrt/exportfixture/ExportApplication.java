package com.baader.devrt.exportfixture;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.h2.jdbcx.JdbcDataSource;

@SpringBootConfiguration
public class ExportApplication {
    @Bean public javax.sql.DataSource dataSource(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:export-fixture;DB_CLOSE_DELAY=-1");return source;}
    @Bean public JdbcTemplate jdbc(javax.sql.DataSource source){var jdbc=new JdbcTemplate(source);jdbc.execute("create table if not exists entries (id integer)");return jdbc;}
    @Bean public DataSourceTransactionManager transactionManager(javax.sql.DataSource source){return new DataSourceTransactionManager(source);}
}
