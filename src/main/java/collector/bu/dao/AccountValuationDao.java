package collector.bu.dao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static collector.bu.model.ReportModels.*;

/** 余额估值模块 SQL：保存和查询日终余额、投资市值及审计历史。 */
@Repository
public class AccountValuationDao {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Valuation> VALUATION=(r,n) -> new Valuation(r.getLong("id"),r.getLong("account_id"),r.getDate("valuation_date").toLocalDate(),r.getBigDecimal("balance"),r.getString("note"),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    /** 注入 JDBC，所有查询绑定服务端确认的用户ID。 */
    public AccountValuationDao(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** 获取本人未删除余额校准记录，按日期及主键决定日终最后一次值。 */
    public List<Valuation> valuations(long userId,LocalDate end) { return jdbc.query("SELECT v.* FROM account_valuation v JOIN ledger_account a ON a.id=v.account_id AND a.user_id=v.user_id WHERE v.user_id=? AND v.valuation_date<=? AND v.is_deleted=FALSE AND a.is_deleted=FALSE ORDER BY v.valuation_date,v.id",VALUATION,userId,end); }
    /** 新增日终余额或投资估值，保留之前的同日记录以便审计。 */
    public Valuation value(long userId,String actor,long accountId,ValuationInput input) {
        var keys=new GeneratedKeyHolder();jdbc.update(c -> {
            var p=c.prepareStatement("INSERT INTO account_valuation(user_id,account_id,valuation_date,balance,note,created_by,updated_by) VALUES(?,?,?,?,?,?,?)",new String[]{"id"});
            p.setLong(1,userId);p.setLong(2,accountId);p.setObject(3,input.date());p.setBigDecimal(4,new BigDecimal(input.balance()));p.setString(5,input.note()==null ? "" : input.note().strip());p.setString(6,actor);p.setString(7,actor);return p;
        },keys);return jdbc.query("SELECT * FROM account_valuation WHERE user_id=? AND id=?",VALUATION,userId,keys.getKey().longValue()).get(0);
    }
}
