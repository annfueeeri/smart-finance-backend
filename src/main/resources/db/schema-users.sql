CREATE TABLE IF NOT EXISTS app_user (
    -- 主键：保留现有自增规则。
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    -- 是否逻辑删除；删除账号不能认证或使用原有会话。
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    -- 创建时间和创建用户，后续修改不覆盖。
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
    -- 修改时间和修改用户，业务写入时记录实际操作者。
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
    CONSTRAINT chk_app_user_role CHECK (role IN ('ADMIN', 'USER'))
);
