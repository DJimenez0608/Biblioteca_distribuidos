package org.example.db;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

public class DatabaseConfig {
    private final Properties properties = new Properties();

    public DatabaseConfig() {
        try (InputStream inputStream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("database.properties"),
                "No se encontró el archivo database.properties")) {
            properties.load(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron cargar las propiedades de la base de datos", e);
        }
    }

    public DatabaseEndpoint getPrimaryEndpoint() {
        return new DatabaseEndpoint(
                properties.getProperty("db.primary.name", "library"),
                properties.getProperty("db.primary.url"),
                properties.getProperty("db.primary.user"),
                properties.getProperty("db.primary.password")
        );
    }

    public DatabaseEndpoint getSecondaryEndpoint() {
        return new DatabaseEndpoint(
                properties.getProperty("db.secondary.name", "librarybackup"),
                properties.getProperty("db.secondary.url"),
                properties.getProperty("db.secondary.user"),
                properties.getProperty("db.secondary.password")
        );
    }

    public long getReplicationPollInterval() {
        return Long.parseLong(properties.getProperty("db.replication.pollIntervalMs", "2000"));
    }

    public int getReplicationBatchSize() {
        return Integer.parseInt(properties.getProperty("db.replication.batchSize", "25"));
    }

    public int getMaxConnections() {
        return Integer.parseInt(properties.getProperty("db.pool.maxConnections", "5"));
    }
}

