package collector.bu;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class UserAuditMigrationTests {
    /** 验证资料迁移回填姓名与偏好，保留旧账号主键、权限及创建和修改审计信息。 */
    @Test
    void profileMigrationPreservesExistingAccountsAndAuditHistory() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:profile-migration;MODE=MySQL", "sa", "");
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL, "
                    + "role VARCHAR(16) NOT NULL, enabled BOOLEAN NOT NULL, is_deleted BOOLEAN NOT NULL, "
                    + "created_at TIMESTAMP(6) NOT NULL, created_by VARCHAR(64) NOT NULL, "
                    + "updated_at TIMESTAMP(6) NOT NULL ON UPDATE CURRENT_TIMESTAMP(6), updated_by VARCHAR(64) NOT NULL)");
            statement.execute("INSERT INTO app_user VALUES (42, 'legacy-user', 'unchanged-hash', 'ADMIN', TRUE, FALSE, "
                    + "'2020-01-01 00:00:00', 'original-creator', '2021-01-01 00:00:00', 'original-editor')");
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V4__add_user_profile_mysql.sql"));
            try (var rows = statement.executeQuery("SELECT * FROM app_user WHERE id = 42")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("display_name")).isEqualTo("legacy-user");
                assertThat(rows.getString("username")).isEqualTo("legacy-user");
                assertThat(rows.getString("password_hash")).isEqualTo("unchanged-hash");
                assertThat(rows.getString("role")).isEqualTo("ADMIN");
                assertThat(rows.getTimestamp("created_at").toLocalDateTime().toString()).isEqualTo("2020-01-01T00:00");
                assertThat(rows.getTimestamp("updated_at").toLocalDateTime().toString()).isEqualTo("2021-01-01T00:00");
                assertThat(rows.getString("created_by")).isEqualTo("original-creator");
                assertThat(rows.getString("updated_by")).isEqualTo("original-editor");
                assertThat(rows.getString("email")).isEmpty();
                assertThat(rows.getString("phone")).isEmpty();
                assertThat(rows.getString("currency")).isEqualTo("JPY");
                assertThat(rows.getString("timezone")).isEqualTo("Asia/Tokyo");
                assertThat(rows.getBigDecimal("monthly_budget")).isNull();
                assertThat(rows.getInt("budget_start_day")).isEqualTo(1);
            }
        }
    }

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
