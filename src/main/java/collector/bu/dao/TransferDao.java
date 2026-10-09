package collector.bu.dao;

import collector.bu.entity.LedgerAccount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static collector.bu.model.ReportModels.*;

/** 内部转账模块 SQL：查询和保存本人同币种账户间转账。 */
@Repository
public class TransferDao {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Transfer> TRANSFER=(r,n) -> new Transfer(r.getLong("id"),r.getLong("from_account_id"),r.getLong("to_account_id"),r.getString("currency"),r.getBigDecimal("amount"),r.getDate("transfer_date").toLocalDate(),r.getString("note"),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    /** 注入 JDBC，所有查询绑定服务端确认的用户ID。 */
    public TransferDao(JdbcTemplate jdbc) { this.jdbc=jdbc; }

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
}
