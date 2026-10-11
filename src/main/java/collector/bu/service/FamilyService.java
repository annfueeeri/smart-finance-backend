package collector.bu.service;

import static collector.bu.model.FamilyModels.*;

/** 家庭管理业务接口，家庭身份不使用系统 ADMIN/USER 角色。 */
public interface FamilyService {
    /** 查询本人的当前账本选择和家庭成员。 */
    Status status(String username);
    /** 创建独立共享账本并将创建者加入家庭、设为家庭管理员。 */
    Invitation create(String username,Create input);
    /** 校验邀请码后主动加入家庭并切换至共享账本。 */
    Status join(String username,Join input);
    /** 在有权限的个人和家庭账本之间切换。 */
    Status scope(String username,Scope input);
    /** 仅家庭管理员能够重新生成邀请码。 */
    Invitation rotate(String username);
    /** 成员退出本人，或家庭管理员移除其他成员；家庭管理员须先移交管理权才能退出。 */
    Status remove(String username,long userId);
    /** 仅家庭管理员能够向本家庭的启用成员移交管理权。 */
    Status owner(String username,Owner input);
}
