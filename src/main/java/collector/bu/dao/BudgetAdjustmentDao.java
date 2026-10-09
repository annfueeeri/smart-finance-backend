package collector.bu.dao;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import static collector.bu.model.BudgetModels.*;

/** 预算调整模块 SQL：保存额度/结转前后快照和查询调整历史。 */
@Repository
public class BudgetAdjustmentDao {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Adjustment> ADJUSTMENT=(r,n) -> new Adjustment(r.getLong("id"),r.getLong("budget_id"),
            r.getBigDecimal("old_amount"),r.getBigDecimal("new_amount"),r.getBigDecimal("old_carry"),r.getBigDecimal("new_carry"),
            r.getString("reason"),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),
            r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    /** 注入 JDBC，所有查询绑定服务端确认的用户ID。 */
    public BudgetAdjustmentDao(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** 保存调整前后的基础额度与结转额度快照，用户和后台结转修改都可追踪。 */
    public void history(Plan before,BigDecimal afterAmount,BigDecimal afterCarry,String actor,String reason) {
        jdbc.update("INSERT INTO budget_adjustment(user_id,budget_id,old_amount,new_amount,old_carry,new_carry,reason,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",
                before.userId(),before.id(),before.amount(),afterAmount,before.carryIn(),afterCarry,reason.strip(),actor,actor);
    }
    /** 查询本人预算的修改历史，包含手动调整及系统结转，按修改时间排列。 */
    public List<Adjustment> adjustments(long userId,long id) { return jdbc.query("SELECT * FROM budget_adjustment WHERE user_id=? AND budget_id=? AND is_deleted=FALSE ORDER BY id",ADJUSTMENT,userId,id); }
}
