package collector.bu.report;

import collector.bu.ledger.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static collector.bu.report.ReportModels.*;

/** 报表和账户辅助 SQL 层，归属限定与值绑定均在服务器执行。 */
@Repository
public class ReportDao {
    private final JdbcTemplate jdbc;
    private final LedgerDao ledger;
    private static final RowMapper<Transfer> TRANSFER=(r,n) -> new Transfer(r.getLong("id"),r.getLong("from_account_id"),r.getLong("to_account_id"),r.getString("currency"),r.getBigDecimal("amount"),r.getDate("transfer_date").toLocalDate(),r.getString("note"),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    private static final RowMapper<Valuation> VALUATION=(r,n) -> new Valuation(r.getLong("id"),r.getLong("account_id"),r.getDate("valuation_date").toLocalDate(),r.getBigDecimal("balance"),r.getString("note"),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    /** 注入 JDBC 和统一标签解码器。 */
    public ReportDao(JdbcTemplate jdbc,LedgerDao ledger) { this.jdbc=jdbc;this.ledger=ledger; }
    /** 获取用户截至报告终点的全量日聚合流水；标签以二进制补充分组，避免MySQL默认大小写不敏感排序规则合并不同项目；不截断一万条。 */
    public List<Movement> movements(long userId,LocalDate end) {
        return jdbc.query("SELECT t.account_id,t.transaction_date,t.kind,t.category,t.merchant,t.tags_json,SUM(t.amount) AS amount,COUNT(*) AS count FROM ledger_transaction t JOIN ledger_account a ON a.id=t.account_id AND a.user_id=t.user_id WHERE t.user_id=? AND t.transaction_date<=? AND t.is_deleted=FALSE AND a.is_deleted=FALSE GROUP BY t.account_id,t.transaction_date,t.kind,t.category,t.merchant,t.tags_json,CAST(t.tags_json AS BINARY(4096)) ORDER BY t.transaction_date,t.account_id",
                (r,n) -> new Movement(r.getLong("account_id"),r.getDate("transaction_date").toLocalDate(),r.getString("kind"),r.getString("category"),r.getString("merchant"),ledger.decodeTags(r.getString("tags_json")),r.getBigDecimal("amount"),r.getLong("count")),userId,end);
    }
    /** 读取本人截至日期的未删除内部转账，两个账户都必须未删除。 */
    public List<Transfer> transfers(long userId,LocalDate end) {
        return jdbc.query("SELECT t.* FROM ledger_transfer t JOIN ledger_account a ON a.id=t.from_account_id AND a.user_id=t.user_id JOIN ledger_account b ON b.id=t.to_account_id AND b.user_id=t.user_id WHERE t.user_id=? AND t.transfer_date<=? AND t.is_deleted=FALSE AND a.is_deleted=FALSE AND b.is_deleted=FALSE ORDER BY t.transfer_date,t.id",TRANSFER,userId,end);
    }
    /** 保存同币种个人转账，审计操作者为认证用户名。 */
    public Transfer transfer(long userId,String actor,LedgerAccount from,LedgerAccount to,TransferInput input) {
        var keys=new GeneratedKeyHolder();jdbc.update(c -> {
            var p=c.prepareStatement("INSERT INTO ledger_transfer(user_id,from_account_id,to_account_id,currency,amount,transfer_date,note,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            p.setLong(1,userId);p.setLong(2,from.id());p.setLong(3,to.id());p.setString(4,from.currency());p.setBigDecimal(5,new BigDecimal(input.amount()));p.setObject(6,input.date());p.setString(7,input.note()==null ? "" : input.note().strip());p.setString(8,actor);p.setString(9,actor);return p;
        },keys);return jdbc.query("SELECT * FROM ledger_transfer WHERE user_id=? AND id=?",TRANSFER,userId,keys.getKey().longValue()).get(0);
    }
    /** 获取本人未删除余额校准记录，按日期及主键决定日终最后一次值。 */
    public List<Valuation> valuations(long userId,LocalDate end) { return jdbc.query("SELECT v.* FROM account_valuation v JOIN ledger_account a ON a.id=v.account_id AND a.user_id=v.user_id WHERE v.user_id=? AND v.valuation_date<=? AND v.is_deleted=FALSE AND a.is_deleted=FALSE ORDER BY v.valuation_date,v.id",VALUATION,userId,end); }
    /** 新增日终余额或投资估值，保留之前的同日记录以便审计。 */
    public Valuation value(long userId,String actor,long accountId,ValuationInput input) {
        var keys=new GeneratedKeyHolder();jdbc.update(c -> {
            var p=c.prepareStatement("INSERT INTO account_valuation(user_id,account_id,valuation_date,balance,note,created_by,updated_by) VALUES(?,?,?,?,?,?,?)",new String[]{"id"});
            p.setLong(1,userId);p.setLong(2,accountId);p.setObject(3,input.date());p.setBigDecimal(4,new BigDecimal(input.balance()));p.setString(5,input.note()==null ? "" : input.note().strip());p.setString(6,actor);p.setString(7,actor);return p;
        },keys);return jdbc.query("SELECT * FROM account_valuation WHERE user_id=? AND id=?",VALUATION,userId,keys.getKey().longValue()).get(0);
    }
    /** 修改本人账户名称和资产类别，不能通过此方法改变币种或覆盖余额历史。 */
    public void profile(long userId,long id,String actor,AccountProfile input) { jdbc.update("UPDATE ledger_account SET name=?,account_type=?,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE user_id=? AND id=? AND is_deleted=FALSE",input.name().strip(),input.type(),actor,userId,id); }
    /** 提取真实标签列表，去重排序后供报表筛选。 */
    public List<String> tags(long userId) { return jdbc.queryForList("SELECT tags_json FROM ledger_transaction WHERE user_id=? AND is_deleted=FALSE GROUP BY tags_json,CAST(tags_json AS BINARY(4096))",String.class,userId).stream().flatMap(s -> ledger.decodeTags(s).stream()).distinct().sorted().toList(); }
}
