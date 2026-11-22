package com.kunliang.citriage.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.sqlite.SQLiteConfig;

/** Opens connections and applies migration resources in the same transaction as an import. */
final class SqliteDatabase {
    private static final int APPLICATION_ID = 0x43495452;
    private static final List<String> MIGRATIONS = List.of("/db/migrations/V001__create_storage.sql");

    private final Path path;

    SqliteDatabase(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    <T> T transaction(boolean writable, Work<T> work) throws SQLException {
        if (!writable && Files.notExists(path)) {
            throw new SQLException("Database does not exist: " + path);
        }
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw new SQLException("Expected a regular database file: " + path);
        }

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setReadOnly(!writable);
        config.setBusyTimeout(5000);
        config.setTransactionMode(writable ? SQLiteConfig.TransactionMode.IMMEDIATE
                : SQLiteConfig.TransactionMode.DEFERRED);
        // The encoded absolute file URI handles spaces, Unicode, and URI delimiter characters in filenames.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString(),
                config.toProperties())) {
            connection.setAutoCommit(false);
            try {
                prepareSchema(connection, writable);
                T result = work.run(connection);
                // This commits and ends the transaction. SQLite JDBC's commit() immediately begins another one.
                connection.setAutoCommit(true);
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                    }
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        }
    }

    private static void prepareSchema(Connection connection, boolean writable) throws SQLException {
        int version = intPragma(connection, "PRAGMA user_version");
        if (version < 0 || version > MIGRATIONS.size()) {
            throw new SQLException("Unsupported database schema version " + version
                    + " (supported through " + MIGRATIONS.size() + ").");
        }
        int application = intPragma(connection, "PRAGMA application_id");
        if (version == 0 && application == 0 && isEmptyDatabase(connection)) {
            if (!writable) {
                throw new SQLException("Database has no CI Failure Triage schema. Save an import first.");
            }
            execute(connection, "PRAGMA application_id = " + APPLICATION_ID);
        } else if (application != APPLICATION_ID) {
            throw new SQLException("The database does not have a CI Failure Triage schema.");
        }

        if (!writable && version < MIGRATIONS.size()) {
            throw new SQLException("Database schema version " + version + " requires an import to migrate it.");
        }
        for (int i = version; i < MIGRATIONS.size(); i++) {
            applyMigration(connection, MIGRATIONS.get(i));
            execute(connection, "PRAGMA user_version = " + (i + 1));
        }
    }

    private static void applyMigration(Connection connection, String resource) throws SQLException {
        try (InputStream input = SqliteDatabase.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new SQLException("Missing database migration " + resource);
            }
            // Xerial executes the bundled SQL as a script, including statements that contain semicolons.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            throw new SQLException("Cannot read database migration " + resource, exception);
        }
    }

    private static boolean isEmptyDatabase(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM sqlite_schema LIMIT 1");
             ResultSet rows = statement.executeQuery()) {
            return !rows.next();
        }
    }

    private static int intPragma(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Missing SQLite schema information.");
            }
            return rows.getInt(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }
}
