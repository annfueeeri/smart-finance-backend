package collector.bu;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class UserAuditMigrationTests {
    /** 验证已有表迁移保留主键和账号信息，补齐非空审计字段，并明确标记未知历史数据。 */
    @Test
    void migrationPreservesLegacyAccountsAndPrimaryKey() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:audit-migration;MODE=MySQL", "sa", "");
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL, "
                    + "enabled BOOLEAN NOT NULL DEFAULT TRUE, role VARCHAR(16) NOT NULL DEFAULT 'USER')");
            statement.execute("INSERT INTO app_user (id, username, password_hash, enabled, role) "
                    + "VALUES (42, 'legacy-admin', 'unchanged-hash', TRUE, 'ADMIN')");
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V3__add_user_audit_mysql.sql"));
            try (var rows = statement.executeQuery("SELECT * FROM app_user WHERE id = 42")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("username")).isEqualTo("legacy-admin");
                assertThat(rows.getString("password_hash")).isEqualTo("unchanged-hash");
                assertThat(rows.getString("role")).isEqualTo("ADMIN");
                assertThat(rows.getBoolean("enabled")).isTrue();
                assertThat(rows.getBoolean("is_deleted")).isFalse();
                assertThat(rows.getTimestamp("created_at")).isNotNull();
                assertThat(rows.getTimestamp("updated_at")).isNotNull();
                assertThat(rows.getString("created_by")).isEqualTo("LEGACY");
                assertThat(rows.getString("updated_by")).isEqualTo("LEGACY");
            }
            try (var keys = connection.getMetaData().getPrimaryKeys(null, "PUBLIC", "APP_USER")) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getString("COLUMN_NAME")).isEqualTo("ID");
                assertThat(keys.next()).isFalse();
            }
            statement.execute("INSERT INTO app_user (username, password_hash) VALUES ('new-user', 'new-hash')");
            try (var rows = statement.executeQuery("SELECT * FROM app_user WHERE username = 'new-user'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean("is_deleted")).isFalse();
                assertThat(rows.getTimestamp("created_at")).isNotNull();
                assertThat(rows.getTimestamp("updated_at")).isNotNull();
                assertThat(rows.getString("created_by")).isEqualTo("SYSTEM");
                assertThat(rows.getString("updated_by")).isEqualTo("SYSTEM");
            }
        }
    }
}
