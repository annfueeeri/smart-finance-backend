package collector.bu.dao;

import collector.bu.support.TransactionTagCodec;

import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import static collector.bu.model.ReportModels.*;

/** 财务报表模块 SQL：只读聚合本人真实流水，不写入账户、转账或估值。 */
@Repository
public class ReportDao {
    private final JdbcTemplate jdbc;
    private final TransactionTagCodec tags;
    /** 注入JDBC和标签解码器，报表聚合与流水使用相同标签语义。 */
    public ReportDao(JdbcTemplate jdbc, TransactionTagCodec tags) { this.jdbc=jdbc; this.tags=tags; }

    /** 获取用户截至报告终点的全量日聚合流水；标签以二进制补充分组，避免MySQL默认大小写不敏感排序规则合并不同项目；不截断一万条。 */
    public List<Movement> movements(long userId,LocalDate end) {
        return jdbc.query("SELECT t.account_id,t.transaction_date,t.kind,t.category,t.merchant,t.tags_json,SUM(t.amount) AS amount,COUNT(*) AS count FROM ledger_transaction t JOIN ledger_account a ON a.id=t.account_id AND a.user_id=t.user_id WHERE t.user_id=? AND t.transaction_date<=? AND t.is_deleted=FALSE AND a.is_deleted=FALSE GROUP BY t.account_id,t.transaction_date,t.kind,t.category,t.merchant,t.tags_json,CAST(t.tags_json AS BINARY(4096)) ORDER BY t.transaction_date,t.account_id",
                (r,n) -> new Movement(r.getLong("account_id"),r.getDate("transaction_date").toLocalDate(),r.getString("kind"),r.getString("category"),r.getString("merchant"),tags.decodeTags(r.getString("tags_json")),r.getBigDecimal("amount"),r.getLong("count")),userId,end);
    }
}
