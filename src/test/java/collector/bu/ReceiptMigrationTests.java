package collector.bu;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import static org.assertj.core.api.Assertions.*;

class ReceiptMigrationTests {
    /** 在V8结构上执行真实V9，验证旧流水不变、BLOB保存与跨账本复合外键。 */
    @Test void v9PreservesExistingTransactionsAndEnforcesAttachmentOwnership() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:receipt-migration-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");var jdbc=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()) {
            for(var name:new String[]{"schema-users.sql","schema-family.sql","schema-ledger.sql"}) {
                var sql=Files.readString(Path.of("src/main/resources/db/"+name)).replace("    CONSTRAINT uq_ledger_transaction_owner UNIQUE (id,book_id),\n","");
                ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            for(long id:new long[]{1,2}) { jdbc.update("INSERT INTO app_user(id,username,display_name,password_hash) VALUES(?,?,?,'hash')",id,"legacy-"+id,"Legacy");jdbc.update("INSERT INTO ledger_book(id,owner_user_id,currency,timezone,created_by,updated_by) VALUES(?,?,'JPY','Asia/Tokyo','LEGACY','LEGACY')",id,id); }
            jdbc.update("INSERT INTO ledger_account(id,book_id,name,currency,created_by,updated_by) VALUES(1,1,'Old cash','JPY','LEGACY','LEGACY')");
            jdbc.update("INSERT INTO ledger_transaction(id,book_id,account_id,kind,amount,currency,transaction_date,category,created_by,updated_by) VALUES(1,1,1,'EXPENSE',123,'JPY','2026-10-01','FOOD','LEGACY','LEGACY')");
            ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.FileSystemResource("src/main/resources/db/migration/V9__add_transaction_receipts_mysql.sql"));
            assertThat(jdbc.queryForObject("SELECT amount FROM ledger_transaction WHERE id=1",java.math.BigDecimal.class)).isEqualByComparingTo("123");
            String insert="INSERT INTO transaction_receipt(book_id,transaction_id,file_name,content_type,file_size,file_sha256,file_content,created_by,updated_by) VALUES(?,1,'test.png','image/png',3,?,?,'LEGACY','LEGACY')";
            byte[] bytes={1,2,3};jdbc.update(insert,1,"a".repeat(64),bytes);assertThat(jdbc.queryForObject("SELECT file_content FROM transaction_receipt",byte[].class)).isEqualTo(bytes);
            assertThatThrownBy(()->jdbc.update(insert,2,"b".repeat(64),bytes)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
