package com.aiterminal.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@Slf4j
@Configuration
public class PostgresBootstrapConfig {
    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @Bean
    @Primary
    public DataSource dataSource() {
        ensureDatabaseExists(datasourceUrl, datasourceUsername, datasourcePassword);
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(datasourceUrl);
        dataSource.setUsername(datasourceUsername);
        dataSource.setPassword(datasourcePassword);
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        dataSource.setPoolName("ai-terminal-worklog-pool");
        return dataSource;
    }

    private void ensureDatabaseExists(String jdbcUrl, String username, String password) {
        String databaseName = extractDatabaseName(jdbcUrl);
        String adminUrl = buildAdminUrl(jdbcUrl);
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password)) {
            boolean exists;
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
                statement.setString(1, databaseName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    exists = resultSet.next();
                }
            }
            if (!exists) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE DATABASE \"" + escapeIdentifier(databaseName) + "\"");
                }
                log.info("Created PostgreSQL database: {}", databaseName);
            } else {
                log.info("PostgreSQL database already exists: {}", databaseName);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize PostgreSQL database from URL: " + jdbcUrl, e);
        }
    }

    private String extractDatabaseName(String jdbcUrl) {
        String normalized = jdbcUrl.replace("jdbc:", "");
        try {
            URI uri = new URI(normalized);
            String path = uri.getPath();
            if (path == null || path.length() <= 1) {
                throw new IllegalArgumentException("Invalid PostgreSQL JDBC URL, missing database name: " + jdbcUrl);
            }
            return path.substring(1);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid PostgreSQL JDBC URL: " + jdbcUrl, e);
        }
    }

    private String buildAdminUrl(String jdbcUrl) {
        String normalized = jdbcUrl.replace("jdbc:", "");
        try {
            URI uri = new URI(normalized);
            String query = uri.getQuery();
            StringBuilder builder = new StringBuilder("jdbc:postgresql://")
                    .append(uri.getHost())
                    .append(":")
                    .append(uri.getPort() > 0 ? uri.getPort() : 5432)
                    .append("/postgres");
            if (query != null && !query.isBlank()) {
                builder.append("?").append(query);
            }
            return builder.toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid PostgreSQL JDBC URL: " + jdbcUrl, e);
        }
    }

    private String escapeIdentifier(String identifier) {
        return identifier.replace("\"", "\"\"");
    }
}
