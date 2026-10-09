package collector.bu.entity;

import java.time.LocalDateTime;

/**
 * 数据库账号的内部表示，禁止直接作为接口响应，以免泄露密码哈希。
 * record 自动生成各字段的只读访问方法，业务代码通过这些方法读取账号信息。
 * @param id 数据库主键
 * @param username 唯一用户名
 * @param passwordHash 保存的 BCrypt 密码哈希，不是明文密码
 * @param enabled 是否允许该账号登录和继续使用已有会话
 * @param role 数据库中保存的 ADMIN 或 USER 身份
 * @param deleted 是否已逻辑删除，已删除账号不能登录或使用原会话
 * @param createdAt 数据库记录的创建时间
 * @param createdBy 创建操作者用户名，系统初始化为 SYSTEM，历史数据迁移为 LEGACY
 * @param updatedAt 数据库记录的最后修改时间
 * @param updatedBy 最后修改操作者用户名，创建时与创建用户一致
 */
public record UserAccount(long id, String username, String passwordHash, boolean enabled, UserRole role, boolean deleted,
        LocalDateTime createdAt, String createdBy, LocalDateTime updatedAt, String updatedBy) {
}
