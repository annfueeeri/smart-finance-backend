package collector.bu.service;

import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import java.util.List;

public interface UserManagementService {
    /**
     * 核实操作者在数据库中仍为未删除且启用的管理员，再读取全部未删除账号。
     * 返回内部账号数据，Controller 必须移除密码哈希后再对外响应。
     * @param actor 当前登录的操作者用户名
     * @return 包含身份和启用状态的内部账号列表
     * @throws org.springframework.security.access.AccessDeniedException 操作者不是启用的管理员
     */
    List<UserAccount> listUsers(String actor);
    /**
     * 由管理员修改指定账号身份，并阻止降级最后一个启用的管理员。
     * 在事务内锁定启用的管理员记录，再复查操作者权限，防止并发降级破坏约束。
     * 成功修改时由后端记录实际操作者和修改时间；已删除目标按不存在处理。
     * @param actor 当前登录的操作者用户名
     * @param id 目标账号的数据库 ID
     * @param role 要写入的新身份
     * @return 修改后的内部账号数据，包含密码哈希，不能直接作为接口响应
     * @throws collector.bu.service.UserManagementException 目标不存在或会失去最后一个启用的管理员
     * @throws org.springframework.security.access.AccessDeniedException 操作者不是启用的管理员
     */
    UserAccount updateRole(String actor, long id, UserRole role);
}
