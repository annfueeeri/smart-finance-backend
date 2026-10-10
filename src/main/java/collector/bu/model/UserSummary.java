package collector.bu.model;

import collector.bu.entity.UserRole;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 用户一览响应，公开资料、记账偏好和审计字段，不包含密码。 */
public record UserSummary(Long id, String username, UserRole role, boolean enabled,
        String createdAt, String createdBy, String updatedAt, String updatedBy,
        @JsonProperty("isDeleted") boolean isDeleted, String displayName, String email, String phone,
        String currency, String timezone, String monthlyBudget, Integer budgetStartDay) { }
