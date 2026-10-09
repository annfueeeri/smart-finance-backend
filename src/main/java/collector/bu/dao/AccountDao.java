package collector.bu.dao;

import collector.bu.entity.LedgerAccount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static collector.bu.model.ReportModels.*;

/** 账户模块 SQL：仅负责个人账户的查询、新增及资料修改。 */
@Repository
public class AccountDao {
    private final JdbcTemplate jdbc;
    private static final RowMapper<LedgerAccount> ACCOUNT = (rs, row) -> new LedgerAccount(rs.getLong("id"),
            rs.getString("name"), rs.getString("currency"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getString("created_by"), rs.getTimestamp("updated_at").toLocalDateTime(), rs.getString("updated_by"),
            rs.getBoolean("is_deleted"),rs.getString("account_type"),rs.getBigDecimal("opening_balance"),rs.getDate("opening_date").toLocalDate());
    /** 注入 JDBC，所有查询绑定服务端确认的用户ID。 */
    public AccountDao(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** 返回指定用户自己的未删除账户，按创建顺序排列。 */
    public List<LedgerAccount> accounts(long userId) {
        return jdbc.query("SELECT * FROM ledger_account WHERE user_id=? AND is_deleted=FALSE ORDER BY id", ACCOUNT, userId);
    }
    /** 查询用户拥有的未删除账户；其他用户的账户也返回空，避免泄露其存在状态。 */
    public Optional<LedgerAccount> account(long userId, long id) {
        return jdbc.query("SELECT * FROM ledger_account WHERE user_id=? AND id=? AND is_deleted=FALSE", ACCOUNT,
                userId, id).stream().findFirst();
    }
    /** 创建个人账户并记录当前操作者，使用 JDBC 生成主键以兼容 MySQL 和 H2。 */
    public LedgerAccount createAccount(long userId, String actor, String name, String currency,String type,BigDecimal openingBalance,LocalDate openingDate) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO ledger_account "
                    + "(user_id,name,currency,created_by,updated_by,account_type,opening_balance,opening_date) VALUES (?,?,?,?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, userId); statement.setString(2, name); statement.setString(3, currency);
            statement.setString(4, actor); statement.setString(5, actor); statement.setString(6,type);statement.setBigDecimal(7,openingBalance);statement.setObject(8,openingDate);return statement;
        }, keys);
        return account(userId, keys.getKey().longValue()).orElseThrow();
    }
    /** 修改本人账户名称和资产类别，不能通过此方法改变币种或覆盖余额历史。 */
    public void profile(long userId,long id,String actor,AccountProfile input) { jdbc.update("UPDATE ledger_account SET name=?,account_type=?,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE user_id=? AND id=? AND is_deleted=FALSE",input.name().strip(),input.type(),actor,userId,id); }
}
