package collector.bu;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import static org.assertj.core.api.Assertions.*;

class FamilyMigrationTests {
    /** 在兼容数据库上迁移真实旧账户和流水，验证金额、个人归属和审计保留及跨账本外键。 */
    @Test void migrationPreservesLegacyPersonalDataAndEnforcesBookOwnership() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:family-migration-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");var jdbc=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("src/main/resources/db/schema-users.sql"));
            for(var script:new String[]{"V5__create_ledger_mysql.sql","V6__create_budgets_mysql.sql","V7__add_financial_reports_mysql.sql"}) {
                // H2 不支持一个 ALTER 中添加多个列，拆分旧 V7 的语法，保留原有迁移内容。
                var legacy=Files.readString(Path.of("src/main/resources/db/migration/"+script)).replace(",\n    ADD COLUMN",";\nALTER TABLE ledger_account ADD COLUMN");
                ScriptUtils.executeSqlScript(connection,new ByteArrayResource(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            jdbc.update("INSERT INTO app_user(id,username,display_name,password_hash,created_by,updated_by) VALUES(101,'legacy-family','Legacy','hash','LEGACY','LEGACY')");
            jdbc.update("INSERT INTO ledger_account(id,user_id,name,currency,created_by,updated_by) VALUES(201,101,'Original personal','JPY','legacy-family','legacy-family')");
            jdbc.update("INSERT INTO ledger_transaction(user_id,account_id,kind,amount,currency,transaction_date,category,created_by,updated_by) VALUES(101,201,'EXPENSE',123,'JPY','2026-10-01','FOOD','legacy-family','legacy-family')");
            var migration=Files.readString(Path.of("src/main/resources/db/migration/V8__add_family_books_mysql.sql")).replace("DROP FOREIGN KEY","DROP CONSTRAINT");
            ScriptUtils.executeSqlScript(connection,new ByteArrayResource(migration.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(jdbc.queryForObject("SELECT book_id FROM ledger_transaction",Long.class)).isEqualTo(101);
            assertThat(jdbc.queryForObject("SELECT amount FROM ledger_transaction",java.math.BigDecimal.class)).isEqualByComparingTo("123");
            assertThat(jdbc.queryForObject("SELECT created_by FROM ledger_transaction",String.class)).isEqualTo("legacy-family");
            assertThat(jdbc.queryForObject("SELECT owner_user_id FROM ledger_book WHERE id=101",Long.class)).isEqualTo(101);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM family_member",Integer.class)).isZero();
            assertThatThrownBy(()->jdbc.update("INSERT INTO ledger_account(book_id,name,currency,created_by,updated_by) VALUES(-999,'Invalid','JPY','X','X')")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
