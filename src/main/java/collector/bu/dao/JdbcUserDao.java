package collector.bu.dao;

import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserDao implements UserDao {
    private final JdbcTemplate jdbc;
    private static final String COLUMNS = "id, username, password_hash, enabled, role, is_deleted, "
            + "created_at, created_by, updated_at, updated_by, display_name, email, phone, "
            + "currency, timezone, monthly_budget, budget_start_day";
    private static final RowMapper<UserAccount> ROW = (rs, row) -> new UserAccount(
            rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
            rs.getBoolean("enabled"), UserRole.valueOf(rs.getString("role")),
            rs.getBoolean("is_deleted"),
            rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("created_by"),
            rs.getTimestamp("updated_at").toLocalDateTime(), rs.getString("updated_by"),
            rs.getString("display_name"), rs.getString("email"), rs.getString("phone"),
            rs.getString("currency"), rs.getString("timezone"), rs.getBigDecimal("monthly_budget"),
            rs.getInt("budget_start_day"));

    /**
     * 注入 JdbcTemplate，以参数化 SQL 查询和修改 app_user 表。
     * @param jdbc Spring 提供的 JDBC 操作组件
     */
    public JdbcUserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按用户名查询账号，为登录认证和实时权限校验提供数据库数据。
     * 返回值包含密码哈希及删除状态，仅供内部使用；认证调用方必须拒绝已删除账号。
     * 包含已删除账号以保留用户名占用状态；SQL 使用占位符绑定用户名，避免 SQL 注入。
     * @param username 要查询的用户名
     * @return 匹配的账号；不存在时返回 Optional.empty()
     */
    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM app_user WHERE username = ?", ROW,
                username).stream().findFirst();
    }

    /**
     * 新增启用的账号并保存指定身份和审计信息；密码参数必须已完成哈希处理。
     * 用户名唯一性由数据库约束保证，权限检查和注册校验由调用方完成。
     * @param username 新用户名
     * @param passwordHash 已生成的密码哈希，禁止传入明文密码
     * @param role 新账号身份
     * @param actor 后端确定的创建操作者，创建和初始修改用户均记录该值
     * @param displayName 姓名或昵称，与登录账号分开保存
     * @param email 可选联系邮箱，未填写为空字符串
     * @param phone 可选联系手机号，未填写为空字符串
     * @param currency 默认记账币种
     * @param timezone 用户时区
     * @param monthlyBudget 可选月度预算，未设置为 null
     * @param budgetStartDay 预算周期起始日
     * @throws org.springframework.dao.DuplicateKeyException 用户名已存在
     */
    @Override
    public void insert(String username, String passwordHash, UserRole role, String actor,
            String displayName, String email, String phone, String currency, String timezone,
            BigDecimal monthlyBudget, int budgetStartDay) {
        jdbc.update("INSERT INTO app_user (username, password_hash, enabled, role, "
                + "created_at, created_by, updated_at, updated_by, display_name, email, phone, "
                + "currency, timezone, monthly_budget, budget_start_day) "
                + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6), ?, CURRENT_TIMESTAMP(6), ?, ?, ?, ?, ?, ?, ?, ?)",
                username, passwordHash, true, role.name(), actor, actor, displayName, email, phone,
                currency, timezone, monthlyBudget, budgetStartDay);
    }

    /**
     * 按数据库主键查询未删除账号，供身份修改时确认目标用户。
     * @param id 账号数据库 ID
     * @return 匹配的内部账号；不存在时返回 Optional.empty()
     */
    @Override
    public Optional<UserAccount> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE id = ? AND is_deleted = FALSE",
                ROW, id).stream().findFirst();
    }

    /**
     * 按 ID 升序读取所有未删除账号，包括禁用账号。
     * 结果包含密码哈希，仅供内部使用；调用方负责权限检查和响应字段过滤。
     * @return 全部内部账号列表，没有账号时为空列表
     */
    @Override
    public List<UserAccount> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE is_deleted = FALSE ORDER BY id", ROW);
    }

    /**
     * 查询并锁定所有未删除且启用的管理员记录，用于串行化管理员身份变更。
     * 必须在事务内调用；锁持续到事务结束，避免并发请求把所有管理员都降级。
     * @return 按 ID 升序排列的启用管理员内部账号列表
     */
    @Override
    public List<UserAccount> lockEnabledAdmins() {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user "
                + "WHERE role = 'ADMIN' AND enabled = TRUE AND is_deleted = FALSE ORDER BY id FOR UPDATE", ROW);
    }

    /**
     * 根据账号 ID 更新身份，同时记录数据库当前时间和实际修改用户；保留创建信息。
     * 此方法只执行存储操作，权限检查、目标存在检查及最后管理员保护由业务层完成。
     * @param id 要修改的账号数据库 ID
     * @param role 要保存的新身份
     * @param actor 后端认证的实际操作者用户名
     */
    @Override
    public void updateRole(long id, UserRole role, String actor) {
        jdbc.update("UPDATE app_user SET role = ?, updated_at = CURRENT_TIMESTAMP(6), updated_by = ? "
                + "WHERE id = ? AND is_deleted = FALSE",
                role.name(), actor, id);
    }
    /** 锁定当前用户行，使同一用户并发导入的重复检测和入库按顺序执行。 */
    @Override
    public void lockUser(long userId) { jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", Long.class,userId); }
}
