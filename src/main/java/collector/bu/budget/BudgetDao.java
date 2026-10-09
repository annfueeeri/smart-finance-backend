package collector.bu.budget;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static collector.bu.budget.BudgetModels.*;

/** 预算 SQL 访问层，所有数据读取和修改均绑定服务端当前用户 ID。 */
@Repository
public class BudgetDao {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<Plan> plan;
    private final RowMapper<Template> template;
    private static final RowMapper<Adjustment> ADJUSTMENT=(r,n) -> new Adjustment(r.getLong("id"),r.getLong("budget_id"),
            r.getBigDecimal("old_amount"),r.getBigDecimal("new_amount"),r.getBigDecimal("old_carry"),r.getBigDecimal("new_carry"),
            r.getString("reason"),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),
            r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    private static final RowMapper<Notice> NOTICE=(r,n) -> new Notice(r.getLong("id"),r.getLong("budget_id"),r.getString("event_key"),
            r.getString("message"),r.getBoolean("is_read"),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),
            r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    /** 初始化 JDBC 和内部 JSON 模板序列化，构造数据库行映射器。 */
    public BudgetDao(JdbcTemplate jdbc,ObjectMapper mapper) {
        this.jdbc=jdbc; this.mapper=mapper;
        plan=(r,n) -> new Plan(r.getLong("id"),r.getLong("user_id"),r.getString("name"),r.getString("category"),r.getString("currency"),
                r.getString("period"),r.getDate("start_date").toLocalDate(),r.getDate("end_date").toLocalDate(),r.getBigDecimal("amount"),
                Arrays.stream(r.getString("thresholds").split(",")).map(Integer::valueOf).toList(),r.getString("rollover_mode"),
                r.getBigDecimal("carry_in"),r.getObject("carry_from_id",Long.class),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),
                r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
        template=(r,n) -> new Template(r.getLong("id"),r.getString("name"),decode(r.getString("items_json")),
                r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    }
    /** 锁定当前用户，序列化预算变更、结转、预警和收支写入，避免重复结转或通知。 */
    public void lock(long userId) { jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,userId); }
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
    /** 按币种、日期及可选支出分类汇总真实个人支出，不混入收入、删除记录或他人的记录。 */
    public BigDecimal spent(Plan plan,LocalDate through) {
        var end=through.isBefore(plan.end()) ? through : plan.end();
        if (end.isBefore(plan.start())) return BigDecimal.ZERO;
        var args=new ArrayList<Object>(List.of(plan.userId(),plan.currency(),plan.start(),end));
        var category=plan.category().equals("TOTAL") ? "" : " AND t.category=?"; if(!category.isEmpty())args.add(plan.category());
        return jdbc.queryForObject("SELECT COALESCE(SUM(t.amount),0) FROM ledger_transaction t JOIN ledger_account a ON a.id=t.account_id AND a.user_id=t.user_id WHERE t.user_id=? AND t.currency=? AND t.transaction_date BETWEEN ? AND ? AND t.kind='EXPENSE' AND t.is_deleted=FALSE AND a.is_deleted=FALSE"+category,BigDecimal.class,args.toArray());
    }
    /** 将排序后的阈值转换成数据库文本，避免使用不可移植的 JSON SQL。 */
    private String thresholds(List<Integer> values) { return values.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")); }
    /** 保存调整前后的基础额度与结转额度快照，用户和后台结转修改都可追踪。 */
    public void history(Plan before,BigDecimal afterAmount,BigDecimal afterCarry,String actor,String reason) {
        jdbc.update("INSERT INTO budget_adjustment(user_id,budget_id,old_amount,new_amount,old_carry,new_carry,reason,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",
                before.userId(),before.id(),before.amount(),afterAmount,before.carryIn(),afterCarry,reason.strip(),actor,actor);
    }
    /** 修改额度、阈值及结转策略，更新时间由数据库写入，归属与周期保持固定。 */
    public void adjust(Plan plan,AdjustmentInput input,String actor) {
        var amount=new BigDecimal(input.amount());history(plan,amount,plan.carryIn(),actor,input.reason());
        jdbc.update("UPDATE budget_plan SET amount=?,thresholds=?,rollover_mode=?,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE user_id=? AND id=? AND is_deleted=FALSE",
                amount,thresholds(input.thresholds()),input.rolloverMode(),actor,plan.userId(),plan.id());
    }
    /** 写入或修正来源月份的结转金额，仅在金额或来源发生变化时留下历史。 */
    public void carry(Plan target,Plan source,BigDecimal amount,String actor) {
        if (target.carryIn().compareTo(amount)==0 && Objects.equals(target.carryFromId(),source.id()))return;
        history(target,target.amount(),amount,actor,"月度结转，来源预算 #"+source.id()+"（补录历史支出时重新核算）");
        jdbc.update("UPDATE budget_plan SET carry_in=?,carry_from_id=?,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE user_id=? AND id=? AND is_deleted=FALSE",amount,source.id(),actor,target.userId(),target.id());
    }
    /** 查询本人预算的修改历史，包含手动调整及系统结转，按修改时间排列。 */
    public List<Adjustment> adjustments(long userId,long id) { return jdbc.query("SELECT * FROM budget_adjustment WHERE user_id=? AND budget_id=? AND is_deleted=FALSE ORDER BY id",ADJUSTMENT,userId,id); }
    /** 仅在该预算尚未出现此事件时写入一条站内消息，事件键实现持久去重。 */
    public void notify(Plan plan,String event,String message,String actor) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM budget_notification WHERE user_id=? AND budget_id=? AND event_key=?",Long.class,plan.userId(),plan.id(),event)>0)return;
        jdbc.update("INSERT INTO budget_notification(user_id,budget_id,event_key,message,created_by,updated_by) VALUES(?,?,?,?,?,?)",plan.userId(),plan.id(),event,message,actor,actor);
    }
    /** 读取本人最近 200 条站内预算提醒，不展示其他人的消费信息。 */
    public List<Notice> notices(long userId) { return jdbc.query("SELECT * FROM budget_notification WHERE user_id=? AND is_deleted=FALSE ORDER BY id DESC LIMIT 200",NOTICE,userId); }
    /** 统计本人全部未读提醒，数量不受消息列表分页上限影响。 */
    public int unread(long userId) { return jdbc.queryForObject("SELECT COUNT(*) FROM budget_notification WHERE user_id=? AND is_deleted=FALSE AND is_read=FALSE",Integer.class,userId); }
    /** 把本人提醒标记已读，不接受其他用户的消息 ID。 */
    public void readNotice(long userId,long id,String actor) {
        if(jdbc.update("UPDATE budget_notification SET is_read=TRUE,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE user_id=? AND id=? AND is_deleted=FALSE",actor,userId,id)==0)throw new BudgetException("BUDGET_NOT_FOUND");
    }
    /** 将模板配置序列化为内部 JSON，仅由业务层验证后的字段生成。 */
    private String encode(List<TemplateItem> items) { try { return mapper.writeValueAsString(items); } catch(java.io.IOException e) { throw new IllegalStateException("Template serialization failed"); } }
    /** 解码数据库模板，损坏配置不会被静默当作空模板应用。 */
    private List<TemplateItem> decode(String json) { try { return mapper.readValue(json,new TypeReference<List<TemplateItem>>(){}); } catch(java.io.IOException e) { throw new IllegalStateException("Template data invalid"); } }
    /** 保存本人常用方案，名称唯一，创建审计由后端填写。 */
    public Template saveTemplate(long userId,String actor,String name,List<TemplateItem> items) {
        jdbc.update("INSERT INTO budget_template(user_id,name,items_json,created_by,updated_by) VALUES(?,?,?,?,?)",userId,name.strip(),encode(items),actor,actor);
        return jdbc.query("SELECT * FROM budget_template WHERE user_id=? AND name=?",template,userId,name.strip()).get(0);
    }
    /** 返回本人未删除模板及完整配置，不返回其他人的方案。 */
    public List<Template> templates(long userId) { return jdbc.query("SELECT * FROM budget_template WHERE user_id=? AND is_deleted=FALSE ORDER BY id",template,userId); }
    /** 查找本人模板以应用到新月份，不能借用其他用户的模板 ID。 */
    public Template template(long userId,long id) { return templates(userId).stream().filter(t -> t.id()==id).findFirst().orElseThrow(() -> new BudgetException("BUDGET_NOT_FOUND")); }
    /** 找出需要后台刷新预算的启用用户，账户删除或禁用后停止自动处理。 */
    public List<String> owners() { return jdbc.queryForList("SELECT DISTINCT u.username FROM app_user u JOIN budget_plan b ON b.user_id=u.id WHERE u.enabled=TRUE AND u.is_deleted=FALSE AND b.is_deleted=FALSE",String.class); }
}
