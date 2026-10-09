package collector.bu.dao;

import collector.bu.exception.BudgetException;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import static collector.bu.model.budget.BudgetModels.*;

/** 预算通知模块 SQL：站内提醒持久去重、未读统计和已读更新。 */
@Repository
public class BudgetNotificationDao {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Notice> NOTICE=(r,n) -> new Notice(r.getLong("id"),r.getLong("budget_id"),r.getString("event_key"),
            r.getString("message"),r.getBoolean("is_read"),r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),
            r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    /** 注入 JDBC，所有查询绑定服务端确认的用户ID。 */
    public BudgetNotificationDao(JdbcTemplate jdbc) { this.jdbc=jdbc; }

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
}
