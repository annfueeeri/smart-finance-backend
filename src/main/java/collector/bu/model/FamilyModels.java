package collector.bu.model;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

/** 家庭管理契约；家庭管理员与系统 ADMIN 身份相互独立。 */
public final class FamilyModels {
    /** 创建共享账本的名称、默认币种和所有成员共用的记账时区。 */
    public record Create(@NotBlank @Size(max=80) String name, @NotBlank @Size(min=3,max=3) String currency,
            @NotBlank @Size(max=64) String timezone) { }
    /** 使用随机邀请码主动加入家庭，禁止仅凭家庭 ID 加入。 */
    public record Join(@NotBlank @Size(max=64) String invitationCode) { }
    /** 切换当前账号所有会话使用的账本，允许 PERSONAL 或 FAMILY。 */
    public record Scope(@NotBlank String scope) { }
    /** 将家庭管理权移交给当前家庭内另一名启用成员。 */
    public record Owner(@NotNull @Positive Long userId) { }
    /** 只公开成员姓名、账号、家庭身份和审计信息，不公开联系方式或密码。 */
    public record Member(long userId, String username, String displayName, String role,
            LocalDateTime createdAt, String createdBy, LocalDateTime updatedAt, String updatedBy, boolean deleted) { }
    /** 家庭信息不包含邀请码哈希，只有创建和重新生成邀请码时返回原始邀请码。 */
    public record Family(long id, String name, String currency, String timezone, long ownerUserId,
            List<Member> members, LocalDateTime createdAt, String createdBy,
            LocalDateTime updatedAt, String updatedBy, boolean deleted) { }
    /** 当前账本及家庭成员信息；没有家庭时 family 为 null。 */
    public record Status(String scope, Family family) { }
    /** 一次性展示的邀请凭据，重新生成后旧邀请码立即失效。 */
    public record Invitation(Status status, String invitationCode) { }
}
