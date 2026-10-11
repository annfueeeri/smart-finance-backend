package collector.bu.dao;

import collector.bu.entity.UserAccount;
import collector.bu.model.BookContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 账本归属和当前选择的 SQL；个人账本主键为用户 ID，家庭账本主键为负家庭 ID。 */
@Repository
public class BookDao {
    private final JdbcTemplate jdbc;
    /** 注入账本持久化组件。 */
    public BookDao(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** 在用户行已锁定的事务中补齐个人账本，旧个人数据无需更改主键归属值。 */
    public void ensurePersonal(UserAccount user) {
        jdbc.update("INSERT INTO ledger_book(id,owner_user_id,currency,timezone,created_by,updated_by) SELECT ?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM ledger_book WHERE id=?)",
                user.id(),user.id(),user.currency(),user.timezone(),user.username(),user.username(),user.id());
    }
    /** 创建独立家庭账本，不复制或公开成员的个人流水。 */
    public void createFamily(long familyId,String currency,String timezone,String actor) {
        jdbc.update("INSERT INTO ledger_book(id,family_id,currency,timezone,created_by,updated_by) VALUES(?,?,?,?,?,?)",-familyId,familyId,currency,timezone,actor,actor);
    }
    /** 读取服务端持久化的账本选择，未设置时使用个人账本。 */
    public String scope(long userId) {
        return jdbc.queryForList("SELECT scope FROM book_preference WHERE user_id=? AND is_deleted=FALSE",String.class,userId).stream().findFirst().orElse("PERSONAL");
    }
    /** 修改当前选择并记录真实操作者；所有会话下一次请求都读取这个选择。 */
    public void scope(long userId,String scope,String actor) {
        int changed=jdbc.update("UPDATE book_preference SET scope=?,is_deleted=FALSE,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE user_id=?",scope,actor,userId);
        if(changed==0)jdbc.update("INSERT INTO book_preference(user_id,scope,created_by,updated_by) VALUES(?,?,?,?)",userId,scope,actor,actor);
    }
    /** 锁定账本行，串行化家庭财务写入、加入退出和权限撤销。 */
    public void lock(long bookId) { jdbc.queryForObject("SELECT id FROM ledger_book WHERE id=? AND is_deleted=FALSE FOR UPDATE",Long.class,bookId); }
    /** 读取家庭默认币种和统一时区，家庭不沿用某位成员的个人预算建议。 */
    public BookContext family(long familyId) {
        return jdbc.queryForObject("SELECT id,currency,timezone FROM ledger_book WHERE family_id=? AND is_deleted=FALSE",
                (rs,row)->new BookContext(rs.getLong("id"),rs.getString("currency"),rs.getString("timezone"),null),familyId);
    }
    /** 为后台预算任务读取有效账本，无需改变任何成员当前选择；家庭需仍有启用成员。 */
    public BookContext scheduled(long bookId) {
        return jdbc.query("SELECT b.id,COALESCE(u.currency,b.currency) currency,COALESCE(u.timezone,b.timezone) timezone,u.monthly_budget FROM ledger_book b LEFT JOIN app_user u ON u.id=b.owner_user_id WHERE b.id=? AND b.is_deleted=FALSE AND ((u.enabled=TRUE AND u.is_deleted=FALSE) OR EXISTS(SELECT 1 FROM family_member m JOIN app_user a ON a.id=m.user_id JOIN family_group f ON f.id=m.family_id WHERE m.family_id=b.family_id AND m.is_deleted=FALSE AND a.enabled=TRUE AND a.is_deleted=FALSE AND f.is_deleted=FALSE))",
                (rs,row)->new BookContext(rs.getLong("id"),rs.getString("currency"),rs.getString("timezone"),rs.getBigDecimal("monthly_budget")),bookId).stream().findFirst().orElse(null);
    }
}
