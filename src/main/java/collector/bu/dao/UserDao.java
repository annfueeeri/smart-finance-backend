package collector.bu.dao;

import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import java.util.List;
import java.util.Optional;

public interface UserDao {
    /**
     * 按用户名查询账号，为登录认证和实时权限校验提供数据库数据。
     * 返回值包含密码哈希，仅供内部使用。
     * @param username 要查询的用户名
     * @return 匹配的账号；不存在时返回 Optional.empty()
     */
    Optional<UserAccount> findByUsername(String username);

    /**
     * 以 USER 身份创建启用的一般用户账号，委托给可指定身份的插入方法。
     * @param username 新用户名
     * @param passwordHash 已生成的密码哈希，禁止传入明文密码
     */
    default void insert(String username, String passwordHash) {
        insert(username, passwordHash, UserRole.USER);
    }

    /**
     * 新增启用的账号并保存指定身份；密码参数必须已完成哈希处理。
     * 用户名唯一性由数据库约束保证，权限检查和注册校验由调用方完成。
     * @param username 新用户名
     * @param passwordHash 已生成的密码哈希，禁止传入明文密码
     * @param role 新账号身份
     * @throws org.springframework.dao.DuplicateKeyException 用户名已存在
     */
    void insert(String username, String passwordHash, UserRole role);

    /**
     * 按数据库主键查询账号，供身份修改时确认目标用户。
     * @param id 账号数据库 ID
     * @return 匹配的内部账号；不存在时返回 Optional.empty()
     */
    Optional<UserAccount> findById(long id);

    /**
     * 按 ID 升序读取全部账号，包括禁用账号。
     * 结果包含密码哈希，仅供内部使用；调用方负责权限检查和响应字段过滤。
     * @return 全部内部账号列表，没有账号时为空列表
     */
    List<UserAccount> findAll();

    /**
     * 查询并锁定所有启用的管理员记录，用于串行化管理员身份变更。
     * 必须在事务内调用；锁持续到事务结束，避免并发请求把所有管理员都降级。
     * @return 按 ID 升序排列的启用管理员内部账号列表
     */
    List<UserAccount> lockEnabledAdmins();

    /**
     * 根据账号 ID 更新数据库中的身份字段，不改变密码或启用状态。
     * 此方法只执行存储操作，权限检查、目标存在检查及最后管理员保护由业务层完成。
     * @param id 要修改的账号数据库 ID
     * @param role 要保存的新身份
     */
    void updateRole(long id, UserRole role);
}
