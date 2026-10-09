package collector.bu.service.impl;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import collector.bu.service.UserManagementException;
import collector.bu.service.UserManagementService;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementServiceImpl implements UserManagementService {
    private final UserDao users;

    /**
     * 注入账号数据库访问组件，供权限复查和身份修改使用。
     * @param users 账号数据库访问组件
     */
    public UserManagementServiceImpl(UserDao users) { this.users = users; }

    /**
     * 查询用户一览，在后端按数据库当前身份限定范围，不信任前端传入的用户名或身份。
     * @param actor 安全上下文提供的当前登录用户名
     * @return 管理员可见全部未删除账号，一般用户仅返回自己
     * @throws AccessDeniedException 当前账号不存在、被禁用或已删除
     */
    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> listVisibleUsers(String actor) {
        var account = users.findByUsername(actor).filter(user -> user.enabled() && !user.deleted())
                .orElseThrow(() -> new AccessDeniedException("Active account required"));
        return account.role() == UserRole.ADMIN ? users.findAll() : List.of(account);
    }

    /**
     * 根据数据库实时状态检查操作者是否存在、未删除、已启用且身份为 ADMIN。
     * @param actor 要核实权限的操作者用户名
     * @throws org.springframework.security.access.AccessDeniedException 不具备管理员权限
     */
    private void requireAdmin(String actor) {
        var account = users.findByUsername(actor);
        if (account.isEmpty() || !account.get().enabled() || account.get().deleted()
                || account.get().role() != UserRole.ADMIN) {
            throw new AccessDeniedException("Administrator required");
        }
    }

    /**
     * 核实操作者在数据库中仍为未删除且启用的管理员，再读取全部未删除账号。
     * 返回内部账号数据，Controller 必须移除密码哈希后再对外响应。
     * @param actor 当前登录的操作者用户名
     * @return 包含身份和启用状态的内部账号列表
     * @throws org.springframework.security.access.AccessDeniedException 操作者不是启用的管理员
     */
    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> listUsers(String actor) {
        requireAdmin(actor);
        return users.findAll();
    }

    /**
     * 由管理员修改指定账号身份，并阻止降级最后一个启用的管理员。
     * 在事务内锁定启用的管理员记录，再复查操作者权限，防止并发降级破坏约束。
     * 成功修改时保存实际操作者和修改时间，并重新读取完整账号及审计信息。
     * @param actor 当前登录的操作者用户名
     * @param id 目标账号的数据库 ID
     * @param role 要写入的新身份
     * @return 修改后的内部账号数据，包含密码哈希，不能直接作为接口响应
     * @throws collector.bu.service.UserManagementException 目标不存在或会失去最后一个启用的管理员
     * @throws org.springframework.security.access.AccessDeniedException 操作者不是启用的管理员
     */
    @Override
    @Transactional
    public UserAccount updateRole(String actor, long id, UserRole role) {
        // 锁定管理员记录，使并发降级串行执行，避免失去全部启用的管理员。
        var admins = users.lockEnabledAdmins();
        requireAdmin(actor);
        var target = users.findById(id).orElseThrow(() ->
                new UserManagementException(UserManagementException.Reason.USER_NOT_FOUND));
        if (target.role() == UserRole.ADMIN && target.enabled() && role == UserRole.USER && admins.size() <= 1) {
            throw new UserManagementException(UserManagementException.Reason.LAST_ADMIN);
        }
        users.updateRole(id, role, actor);
        return users.findById(id).orElseThrow(() ->
                new UserManagementException(UserManagementException.Reason.USER_NOT_FOUND));
    }
}
