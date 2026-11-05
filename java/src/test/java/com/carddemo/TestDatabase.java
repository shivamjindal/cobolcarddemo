package com.carddemo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Spring tests run on database {@code carddemo_test} ({@code CARDDEMO_TEST_DB} overrides it), created on
 * first use. VsamLoadIT cleans and edits its database, and {@code carddemo} is the dev data the running app serves.
 */
public final class TestDatabase {

    private static final String SERVER = "jdbc:postgresql://localhost:5432/";
    private static final String NAME = env("CARDDEMO_TEST_DB", "carddemo_test");

    private TestDatabase() {}

    public static void use(DynamicPropertyRegistry properties) {
        try (Connection admin = DriverManager.getConnection(SERVER + "postgres", env("CARDDEMO_JDBC_USER", "carddemo"),
                        env("CARDDEMO_JDBC_PASSWORD", "carddemo"));
                Statement sql = admin.createStatement();
                ResultSet found = sql.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + NAME + "'")) {
            if (!found.next()) {
                sql.execute("CREATE DATABASE " + NAME);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create database " + NAME, e);
        }
        properties.add("spring.datasource.url", () -> SERVER + NAME);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
