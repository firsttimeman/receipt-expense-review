package com.example.receipt;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class EmployeeMigrationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test void upgradesPopulatedV4WithoutGuessingOwnersAndEnforcesAccountConstraints() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("4").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var sql = connection.createStatement()) {
            sql.executeUpdate("""
                INSERT INTO receipts (company_id,image_sha256,file_size,status,rule_results,created_at,updated_at)
                VALUES ('historical-company',REPEAT('a',64),123,'NEEDS_REVIEW',JSON_ARRAY(),NOW(),NOW())
                """);
            sql.executeUpdate("""
                INSERT INTO idempotency_records (company_id,idempotency_key,receipt_id,created_at)
                VALUES ('historical-company','historical-key',1,NOW())
                """);
            sql.executeUpdate("""
                INSERT INTO audit_events (receipt_id,occurred_at,actor,action,details)
                VALUES (1,NOW(),'historical-reviewer','UPLOADED',JSON_OBJECT())
                """);
            sql.executeUpdate("""
                INSERT INTO receipt_extraction_jobs (receipt_id,status,image_storage_key,available_at,created_at,updated_at)
                VALUES (1,'QUEUED','historical-image',NOW(),NOW(),NOW())
                """);
        }
        var flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT owner_employee_id,company_id FROM receipts WHERE id=1")) {
                assertThat(result.next()).isTrue(); assertThat(result.getObject(1)).isNull();
                assertThat(result.getString(2)).isEqualTo("historical-company");
            }
            for (String table : new String[]{"audit_events","receipt_extraction_jobs","idempotency_records"}) {
                try (var result = sql.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE receipt_id=1")) {
                    result.next(); assertThat(result.getInt(1)).isOne();
                }
            }
            sql.executeUpdate("INSERT INTO employees(login_id,name,role,active) VALUES ('migration-user','test','EMPLOYEE',true)");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO employees(login_id,name,role,active) VALUES ('migration-user','duplicate','ADMIN',true)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE receipts SET owner_employee_id=999999 WHERE id=1"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO employees(login_id,name,role,active) VALUES ('invalid-role','test','ROOT',true)"))
                    .isInstanceOf(SQLException.class);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }
}
