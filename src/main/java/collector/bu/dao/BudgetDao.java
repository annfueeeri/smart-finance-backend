package collector.bu.dao;

import collector.bu.exception.BudgetException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static collector.bu.model.BudgetModels.*;

/** 预算方案模块 SQL：预算创建、查询、额度修改、结转保存及待刷新用户查询。 */
@Repository
public class BudgetDao {
    private final JdbcTemplate jdbc;
    private final RowMapper<Plan> plan;
    /** 注入JDBC并初始化预算方案行映射，审计字段保留数据库原值。 */
    public BudgetDao(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        plan=(r,n) -> new Plan(r.getLong("id"),r.getLong("user_id"),r.getString("name"),r.getString("category"),r.getString("currency"),
                r.getString("period"),r.getDate("start_date").toLocalDate(),r.getDate("end_date").toLocalDate(),r.getBigDecimal("amount"),
                Arrays.stream(r.getString("thresholds").split(",")).map(Integer::valueOf).toList(),r.getString("rollover_mode"),
                r.getBigDecimal("carry_in"),r.getObject("carry_from_id",Long.class),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),
                r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    }

    /** 读取本人全部未删除预算，以时间顺序支持结转链和历史查询。 */
    public List<Plan> plans(long userId) { return jdbc.query("SELECT * FROM budget_plan WHERE user_id=? AND is_deleted=FALSE ORDER BY start_date,id",plan,userId); }
    /** 查询本人单个预算；不泄露其他用户预算是否存在。 */
    public Plan plan(long userId,long id) { return jdbc.query("SELECT * FROM budget_plan WHERE user_id=? AND id=? AND is_deleted=FALSE",plan,userId,id).stream().findFirst().orElseThrow(() -> new BudgetException("BUDGET_NOT_FOUND")); }
    /** 创建预算并保存操作者，日期和金额已经经过业务层严格校验。 */
    public Plan create(long userId,String actor,Input input) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(c -> {
            var p=c.prepareStatement("INSERT INTO budget_plan(user_id,name,category,currency,period,start_date,end_date,amount,thresholds,rollover_mode,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            p.setLong(1,userId);p.setString(2,input.name().strip());p.setString(3,input.category());p.setString(4,input.currency());p.setString(5,input.period());
            p.setObject(6,input.start());p.setObject(7,input.end());p.setBigDecimal(8,new BigDecimal(input.amount()));p.setString(9,thresholds(input.thresholds()));
            p.setString(10,input.rolloverMode());p.setString(11,actor);p.setString(12,actor);return p;
        },keys);
        return plan(userId,keys.getKey().longValue());
    }
    /** 将排序后的阈值转换成数据库文本，避免使用不可移植的 JSON SQL。 */
    private String thresholds(List<Integer> values) { return values.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")); }
    /** 修改额度、阈值及结转策略，更新时间由数据库写入，归属与周期保持固定。 */
    public void adjust(Plan plan,AdjustmentInput input,String actor) {
        var amount=new BigDecimal(input.amount());
        jdbc.update("UPDATE budget_plan SET amount=?,thresholds=?,rollover_mode=?,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE user_id=? AND id=? AND is_deleted=FALSE",
                amount,thresholds(input.thresholds()),input.rolloverMode(),actor,plan.userId(),plan.id());
    }
    /** 写入或修正结转金额和来源；是否变化及审计历史由Service在同一事务中协调。 */
    public void carry(Plan target,Plan source,BigDecimal amount,String actor) {
        jdbc.update("UPDATE budget_plan SET carry_in=?,carry_from_id=?,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE user_id=? AND id=? AND is_deleted=FALSE",amount,source.id(),actor,target.userId(),target.id());
    }
    /** 找出需要后台刷新预算的启用用户，账户删除或禁用后停止自动处理。 */
    public List<String> owners() { return jdbc.queryForList("SELECT DISTINCT u.username FROM app_user u JOIN budget_plan b ON b.user_id=u.id WHERE u.enabled=TRUE AND u.is_deleted=FALSE AND b.is_deleted=FALSE",String.class); }
}
